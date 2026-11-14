package info.ibtaura.aura.core

import java.time.LocalDate

/**
 * Sample mode's backend: weeks of made-up use, all in memory, no network. Tasks, events, sessions, logs, habits and
 * checks come from StubApi's sample seeds (and every edit works as it does there); health metrics, scores, alerts and
 * each day's capacity come from SampleHealth, so day zones and Find a time follow the same made-up sleep.
 */
class SampleApi private constructor(
    private val now: AuraNow,
    private val base: StubApi,
) : AuraApi by base {
    constructor(now: AuraNow = realNow) : this(
        now,
        StubApi(workSchedule = StubApi.sampleSchedule, sample = true, zoneScoreFor = SampleHealth::zoneScore, now = now),
    )

    private fun today(): LocalDate = now().atZone(DayKey.zone).toLocalDate()

    /** SampleHealth's scores, keeping the stub's running count of Quick checks taken here. */
    override suspend fun scoresToday(): ScoresToday =
        SampleHealth.scores(today(), base.scoresToday().attention.checksCounted)

    override suspend fun alerts(from: String, to: String): List<AlertItem> = SampleHealth.alerts(from, to, today())

    override suspend fun dailyMetrics(from: String, to: String): List<DailyMetrics> =
        SampleHealth.dailyMetrics(from, to, today())

    override suspend fun catalogue(): List<CatalogueSection> = SampleHealth.catalogue()

    override suspend fun availability(from: String, to: String): List<FieldAvailability> =
        SampleHealth.availability(today(), from, to)

    override suspend fun series(field: String, from: String, to: String): MetricSeries =
        SampleHealth.series(field, from, to, today())
}
