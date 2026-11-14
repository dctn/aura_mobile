package info.ibtaura.aura.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

// Session history for StubApi: a month of believable sessions, from two days ago back (yesterday and today stay the
// stub's own live sessions, so Home's list and the history agree). Deterministic: the same day always has the same
// sessions. Ids are "h-<yyyy-MM-dd>-<n>".

internal object StubSessionHistory {
    /** How far back the seeds go. */
    const val days = 40

    private const val prefix = "h-"

    fun isSeed(id: String): Boolean = id.startsWith(prefix) && dayOf(id) != null

    /** The day a seed id names. */
    private fun dayOf(id: String): LocalDate? = DayKey.localDate(id.removePrefix(prefix).take(10))

    private data class Plan(
        val hour: Int,
        val minute: Int,
        val kind: String,
        val title: String,
        val minutes: Int,
        val mode: String,
        val recoveryType: RecoveryType? = null,
    )

    /** One day's plan by how many days back it is: a rest day every sixth day, lighter weekends. */
    private fun plans(back: Int, day: LocalDate): List<Plan> {
        if (back % 6 == 5) return emptyList()
        val weekend = day.dayOfWeek.value >= 6
        val list = mutableListOf<Plan>()
        list.add(Plan(9, 30, "focus", if (back % 2 == 0) "Deep work" else "Write the review", if (weekend) 30 else 50, "timer"))
        if (!weekend && back % 2 == 1) list.add(Plan(11, 0, "meeting", "Team sync", 30, "meeting"))
        if (back % 3 != 2) list.add(Plan(13, 15, "recovery", "Walk", 15, "walk", RecoveryType.walk))
        if (!weekend) list.add(Plan(15, 0, "focus", if (back % 3 == 0) "Plan the week" else "Reply to Ana", 40, "flowtime"))
        if (back % 5 == 0) list.add(Plan(16, 30, "brainstorm", "Walking brainstorm", 20, "walk"))
        if (back % 4 == 1) list.add(Plan(17, 10, "recovery", "Rest (no screen)", 20, "timer", RecoveryType.rest))
        return list
    }

    /** Every seed from `days` back to two days ago, oldest first. `ratings` replaces a focus seed's difficulty. */
    fun seeds(today: LocalDate, zone: ZoneId, ratings: Map<String, Difficulty> = emptyMap()): List<SessionDetail> =
        (days downTo 2).flatMap { back -> seedsOn(today.minusDays(back.toLong()), back, zone, ratings) }

    private fun seedsOn(day: LocalDate, back: Int, zone: ZoneId, ratings: Map<String, Difficulty>): List<SessionDetail> {
        var capacity = 82.0 - (back % 7)
        return plans(back, day).mapIndexed { index, plan ->
            val id = "$prefix${DayKey.string(day)}-$index"
            val start = day.atTime(plan.hour, plan.minute).atZone(zone).toInstant()
            val end = start.plusSeconds(plan.minutes * 60L)
            val interruptions = if (plan.kind == "focus") (back + index) % 4 else 0
            val active = if (plan.kind == "focus") plan.minutes - interruptions * 2 else plan.minutes
            val difficulty = if (plan.kind == "focus") ratings[id] ?: defaultRating(back, index) else null
            val cost = when (plan.kind) {
                "focus" -> StubApi.charge(difficulty).cost * active / 50.0
                "meeting" -> 0.3
                "recovery" -> -1.2
                else -> 0.2
            }
            val before = capacity
            capacity -= cost
            val outcome = when {
                plan.kind == "focus" && back % 9 == 3 && index == 0 -> "stopped"
                else -> "completed"
            }
            SessionDetail(
                record = SessionRecord(
                    id = id, kind = plan.kind, title = plan.title, startedAt = start, endedAt = end, minutes = active,
                    outcome = outcome, difficulty = difficulty, recoveryType = plan.recoveryType,
                    capacityCost = if (cost > 0) round1(cost) else null,
                ),
                mode = plan.mode,
                state = "ended",
                date = DayKey.string(day),
                plannedMinutes = if (plan.mode == "flowtime") null else plan.minutes,
                wallMinutes = plan.minutes,
                activeMinutes = active,
                protectedMinutes = (active - interruptions * 3).coerceAtLeast(0),
                interruptions = interruptions,
                capacity = SessionCapacity(before = round1(before), after = round1(capacity), cost = round1(cost), charged = true),
                notes = if (plan.kind == "brainstorm") notes(start) else emptyList(),
                xpEarned = if (plan.kind == "focus") active else null,
            )
        }
    }

    /** Some focus seeds are already rated, some aren't (so the detail page asks). */
    private fun defaultRating(back: Int, index: Int): Difficulty? = when ((back + index) % 5) {
        0 -> Difficulty.normal
        1 -> Difficulty.hard
        2 -> null
        3 -> Difficulty.light
        else -> null
    }

    private fun notes(start: Instant): List<SessionNote> = listOf(
        SessionNote(start.plusSeconds(4 * 60), "Try a shorter onboarding: three screens, not five."),
        SessionNote(start.plusSeconds(13 * 60), "Ask Ana about the review dates before Friday."),
    )

    /** A seed by id, with its rating applied, or null. */
    fun find(id: String, today: LocalDate, zone: ZoneId, ratings: Map<String, Difficulty>): SessionDetail? {
        val day = dayOf(id) ?: return null
        val back = (today.toEpochDay() - day.toEpochDay()).toInt()
        if (back < 2 || back > days) return null
        return seedsOn(day, back, zone, ratings).firstOrNull { it.id == id }
    }

