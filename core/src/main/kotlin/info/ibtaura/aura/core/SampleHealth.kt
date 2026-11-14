package info.ibtaura.aura.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The health half of sample data: weeks of made-up days for one healthy-ish adult, so every metric screen has
 * something to show without a backend. Each value is worked out from its date alone, so a day always reads the same.
 * Nothing is after `today`, and nothing is older than [historyDays] days.
 *
 * The days hang together: a busy stretch of work raises stress and shortens sleep, short nights lower HRV, recovery
 * and the capacity score, and the alerts follow from them. Field names, units and catalogue rows are the Health Data
 * service's (metric-fields.ts, desktop-sections-data.ts).
 */
internal object SampleHealth {
    /** Days of history, today included. */
    const val historyDays = 45

    // MARK: Catalogue

    fun catalogue(): List<CatalogueSection> = sections

    /** The burnout risk words Health Data sends, by score. */
    fun risk(score: Int): String = when {
        score >= 72 -> "Critical"
        score >= 55 -> "High"
        score >= 32 -> "Moderate"
        else -> "Low"
    }

    // MARK: Reads

    fun availability(today: LocalDate, from: String, to: String): List<FieldAvailability> {
        val days = window(today, from, to) ?: return emptyList()
        val out = mutableListOf<FieldAvailability>()
        for (field in availableFields) {
            var e = days.last
            while (e >= days.first) {
                val flag = formats[field] == "bool"
                val number = number(field, e)
                val text = if (number == null) text(field, e) else null
                if (number != null || text != null) {
                    val key = DayKey.string(LocalDate.ofEpochDay(e))
                    out.add(
                        if (flag) FieldAvailability(field, key, latestText = if (number != 0.0) "Yes" else "No")
                        else FieldAvailability(field, key, latestNumber = number, latestText = text),
                    )
                    break
                }
                e -= 1
            }
        }
        return out
    }

    fun series(field: String, from: String, to: String, today: LocalDate): MetricSeries {
        val days = window(today, from, to) ?: return MetricSeries(field, units[field], emptyList())
        val points = days.mapNotNull { e ->
            number(field, e)?.let { MetricPoint(DayKey.string(LocalDate.ofEpochDay(e)), it) }
        }
        return MetricSeries(field, units[field], points)
    }

    fun dailyMetrics(from: String, to: String, today: LocalDate): List<DailyMetrics> {
        val days = window(today, from, to) ?: return emptyList()
        return days.map { e ->
            val score = number("burnoutIndexScore", e)
            DailyMetrics(
                date = DayKey.string(LocalDate.ofEpochDay(e)),
                burnoutIndexScore = score,
                burnoutIndexRisk = score?.let { risk(it.toInt()) },
            )
        }
    }

    /** 100 is the person's normal. Attention counts the Quick checks taken here. */
    fun scores(today: LocalDate, checksCounted: Int): ScoresToday {
        val e = today.toEpochDay()
        val lift = (zoneScore(today) - 72).toDouble()
        return ScoresToday(
            attention = Score("Attention", attention(e), checksCounted = checksCounted),
            memory = Score("Memory", whole(90 + lift * 0.35 + 4 * white(e, 71))),
            thinking = Score("Thinking", whole(94 + lift * 0.45 + 4 * white(e, 72))),
            focus = Score("Focus", focusScore(e)),
        )
    }

    /** Capacity score 0-100 for a day; drives day zones and Find a time. */
    fun zoneScore(day: LocalDate): Int = capacity(day.toEpochDay())

    /** Today's alerts and one for each of the two days before, as Health Data's rules would raise them. */
    fun alerts(from: String, to: String, today: LocalDate): List<AlertItem> {
        val days = window(today, from, to) ?: return emptyList()
        val first = max(days.first, today.toEpochDay() - 2)
        if (first > days.last) return emptyList()
        val out = mutableListOf<AlertItem>()
        for (e in first..days.last) {
            val isToday = e == today.toEpochDay()
            val picked = alertsFor(e).take(if (isToday) 2 else 1)
            picked.forEach { out.add(it.copy(done = !isToday)) }
        }
        return out
    }

    // MARK: Days

    /** The days of [from, to] that have data, as epoch days. Null when a key isn't a date. */
    private fun window(today: LocalDate, from: String, to: String): LongRange? {
        val start = DayKey.localDate(from)?.toEpochDay() ?: return null
        val end = DayKey.localDate(to)?.toEpochDay() ?: return null
        val last = today.toEpochDay()
        val lo = max(start, last - historyDays + 1)
        val hi = min(end, last)
        return if (lo > hi) null else lo..hi
    }

    private enum class Sport { gym, run, swim, cycle, snow, paddle }

    private class Workout(val sport: Sport, val minutes: Int, val kcal: Int, val avgHr: Int, val distanceM: Int)

    /** One day: the night that ended that morning, then the day itself. */
    private class Base(
        val e: Long,
        val weekend: Boolean,
        /** Work pressure, 0-1. */
        val load: Double,
        val sleepMin: Int,
        val deepMin: Int,
        val remMin: Int,
        val lightMin: Int,
        val awakeMin: Int,
        val latencyMin: Int,
        /** Minutes from midnight to getting into bed; negative is the evening before. */
        val bedtimeMin: Int,
        val restingHr: Int,
        val rmssd: Double,
        val stress: Int,
        val walkSteps: Int,
        val workouts: List<Workout>,
        val weightKg: Double,
        val weighed: Boolean,
        val sleepScore: Int,
        val recovery: Int,
        val readiness: Int,
        val focusMin: Int,
        val meetingCount: Int,
        val meetingMin: Int,
    ) {
        val inBedMin: Int get() = sleepMin + awakeMin + latencyMin
        val efficiency: Double get() = sleepMin * 100.0 / inBedMin
        val steps: Int get() = steps(walkSteps, workout(Sport.run)?.distanceM ?: 0)
        val activeKcal: Int get() = (walkSteps * 0.036 + workouts.sumOf { it.kcal }).roundToInt()
        val restingKcal: Int get() = (1665 + 8 * white(e, 40)).roundToInt()
        val exerciseMin: Int get() = workouts.sumOf { it.minutes } + (18 + 7 * white(e, 41)).roundToInt()
        val strain: Int get() = clamp(18 + activeKcal / 11.0, 10.0, 90.0).roundToInt()
        val eveningHrv: Int get() = (rmssd - 5 + 3 * white(e, 42)).roundToInt()
        val deepWorkMin: Int get() = (focusMin * 0.62).roundToInt()

        fun workout(sport: Sport): Workout? = workouts.firstOrNull { it.sport == sport }
    }

