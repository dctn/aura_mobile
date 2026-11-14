package info.ibtaura.auraclarus.recovery

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.platform.LocalContext
import info.ibtaura.aura.core.AuthState
import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.Shelf
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.KeyValues
import info.ibtaura.auraclarus.shell.SharedPrefsKeyValues
import java.security.MessageDigest

// The Recovery tab's Edit (which sections show, in what order) and Filter (which tiles show) choices.

/** Figma's order, with Focus added first. */
internal val recoveryShelves = listOf(
    Shelf.focus, Shelf.sleep, Shelf.movement, Shelf.breathe, Shelf.meditate,
    Shelf.rest, Shelf.mind, Shelf.outside, Shelf.people, Shelf.fuel,
)

/** One section of the Recovery tab that Edit can move or hide: the Now carousel (`shelf == null`) or a shelf. */
data class RecoverySection(val shelf: Shelf?) {
    /** Stable id for storage and test tags: "now", "focus", "sleep"... */
    val key: String get() = shelf?.name ?: NOW_KEY
    val title: String get() = shelf?.raw ?: "Now"

    companion object {
        const val NOW_KEY = "now"
        val now = RecoverySection(null)
        val defaults: List<RecoverySection> = listOf(now) + recoveryShelves.map { RecoverySection(it) }

        fun fromKey(key: String): RecoverySection? = defaults.firstOrNull { it.key == key }
    }
}

/** The sections in the person's order, and the ones they hid. Every known section is in [order] exactly once. */
data class RecoveryLayout(
    val order: List<RecoverySection> = RecoverySection.defaults,
    val hidden: Set<RecoverySection> = emptySet(),
) {
    val visible: List<RecoverySection> get() = order.filter { it !in hidden }
    val isDefault: Boolean get() = order == RecoverySection.defaults && hidden.isEmpty()

    fun isHidden(section: RecoverySection) = section in hidden

    fun toggled(section: RecoverySection): RecoveryLayout =
        copy(hidden = if (section in hidden) hidden - section else hidden + section)

    /** Moves the section at [from] to [to]; indices out of range leave the layout as it is. */
    fun moved(from: Int, to: Int): RecoveryLayout {
        if (from !in order.indices || to !in order.indices || from == to) return this
        val list = order.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        return copy(order = list)
    }

    companion object {
        /**
         * Reads the stored keys: unknown or repeated keys are dropped, and sections the app has gained since are
         * added at the end, so a stored layout never loses or doubles a shelf.
         */
        fun decode(orderText: String?, hiddenText: String?): RecoveryLayout {
            val seen = LinkedHashSet<RecoverySection>()
            orderText?.split(',')?.mapNotNull { RecoverySection.fromKey(it.trim()) }?.forEach { seen.add(it) }
            RecoverySection.defaults.forEach { if (it !in seen) seen.add(it) }
            val hidden = hiddenText?.split(',')?.mapNotNull { RecoverySection.fromKey(it.trim()) }?.toSet() ?: emptySet()
            return RecoveryLayout(seen.toList(), hidden)
        }
    }

    fun encodedOrder(): String = order.joinToString(",") { it.key }
    fun encodedHidden(): String = order.filter { it in hidden }.joinToString(",") { it.key }
}

/**
 * Keeps the layout on the phone, per account (the account's email is hashed into the key, never stored). There is no
 * Identity setting for it yet; `recovery.order` / `recovery.hidden` via `PUT /v1/me/settings` would fit the server's
 * free-form settings if it should follow the person to other devices later.
 */
class RecoveryLayoutStore(private val prefs: KeyValues, account: String?) {
    private val prefix = "recovery.layout.${accountKey(account)}"

    fun load(): RecoveryLayout = RecoveryLayout.decode(prefs.getString("$prefix.order"), prefs.getString("$prefix.hidden"))

    fun save(layout: RecoveryLayout) {
        if (layout.isDefault) {
            prefs.remove("$prefix.order")
            prefs.remove("$prefix.hidden")
            return
        }
        prefs.putString("$prefix.order", layout.encodedOrder())
        prefs.putString("$prefix.hidden", layout.encodedHidden())
    }

    companion object {
        internal fun accountKey(account: String?): String {
            val email = account?.trim()?.lowercase()
            if (email.isNullOrEmpty()) return "signed-out"
            val digest = MessageDigest.getInstance("SHA-256").digest(email.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

/** The store for whoever is signed in. UI tests keep their own file, so a walk never sees the app's own layout. */
@Composable
internal fun rememberRecoveryLayoutStore(app: AppState): RecoveryLayoutStore {
    val context = LocalContext.current.applicationContext
    val account = (app.authState as? AuthState.SignedIn)?.email
    val testing = app.flags.isUiTesting
    return remember(context, account, testing) {
        val name = if (testing) "recovery_uitesting" else "recovery_layout"
        RecoveryLayoutStore(SharedPrefsKeyValues(context.getSharedPreferences(name, Context.MODE_PRIVATE)), account)
    }
}

// MARK: Filter

/** The length chips. A mode's default length decides its band; 0 minutes is open-ended. */
enum class LengthBand(val label: String, val tag: String) {
    UpTo10("≤10 min", "short"),
    From10To20("10–20 min", "medium"),
    Over20("20+ min", "long"),
    Open("Open", "open");

    fun matches(minutes: Int): Boolean = when (this) {
        UpTo10 -> minutes in 1..10
        From10To20 -> minutes in 11..20
        Over20 -> minutes > 20
        Open -> minutes <= 0
    }
}

/** Which tiles show. Empty sets mean "any". */
data class RecoveryFilter(val lengths: Set<LengthBand> = emptySet(), val shelves: Set<Shelf> = emptySet()) {
    val isActive: Boolean get() = lengths.isNotEmpty() || shelves.isNotEmpty()

    fun matches(mode: Mode): Boolean =
        (lengths.isEmpty() || lengths.any { it.matches(mode.minutes) }) &&
            (shelves.isEmpty() || mode.shelf in shelves)

    fun toggled(band: LengthBand) = copy(lengths = if (band in lengths) lengths - band else lengths + band)
    fun toggled(shelf: Shelf) = copy(shelves = if (shelf in shelves) shelves - shelf else shelves + shelf)

    /** The pill's words: "≤10 min · Sleep", or counts once a part names more than two. */
    val summary: String
        get() {
            val parts = mutableListOf<String>()
            val bands = LengthBand.entries.filter { it in lengths }
            if (bands.isNotEmpty()) parts += if (bands.size <= 2) bands.joinToString(", ") { it.label } else "${bands.size} lengths"
            val picked = recoveryShelves.filter { it in shelves }
            if (picked.isNotEmpty()) parts += if (picked.size <= 2) picked.joinToString(", ") { it.raw } else "${picked.size} shelves"
            return parts.joinToString(" · ")
        }

    companion object {
        val saver: Saver<RecoveryFilter, String> = Saver(
            save = { filter ->
                filter.lengths.joinToString(",") { it.name } + "|" + filter.shelves.joinToString(",") { it.name }
            },
            restore = { text ->
                val (bands, shelves) = (text.split('|') + "").take(2)
                RecoveryFilter(
                    bands.split(',').mapNotNull { name -> LengthBand.entries.firstOrNull { it.name == name } }.toSet(),
                    shelves.split(',').mapNotNull { name -> Shelf.entries.firstOrNull { it.name == name } }.toSet(),
                )
            },
        )
    }
}