    /** A believable trace for a seed: heart rate a minute through it, stress for its hours. */
    fun trace(seed: SessionDetail, zone: ZoneId): SessionTrace {
        val start = seed.record.startedAt ?: return SessionTrace(status = "noData")
        val minutes = seed.wallMinutes.coerceAtLeast(1)
        val resting = seed.record.kind == "recovery"
        val base = if (resting) 78.0 else 70.0
        val points = (0..minutes).map { minute ->
            val drift = if (resting) -12.0 * minute / minutes else 6.0 * sin(PI * minute / minutes)
            val wobble = 2.5 * sin(minute * 0.9) + 1.5 * sin(minute * 2.3)
            // A few minutes without a reading, as a band slipping on the wrist gives.
            val bpm = if (minute % 17 == 9) null else round1(base + drift + wobble)
            HeartRatePoint(start.plusSeconds(minute * 60L), bpm)
        }
        val firstHour = start.atZone(zone).hour
        val lastHour = start.plusSeconds(minutes * 60L).atZone(zone).hour
        val stress = (firstHour..lastHour).mapIndexed { index, hour ->
            StressPoint(hour, if (resting) 42.0 - index * 8 else 38.0 + index * 6)
        }
        val known = points.mapNotNull { it.bpm }
        return SessionTrace(
            status = "ready",
            stressDrop = if (resting) 9.0 else null,
            heartRate = points,
            stress = stress,
            avgHr = if (known.isEmpty()) null else round1(known.average()),
        )
    }

    /** The XP rules of services/session/src/service/rewards.ts over `sessions` (ended focus sessions earn their minutes). */
    fun rewards(sessions: List<SessionDetail>): Rewards {
        val focus = sessions.filter { it.record.isFocus && it.hasEnded }.sortedBy { it.record.startedAt ?: Instant.MIN }
        val xp = focus.sumOf { it.activeMinutes }
        val perLevel = 60
        val level = xp / perLevel + 1
        val stage = minOf(plantNames.size - 1, level - 1)
        val badges = badgeRules.map { (id, title, needed) ->
            var running = 0
            val reached = focus.firstOrNull { running += it.activeMinutes; running >= needed }
            RewardBadge(id = id, title = title, requirementMinutes = needed, unlocked = xp >= needed,
                unlockedAt = if (xp >= needed) reached?.record?.endedAt else null)
        }
        return Rewards(xp = xp, level = level, xpIntoLevel = xp % perLevel, xpPerLevel = perLevel, plantStage = stage,
            plantName = plantNames[stage], badges = badges, totalFocusMinutes = xp, sessionsCompleted = focus.size)
    }

    private val plantNames = listOf("Seed", "Sprout", "Seedling", "Young Tree", "Flourishing", "Ancient Oak")
    private val badgeRules = listOf(
        Triple("first", "First Focus", 1),
        Triple("hour", "Hour of Power", 60),
        Triple("deep", "Deep Diver", 300),
        Triple("master", "Focus Master", 1000),
    )

    /** The server's CSV columns (services/session/src/service/queries.ts), filled from what the stub knows. */
    fun csv(sessions: List<SessionDetail>, zone: ZoneId): String {
        val columns = listOf(
            "id", "date", "kind", "mode", "title", "taskId", "blockId", "workType", "device", "startedBy", "startedAt",
            "endedAt", "outcome", "plannedMinutes", "minutes", "activeMinutes", "protectedMinutes", "interruptions",
            "appSwitches", "phonePickups", "notificationsOpened", "onTaskMinutes", "breaks", "pauses", "capacityCost",
        )
        val rows = sessions.sortedBy { it.record.startedAt ?: Instant.MIN }.map { s ->
            val r = s.record
            val values = mapOf(
                "id" to r.id,
                "date" to (s.date ?: r.startedAt?.let { DayKey.string(it, zone) }),
                "kind" to r.kind,
                "mode" to s.mode,
                "title" to r.title,
                "device" to "phone",
                "startedBy" to "user",
                "startedAt" to r.startedAt?.toString(),
                "endedAt" to r.endedAt?.toString(),
                "outcome" to r.outcome,
                "plannedMinutes" to s.plannedMinutes?.toString(),
                "minutes" to s.wallMinutes.toString(),
                "activeMinutes" to s.activeMinutes.toString(),
                "protectedMinutes" to s.protectedMinutes.toString(),
                "interruptions" to s.interruptions.toString(),
                "breaks" to "0",
                "pauses" to "0",
                "capacityCost" to s.capacity?.cost?.toString(),
            )
            columns.joinToString(",") { csvCell(values[it]) }
        }
        return (listOf(columns.joinToString(",")) + rows).joinToString("\r\n") + "\r\n"
    }

    /** As the server's csvCell: quoted when it holds a comma, quote or line break; a leading = + - @ is defused. */
    internal fun csvCell(value: String?): String {
        if (value == null) return ""
        var text = value
        if (text.isNotEmpty() && text[0] in "=+-@") text = "'$text"
        return if (text.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${text.replace("\"", "\"\"")}\"" else text
    }

    private fun round1(value: Double): Double = String.format(Locale.ROOT, "%.1f", value).toDouble()
}