    private val cache = object : LinkedHashMap<Long, Base>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Base>?): Boolean = size > 600
    }

    private fun base(e: Long): Base = synchronized(cache) { cache[e] } ?: make(e).also { synchronized(cache) { cache[e] = it } }

    private fun dow(e: Long): DayOfWeek = LocalDate.ofEpochDay(e).dayOfWeek

    private fun isWeekend(e: Long): Boolean = dow(e) == DayOfWeek.SATURDAY || dow(e) == DayOfWeek.SUNDAY

    /** Work pressure, 0-1: a four-week cycle, weekdays above weekends. */
    private fun load(e: Long): Double {
        val week = when (dow(e)) {
            DayOfWeek.MONDAY -> 0.05
            DayOfWeek.FRIDAY -> 0.0
            DayOfWeek.SATURDAY -> -0.3
            DayOfWeek.SUNDAY -> -0.25
            else -> 0.1
        }
        return clamp(0.5 + 0.28 * sin(2 * PI * e / 26.0) + 0.14 * smooth(e, 1) + 0.08 * white(e, 2) + week, 0.0, 1.0)
    }

    /** The night that ended on the morning of `e`: longer into the weekend, shorter after a busy day. */
    private fun sleepMinutes(e: Long): Int {
        val bonus = when (dow(e)) {
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> 38.0
            DayOfWeek.MONDAY -> -10.0
            else -> 0.0
        }
        var m = 420 + bonus - 50 * (load(e - 1) - 0.5) + 20 * smooth(e, 3) + 14 * white(e, 4)
        if (white(e, 5) > 0.8) m -= 55
        return clamp(m, 340.0, 490.0).roundToInt()
    }

    private fun make(e: Long): Base {
        val day = dow(e)
        val weekend = isWeekend(e)
        val load = load(e)
        val before = load(e - 1)
        val sleep = sleepMinutes(e)
        val deepShare = 0.165 + 0.025 * white(e, 8) + (sleep - 420) / 4000.0
        val deep = (sleep * deepShare).roundToInt()
        val rem = (sleep * (0.225 + 0.02 * white(e, 9))).roundToInt()
        val awake = (16 + 9 * abs(white(e, 10)) + 10 * before).roundToInt()
        val latency = (8 + 3 * (white(e, 11) + 1) + 8 * before).roundToInt()
        val lateStart = if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) 15.0 else -45.0
        val bedtime = (lateStart + 25 * smooth(e, 6) + 12 * white(e, 7) + 25 * (before - 0.5)).roundToInt()
        val sleep3 = (sleep + sleepMinutes(e - 1) + sleepMinutes(e - 2)) / 3.0
        val fit = fitness(e)
        val rhr = clamp(60.5 + 4 * (before - 0.5) - 1.5 * fit + 0.03 * (420 - sleep3) + 1.2 * white(e, 12), 56.0, 66.0)
            .roundToInt()
        val rmssd = r1(clamp(47 - 2.2 * (rhr - 60) - 5 * (before - 0.5) + 3.5 * smooth(e, 13) + 2 * white(e, 14), 28.0, 70.0))
        val stress = clamp(24 + 40 * load + 0.06 * (420 - sleep) + 5 * white(e, 15), 20.0, 70.0).roundToInt()
        val walk = when (day) {
            DayOfWeek.SATURDAY -> 8000 + 2200 * white(e, 18)
            DayOfWeek.SUNDAY -> 6000 + 1800 * white(e, 18)
            else -> 6200 + 1300 * smooth(e, 16) + 900 * white(e, 17) - 1400 * (load - 0.5)
        } + 500 * fit
        val workouts = workouts(e)
        val inBed = sleep + awake + latency
        val efficiency = sleep * 100.0 / inBed
        val sleepScore = clamp(52 + (sleep - 360) * 0.2 + (efficiency - 85) * 0.9 + (deepShare - 0.15) * 90 + 2 * white(e, 19),
            45.0, 95.0).roundToInt()
        val recovery = clamp(58 + (rmssd - 45) * 1.1 + (sleep - 420) * 0.08 - (rhr - 60) * 1.2 - 10 * (before - 0.5) +
            3 * white(e, 20), 30.0, 95.0).roundToInt()
        val readiness = clamp(0.4 * sleepScore + 0.4 * recovery + 0.2 * (100 - stress), 25.0, 95.0).roundToInt()
        val meetings = if (weekend) 0 else clamp(2.5 + 2.5 * load + 1.2 * white(e, 21), 0.0, 7.0).roundToInt()
        val meetingMin = if (meetings == 0) 0 else max(15, (meetings * 32 + 10 * white(e, 22)).roundToInt())
        val focus = when (day) {
            DayOfWeek.SATURDAY -> 20 + 15 * white(e, 23)
            DayOfWeek.SUNDAY -> 10 + 10 * white(e, 23)
            else -> 160 + 45 * smooth(e, 24) + 30 * white(e, 23) - 0.35 * meetingMin + (sleep - 420) * 0.3
        }
        val weight = r1(72.0 + 0.7 * sin(2 * PI * e / 97.0) + 0.25 * white(e, 30))
        val weighed = (day == DayOfWeek.MONDAY || day == DayOfWeek.WEDNESDAY || day == DayOfWeek.FRIDAY ||
            day == DayOfWeek.SUNDAY) && (day == DayOfWeek.MONDAY || white(e, 31) > -0.6)
        return Base(
            e = e, weekend = weekend, load = load, sleepMin = sleep, deepMin = deep, remMin = rem,
            lightMin = sleep - deep - rem, awakeMin = awake, latencyMin = latency, bedtimeMin = bedtime, restingHr = rhr,
            rmssd = rmssd, stress = stress, walkSteps = clamp(walk, 3600.0, 12000.0).roundToInt(), workouts = workouts,
            weightKg = weight, weighed = weighed, sleepScore = sleepScore, recovery = recovery, readiness = readiness,
            focusMin = max(0, focus.roundToInt()), meetingCount = meetings, meetingMin = meetingMin,
        )
    }

    /** A run replaces some walking, and the biggest days level off below 13,000 steps. */
    private fun steps(walk: Int, runMetres: Int): Int {
        var steps = walk - runMetres * 0.4 + runMetres * 0.95
        if (steps > 11_000) steps = 11_000 + (steps - 11_000) * 0.3
        return clamp(steps, 4000.0, 13000.0).roundToInt()
    }

    /** A slow rise and fall in fitness over three months, -1 to 1. */
    private fun fitness(e: Long): Double = sin(2 * PI * e / 90.0)

    /**
     * A fortnight's training: gym Monday and most Fridays, runs Tuesday and most Thursdays, a swim on Wednesday, a
     * long run or a day in the snow on Saturday, a ride on Sunday with a paddle every other week.
     */
    private fun workouts(e: Long): List<Workout> {
        val fortnight = Math.floorMod(e, 14L)
        fun gym(): Workout {
            val minutes = (55 + 10 * white(e, 101)).roundToInt()
            return Workout(Sport.gym, minutes, minutes * 5, (118 + 6 * white(e, 102)).roundToInt(), 0)
        }
        fun run(km: Double, spread: Double): Workout {
            val metres = ((km + spread * white(e, 103)) * 1000).roundToInt()
            val pace = runPace(e, km > 8)
            val minutes = (metres / 1000.0 * pace / 60).roundToInt()
            return Workout(Sport.run, minutes, (metres / 1000.0 * 72).roundToInt(), (153 + 5 * white(e, 104)).roundToInt(), metres)
        }
        return when (dow(e)) {
            DayOfWeek.MONDAY -> listOf(gym())
            DayOfWeek.TUESDAY -> listOf(run(6.5, 1.0))
            DayOfWeek.WEDNESDAY -> {
                val metres = (1500 + 300 * white(e, 105)).roundToInt()
                val minutes = (metres / 100.0 * swimPace(e) / 60).roundToInt()
                listOf(Workout(Sport.swim, minutes, (minutes * 10.5).roundToInt(), (130 + 5 * white(e, 106)).roundToInt(), metres))
            }
            DayOfWeek.THURSDAY -> if (white(e, 107) > -0.5) listOf(run(5.0, 1.0)) else emptyList()
            DayOfWeek.FRIDAY -> if (white(e, 108) > -0.4) listOf(gym()) else emptyList()
            DayOfWeek.SATURDAY ->
                if (fortnight == 2L) listOf(Workout(Sport.snow, 240, 1100, (125 + 5 * white(e, 109)).roundToInt(), 0))
                else listOf(run(10.5, 1.5))
            else -> {
                val km = 25 + 7 * white(e, 110)
                val speed = cyclingSpeed(e)
                val minutes = (km / speed * 60).roundToInt()
                val ride = Workout(Sport.cycle, minutes, minutes * 9, (138 + 6 * white(e, 111)).roundToInt(), (km * 1000).roundToInt())
                if (fortnight == 10L) listOf(ride, Workout(Sport.paddle, 70, 420, 118, (6500 + 1500 * white(e, 112)).roundToInt()))
                else listOf(ride)
            }
        }
    }

    private fun runPace(e: Long, long: Boolean): Double = 340 - 10 * fitness(e) + 12 * white(e, 113) + if (long) 15 else 0

    private fun swimPace(e: Long): Double = 125 + 8 * white(e, 114) - 3 * fitness(e)

    private fun cyclingSpeed(e: Long): Double = 24.5 + 2 * white(e, 115) + fitness(e)

    // MARK: Windows

    private fun mean(e: Long, days: Int, value: (Base) -> Double): Double =
        (0 until days).sumOf { value(base(e - it)) } / days

    private fun hrvBefore(e: Long): Double = mean(e - 1, 7) { it.rmssd }

    private fun suppressed(e: Long): Boolean = base(e).rmssd < hrvBefore(e) * 0.93

    private fun consecutiveSuppressed(e: Long): Int {
        var n = 0
        while (n < 7 && suppressed(e - n)) n += 1
        return n
    }

    private fun focusStreak(e: Long): Int {
        var n = 0
        var i = e
        while (n < 30) {
            val b = base(i)
            if (!b.weekend) {
                if (b.focusMin < 90) break
                n += 1
            }
            i -= 1
        }
        return n
    }

    private fun longestFocusStreak(e: Long): Int {
        var best = 0
        var run = 0
        for (i in e - 59..e) {
            val b = base(i)
            if (b.weekend) continue
            run = if (b.focusMin >= 90) run + 1 else 0
            best = max(best, run)
        }
        return max(best, 9)
    }

    private fun lateNights(e: Long): Int = (0 until 7).count { base(e - it).bedtimeMin > 30 }

    private fun burnout(e: Long): Int {
        val stress7 = mean(e, 7) { it.stress.toDouble() }
        val sleep7 = mean(e, 7) { it.sleepMin.toDouble() }
        val score = 38 + 1.2 * (stress7 - 42) + 0.2 * (430 - sleep7) + 3 * lateNights(e) +
            12 * sin(2 * PI * e / 26.0 - 1.0) + 4 * smooth(e, 60)
        return clamp(score, 12.0, 80.0).roundToInt()
    }

    /** Capacity: last night, the last three nights, HRV and yesterday's stress. */
    private fun capacity(e: Long): Int {
        val b = base(e)
        val sleep3 = mean(e, 3) { it.sleepMin.toDouble() }
        val score = 72 + (b.sleepMin - 420) * 0.11 + (sleep3 - 420) * 0.09 + (b.rmssd - 45) * 0.45 -
            (base(e - 1).stress - 42) * 0.2 + 3 * white(e, 50)
        return clamp(score, 45.0, 92.0).roundToInt()
    }

    private fun attention(e: Long): Double = whole(96 + (capacity(e) - 72) * 0.55 + 3 * white(e, 73))

    private fun focusScore(e: Long): Double = whole(97 + (capacity(e) - 72) * 0.5 + 3 * white(e, 74))

    /** A morning Quick check on most weekdays and some weekends. */
    private fun checked(e: Long): Boolean = white(e, 75) > if (isWeekend(e)) 0.3 else -0.7

    private fun mindful(e: Long): Boolean = dow(e) == DayOfWeek.MONDAY || white(e, 76) > -0.4

    private fun moodLogged(e: Long): Boolean = white(e, 77) > -0.5

    private fun foodLogged(e: Long): Boolean = dow(e) == DayOfWeek.TUESDAY || white(e, 78) > -0.6

    private val anchor = LocalDate.of(2025, 1, 1).toEpochDay()

    // MARK: Catalogue data

    /** Health Data's 25 sections (desktop-sections-data.ts), each row resolved as `/v1/metrics/catalogue` sends it. */
    private val sections: List<CatalogueSection> by lazy {
        listOf(
        section("movement", "Movement",
            s("Step Count", "stepCount", "steps", "int"),
            s("Walk Distance", "walkDistanceM", "km", "metresAsKm"),
            s("Run Distance", "runDistanceM", "km", "metresAsKm"),
            s("Active Calories", "activeKcal", "kcal", "int"),
            s("Resting Calories", "restingKcal", "kcal", "int"),
            s("Total Calories", "totalKcal", "kcal", "int"),
            s("Exercise Duration", "exerciseMinutes", "min", "minutes"),
            s("Active Minutes", "activeMinutes", "min", "minutes"),
            s("Standing Hours", "standHours", "hr", "int"),
            s("Stairs Climbed", "tierCPhoneJSON.stairsClimbed", "floors", "int"),
            s("Physical Effort Score", "physicalEffortScore", "", "int"),
            none("Workout Effort Rating"),
            s("Movement Time", "moveMinutes", "min", "minutes"),
            s("Push Count", "tierCPhoneJSON.pushCount", "pushes", "int"),
            s("Wheelchair Distance", "tierCPhoneJSON.wheelchairDistanceM", "km", "metresAsKm"),
            s("Daily Step Goal Progress", "stepGoalProgressPct", "%", "percent"),
            s("Active Calorie Goal Progress", "activeKcalGoalProgressPct", "%", "percent"),
            s("Stand Hours Goal Progress", "standHoursGoalProgressPct", "%", "percent"),
            s("Inactivity Minutes", "inactiveMinutes", "min", "minutes"),
            s("Move Reminder Count", "moveReminderCount", "", "int"),
        ),
        section("running", "Running",
            s("Running Distance", "runDistanceM", "km", "metresAsKm"),
            s("Running Pace", "runningPaceSecPerKm", "/km", "hhmm"),
            s("Running Heart Rate", "tierCModeJSON.runningHrBpm", "bpm", "int"),
            s("Running Calories", "tierCModeJSON.runningKcal", "kcal", "int"),
            s("Running Speed", "runningSpeedKmh", "km/h", "decimal1"),
            s("Stride Length", "tierCModeJSON.strideLengthM", "m", "decimal2"),
            s("Ground Contact Time", "tierCModeJSON.groundContactMs", "ms", "int"),
            s("Vertical Bounce", "tierCModeJSON.verticalOscillationCm", "cm", "decimal1"),
            s("Running Power", "tierCModeJSON.runningPowerW", "W", "int"),
            s("Cadence", "tierCModeJSON.cadenceSpm", "spm", "int"),
            none("Split Pace"),
            s("Elevation Gain", "runningElevationGainM", "m", "int"),
            s("Negative Split Ratio", "tierCModeJSON.negativeSplitRatio", "", "decimal2"),
        ),
        section("cycling", "Cycling",
            s("Cycling Distance", "tierCModeJSON.cyclingDistanceM", "km", "metresAsKm"),
            s("Cycling Speed", "cyclingSpeedKmh", "km/h", "decimal1"),
            s("Cycling Cadence", "tierCModeJSON.cyclingCadenceRpm", "rpm", "int"),
            s("Cycling Calories", "tierCModeJSON.cyclingKcal", "kcal", "int"),
            s("Cycling Heart Rate", "tierCModeJSON.cyclingHrBpm", "bpm", "int"),
            none("Cycling Elevation"),
        ),
        section("gym", "Gym and Strength",
            s("Gym Duration", "tierCModeJSON.gymDurationMin", "min", "minutes"),
            s("Gym Calories", "tierCModeJSON.gymKcal", "kcal", "int"),
            s("Gym Avg Heart Rate", "tierCModeJSON.gymAvgHrBpm", "bpm", "int"),
            l("Sets Completed", "strengthJSON.exercises"),
            l("Reps Per Set", "strengthJSON.exercises"),
            s("Weight Lifted", "strengthJSON.maxWeightKg", "kg", "decimal1"),
            s("Training Volume", "strengthJSON.volumeLiftedKg", "kg", "int"),
            l("Muscle Group Targeted", "strengthJSON.muscleGroups"),
            s("Rest Between Sets", "tierCModeJSON.restBetweenSetsSec", "s", "int"),
        ),
        section("swimming", "Swimming",
            s("Swim Distance", "tierCModeJSON.swimDistanceM", "m", "int"),
            s("Swim Stroke Count", "tierCModeJSON.swimStrokeCount", "strokes", "int"),
            s("Rowing Distance", "tierCModeJSON.rowingDistanceM", "km", "metresAsKm"),
            s("Rowing Speed", "tierCModeJSON.rowingSpeedKmh", "km/h", "decimal1"),
            s("Paddle Sports Distance", "tierCModeJSON.paddleDistanceM", "km", "metresAsKm"),
            s("Swim Pace", "tierCModeJSON.swimPaceSecPer100m", "/100 m", "hhmm"),
            s("SWOLF Score", "tierCModeJSON.swolfScore", "", "int"),
            s("Swim Calories", "tierCModeJSON.swimKcal", "kcal", "int"),
        ),
        section("winter", "Winter Sports",
            s("Cross-Country Ski Distance", "tierCModeJSON.crossCountrySkiDistanceM", "km", "metresAsKm"),
            s("Cross-Country Ski Speed", "tierCModeJSON.crossCountrySkiSpeedKmh", "km/h", "decimal1"),
            s("Downhill Snow Distance", "tierCModeJSON.downhillSnowDistanceM", "km", "metresAsKm"),
            s("Skating Distance", "tierCModeJSON.skatingDistanceM", "km", "metresAsKm"),
            s("Skating Speed", "tierCModeJSON.skatingSpeedKmh", "km/h", "decimal1"),
            s("Vertical Drop", "tierCModeJSON.verticalDropM", "m", "int"),
            s("Run Count", "tierCModeJSON.runCount", "runs", "int"),
        ),
        section("body", "Body Metrics",
            p("Height", "heightCm", "profile", "cm", "decimal1"),
            p("Weight", "weightKg", "profile", "kg", "decimal1"),
            p("Max Heart Rate", "maxHrBpm", "profile", "bpm", "int"),
            s("Body Mass Index", "measurementsJSON.bmi", "", "decimal1"),
            s("Body Fat Percentage", "measurementsJSON.bodyFatPct", "%", "decimal1"),
            s("Lean Body Mass", "measurementsJSON.leanBodyMassKg", "kg", "decimal1"),
            none("Waist Circumference"),
            none("Basal Body Temperature"),
            s("Electrodermal Response", "edaLevelUs", "µS", "decimal2"),
            s("Wrist Skin Temperature", "skinTempC", "°C", "decimal2"),
            s("Resting Metabolic Rate", "restingKcal", "kcal", "int"),
            s("Hydration Level", "measurementsJSON.bodyWaterPct", "%", "decimal1"),
            s("Fitness Age", "metabolicAge", "yrs", "int"),
            none("Blood Glucose"),
            none("Insulin Delivery"),
        ),
        section("heart", "Heart",
            s("Heart Rate", "currentHrBpm", "bpm", "int"),
            s("Resting Heart Rate", "restingHrBpm", "bpm", "int"),
            s("Walking Heart Rate Avg", "walkingHrAvgBpm", "bpm", "int"),
            s("HRV (SDNN)", "hrvSdnnMs", "ms", "decimal1"),
            s("HRV rMSSD", "hrvRmssdMs", "ms", "decimal1"),
            s("HRV 7-Day Average", "hrv7DayAvgMs", "ms", "int"),
            s("Heart Rate Recovery", "hrr60Bpm", "bpm", "int"),
            s("Cardio Fitness (VO2 Max)", "vo2max", "ml/kg/min", "decimal1"),
            none("High HR Alert Threshold"),
            none("Low HR Alert Threshold"),
            none("Heart Rate Zones Config"),
            s("Cardiac Drift", "cardiacDriftPct", "%", "percent"),
        ),
        section("stress", "Stress",
            s("Stress Score", "stressScore", "", "int"),
            s("Stress Duration", "stressDurationMin", "min", "minutes"),
            s("Stress Hours", "stressHours", "hr", "int"),
            s("Peak Stress", "peakStress", "", "int"),
            s("Average Stress", "averageStress", "", "int"),
            s("Calm Score", "calmScore", "", "int"),
            s("EDA (Skin Conductance)", "edaLevelUs", "µS", "decimal1"),
            s("Stress Drop", "stressDrop", "", "int"),
            p("Per-App Stress Impact", "tierCPhoneJSON.perAppStressImpact", "route", "", "text"),
            l("Stress Level Class", "stressLevelClass"),
            p("Top Stress Triggers", "tierCPhoneJSON.topStressTriggers", "route", "", "text"),
            p("Top Calming Apps", "tierCPhoneJSON.topCalmingApps", "route", "", "text"),
            s("Autonomic Balance Index", "autonomicBalanceIndex", "", "int"),
            s("Sympathetic Load", "sympatheticLoad", "%", "percent"),
            s("Parasympathetic Recovery", "parasympatheticRecovery", "%", "percent"),
            s("Stress Resilience Score", "stressResilienceScore", "", "int"),
            p("Stress Timeline", "stressHourlyJSON", "route", "", "text"),
            s("Stress Block Events", "stressBlockEvents", "events", "int"),
        ),
        section("sleep", "Sleep",
            s("Sleep", "sleepScore", "/100", "int"),
            s("Sleep Score", "sleepScore", "/100", "int"),
            s("Sleep Duration", "totalSleepMin", "hr", "hoursFromMinutes"),
            s("Deep Sleep", "deepSleepMin", "hr", "hoursFromMinutes"),
            s("Light Sleep", "lightSleepMin", "hr", "hoursFromMinutes"),
            s("REM Sleep", "remSleepMin", "hr", "hoursFromMinutes"),
            s("Awake Time", "awakeMin", "min", "minutes"),
            s("Sleep Cycles", "sleepCycles", "count", "int"),
            s("Late Night Hours", "lateNightHours", "hr", "decimal1"),
            s("Sleep Efficiency", "sleepEfficiencyPct", "%", "percent"),
            s("Time to Fall Asleep", "sleepLatencyMin", "min", "minutes"),
            s("Sleep Regularity Index", "sleepRegularityIndex", "%", "percent"),
            s("Wrist Temp During Sleep", "wristTempDuringSleepC", "°C", "decimal1"),
            s("Breathing Disturbances", "breathingDisturbances", "events", "int"),
            s("Sleep HR Dip", "sleepHrDipPct", "%", "percent"),
            s("Lowest Overnight HR", "lowestOvernightHrBpm", "bpm", "int"),
            p("Sleep Stage Hypnogram", "hypnogramJSON", "route", "", "text"),
            p("Bedtime Target", "healthGoals.bedtime", "profile", "", "text"),
            p("Wake Target", "healthGoals.wakeTime", "profile", "", "text"),
            p("Sleep Goal", "healthGoals.sleepNeedMinutes", "profile", "hr", "hoursFromMinutes"),
            s("Bedtime Deviation", "bedtimeDeviationMin", "min", "minutes"),
        ),
        section("recovery", "Recovery",
            s("Recovery Score", "recoveryScore", "0-100", "int"),
            s("Readiness Score", "readiness", "0-100", "int"),
            l("Day Mode", "dayMode"),
            s("Morning HRV", "morningHrvMs", "ms", "int"),
            s("Evening HRV", "eveningHrvMs", "ms", "int"),
            s("HRV Above Baseline", "hrvAboveBaseline", "", "bool"),
            s("Recovery Debt", "recoveryDebt", "0-100", "int"),
            s("Consecutive Suppressed", "consecutiveSuppressed", "nights", "int"),
            s("Surpassed Nights", "surpassedNights", "nights", "int"),
            s("Weekly Avg Evening HRV", "weeklyAvgEveningHrvMs", "ms", "int"),
            none("Weekly Avg Shutdown Hour"),
            s("Recovery Minutes", "recoveryMinutes", "min", "minutes"),
            s("Body Battery", "bodyBatteryEnd", "0-100", "int"),
            s("Training Load", "trimp", "TRIMP", "decimal1"),
            s("Recovery Time Needed", "recoveryTimeNeededHrs", "hr", "int"),
            s("Strain Score", "strainScore", "0-100", "int"),
        ),
        section("focus", "Focus",
            s("Focus Score", "focusScore", "", "int"),
            s("Focus Minutes (Today)", "focusMinutesToday", "min", "minutes"),
            s("Focus Minutes (Total)", "focusMinutesTotal", "hr", "hoursFromMinutes"),
            p("Flow Minutes", "flowMinutes", "none", "min", "minutes"),
            p("Flow Percentage", "flowPercentage", "none", "%", "percent"),
            s("Context Switches", "contextSwitches", "switches", "int"),
            s("Deep Work Hours", "deepWorkHours", "hr", "decimal1"),
            s("Session Count (Weekly)", "sessionCountWeekly", "sessions", "int"),
            s("Focus Streak Days", "focusStreakDays", "days", "int"),
            s("Best Day Focus", "bestDayFocusMin", "min", "minutes"),
            s("Worst Day Focus", "worstDayFocusMin", "min", "minutes"),
            s("Productive Percentage", "productivePercentage", "%", "percent"),
            s("Idle Time Detected", "idleTimeMin", "min", "minutes"),
            s("Weekly Deep Work Total", "weeklyDeepWorkTotalMin", "min", "minutes"),
            p("Flow State Detection", "flowStateDetected", "none", "", "bool"),
            p("Daily Flow Trend", "dailyFlowTrend", "none", "", "text"),
            s("Break Suggestion Count", "breakSuggestionCount", "breaks", "int"),
            s("Attention Span Estimate", "attentionSpanEstimateMin", "min", "minutes"),
            s("Peak Focus Window", "peakWindowStartHour", "", "hourOfDay"),
            s("Cognitive Load Index", "cognitiveLoadIndex", "", "int"),
            s("Focus Mode Timer", "focusModeTimerMin", "min", "minutes"),
            s("Focus Mode Flowtime", "focusModeFlowtime", "", "bool"),
            s("Quick Check", "quickCheck", "/s", "decimal2"),
        ),
        section("screen", "Screen Wellness",
            s("Foreground App Duration", "appUsageMinutes", "min", "minutes"),
            p("Per-App Daily Usage", null, "route", "min", "minutes"),
            s("Screen Time Total", "tierCPhoneJSON.screenTimeTotalMin", "min", "minutes"),
            s("Pickup Count", "tierCPhoneJSON.pickupCount", "pickups", "int"),
            s("Longest Unbroken Session", "tierCPhoneJSON.longestUnbrokenSessionMin", "min", "minutes"),
            s("Stress Block Events", "stressBlockEvents", "events", "int"),
            s("Productive App Time", "tierCPhoneJSON.productiveAppMin", "min", "minutes"),
            s("Distracting App Time", "tierCPhoneJSON.distractingAppMin", "min", "minutes"),
            s("Digital Detox Score", "tierCPhoneJSON.digitalDetoxScore", "", "int"),
            s("Blue Light Exposure Est", "tierCPhoneJSON.blueLightExposureMin", "min", "minutes"),
            s("Screen Stress Delta", "tierCPhoneJSON.screenStressDelta", "", "int"),
        ),
        section("meetings", "Meetings",
            s("Today Meeting Count", "todayMeetingCount", "meetings", "int"),
            s("Meeting-to-Focus Ratio", "meetingToFocusRatio", "", "decimal2"),
            s("Meeting Load", "meetingLoadPct", "%", "percent"),
            s("Meeting Duration Total", "meetingDurationTotalMin", "hr", "hoursFromMinutes"),
            s("Back-to-Back Meetings", "backToBackMeetings", "meetings", "int"),
            s("Meeting-Free Blocks", "meetingFreeBlocks", "blocks", "int"),
            s("Fragmentation Score", "fragmentationScore", "", "int"),
            s("Maker Schedule Score", "makerScheduleScore", "", "int"),
            l("Heaviest Meeting Day", "heaviestMeetingDay"),
        ),
        section("mental", "Mental Wellness",
            s("Meditation Minutes", "tierCModeJSON.meditationMinutes", "min", "minutes"),
            s("Mindfulness Minutes", "tierCModeJSON.mindfulnessMinutes", "min", "minutes"),
            s("EDA Drop (per session)", "tierCModeJSON.edaDropUs", "µS", "decimal2"),
            s("Mood Rating", "mood", "", "decimal1"),
            none("Average Mood"),
            none("Mood Correlation"),
            s("Breathing Duration", "tierCModeJSON.breathingDurationMin", "min", "minutes"),
            s("Calm Score", "calmScore", "", "int"),
            p("Brain Dump (entries)", null, "route", "entries", "int"),
            p("Reflection Entries", "eveningReflection", "route", "", "text"),
            none("Gratitude Entries"),
            s("Anxiety Level", "feelingOnEdge", "", "decimal1"),
            l("Energy Level", "energyLevel"),
            none("Motivation Level"),
            s("Mental Clarity Score", "checkin.alertness", "", "decimal1"),
            s("Cognitive Performance", "attentionScore", "", "int"),
        ),
        section("respiration", "Respiration",
            s("Respiration Rate", "breathsPerMin", "br/min", "decimal1"),
            s("Blood Oxygen (SpO2)", "spo2Pct", "%", "percent"),
            s("Breath Rate (Session)", "tierCModeJSON.breathRateSession", "br/min", "decimal1"),
            s("Breath Count (Session)", "tierCModeJSON.breathCountSession", "breaths", "int"),
            none("Inhaler Usage"),
            s("Overnight SpO2 Average", "overnightSpo2AvgPct", "%", "percent"),
            s("SpO2 Dip Events", "spo2DipEvents", "events", "int"),
        ),
        section("mobility", "Mobility",
            s("Flights Climbed", "tierCPhoneJSON.flightsClimbed", "flights", "int"),
            s("Walking Speed", "walkingSpeedKmh", "km/h", "decimal1"),
            s("Walking Step Length", "tierCPhoneJSON.walkingStepLengthCm", "cm", "decimal1"),
            s("Double Support Time", "tierCPhoneJSON.doubleSupportPct", "%", "decimal1"),
            s("Walking Asymmetry", "tierCPhoneJSON.walkingAsymmetryPct", "%", "decimal1"),
            s("Stair Ascent Speed", "tierCPhoneJSON.stairAscentSpeedMs", "m/s", "decimal2"),
            s("Stair Descent Speed", "tierCPhoneJSON.stairDescentSpeedMs", "m/s", "decimal2"),
            s("Walking Steadiness", "tierCPhoneJSON.walkingSteadinessPct", "%", "percent"),
            s("Gait Regularity Index", "tierCPhoneJSON.gaitRegularityIndex", "", "decimal2"),
            s("Balance Score", "tierCPhoneJSON.balanceScore", "", "int"),
        ),
        section("nutrition", "Nutrition",
            s("Water Intake", "waterBeforeFocus", "times", "int"),
            none("Supplement Vitamin D"),
            none("Supplement Omega-3"),
            none("Supplement Magnesium"),
            none("Supplement Creatine"),
            s("Calories Consumed", "caloriesConsumed", "kcal", "int"),
            s("Carbohydrates", "foodCarbsG", "g", "decimal1"),
            none("Dietary Fiber"),
            none("Sugar"),
            s("Total Fat", "foodFatG", "g", "decimal1"),
            s("Protein", "foodProteinG", "g", "decimal1"),
            none("Sodium"),
            none("Caffeine"),
            l("Meal Timing", "mealTiming"),
            s("Fasting Duration", "fastingDurationHrs", "hrs", "decimal1"),
            s("Protein-to-Calorie Ratio", "proteinToCalorieRatio", "g/100 kcal", "decimal2"),
        ),
        section("cycle", "Cycle Tracking",
            none("Menstrual Flow"),
            none("Intermenstrual Bleeding"),
            none("Cervical Mucus Quality"),
            none("Ovulation Test Result"),
            none("Progesterone Test"),
            none("Pregnancy Test"),
            none("Pregnancy Status"),
            none("Lactation Status"),
            none("Contraceptive Type"),
            none("Cycle Length"),
            none("Fertile Window"),
            none("Period Prediction"),
            none("PMS Severity"),
            s("Basal Temp Shift", "skinTempDeviationC", "°C", "decimal2"),
        ),
        section("hearing", "Hearing",
            s("Headphone Audio Level", "tierCPhoneJSON.headphoneAudioDb", "dB", "int"),
            s("Environment Sound Level", "tierCPhoneJSON.environmentSoundDb", "dB", "int"),
            none("Environment Sound Reduction"),
            none("Headphone Exposure Alert"),
            none("Environment Noise Alert"),
            s("Daily Noise Dose", "tierCPhoneJSON.dailyNoseDosePct", "%", "percent"),
            s("Safe Listening Remaining", "tierCPhoneJSON.safeListeningRemainingMin", "min", "minutes"),
        ),
        section("symptoms", "Symptoms", *symptoms.map { none(it) }.toTypedArray()),
        section("burnout", "Burnout",
            s("Founder Burnout Index", "burnoutIndexScore", "/100", "int"),
            s("Avg Sleep (Burnout)", "avgSleepBurnoutHrs", "hr", "decimal1"),
            s("Stress Score (Burnout)", "stressScoreBurnout", "/100", "int"),
            s("Late Nights (Burnout)", "lateNightsBurnout", "nights", "int"),
            s("Deep Work (Burnout)", "deepWorkBurnoutMin", "hr", "hoursFromMinutes"),
            s("Shutdown Discipline", "shutdownDisciplinePct", "%", "percent"),
            s("Shutdown Checklist Done", "shutdownChecklistDone", "", "bool"),
            s("Work-Life Boundary", "workLifeBoundaryScore", "/100", "int"),
            s("Weekend Recovery Delta", "weekendRecoveryDelta", "pts", "int"),
            s("Overwork Streak", "overworkStreakDays", "days", "int"),
            s("Rest Day Compliance", "restDayCompliancePct", "%", "percent"),
        ),
        section("habits", "Habits",
            p("Habit Completion Rate", null, "route", "%", "percent"),
            p("Daily Habit Count", null, "route", "habits", "int"),
            p("Habit Performance", "habitsKeptThisWeek", "route", "habits", "int"),
            none("Habit Mood Logged"),
            none("Mood Correlation"),
            none("Evening Wind-Down Score"),
            p("Consistency Streak", null, "route", "days", "int"),
            p("Habits Completed Today", null, "route", "habits", "int"),
            p("Habits Skipped Today", null, "route", "habits", "int"),
        ),
        section("progress", "Progress",
            p("Experience Points (XP)", "experiencePoints", "route", "pts", "int"),
            p("Level", "level", "route", "", "int"),
            p("Plant Growth Stage", "plantGrowthStage", "route", "", "int"),
            p("Badges Earned", "badgesEarned", "route", "", "int"),
            s("Weekly Challenge Progress", "weeklyChallengeProgressPct", "%", "percent"),
            s("Longest Focus Streak", "longestFocusStreakDays", "days", "int"),
            s("Wellness Score (Overall)", "wellnessScoreOverall", "/100", "int"),
        ),
        section("other", "Other",
            none("Alcohol Consumption"),
            none("Blood Alcohol Content"),
            s("Falls Detected", "tierCPhoneJSON.fallsDetected", "falls", "int"),
            s("Time in Daylight", "tierCPhoneJSON.timeInDaylightMin", "min", "minutes"),
            s("UV Exposure Index", "tierCPhoneJSON.uvExposureIndex", "", "decimal1"),
            s("Handwashing Events", "tierCPhoneJSON.handwashingEvents", "events", "int"),
            none("Toothbrushing Events"),
            none("Sexual Activity"),
            none("Travel Mode Active"),
            s("Flight Tracking", "tierCPhoneJSON.flightTracking", "", "bool"),
            s("Jet Lag Estimate", "jetLagHours", "hr", "decimal1"),
            s("Circadian Alignment", "circadianAlignmentPct", "%", "percent"),
            s("Sunlight Minutes", "tierCPhoneJSON.timeInDaylightMin", "min", "minutes"),
            s("Mindful Sessions", "tierCModeJSON.mindfulSessions", "sessions", "int"),
        ),
        )
    }

    /** Fields that answer availability: every row with a value, and the rewards rows Progress routes elsewhere. */
    private val availableFields: List<String> by lazy {
        val rows = sections.flatMap { it.parameters }.filter { it.field != null }
        val read = rows.filter { it.read == "series" || it.read == "labels" || it.read == "profile" }.mapNotNull { it.field }
        (read + listOf("experiencePoints", "level", "plantGrowthStage", "badgesEarned")).distinct()
    }

    /** Each field's catalogue format, the first row's when two rows share it. */
    private val formats: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        sections.flatMap { it.parameters }.forEach { p -> p.field?.let { if (!out.containsKey(it)) out[it] = p.format } }
        out
    }

    /** The unit a series is stored in: metres and minutes where the page shows km and hours. */
    private val units: Map<String, String?> by lazy {
        val out = HashMap<String, String?>()
        for (p in sections.flatMap { it.parameters }) {
            val field = p.field ?: continue
            if (out.containsKey(field)) continue
            out[field] = when (p.format) {
                "metresAsKm" -> "m"
                "hoursFromMinutes" -> "min"
                else -> p.unit.ifEmpty { null }
            }
        }
        out["timeInBedMin"] = "min"
        out["totalDistanceM"] = "m"
        out["maxHrObservedBpm"] = "bpm"
        out["restingHrBaselineBpm"] = "bpm"
        out["sleepNeedMin"] = "min"
        out["sleepDebtMin14"] = "min"
        out
    }

    // MARK: Values

    /** A field's number on day `e`, or null when there is none that day. Yes/no fields are 1 or 0. */
    private fun number(field: String, e: Long): Double? {
        val b = base(e)
        fun n(salt: Int): Double = white(e, salt)
        fun flag(on: Boolean): Double = if (on) 1.0 else 0.0
        val run = b.workout(Sport.run)
        val gym = b.workout(Sport.gym)
        val swim = b.workout(Sport.swim)
        val ride = b.workout(Sport.cycle)
        val snow = b.workout(Sport.snow)
        val paddle = b.workout(Sport.paddle)
        val activeMin = b.exerciseMin + (24 + 10 * n(201)).roundToInt()
        val standHours = clamp((if (b.weekend) 10.0 else 9.5) + 1.8 * n(202), 6.0, 14.0).roundToInt()
        val dose = clamp(30 + 18 * n(203), 8.0, 60.0).roundToInt()
        return when (field) {
            // Movement
            "stepCount" -> b.steps.toDouble()
            "walkDistanceM" -> whole(b.walkSteps * 0.74)
            "runDistanceM" -> run?.distanceM?.toDouble()
            "totalDistanceM" -> whole(b.walkSteps * 0.74 + (run?.distanceM ?: 0))
            "activeKcal" -> b.activeKcal.toDouble()
            "restingKcal" -> b.restingKcal.toDouble()
            "totalKcal" -> (b.activeKcal + b.restingKcal).toDouble()
            "exerciseMinutes" -> b.exerciseMin.toDouble()
            "activeMinutes" -> activeMin.toDouble()
            "standHours" -> standHours.toDouble()
            "tierCPhoneJSON.stairsClimbed", "tierCPhoneJSON.flightsClimbed" -> whole(10 + 5 * n(204))
            "physicalEffortScore" -> whole(clamp(b.strain * 0.9 + 5 * n(205), 10.0, 95.0))
            "moveMinutes" -> whole(activeMin + 75 + 25 * n(206))
            "stepGoalProgressPct" -> whole(b.steps / 80.0)
            "activeKcalGoalProgressPct" -> whole(b.activeKcal / 5.0)
            "standHoursGoalProgressPct" -> whole(standHours * 100 / 12.0)
            "inactiveMinutes" -> whole((if (b.weekend) 440.0 else 580.0) + 50 * n(207))
            "moveReminderCount" -> whole(if (b.weekend) 1 + n(208) else 4 + 2 * n(208))
            // Running
            "runningPaceSecPerKm" -> run?.let { whole(it.minutes * 60_000.0 / it.distanceM) }
            "runningSpeedKmh" -> run?.let { r1(it.distanceM / 1000.0 / (it.minutes / 60.0)) }
            "tierCModeJSON.runningHrBpm" -> run?.avgHr?.toDouble()
            "tierCModeJSON.runningKcal" -> run?.kcal?.toDouble()
            "tierCModeJSON.strideLengthM" -> run?.let { r2(1.1 + 0.05 * n(210)) }
            "tierCModeJSON.groundContactMs" -> run?.let { whole(255 + 10 * n(211) - 3 * fitness(e)) }
            "tierCModeJSON.verticalOscillationCm" -> run?.let { r1(8.8 + 0.6 * n(212)) }
            "tierCModeJSON.runningPowerW" -> run?.let { whole(245 + 15 * n(213) + 5 * fitness(e)) }
            "tierCModeJSON.cadenceSpm" -> run?.let { whole(170 + 4 * n(214)) }
            "runningElevationGainM" -> run?.let { whole(it.distanceM / 1000.0 * (8 + 6 * n(215))) }
            "tierCModeJSON.negativeSplitRatio" -> run?.let { r2(1.0 + 0.03 * n(216)) }
            // Cycling
            "tierCModeJSON.cyclingDistanceM" -> ride?.distanceM?.toDouble()
            "cyclingSpeedKmh" -> ride?.let { r1(cyclingSpeed(e)) }
            "tierCModeJSON.cyclingCadenceRpm" -> ride?.let { whole(85 + 5 * n(220)) }
            "tierCModeJSON.cyclingKcal" -> ride?.kcal?.toDouble()
            "tierCModeJSON.cyclingHrBpm" -> ride?.avgHr?.toDouble()
            // Gym and strength
            "tierCModeJSON.gymDurationMin" -> gym?.minutes?.toDouble()
            "tierCModeJSON.gymKcal" -> gym?.kcal?.toDouble()
            "tierCModeJSON.gymAvgHrBpm" -> gym?.avgHr?.toDouble()
            "strengthJSON.maxWeightKg" -> gym?.let { r1(roundTo(77.5 + 5 * fitness(e) + 2.5 * n(221), 2.5)) }
            "strengthJSON.volumeLiftedKg" -> gym?.let { whole(8000 + 1400 * n(222) + 600 * fitness(e)) }
            "tierCModeJSON.restBetweenSetsSec" -> gym?.let { whole(90 + 15 * n(223)) }
            // Swimming, rowing and paddling
            "tierCModeJSON.swimDistanceM" -> swim?.distanceM?.toDouble()
            "tierCModeJSON.swimStrokeCount" -> swim?.let { whole(it.distanceM / 25.0 * (17 + n(224))) }
            "tierCModeJSON.swimPaceSecPer100m" -> swim?.let { whole(swimPace(e)) }
            "tierCModeJSON.swolfScore" -> swim?.let { whole(42 + 3 * n(225)) }
            "tierCModeJSON.swimKcal" -> swim?.kcal?.toDouble()
            "tierCModeJSON.rowingDistanceM" -> gym?.let { whole(2250 + 250 * n(226)) }
            "tierCModeJSON.rowingSpeedKmh" -> gym?.let { r1(8.2 + 0.4 * n(227)) }
            "tierCModeJSON.paddleDistanceM" -> paddle?.distanceM?.toDouble()
            // Winter sports
            "tierCModeJSON.crossCountrySkiDistanceM" -> snow?.let { whole(10_000 + 2000 * n(230)) }
            "tierCModeJSON.crossCountrySkiSpeedKmh" -> snow?.let { r1(10 + n(231)) }
            "tierCModeJSON.downhillSnowDistanceM" -> snow?.let { whole(20_000 + 5000 * n(232)) }
            "tierCModeJSON.skatingDistanceM" -> snow?.let { whole(5000 + 1000 * n(233)) }
            "tierCModeJSON.skatingSpeedKmh" -> snow?.let { r1(12.5 + 1.5 * n(234)) }
            "tierCModeJSON.verticalDropM" -> snow?.let { whole(2150 + 650 * n(235)) }
            "tierCModeJSON.runCount" -> snow?.let { whole(11 + 3 * n(236)) }
            // Body
            "weightKg" -> if (b.weighed) b.weightKg else null
            "heightCm" -> 176.0
            "maxHrBpm" -> 188.0
            "measurementsJSON.bmi" -> if (b.weighed) r1(b.weightKg / (1.76 * 1.76)) else null
            "measurementsJSON.bodyFatPct" -> if (b.weighed) bodyFat(e) else null
            "measurementsJSON.leanBodyMassKg" -> if (b.weighed) r1(b.weightKg * (1 - bodyFat(e) / 100)) else null
            "measurementsJSON.bodyWaterPct" -> if (b.weighed) r1(56.5 + 0.8 * n(240)) else null
            "edaLevelUs" -> r2(2.2 + b.stress * 0.04 + 0.3 * n(241))
            "skinTempC" -> r2(33.6 + 0.3 * smooth(e, 242) + 0.15 * n(243))
            "metabolicAge" -> whole(30 - 2 * fitness(e) + 0.5 * n(244))
            // Heart
            "currentHrBpm" -> whole(70 + 6 * n(250) + 5 * b.load)
            "restingHrBpm" -> b.restingHr.toDouble()
            "restingHrBaselineBpm" -> whole(mean(e - 1, 28) { it.restingHr.toDouble() })
            "maxHrObservedBpm" -> whole(if (b.workouts.isEmpty()) 128 + 6 * n(251) else 172 + 6 * n(251))
            "walkingHrAvgBpm" -> whole(96 + 3 * n(252) + (b.restingHr - 60) * 0.6)
            "hrvSdnnMs" -> r1(b.rmssd * 1.28 + 2 * n(253))
            "hrvRmssdMs" -> b.rmssd
            "hrv7DayAvgMs" -> whole(mean(e, 7) { it.rmssd })
            "hrr60Bpm" -> if (b.workouts.isEmpty()) null else whole(28 + 3 * fitness(e) + 3 * n(254))
            "vo2max" -> run?.let { r1(45.0 + 0.8 * fitness(e) + 0.3 * n(255)) }
            "cardiacDriftPct" -> if (run == null && ride == null) null else whole(4.5 + 2 * n(256))
            // Stress
            "stressScore" -> b.stress.toDouble()
            "stressDurationMin" -> whole(max(0.0, (b.stress - 20) * 4.5 + 15 * n(260)))
            "stressHours" -> whole(max(0.0, (b.stress - 20) * 4.5 + 15 * n(260)) / 60)
            "peakStress" -> whole(min(95.0, b.stress + 22 + 5 * n(261)))
            "averageStress" -> whole(b.stress + 2 * n(262))
            "calmScore" -> whole(clamp(95.0 - b.stress + 4 * n(263), 20.0, 85.0))
            "stressDrop" -> whole(14 + 6 * n(264))
            "autonomicBalanceIndex" -> whole(clamp(50 + (b.rmssd - 45) * 0.8 - (b.stress - 40) * 0.3, 25.0, 80.0))
            "sympatheticLoad" -> whole(clamp(35 + (b.stress - 40) * 0.6 + 3 * n(265), 20.0, 65.0))
            "parasympatheticRecovery" -> whole(100 - clamp(35 + (b.stress - 40) * 0.6 + 3 * n(265), 20.0, 65.0))
            "stressResilienceScore" -> whole(clamp(62 + (b.rmssd - 45) * 0.6 - (b.load - 0.5) * 12, 40.0, 85.0))
            "stressBlockEvents" -> whole(if (b.weekend) 0.0 else max(0.0, (b.stress - 35) / 8.0 + n(266)))
            // Sleep
            "sleepScore" -> b.sleepScore.toDouble()
            "totalSleepMin" -> b.sleepMin.toDouble()
            "deepSleepMin" -> b.deepMin.toDouble()
            "lightSleepMin" -> b.lightMin.toDouble()
            "remSleepMin" -> b.remMin.toDouble()
            "awakeMin" -> b.awakeMin.toDouble()
            "timeInBedMin" -> b.inBedMin.toDouble()
            "sleepLatencyMin" -> b.latencyMin.toDouble()
            "awakenings" -> whole(1 + b.awakeMin / 12.0)
            "sleepCycles" -> whole(b.sleepMin / 92.0)
            "lateNightHours" -> r1(max(0, b.bedtimeMin + 60) / 60.0)
            "sleepEfficiencyPct" -> r1(b.efficiency)
            "sleepRegularityIndex" -> whole(clamp(90 - abs(b.bedtimeMin + 40) * 0.25 + 2 * n(270), 60.0, 95.0))
            "wristTempDuringSleepC" -> r1(34.3 + 0.3 * smooth(e, 271) + 0.1 * n(272))
            "breathingDisturbances" -> whole(max(0.0, 1.2 + 1.5 * n(273)))
            "sleepHrDipPct" -> whole(16 + 3 * n(274))
            "lowestOvernightHrBpm" -> whole(b.restingHr - 5 + n(275))
            "bedtimeDeviationMin" -> whole(abs(b.bedtimeMin + 40).toDouble())
            "sleepNeedMin" -> 450.0
            "sleepDebtMin14" -> whole((0 until 14).sumOf { max(0, 450 - base(e - it).sleepMin) }.toDouble())
            "healthGoals.sleepNeedMinutes" -> 450.0
            // Recovery
            "recoveryScore" -> b.recovery.toDouble()
            "readiness" -> b.readiness.toDouble()
            "morningHrvMs" -> whole(b.rmssd + 2)
            "eveningHrvMs" -> b.eveningHrv.toDouble()
            "hrvAboveBaseline" -> flag(b.rmssd > hrvBefore(e))
            "recoveryDebt" -> whole(clamp((0 until 7).sumOf { max(0, 450 - base(e - it).sleepMin) } / 6.0 +
                8.0 * consecutiveSuppressed(e), 0.0, 100.0))
            "consecutiveSuppressed" -> consecutiveSuppressed(e).toDouble()
            "surpassedNights" -> (0 until 7).count { base(e - it).rmssd > hrvBefore(e - it) * 1.05 }.toDouble()
            "weeklyAvgEveningHrvMs" -> whole(mean(e, 7) { it.eveningHrv.toDouble() })
            "recoveryMinutes" -> whole(if (b.weekend) 10 + 8 * n(280) else 22 + 10 * n(280))
            "bodyBatteryEnd" -> whole(clamp(b.recovery - b.strain * 0.5 + 18 + 5 * n(281), 5.0, 70.0))
            "trimp" -> r1(b.workouts.sumOf { it.minutes * (it.avgHr - 60) / 40.0 } + 18 + 5 * n(282))
            "recoveryTimeNeededHrs" -> whole(clamp(b.strain * 0.15 + (100 - b.recovery) * 0.2, 0.0, 48.0))
            "strainScore" -> b.strain.toDouble()
            // Focus
            "focusScore" -> focusScore(e)
            "focusMinutesToday" -> b.focusMin.toDouble()
            "focusMinutesTotal" -> whole(4000 + (e - anchor) * 118.0)
            "contextSwitches" -> whole(if (b.weekend) 8 + 4 * n(290) else 40 + 10 * b.meetingCount / 2.0 + 10 * n(290))
            "deepWorkHours" -> r1(b.deepWorkMin / 60.0)
            "sessionCountWeekly" -> (0 until 7).sumOf { (base(e - it).focusMin / 50.0).roundToInt() }.toDouble()
            "focusStreakDays" -> focusStreak(e).toDouble()
            "bestDayFocusMin" -> (0 until 7).maxOf { base(e - it).focusMin }.toDouble()
            "worstDayFocusMin" -> (0 until 7).map { base(e - it) }.filter { !it.weekend }.minOf { it.focusMin }.toDouble()
            "productivePercentage" -> whole(clamp(68 + (b.readiness - 65) * 0.4 + 6 * n(291), 45.0, 90.0))
            "idleTimeMin" -> whole(if (b.weekend) 10 + 5 * n(292) else 38 + 15 * n(292))
            "weeklyDeepWorkTotalMin" -> (0 until 7).sumOf { base(e - it).deepWorkMin }.toDouble()
            "breakSuggestionCount" -> whole(if (b.weekend) 1.0 else 4 + 2 * n(293))
            "attentionSpanEstimateMin" -> whole(clamp(30 + (b.sleepMin - 420) * 0.08 + 5 * n(294), 18.0, 45.0))
            "peakWindowStartHour" -> roundTo(10 + 0.5 * n(295) + max(0, b.bedtimeMin) / 120.0, 0.25)
            "peakWindowEndHour" -> roundTo(12 + 0.5 * n(295) + max(0, b.bedtimeMin) / 120.0, 0.25)
            "cognitiveLoadIndex" -> whole(clamp(25 + 50 * b.load + 5 * n(296), 15.0, 85.0))
            "focusModeTimerMin" -> if (b.focusMin < 25) null else (if (n(297) > 0.2) 25.0 else 50.0)
            "focusModeFlowtime" -> if (b.focusMin < 25) null else flag(n(298) > 0.3)
            "quickCheck" -> if (checked(e)) r2(3.3 + (capacity(e) - 72) * 0.012 + 0.08 * n(299)) else null
            "attentionScore" -> if (checked(e)) attention(e) else null
            // Screen wellness
            "appUsageMinutes" -> whole(if (b.weekend) 55 + 25 * n(300) else 390 + 60 * n(300))
            "tierCPhoneJSON.screenTimeTotalMin" -> whole((if (b.weekend) 240.0 else 190.0) + 40 * n(301))
            "tierCPhoneJSON.pickupCount" -> whole(80 + 20 * n(302) + 15 * b.load)
            "tierCPhoneJSON.longestUnbrokenSessionMin" -> whole(38 + 14 * n(303))
            "tierCPhoneJSON.productiveAppMin" -> whole((if (b.weekend) 45.0 else 105.0) + 25 * n(304))
            "tierCPhoneJSON.distractingAppMin" -> whole((if (b.weekend) 95.0 else 65.0) + 25 * n(305))
            "tierCPhoneJSON.digitalDetoxScore" -> whole(clamp(64 - 12 * b.load + 10 * n(306), 35.0, 90.0))
            "tierCPhoneJSON.blueLightExposureMin" -> whole(95 + 30 * n(307) + max(0, b.bedtimeMin) / 2.0)
            "tierCPhoneJSON.screenStressDelta" -> whole(3 + 6 * n(308) + 6 * (b.load - 0.5))
            // Meetings
            "todayMeetingCount" -> b.meetingCount.toDouble()
            "meetingDurationTotalMin" -> b.meetingMin.toDouble()
            "meetingToFocusRatio" -> if (b.focusMin > 0) r2(b.meetingMin.toDouble() / b.focusMin) else null
            "meetingLoadPct" -> whole(b.meetingMin * 100 / 540.0)
            "backToBackMeetings" -> whole(max(0.0, (b.meetingCount - 2) / 2.0 + 0.5 * n(310)))
            "meetingFreeBlocks" -> if (b.weekend) null else whole(clamp(5 - b.meetingCount * 0.7 + n(311), 1.0, 5.0))
            "fragmentationScore" -> if (b.weekend) null else whole(clamp(18 + b.meetingCount * 8 + 6 * n(312), 10.0, 80.0))
            "makerScheduleScore" -> if (b.weekend) null else whole(clamp(88 - b.meetingCount * 9 + 6 * n(313), 20.0, 95.0))
            // Mental wellness
            "tierCModeJSON.meditationMinutes" -> if (mindful(e)) whole(11 + 4 * n(320)) else null
            "tierCModeJSON.mindfulnessMinutes" -> if (mindful(e)) whole(15 + 4 * n(320) + 2 * n(321)) else null
            "tierCModeJSON.edaDropUs" -> if (mindful(e)) r2(0.4 + 0.15 * n(322)) else null
            "tierCModeJSON.breathingDurationMin" -> if (mindful(e)) whole(5 + 1.5 * n(323)) else null
            "tierCModeJSON.breathRateSession" -> if (mindful(e)) r1(6 + 0.5 * n(324)) else null
            "tierCModeJSON.breathCountSession" ->
                if (mindful(e)) whole((6 + 0.5 * n(324)) * (5 + 1.5 * n(323)).roundToInt()) else null
            "tierCModeJSON.mindfulSessions" -> if (mindful(e)) whole(1.5 + 0.6 * n(325)) else null
            "mood" -> if (moodLogged(e)) r1(clamp(3.6 + (b.readiness - 62) / 40.0 + 0.4 * n(326), 2.0, 5.0)) else null
            "feelingOnEdge" -> if (moodLogged(e)) r1(clamp(1.6 + (b.stress - 40) / 20.0 + 0.4 * n(327), 1.0, 4.5)) else null
            "checkin.alertness" -> if (checked(e)) r1(clamp(6 + (b.sleepMin - 420) / 40.0 + 0.7 * n(328), 3.0, 8.5)) else null
            // Respiration
            "breathsPerMin" -> r1(14.2 + 0.6 * n(330) + 0.8 * (b.load - 0.5))
            "spo2Pct" -> r1(clamp(97.2 + 0.9 * n(331), 95.0, 99.0))
            "overnightSpo2AvgPct" -> r1(clamp(96.6 + 0.8 * n(332), 94.5, 98.5))
            "spo2DipEvents" -> whole(max(0.0, 1 + 1.5 * n(333)))
            "sleepBreathingRate" -> r1(13.6 + 0.5 * n(334))
            // Mobility
            "walkingSpeedKmh" -> r1(5.1 + 0.25 * n(340) + 0.1 * fitness(e))
            "tierCPhoneJSON.walkingStepLengthCm" -> r1(75 + 2 * n(341))
            "tierCPhoneJSON.doubleSupportPct" -> r1(28 + 1.5 * n(342))
            "tierCPhoneJSON.walkingAsymmetryPct" -> r1(max(0.0, 1.5 + 1.5 * n(343)))
            "tierCPhoneJSON.stairAscentSpeedMs" -> r2(0.52 + 0.06 * n(344))
            "tierCPhoneJSON.stairDescentSpeedMs" -> r2(0.58 + 0.06 * n(345))
            "tierCPhoneJSON.walkingSteadinessPct" -> whole(93 + 4 * n(346))
            "tierCPhoneJSON.gaitRegularityIndex" -> r2(0.9 + 0.04 * n(347))
            "tierCPhoneJSON.balanceScore" -> whole(83 + 6 * n(348))
            // Nutrition
            "waterBeforeFocus" -> if (n(350) > -0.7) whole(4 + 2 * n(351)) else null
            "caloriesConsumed" -> if (foodLogged(e)) consumed(b) else null
            "foodProteinG" -> if (foodLogged(e)) r1(consumed(b) * 0.21 / 4) else null
            "foodCarbsG" -> if (foodLogged(e)) r1(consumed(b) * 0.48 / 4) else null
            "foodFatG" -> if (foodLogged(e)) r1(consumed(b) * 0.31 / 9) else null
            "fastingDurationHrs" -> if (foodLogged(e)) r1(13.5 + 1.5 * n(352)) else null
            "proteinToCalorieRatio" -> if (foodLogged(e)) r2(consumed(b) * 0.21 / 4 / (consumed(b) / 100)) else null
            // Cycle tracking
            "skinTempDeviationC" -> r2(0.3 * smooth(e, 271) + 0.1 * n(272))
            // Hearing
            "tierCPhoneJSON.headphoneAudioDb" -> whole(69 + 5 * n(360))
            "tierCPhoneJSON.environmentSoundDb" -> whole(59 + 7 * n(361))
            "tierCPhoneJSON.dailyNoseDosePct" -> dose.toDouble()
            "tierCPhoneJSON.safeListeningRemainingMin" -> whole((100 - dose) * 18.0)
            // Burnout
            "burnoutIndexScore" -> burnout(e).toDouble()
            "avgSleepBurnoutHrs" -> r1(mean(e, 7) { it.sleepMin.toDouble() } / 60)
            "stressScoreBurnout" -> whole(mean(e, 7) { it.stress.toDouble() })
            "lateNightsBurnout" -> lateNights(e).toDouble()
            "deepWorkBurnoutMin" -> (0 until 7).sumOf { base(e - it).deepWorkMin }.toDouble()
            "shutdownDisciplinePct" -> whole(clamp(78 - 25 * (b.load - 0.5) + 8 * n(370), 40.0, 98.0))
            "shutdownChecklistDone" -> if (b.weekend) null else flag(n(371) > -0.4 + b.load * 0.4)
            "workLifeBoundaryScore" -> whole(clamp(72 - 20 * (b.load - 0.5) - 3 * lateNights(e) + 5 * n(372), 35.0, 92.0))
            "weekendRecoveryDelta" -> weekendDelta(e)
            "overworkStreakDays" -> {
                var streak = 0
                while (streak < 10 && base(e - streak).load > 0.75) streak += 1
                streak.toDouble()
            }
            "restDayCompliancePct" -> whole(clamp(100 - (0 until 7).map { base(e - it) }.filter { it.weekend }
                .sumOf { it.focusMin } / 2.0, 55.0, 100.0))
            // Progress
            "weeklyChallengeProgressPct" -> whole(min(100.0, dow(e).value * 14.3 * (0.85 + 0.15 * n(380))))
            "longestFocusStreakDays" -> longestFocusStreak(e).toDouble()
            "wellnessScoreOverall" -> whole((b.readiness + b.sleepScore + (100 - b.stress)) / 3.0)
            "experiencePoints" -> whole(2000 + (e - anchor) * 95.0)
            "level" -> (1 + (2000 + (e - anchor) * 95) / 2500).toDouble()
            "plantGrowthStage" -> (1 + ((2000 + (e - anchor) * 95) / 2500) % 5).toDouble()
            "badgesEarned" -> (3 + (e - anchor) / 21).toDouble()
            // Other
            "tierCPhoneJSON.fallsDetected" -> 0.0
            "tierCPhoneJSON.timeInDaylightMin" -> whole((if (b.weekend) 105.0 else 55.0) + 25 * n(390))
            "tierCPhoneJSON.uvExposureIndex" -> r1(1.5 + 0.9 * n(391))
            "tierCPhoneJSON.handwashingEvents" -> whole(8 + 3 * n(392))
            "tierCPhoneJSON.flightTracking" -> 0.0
            "jetLagHours" -> 0.0
            "circadianAlignmentPct" -> whole(clamp(92 - abs(b.bedtimeMin + 40) * 0.25 + 2 * n(393), 60.0, 97.0))
            else -> null
        }
    }

    /** A field's words on day `e`, for the fields that are words. */
    private fun text(field: String, e: Long): String? {
        val b = base(e)
        return when (field) {
            "stressLevelClass" -> when {
                b.stress >= 70 -> "High"
                b.stress >= 45 -> "Moderate"
                b.stress >= 25 -> "Low"
                else -> "Calm"
            }
            "dayMode" -> if (b.readiness >= 70) "green" else if (b.readiness >= 45) "orange" else "red"
            "energyLevel" -> if (b.readiness >= 70) "High Energy" else if (b.readiness >= 45) "Moderate Energy" else "Low Energy"
            "heaviestMeetingDay" -> (0 until 7).map { base(e - it) }.filter { it.meetingMin > 0 }
                .maxByOrNull { it.meetingMin }?.let { DayKey.string(LocalDate.ofEpochDay(it.e)) }
            "mealTiming" -> if (!foodLogged(e)) null else if (white(e, 353) > 0.5) "Late dinner" else "Regular"
            "strengthJSON.exercises" -> b.workout(Sport.gym)?.let {
                if (dow(e) == DayOfWeek.MONDAY) "Back squat 4x8, Bench press 4x8, Barbell row 3x10"
                else "Deadlift 4x5, Overhead press 4x8, Pull-up 3x8"
            }
            "strengthJSON.muscleGroups" -> b.workout(Sport.gym)?.let {
                if (dow(e) == DayOfWeek.MONDAY) "Legs, Chest, Back" else "Back, Shoulders, Arms"
            }
            "healthGoals.bedtime" -> "23:15"
            "healthGoals.wakeTime" -> "07:00"
            "burnoutIndexRisk" -> number("burnoutIndexScore", e)?.let { risk(it.toInt()) }
            else -> null
        }
    }

    private fun bodyFat(e: Long): Double = r1(18.2 - 0.5 * fitness(e) + 0.3 * white(e, 245))

    private fun consumed(b: Base): Double = whole((b.activeKcal + b.restingKcal) * (0.96 + 0.1 * white(b.e, 354)))

    /** Weekend recovery against the weekdays of the last seven days. */
    private fun weekendDelta(e: Long): Double {
        val days = (0 until 7).map { base(e - it) }
        val weekend = days.filter { it.weekend }.map { it.recovery.toDouble() }
        val weekdays = days.filter { !it.weekend }.map { it.recovery.toDouble() }
        return whole(weekend.average() - weekdays.average())
    }

    // MARK: Alerts

    /** What Health Data's rules would raise on day `e`, most pressing first. */
    private fun alertsFor(e: Long): List<AlertItem> {
        val b = base(e)
        val key = DayKey.string(LocalDate.ofEpochDay(e))
        fun alert(featureId: String, title: String, detail: String, ref: String?) =
            AlertItem(id = "sample-$key-$featureId", featureId = featureId, title = title, detail = detail, date = key,
                actionRef = ref)
        val out = mutableListOf<AlertItem>()
        if (b.sleepMin < 400) {
            val hours = r1(b.sleepMin / 60.0)
            out.add(alert("DX-04", "Short night - a nap will help",
                "You got about ${hours}h last night. A 20-minute nap early this afternoon will take the edge off.", "nap"))
        }
        if (b.recovery < 56) {
            out.add(alert("DX-05", "Take it easy today",
                "Your body is under-recovered this morning. Keep the load light and give yourself an easier day where you can.",
                "recovery"))
        }
        if (b.stress >= 48 || base(e - 1).stress >= 55) {
            out.add(alert("DX-07", "Time for a stress reset",
                "Your stress has stayed high for a while. A minute of slow breathing - in for four, out for six - will bring it down.",
                "breathing"))
        }
        out.add(alert("PB-03", "You've been sitting a while",
            "Stand up and move for a minute or two. A short walk resets your posture and your attention.", "move"))
        return out
    }

    // MARK: Numbers

    /** -1 to 1, the same for a day and salt every time (SplitMix64). */
    private fun white(e: Long, salt: Int): Double {
        var z = e * 31 + salt * 1_000_003L - 7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble() * 2 - 1
    }

    /** -1 to 1, drifting smoothly from one five-day anchor to the next. */
    private fun smooth(e: Long, salt: Int): Double {
        val k = Math.floorDiv(e, 5L)
        val t = (e - k * 5) / 5.0
        val a = white(k, salt + 5000)
        val b = white(k + 1, salt + 5000)
        return a + (b - a) * (1 - cos(t * PI)) / 2
    }

    private fun clamp(value: Double, low: Double, high: Double): Double = min(high, max(low, value))

    private fun whole(value: Double): Double = Math.round(value).toDouble()

    private fun r1(value: Double): Double = Math.round(value * 10) / 10.0

    private fun r2(value: Double): Double = Math.round(value * 100) / 100.0

    private fun roundTo(value: Double, step: Double): Double = Math.round(value / step) * step

    // MARK: Catalogue rows

    private fun section(id: String, title: String, vararg rows: CatalogueParameter) = CatalogueSection(id, title, rows.toList())

    private fun s(name: String, field: String, unit: String, format: String) =
        CatalogueParameter(name = name, field = field, read = "series", unit = unit, format = format)

    private fun l(name: String, field: String) = CatalogueParameter(name = name, field = field, read = "labels", unit = "", format = "text")

    private fun p(name: String, field: String?, read: String, unit: String, format: String) =
        CatalogueParameter(name = name, field = field, read = read, unit = unit, format = format)

    private fun none(name: String) = CatalogueParameter(name = name, field = null, read = "none", unit = "", format = "int")

    private val symptoms = listOf(
        "Headache", "Fatigue", "Fever", "Nausea", "Dizziness", "Bloating", "Abdominal Cramps", "Chest Tightness",
        "Shortness of Breath", "Coughing", "Sore Throat", "Runny Nose", "Sinus Congestion", "Body Ache", "Lower Back Pain",
        "Constipation", "Diarrhea", "Heartburn", "Vomiting", "Appetite Changes", "Chills", "Hot Flashes", "Night Sweats",
        "Dry Skin", "Acne", "Hair Loss", "Mood Changes", "Memory Lapse", "Sleep Disruption", "Wheezing", "Loss of Smell",
        "Loss of Taste", "Fainting", "Rapid Heartbeat", "Skipped Heartbeat", "Bladder Incontinence", "Breast Pain",
        "Pelvic Pain", "Vaginal Dryness", "Eye Strain", "Neck/Shoulder Tension", "Brain Fog", "Wrist Pain (RSI)",
    )
}
