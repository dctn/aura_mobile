package info.ibtaura.auraclarus.logbook

import androidx.compose.ui.graphics.Color
import info.ibtaura.aura.core.Checkin
import info.ibtaura.aura.core.CheckinKind
import info.ibtaura.aura.core.FoodEntry
import info.ibtaura.aura.core.InsomniaScale
import info.ibtaura.aura.core.LogEntry
import info.ibtaura.aura.core.LogType
import info.ibtaura.aura.core.NutritionMaths
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.SearchCategoryStyle
import java.time.Instant
import java.util.Locale

// The Logging page's categories, which entries belong to each, and how an entry reads in a history list. The data keys
// are the ones the Health Data service reads (catalogue-data.ts, derived-series.ts in aws_docs).

internal enum class LogCategory(val title: String, val symbol: String, val color: Color, val blurb: String) {
    mood("Mood & energy", "sun.max.fill", AuraColors.orange, "How you feel, your energy and what's on your mind."),
    stress("Stress", "brain.head.profile", SearchCategoryStyle.orange, "Pressure after work blocks, over the week and the year."),
    people("People & purpose", "person.2.fill", SearchCategoryStyle.pink, "Connection, good conversations and support at work."),
    body("Body measurements", "figure.arms.open", SearchCategoryStyle.blue, "Weight, body composition and blood pressure from your scale or cuff."),
    strength("Movement & strength", "figure.strengthtraining.traditional", AuraColors.burnout, "Strength sessions and time in front of the TV."),
    nutrition("Nutrition", "fork.knife", AuraColors.green, "Meals and calories, in the Calorie Tracker."),
    sleep("Sleep check", "bed.double.fill", AuraColors.indigo, "The Insomnia Severity Index: seven questions about the last two weeks.");

    val tag: String get() = "logging-category-$name"

    companion object {
        private val stressKeys = setOf("stressfulMonths", "challengeOrHindrance")
        private val peopleKeys = setOf("meaningfulConversations", "supportAtWork", "workChallenge")

        /** The category a log belongs to, or null (water lives on the Today card). */
        fun of(log: LogEntry): LogCategory? = when (log.type) {
            LogType.water -> null
            LogType.mood -> mood
            LogType.food -> nutrition
            LogType.measurement -> body
            LogType.strength -> strength
            LogType.custom -> when {
                log.data.keys.any { it in stressKeys } -> stress
                log.data.keys.any { it in peopleKeys } -> people
                log.has("tvTimeMin") -> strength
                else -> null
            }
        }

        fun of(checkin: Checkin): LogCategory? = when (checkin.kind) {
            CheckinKind.mood -> mood
            CheckinKind.connection -> people
            CheckinKind.block, CheckinKind.weekly -> stress
            CheckinKind.insomnia -> sleep
            CheckinKind.morning -> null
        }
    }
}

/** "Not at all" to "Very much", and the other word pairs under 1-5 scales. */
internal object ScaleWords {
    val lowHigh = "Very low" to "Very high"
    val agree = "Not at all" to "Very much"
    val stress = "None" to "The most"
    val moodFaces = listOf("Very low", "Low", "Okay", "Good", "Very good")
    val connectionFaces = listOf("Very alone", "A bit alone", "Okay", "Connected", "Very connected")
}

/** `challengeOrHindrance` is sent as a number so the series route can chart it (it reads numbers only). */
internal val challengeChoices: List<String> = listOf("A challenge", "A hindrance", "Both", "Neither")

/** One line in a history list. */
internal data class HistoryRow(
    val id: String,
    val at: Instant?,
    val date: String,
    val title: String,
    val detail: String,
    val symbol: String,
    val color: Color,
)

internal object LogText {
    private fun number(value: Double?): String? {
        value ?: return null
        return if (value == Math.floor(value)) "${value.toLong()}" else String.format(Locale.getDefault(), "%.1f", value)
    }

    private val measurementLabels = listOf(
        "weightKg" to ("Weight" to "kg"),
        "bodyFatPct" to ("Body fat" to "%"),
        "leanBodyMassKg" to ("Lean mass" to "kg"),
        "bodyWaterPct" to ("Body water" to "%"),
        "boneMassKg" to ("Bone mass" to "kg"),
        "muscleMassKg" to ("Muscle mass" to "kg"),
        "visceralFatScore" to ("Visceral fat" to ""),
    )

    fun row(log: LogEntry): HistoryRow {
        val category = LogCategory.of(log)
        val (title, detail) = words(log)
        val symbol = if (log.type == LogType.water) "drop.fill" else category?.symbol ?: "square.and.pencil"
        val color = if (log.type == LogType.water) SearchCategoryStyle.blue else category?.color ?: AuraColors.gray
        return HistoryRow(log.id, log.at, log.date, title, detail, symbol, color)
    }

    fun row(checkin: Checkin): HistoryRow {
        val category = LogCategory.of(checkin)
        val (title, detail) = words(checkin)
        return HistoryRow(checkin.id, checkin.takenAt, checkin.date, title, detail,
            category?.symbol ?: "sun.max.fill", category?.color ?: AuraColors.orange)
    }

