package info.ibtaura.auraclarus.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import info.ibtaura.aura.core.DailyHealthInput
import info.ibtaura.aura.core.HealthReading
import info.ibtaura.aura.core.HealthSpan
import info.ibtaura.aura.core.HealthWorkout
import info.ibtaura.aura.core.SleepSample
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Reads Health Connect for Aura. Read only: it never writes or deletes anything. Values it reads go to the backend and
 * nowhere else (never to the log).
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
internal class HealthConnectReader(private val context: Context) {

    /** SDK_AVAILABLE, SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED (not installed or too old) or SDK_UNAVAILABLE. */
    fun sdkStatus(): Int = runCatching { HealthConnectClient.getSdkStatus(context) }
        .getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)

    val isAvailable: Boolean get() = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    private fun featureAvailable(feature: Int): Boolean = runCatching {
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /** Every read permission Aura asks for in one go; skin temperature and mindfulness only where this Health Connect has them. */
    fun requestedPermissions(): Set<String> = buildSet {
        add(SLEEP)
        addAll(DAY_PERMISSIONS)
        if (featureAvailable(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)) add(SKIN_TEMPERATURE)
        if (featureAvailable(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)) add(MINDFULNESS)
    }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    // MARK: Sleep

    /**
     * The sleep samples of every session overlapping `from`...`to`. A session is `inBed`; its stages map as `SleepSample`
     * says. A session with no stages counts as asleep throughout, since some apps write only the session.
     */
    suspend fun sleepSamples(from: Instant, to: Instant): List<SleepSample> =
        readAll(SleepSessionRecord::class, TimeRangeFilter.between(from, to)).flatMap { samples(it) }

    private fun samples(session: SleepSessionRecord): List<SleepSample> {
        val out = mutableListOf(SleepSample(session.startTime, session.endTime, SleepSample.Kind.inBed))
        if (session.stages.isEmpty()) {
            out.add(SleepSample(session.startTime, session.endTime, SleepSample.Kind.asleepUnspecified))
        }
        for (stage in session.stages) {
            val kind = when (stage.stage) {
                SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepSample.Kind.asleepCore
                SleepSessionRecord.STAGE_TYPE_DEEP -> SleepSample.Kind.asleepDeep
                SleepSessionRecord.STAGE_TYPE_REM -> SleepSample.Kind.asleepREM
                SleepSessionRecord.STAGE_TYPE_SLEEPING, SleepSessionRecord.STAGE_TYPE_UNKNOWN -> SleepSample.Kind.asleepUnspecified
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepSample.Kind.awake
                else -> null
            } ?: continue
            out.add(SleepSample(stage.startTime, stage.endTime, kind))
        }
        return out
    }

    // MARK: Days

    /**
     * One input per day in `days` (each a day in `zone`), reading only the types in `granted`. Totals come from Health
     * Connect's daily aggregates; readings and workouts are read raw and the builder keeps those inside each day.
     */
    suspend fun days(days: List<LocalDate>, zone: ZoneId, granted: Set<String>, now: Instant): List<DailyHealthInput> {
        if (days.isEmpty()) return emptyList()
        val first = days.min()
        val last = days.max()
        val start = first.atStartOfDay(zone).toInstant()
        val end = last.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(start, end)

        val totals = dailyTotals(first, last, granted)
        val restingHr = if (RESTING_HR in granted) {
            readAll(RestingHeartRateRecord::class, range).map { origin(it) to HealthReading(it.time, it.beatsPerMinute.toDouble()) }
        } else emptyList()
        val hrv = if (HRV in granted) {
            readAll(HeartRateVariabilityRmssdRecord::class, range).map { origin(it) to HealthReading(it.time, it.heartRateVariabilityMillis) }
        } else emptyList()
        val breaths = if (RESPIRATORY in granted) {
            readAll(RespiratoryRateRecord::class, range).map { origin(it) to HealthReading(it.time, it.rate) }
        } else emptyList()
        val spo2 = if (OXYGEN in granted) {
            readAll(OxygenSaturationRecord::class, range).map { origin(it) to HealthReading(it.time, it.percentage.value) }
        } else emptyList()
        val weight = if (WEIGHT in granted) {
            readAll(WeightRecord::class, range).map { origin(it) to HealthReading(it.time, it.weight.inKilograms) }
        } else emptyList()
        val skin = if (SKIN_TEMPERATURE in granted && featureAvailable(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)) {
            readAll(SkinTemperatureRecord::class, range).flatMap { record ->
                record.deltas.map { origin(record) to HealthReading(it.time, it.delta.inCelsius) }
            }
        } else emptyList()
        val mindful = if (MINDFULNESS in granted && featureAvailable(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)) {
            readAll(MindfulnessSessionRecord::class, range).map { origin(it) to HealthSpan(it.startTime, it.endTime) }
        } else emptyList()
        // From the evening before the first day, so the first day's night is whole.
        val sleep = if (SLEEP in granted) {
            sleepSamples(first.minusDays(1).atTime(12, 0).atZone(zone).toInstant(), end)
        } else emptyList()
        val workouts = if (EXERCISE in granted) workouts(range, granted) else emptyList()

        return days.sorted().map { day ->
            val dayStart = day.atStartOfDay(zone).toInstant()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
            fun <T> inDay(list: List<Pair<String, T>>, at: (T) -> Instant) =
                list.filter { at(it.second) >= dayStart && at(it.second) < dayEnd }
            val dayTotals = totals[day]
            val dayReadings = listOf(restingHr, hrv, breaths, spo2, weight, skin).map { inDay(it) { r -> r.at } }
            val dayMindful = mindful.filter { it.second.end > dayStart && it.second.start < dayEnd }
            val dayWorkouts = workouts.filter { it.startedAt >= dayStart && it.startedAt < dayEnd }
            // The night that ended this day: from the evening before to this afternoon.
            val nightFrom = day.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
            val nightTo = day.atTime(14, 0).atZone(zone).toInstant()
            val daySleep = sleep.filter { it.end > nightFrom && it.start < nightTo }
            val sources = buildList {
                dayTotals?.sources?.let { addAll(it) }
                dayReadings.forEach { list -> list.forEach { add(it.first) } }
                dayMindful.forEach { add(it.first) }
                dayWorkouts.forEach { w -> w.source?.let { add(it) } }
            }.filter { it.isNotBlank() }.distinct()
            DailyHealthInput(
                day = day,
                zone = zone,
                recordedAt = now,
                sources = sources,
                steps = dayTotals?.steps,
                distanceMetres = dayTotals?.distanceMetres,
                activeKcal = dayTotals?.activeKcal,
                exerciseMinutes = dayTotals?.exerciseMinutes,
                restingHr = dayReadings[0].map { it.second },
                hrvRmssd = dayReadings[1].map { it.second },
                respiratoryRate = dayReadings[2].map { it.second },
                spo2 = dayReadings[3].map { it.second },
                weight = dayReadings[4].map { it.second },
                skinTempDelta = dayReadings[5].map { it.second },
                mindful = dayMindful.map { it.second },
                sleep = daySleep,
                workouts = dayWorkouts,
            )
        }
    }

    private class DayTotals(
        val steps: Long?,
        val distanceMetres: Double?,
        val activeKcal: Double?,
        val exerciseMinutes: Double?,
        val sources: List<String>,
    )

    /** Health Connect's per-day aggregates (which settle overlapping apps), for the granted types only. */
    private suspend fun dailyTotals(first: LocalDate, last: LocalDate, granted: Set<String>): Map<LocalDate, DayTotals> {
        val metrics = buildSet {
            if (STEPS in granted) add(StepsRecord.COUNT_TOTAL)
            if (DISTANCE in granted) add(DistanceRecord.DISTANCE_TOTAL)
            if (ACTIVE_CALORIES in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (EXERCISE in granted) add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
        }
        if (metrics.isEmpty()) return emptyMap()
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(first.atStartOfDay(), last.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        return groups.associate { group ->
            val result = group.result
            group.startTime.toLocalDate() to DayTotals(
                steps = result[StepsRecord.COUNT_TOTAL],
                distanceMetres = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                activeKcal = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                exerciseMinutes = result[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.let { it.seconds / 60.0 },
                sources = result.dataOrigins.map { it.packageName },
            )
        }
    }

    /** Exercise sessions with their calories and distance from that span's aggregate (up to 60 sessions a read). */
    private suspend fun workouts(range: TimeRangeFilter, granted: Set<String>): List<HealthWorkout> {
        val sessions = readAll(ExerciseSessionRecord::class, range).sortedBy { it.startTime }.takeLast(MAX_WORKOUTS)
        val spanMetrics = buildSet {
            if (ACTIVE_CALORIES in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (DISTANCE in granted) add(DistanceRecord.DISTANCE_TOTAL)
        }
        return sessions.mapNotNull { session ->
            if (!session.endTime.isAfter(session.startTime)) return@mapNotNull null
            val totals = if (spanMetrics.isEmpty()) null else runCatching {
                client.aggregate(AggregateRequest(spanMetrics, TimeRangeFilter.between(session.startTime, session.endTime)))
            }.getOrNull()
            val minutes = java.time.Duration.between(session.startTime, session.endTime).toMinutes().toInt()
            HealthWorkout(
                id = session.metadata.id.ifBlank { "${session.startTime.toEpochMilli()}" },
                type = ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[session.exerciseType] ?: "other",
                startedAt = session.startTime,
                endedAt = session.endTime,
                minutes = minutes.coerceIn(0, 1440),
                activeKcal = totals?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
                distanceKm = totals?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters?.let { it / 1000 },
                avgHrBpm = null,
                source = session.metadata.dataOrigin.packageName.ifBlank { null },
            )
        }
    }

    private fun origin(record: Record): String = record.metadata.dataOrigin.packageName

    /** Every page of one record type in the range, up to a cap that keeps one sync light. */
    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(recordType = type, timeRangeFilter = range, pageSize = 1000, pageToken = token),
            )
            out.addAll(response.records)
            token = response.pageToken
        } while (token != null && out.size < MAX_RECORDS)
        return out
    }

    companion object {
        private const val MAX_RECORDS = 20_000
        private const val MAX_WORKOUTS = 60

        val SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        val DISTANCE = HealthPermission.getReadPermission(DistanceRecord::class)
        val ACTIVE_CALORIES = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
        val EXERCISE = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        val RESTING_HR = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
        val HRV = HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
        val RESPIRATORY = HealthPermission.getReadPermission(RespiratoryRateRecord::class)
        val OXYGEN = HealthPermission.getReadPermission(OxygenSaturationRecord::class)
        val WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)
        val SKIN_TEMPERATURE = HealthPermission.getReadPermission(SkinTemperatureRecord::class)
        val MINDFULNESS = HealthPermission.getReadPermission(MindfulnessSessionRecord::class)

        /** The day data (all but sleep, which the night route takes). */
        val DAY_PERMISSIONS = setOf(STEPS, DISTANCE, ACTIVE_CALORIES, EXERCISE, RESTING_HR, HRV, RESPIRATORY, OXYGEN, WEIGHT)

        /** Any of these granted is enough to send days. */
        val ALL_DAY_PERMISSIONS = DAY_PERMISSIONS + setOf(SLEEP, SKIN_TEMPERATURE, MINDFULNESS)
    }
}
