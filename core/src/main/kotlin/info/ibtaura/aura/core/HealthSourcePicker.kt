package info.ibtaura.aura.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Android only: Health Connect can hold the same kind of data from several apps (Samsung Health and Oura, Garmin and
// Strava). Each brand measures differently, so a day's HRV averaged across two apps, or a run sent twice, is wrong.
// The picker keeps one app per metric, sticks with it across days so the normal stays on one brand's scale, and drops
// workouts another app already recorded.

/** The groups the user can set a main app for in Settings. */
enum class HealthSourceGroup(val raw: String, val title: String) {
    sleep("sleep", "Sleep"),
    heart("heart", "Heart"),
    workouts("workouts", "Workouts"),
    body("body", "Weight");

    companion object {
        fun fromRaw(raw: String): HealthSourceGroup? = entries.firstOrNull { it.raw == raw }
    }
}

/** Each metric that comes as raw records (totals are Health Connect's own aggregates, already settled). */
enum class HealthMetric(val raw: String, val group: HealthSourceGroup) {
    sleep("sleep", HealthSourceGroup.sleep),
    restingHr("restingHr", HealthSourceGroup.heart),
    hrv("hrv", HealthSourceGroup.heart),
    breaths("breaths", HealthSourceGroup.heart),
    spo2("spo2", HealthSourceGroup.heart),
    skinTemp("skinTemp", HealthSourceGroup.heart),
    weight("weight", HealthSourceGroup.body),
    workouts("workouts", HealthSourceGroup.workouts);

    companion object {
        fun fromRaw(raw: String): HealthMetric? = entries.firstOrNull { it.raw == raw }
    }
}

/** A metric's main app, and the last day it had data for that metric. */
data class HealthSourceChoice(val source: String, val lastSeen: LocalDate)

/** The apps Aura knows by package name, and which it prefers for each metric. */
object HealthSources {
    const val OURA = "com.ouraring.oura"
    const val WHOOP = "com.whoop.android"
    const val GARMIN = "com.garmin.android.apps.connectmobile"
    const val FITBIT = "com.fitbit.FitbitMobile"
    const val SAMSUNG = "com.sec.android.app.shealth"
    const val POLAR = "fi.polar.polarflow"
    const val WITHINGS = "com.withings.wiscale2"
    const val SUUNTO = "com.stt.android.suunto"
    const val ZEPP = "com.huami.watch.hmwatchmanager"
    const val MI_FITNESS = "com.xiaomi.wearable"
    const val MI_FIT = "com.xiaomi.hm.health"
    const val HUAWEI = "com.huawei.health"
    const val GOOGLE_FIT = "com.google.android.apps.fitness"
    const val STRAVA = "com.strava"

    private val names = mapOf(
        OURA to "Oura", WHOOP to "Whoop", GARMIN to "Garmin Connect", FITBIT to "Fitbit", SAMSUNG to "Samsung Health",
        POLAR to "Polar Flow", WITHINGS to "Withings", SUUNTO to "Suunto", ZEPP to "Zepp", MI_FITNESS to "Mi Fitness",
        MI_FIT to "Mi Fit", HUAWEI to "Huawei Health", GOOGLE_FIT to "Google Fit", STRAVA to "Strava",
    )

    /** Every app in the table, for checking which are installed. */
    val known: Set<String> get() = names.keys

    /** Apps that can write sleep into Health Connect: every app in the table except the workout and hub apps. */
    val sleepCapable: Set<String> get() = names.keys - STRAVA - GOOGLE_FIT

    /** Watches and bands first, then hubs and phone apps. Apps not listed come after all of these. */
    private val wearables = listOf(OURA, WHOOP, GARMIN, FITBIT, SAMSUNG, POLAR, SUUNTO, WITHINGS, ZEPP, MI_FITNESS, MI_FIT, HUAWEI)
    private val hubs = listOf(GOOGLE_FIT, STRAVA)

    /** The preferred order for one metric. */
    fun order(metric: HealthMetric): List<String> = when (metric) {
        HealthMetric.sleep, HealthMetric.restingHr, HealthMetric.hrv, HealthMetric.skinTemp,
        HealthMetric.breaths, HealthMetric.spo2 -> wearables + hubs
        HealthMetric.workouts -> listOf(GARMIN, POLAR, SUUNTO, FITBIT, SAMSUNG, WHOOP, OURA, ZEPP, MI_FITNESS, MI_FIT, HUAWEI, WITHINGS) + hubs
        HealthMetric.weight -> listOf(WITHINGS) + (wearables - WITHINGS) + hubs
    }

    /** Lower is better; apps not in the table share the last place. */
    fun rank(metric: HealthMetric, source: String): Int = order(metric).indexOf(source).let { if (it < 0) Int.MAX_VALUE else it }

    /** The name to show: the brand when known, else the package's last part ("com.example.walker" -> "walker"). */
    fun name(source: String): String = names[source] ?: source.substringAfterLast('.').ifBlank { source }
}

/** One day after picking: the input holding one app per metric, the choices to keep, and every app seen per group. */
data class HealthSourcePick(
    val input: DailyHealthInput,
    val choices: Map<HealthMetric, HealthSourceChoice>,
    val seen: Map<HealthSourceGroup, Set<String>>,
)

/** Several days after picking, oldest first. `lastSeen`: per group, each app's latest day with data. */
data class HealthSourceDays(
    val inputs: List<DailyHealthInput>,
    val choices: Map<HealthMetric, HealthSourceChoice>,
    val seen: Map<HealthSourceGroup, Set<String>>,
    val lastSeen: Map<HealthSourceGroup, Map<String, LocalDate>> = emptyMap(),
)

object HealthSourcePicker {
    /** A main app with no data for this many days in a row gives way to the best app that has some. */
    const val SWITCH_AFTER_DAYS = 3L

    /** Settings lists (and names as the main app) only apps with data in this many days; an old band drops off. */
    const val FORGET_AFTER_DAYS = 30L

    /** Two workouts are the same one when they overlap by more than this share of the shorter. */
    const val SAME_WORKOUT_OVERLAP = 0.5

    /**
     * The app to use for `metric` on `day`, from the apps that have data then (`amounts`: app -> how much it has), and
     * the metric's choice afterwards. In order: the user's main app when it has data; the current main app when it has
     * data; otherwise the best-ranked app (most data breaks a tie). That fallback becomes the main app only once the
     * main app has had no data for `SWITCH_AFTER_DAYS` days, so one missing day doesn't move the normal to another brand.
     */
    fun choose(
        metric: HealthMetric,
        amounts: Map<String, Double>,
        day: LocalDate,
        current: HealthSourceChoice?,
        override: String?,
    ): Pair<String?, HealthSourceChoice?> {
        if (amounts.isEmpty()) return null to current
        if (override != null && override in amounts) {
            return override to HealthSourceChoice(override, maxOf(day, current?.takeIf { it.source == override }?.lastSeen ?: day))
        }
        if (current != null && current.source in amounts) {
            return current.source to current.copy(lastSeen = maxOf(current.lastSeen, day))
        }
        val best = best(metric, amounts)
        if (current == null) return best to HealthSourceChoice(best, day)
        val missing = ChronoUnit.DAYS.between(current.lastSeen, day)
        return if (missing >= SWITCH_AFTER_DAYS) best to HealthSourceChoice(best, day) else best to current
    }

    /** The best-ranked app, then the one with most data, then by name so the pick is always the same. */
    fun best(metric: HealthMetric, amounts: Map<String, Double>): String =
        amounts.entries.sortedWith(
            compareBy<Map.Entry<String, Double>> { HealthSources.rank(metric, it.key) }
                .thenByDescending { it.value }
                .thenBy { it.key },
        ).first().key

    /** Days in any order; picked oldest first so the main apps move forward in time. */
    fun pickDays(
        inputs: List<DailyHealthInput>,
        choices: Map<HealthMetric, HealthSourceChoice>,
        overrides: Map<HealthSourceGroup, String>,
    ): HealthSourceDays {
        var current = choices
        val seen = mutableMapOf<HealthSourceGroup, MutableSet<String>>()
        val lastSeen = mutableMapOf<HealthSourceGroup, MutableMap<String, LocalDate>>()
        val out = inputs.sortedBy { it.day }.map { input ->
            val pick = pick(input, current, overrides)
            current = pick.choices
            pick.seen.forEach { (group, apps) ->
                seen.getOrPut(group) { mutableSetOf() }.addAll(apps)
                val days = lastSeen.getOrPut(group) { mutableMapOf() }
                apps.forEach { app -> days[app] = maxOf(days[app] ?: input.day, input.day) }
            }
            pick.input
        }
        return HealthSourceDays(out, current, seen, lastSeen)
    }

    /**
     * One day: each reading metric and the night keep one app's records, workouts recorded by two apps are kept once,
     * and `sources` lists the apps actually used (plus whatever the input already listed, e.g. the totals' apps).
     */
    fun pick(
        input: DailyHealthInput,
        choices: Map<HealthMetric, HealthSourceChoice>,
        overrides: Map<HealthSourceGroup, String>,
    ): HealthSourcePick {
        val next = choices.toMutableMap()
        val seen = mutableMapOf<HealthSourceGroup, MutableSet<String>>()
        val used = mutableListOf<String>()
        val window = DailyTotalsBuilder.dayWindow(input.day, input.zone)

        fun readings(metric: HealthMetric, list: List<HealthReading>): List<HealthReading> {
            val inDay = list.filter { it.at >= window.start && it.at < window.end }
            val amounts = inDay.groupingBy { it.source }.eachCount().mapValues { it.value.toDouble() }
            if (amounts.isNotEmpty()) seen.getOrPut(metric.group) { mutableSetOf() }.addAll(amounts.keys)
            val (source, choice) = choose(metric, amounts, input.day, next[metric], overrides[metric.group])
            choice?.let { next[metric] = it }
            if (source == null) return emptyList()
            used.add(source)
            return list.filter { it.source == source }
        }

        val restingHr = readings(HealthMetric.restingHr, input.restingHr)
        val hrv = readings(HealthMetric.hrv, input.hrvRmssd)
        val breaths = readings(HealthMetric.breaths, input.respiratoryRate)
        val spo2 = readings(HealthMetric.spo2, input.spo2)
        val skin = readings(HealthMetric.skinTemp, input.skinTempDelta)
        val weight = readings(HealthMetric.weight, input.weight)

        val sleepAmounts = asleepSeconds(input.sleep)
        if (sleepAmounts.isNotEmpty()) seen.getOrPut(HealthSourceGroup.sleep) { mutableSetOf() }.addAll(sleepAmounts.keys)
        val (sleepSource, sleepChoice) = choose(HealthMetric.sleep, sleepAmounts, input.day, next[HealthMetric.sleep], overrides[HealthSourceGroup.sleep])
        sleepChoice?.let { next[HealthMetric.sleep] = it }
        val sleep = if (sleepSource == null) emptyList() else input.sleep.filter { it.source == sleepSource }
        sleepSource?.let { used.add(it) }

        val dayWorkouts = input.workouts.filter { it.startedAt >= window.start && it.startedAt < window.end }
        val workoutAmounts = dayWorkouts.groupBy { it.source.orEmpty() }.mapValues { (_, list) -> list.sumOf { it.minutes }.toDouble() }
        if (workoutAmounts.isNotEmpty()) seen.getOrPut(HealthSourceGroup.workouts) { mutableSetOf() }.addAll(workoutAmounts.keys)
        val (workoutSource, workoutChoice) = choose(
            HealthMetric.workouts, workoutAmounts, input.day, next[HealthMetric.workouts], overrides[HealthSourceGroup.workouts],
        )
        workoutChoice?.let { next[HealthMetric.workouts] = it }
        val workouts = dedupeWorkouts(input.workouts, workoutSource)
        workouts.mapNotNull { it.source }.forEach { used.add(it) }

        val sources = (input.sources + used).filter { it.isNotBlank() }.distinct()
        val picked = input.copy(
            sources = sources,
            restingHr = restingHr,
            hrvRmssd = hrv,
            respiratoryRate = breaths,
            spo2 = spo2,
            skinTempDelta = skin,
            weight = weight,
            sleep = sleep,
            workouts = workouts,
        )
        return HealthSourcePick(picked, next, seen.mapValues { it.value.toSet() })
    }

    /**
     * Workouts kept once: going from the preferred app down (`first`, then the ranking), a workout overlapping one
     * already kept by more than half of the shorter is the same session from another app and is dropped.
     */
    fun dedupeWorkouts(workouts: List<HealthWorkout>, first: String?): List<HealthWorkout> {
        fun rank(w: HealthWorkout): Int {
            val source = w.source.orEmpty()
            if (first != null && source == first) return -1
            return HealthSources.rank(HealthMetric.workouts, source)
        }
        val kept = mutableListOf<HealthWorkout>()
        for (w in workouts.sortedWith(compareBy<HealthWorkout>({ rank(it) }, { it.startedAt }))) {
            if (kept.none { it.source != w.source && sameSession(it, w) }) kept.add(w)
        }
        return kept.sortedBy { it.startedAt }
    }

    /** More than half of the shorter workout overlaps the other. */
    internal fun sameSession(a: HealthWorkout, b: HealthWorkout): Boolean {
        val start = maxOf(a.startedAt, b.startedAt)
        val end = minOf(a.endedAt, b.endedAt)
        if (!end.isAfter(start)) return false
        val overlap = ChronoUnit.MILLIS.between(start, end).toDouble()
        val shorter = minOf(ChronoUnit.MILLIS.between(a.startedAt, a.endedAt), ChronoUnit.MILLIS.between(b.startedAt, b.endedAt))
        return shorter > 0 && overlap > shorter * SAME_WORKOUT_OVERLAP
    }

    /**
     * Last night from one app only (the sleep port's PUT /v1/sleep/nights): the user's main sleep app when it has the
     * night, else the main app, else the best one. Doesn't change the choices; the day sync does that.
     */
    fun nightSamples(
        samples: List<SleepSample>,
        current: HealthSourceChoice?,
        override: String?,
    ): List<SleepSample> {
        val amounts = asleepSeconds(samples)
        if (amounts.size <= 1) return samples
        val source = when {
            override != null && override in amounts -> override
            current != null && current.source in amounts -> current.source
            else -> best(HealthMetric.sleep, amounts)
        }
        return samples.filter { it.source == source }
    }

    /** Asleep seconds per app, overlaps inside one app counted once; apps with no asleep sample are left out. */
    internal fun asleepSeconds(samples: List<SleepSample>): Map<String, Double> =
        samples.groupBy { it.source }.mapValues { (_, list) ->
            SleepNightBuilder.merge(SleepNightBuilder.spans(list) { it.isAsleep }).sumOf { it.seconds }
        }.filterValues { it > 0 }
}