    private fun words(log: LogEntry): Pair<String, String> = when (log.type) {
        LogType.water -> "Water" to "${number(log.number("ml")) ?: "?"} ml"
        LogType.food -> {
            val meal = FoodEntry.of(log)
            if (meal != null) {
                meal.name to "${meal.meal.label} · ${NutritionMaths.whole(meal.kcal)} kcal"
            } else {
                "Diet pattern" to (log.text("pattern") ?: "")
            }
        }
        LogType.mood -> {
            val parts = listOfNotNull(
                log.number("mood")?.let { "mood ${number(it)}/5" },
                log.number("energy")?.let { "energy ${number(it)}/5" },
                log.number("onEdge")?.let { "on edge ${number(it)}/5" },
                log.number("worry")?.let { "worry ${number(it)}/5" },
            )
            val title = when {
                log.has("energy") && parts.size == 1 -> "Energy"
                log.has("onEdge") && parts.size == 1 -> "Feeling on edge"
                log.has("worry") && parts.size == 1 -> "Worry about work"
                else -> "Mood log"
            }
            title to parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        }
        LogType.measurement -> {
            val parts = measurementLabels.mapNotNull { (key, label) ->
                number(log.number(key))?.let { "${label.first} $it${if (label.second.isEmpty()) "" else " ${label.second}"}" }
            }.toMutableList()
            val sys = number(log.number("bloodPressureSystolic"))
            val dia = number(log.number("bloodPressureDiastolic"))
            if (sys != null || dia != null) parts.add("BP ${sys ?: "?"}/${dia ?: "?"} mmHg")
            val title = if (parts.size == 1 && (sys != null || dia != null)) "Blood pressure" else "Body measurement"
            title to parts.joinToString(", ")
        }
        LogType.strength -> {
            val minutes = number(log.number("minutes"))
            val group = log.text("group")
            "Strength" to listOfNotNull(minutes?.let { "$it min" }, group).joinToString(" · ")
        }
        LogType.custom -> when {
            log.has("tvTimeMin") -> "TV time" to "${number(log.number("tvTimeMin"))} min"
            log.has("stressfulMonths") -> "Stressful months" to "${number(log.number("stressfulMonths"))} of the last 12"
            log.has("challengeOrHindrance") -> {
                val value = log.number("challengeOrHindrance")?.toInt()
                val word = value?.let { challengeChoices.getOrNull(it - 1) } ?: log.text("challengeOrHindrance") ?: ""
                "Challenge or hindrance" to word
            }
            log.has("meaningfulConversations") -> "Conversations" to "${number(log.number("meaningfulConversations"))} meaningful"
            log.has("supportAtWork") -> "Support at work" to "${number(log.number("supportAtWork"))}/5"
            log.has("workChallenge") -> "Work challenge" to "${number(log.number("workChallenge"))}/5"
            else -> "Note" to log.data.keys.joinToString(", ")
        }
    }

    private fun words(checkin: Checkin): Pair<String, String> = when (checkin.kind) {
        CheckinKind.mood -> "Mood" to (checkin.mood?.let { "${ScaleWords.moodFaces.getOrElse(it - 1) { "" }} · $it/5" } ?: "")
        CheckinKind.connection ->
            "Connection" to (checkin.connection?.let { "${ScaleWords.connectionFaces.getOrElse(it - 1) { "" }} · $it/5" } ?: "")
        CheckinKind.block -> "After a block" to listOfNotNull(
            checkin.effort?.let { "effort $it/5" },
            checkin.stress?.let { "stress $it/10" },
        ).joinToString(", ").replaceFirstChar { it.uppercase() }
        CheckinKind.weekly -> "Weekly exhaustion" to (checkin.exhaustion?.let { "$it/10" } ?: "")
        CheckinKind.insomnia -> "Sleep check" to (checkin.insomniaScore?.let { "$it/28 · ${InsomniaScale.band(it)}" } ?: "")
        CheckinKind.morning -> "Morning check-in" to listOfNotNull(
            checkin.alertness?.let { "alertness $it/9" },
            if (checkin.fellAsleepAt != null && checkin.wokeAt != null) "slept ${checkin.fellAsleepAt}–${checkin.wokeAt}" else null,
        ).joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    /** Logs and check-ins of the last 7 days (from `fromKey`), newest first. */
    fun history(logs: List<LogEntry>, checkins: List<Checkin>, fromKey: String, category: LogCategory? = null): List<HistoryRow> {
        val fromLogs = logs.filter { it.date >= fromKey && (category == null || LogCategory.of(it) == category) }.map { row(it) }
        val fromCheckins = checkins.filter { it.date >= fromKey && (category == null || LogCategory.of(it) == category) }.map { row(it) }
        return (fromLogs + fromCheckins).sortedWith(compareByDescending<HistoryRow> { it.date }.thenByDescending { it.at ?: Instant.MIN })
    }

    /** How many entries of `category` are dated `todayKey`. */
    fun todayCount(logs: List<LogEntry>, checkins: List<Checkin>, todayKey: String, category: LogCategory): Int {
        val fromLogs = logs.count { it.date == todayKey && LogCategory.of(it) == category && (category != LogCategory.nutrition || FoodEntry.of(it) != null) }
        return fromLogs + checkins.count { it.date == todayKey && LogCategory.of(it) == category }
    }
}
