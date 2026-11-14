package info.ibtaura.auraclarus.shell

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

// Instant saves: a sheet or editor shows the change at once and closes, and the write carries on in the app's scope.
// On success the screen takes the server's answer; on a failure it puts the change back and says why. Built only on
// AppState's public API (`launch`, `note`, `dataVersion`), so it needs nothing new in AppState.

/**
 * Runs a save in the app's scope, so it carries on when the sheet or page that started it goes away (a composable's
 * scope would cancel it as the editor closes). The change is already on screen: `onSaved` gets the server's answer.
 * On a failure the error goes to `note` first (missing consent, a lost session), then `onFailed` puts the screen back.
 */
fun <T> AppState.saveInBackground(
    write: suspend () -> T,
    onFailed: (Throwable) -> Unit,
    onSaved: (T) -> Unit = {},
): Job = launch {
    val result = try {
        write()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        note(e)
        onFailed(e)
        return@launch
    }
    onSaved(result)
}

/**
 * Lets a screen skip the reload its own save would cause. A save still bumps `app.dataVersion`, so other screens load
 * again, but the screen that saved already shows the change. Each screen keeps one (`remember { OwnSaves() }`) and asks
 * `shouldLoad` at the top of its `LaunchedEffect(app.dataVersion, ...)`.
 */
@Stable
class OwnSaves {
    private val mine = HashSet<Int>()
    private var seenVersion: Int? = null
    private var seenKeys: List<Any?>? = null

    /** Bumps `app.dataVersion` for the other screens, and marks the new value as this screen's own. */
    fun bump(app: AppState) {
        app.dataVersion += 1
        mark(app.dataVersion)
    }

    /** Marks `version` as one this screen bumped itself. */
    fun mark(version: Int) {
        mine += version
    }

    /**
     * True unless every bump since the last load was this screen's own and the other keys (a retry count, a day) are
     * unchanged. The first call always loads.
     */
    fun shouldLoad(version: Int, vararg keys: Any?): Boolean {
        val key = keys.toList()
        val last = seenVersion
        val onlyMine = last != null && key == seenKeys && version > last && (last + 1..version).all { it in mine }
        seenVersion = version
        seenKeys = key
        mine.removeAll { it <= version }
        return !onlyMine
    }
}

/**
 * Where a background save's failure is told: the screen's own message line while that screen shows, else (the editor
 * already closed and its page went with it) a short toast, so a refused save never goes unsaid.
 */
@Stable
class SaveMessenger internal constructor(private val context: Context) {
    internal var screen: ((String) -> Unit)? = null

    fun tell(words: String) {
        val line = screen
        if (line != null) line(words) else Toast.makeText(context, words, Toast.LENGTH_LONG).show()
    }
}

/** A [SaveMessenger] that shows on this screen through `onScreen` while it is composed. */
@Composable
fun rememberSaveMessenger(onScreen: (String) -> Unit): SaveMessenger {
    val context = LocalContext.current.applicationContext
    val messenger = remember(context) { SaveMessenger(context) }
    val current by rememberUpdatedState(onScreen)
    DisposableEffect(messenger) {
        messenger.screen = { current(it) }
        onDispose { messenger.screen = null }
    }
    return messenger
}
