package info.ibtaura.aura.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

// A port of AuraKit/Mode.swift.

@Serializable
enum class Shelf(val raw: String) {
    @SerialName("Focus") focus("Focus"),
    @SerialName("Breathe") breathe("Breathe"),
    @SerialName("Meditate") meditate("Meditate"),
    @SerialName("Sleep") sleep("Sleep"),
    @SerialName("Rest") rest("Rest"),
    @SerialName("Movement") movement("Movement"),
    @SerialName("Mind") mind("Mind"),
    @SerialName("Outside") outside("Outside"),
    @SerialName("People") people("People"),
    @SerialName("Fuel") fuel("Fuel");

    val id: String get() = raw

    companion object {
        fun fromRaw(raw: String): Shelf? = entries.firstOrNull { it.raw == raw }
    }
}

@Serializable
enum class RunKind(val raw: String) {
    @SerialName("timer") timer("timer"),
    @SerialName("flow") flow("flow"),
    @SerialName("breathe") breathe("breathe"),
    @SerialName("guided") guided("guided"),
    @SerialName("winddown") winddown("winddown"),
    @SerialName("plan") plan("plan");

    companion object {
        fun fromRaw(raw: String): RunKind? = entries.firstOrNull { it.raw == raw }
    }
}

/** One breath in seconds: in, an optional hold, out, an optional hold after. */
@Serializable
data class BreathPattern(
    val `in`: Double,
    val hold: Double? = null,
    val out: Double,
    val hold2: Double? = null,
) {
    /** "In 4 · Hold 4 · Out 4 · Hold 4", leaving out empty holds. */
    val line: String
        get() {
            val parts = mutableListOf("In ${seconds(`in`)}")
            hold?.let { if (it > 0) parts.add("Hold ${seconds(it)}") }
            parts.add("Out ${seconds(out)}")
            hold2?.let { if (it > 0) parts.add("Hold ${seconds(it)}") }
            return parts.joinToString(" · ")
        }

    companion object {
        val coherent = BreathPattern(`in` = 5.0, out = 5.0)

        /** "4" for a whole number, else as written, "4.5". */
        private fun seconds(value: Double): String =
            if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }
}

/** Where a plan mode's calendar block goes (Swift returns the tuple `(start, end)`). */
data class PlanSlot(val start: Instant, val end: Instant)

/** One entry of modes.json (65 modes). */
@Serializable
data class Mode(
    val id: String,
    val name: String,
    /** The short tile label. */
    val word: String,
    val shelf: Shelf,
    /** The default length; 0 is open-ended (Flow). */
    val minutes: Int,
    val choices: List<Int>? = null,
    /** The session kind it saves: recovery, walk, focus or meeting. */
    val kind: String = "recovery",
    val run: RunKind = RunKind.timer,
    /** One short plain line, shown on the detail page and the run. */
    val note: String = "",
    /** The "Why this Works" text; null hides that card. */
    val evidence: String? = null,
    val pattern: BreathPattern? = null,
    val prompts: List<String>? = null,
    /** Hours of the day the mode shouldn't be suggested in. */
    val avoidHours: List<Int>? = null,
    /** Hours it suits best. */
    val bestHours: List<Int>? = null,
    /** Copy for the Now carousel card; null keeps it out of Now. */
    val nowTitle: String? = null,
    /** True for a run with a black screen. */
    val night: Boolean? = null,
) {
    /** The backend's session mode for this mode's run. */
    val sessionMode: String
        get() = when {
            run == RunKind.flow -> "flowtime"
            kind == "meeting" -> "meeting"
            kind == "walk" -> "walk"
            else -> "timer"
        }

    /**
     * What a recovery-kind mode sends as recoveryType; null for other kinds. A nap by its id, then by shelf:
     * Outside is time outside, People is talking with someone, Movement is a walk, and the rest is rest.
     */
    val recoveryType: RecoveryType?
        get() {
            if (kind != "recovery") return null
            if (id.contains("nap")) return RecoveryType.nap
            return when (shelf) {
                Shelf.outside -> RecoveryType.outside
                Shelf.people -> RecoveryType.social
                Shelf.movement -> RecoveryType.walk
                else -> RecoveryType.rest
            }
        }

    /** The Focus shelf is the only one with the 3-2-1 first (frame 02). */
    val hasCountdown: Boolean get() = shelf == Shelf.focus

    /** The small caps line over a run's title, e.g. "BREATHE · 5 MIN". */
    fun kicker(minutes: Int): String =
        if (minutes == 0) "$runWord · OPEN" else "$runWord · $minutes MIN"

    /** Frames 03 to 11: said by what the run does, so a walk on any shelf says MOVE. */
    private val runWord: String
        get() {
            if (shelf == Shelf.focus) return if (kind == "meeting") "MEETING" else "FOCUS"
            if (run == RunKind.breathe || pattern != null) return "BREATHE"
            if (run == RunKind.guided) return "MEDITATE"
            if (night == true) return "NAP"
            if (kind == "walk") return "MOVE"
            return "RECOVERY"
        }

    /** True when the search text is in the name or the tile word. Empty text matches everything. */
    fun matches(search: String): Boolean {
        val query = search.trim()
        if (query.isEmpty()) return true
        return name.contains(query, ignoreCase = true) || word.contains(query, ignoreCase = true)
    }

    /**
     * Where a plan mode's calendar block goes: its fixed start today while that's still ahead, otherwise tomorrow.
     * A day off starts at 09:00 and an evening without a drink at 18:00, as on the website.
     */
    fun planSlot(now: Instant, zone: ZoneId = ZoneId.systemDefault()): PlanSlot {
        val hour = if (id == "alcohol-free-evening") 18 else 9
        val local = ZonedDateTime.ofInstant(now, zone)
        val minuteNow = local.hour * 60 + local.minute
        val today = local.toLocalDate()
        val daysAhead = if (minuteNow < hour * 60) 0L else 1L
        val day = today.plusDays(daysAhead)
        val start = day.atTime(hour, 0).atZone(zone).toInstant()
        val length = if (minutes > 0) minutes else 60
        return PlanSlot(start = start, end = start.plusSeconds(length * 60L))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The length pill: "10m", "8h", "1h 30m", "Open". */
        fun lengthText(minutes: Int): String {
            if (minutes <= 0) return "Open"
            if (minutes < 60) return "${minutes}m"
            val hours = minutes / 60
            val rest = minutes % 60
            return if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
        }

        /** Picks the Now cards: not in avoided hours, best-hour modes first, at most `limit`. */
        fun nowCards(from: List<Mode>, hour: Int, limit: Int = 4): List<Mode> {
            val open = from.filter { it.nowTitle != null && !(it.avoidHours ?: emptyList()).contains(hour) }
            val best = open.filter { (it.bestHours ?: emptyList()).contains(hour) }
            val rest = open.filter { !(it.bestHours ?: emptyList()).contains(hour) }
            return (best + rest).take(limit)
        }

        /** The modes in a modes.json text; empty when it can't be read (as the iOS app's `loadModes`). */
        fun decodeCatalogue(text: String): List<Mode> =
            runCatching { json.decodeFromString(ListSerializer(serializer()), text) }.getOrDefault(emptyList())

        /** The modes in core's own `modes.json` resource; empty when it's missing or can't be read. */
        fun loadCatalogue(): List<Mode> {
            val stream = Mode::class.java.getResourceAsStream("/modes.json") ?: return emptyList()
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            return decodeCatalogue(text)
        }
    }
}
