package info.ibtaura.aura.core

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sample mode's backend joins the activity seeds and the health generators into one story. */
class SampleApiTest {
    private val clock = Instant.parse("2026-11-18T06:30:00Z")
    private val api = SampleApi(now = { clock })
    private val today = clock.atZone(DayKey.zone).toLocalDate()
    private val todayKey = DayKey.string(today)
    private val weekAgo = DayKey.string(today.minusDays(6))

    @Test
    fun healthReadsComeFromSampleHealth() = runTest {
        val scores = api.scoresToday()
        assertNotNull(scores.attention.value)
        assertEquals(12, scores.attention.checksCounted, "keeps the stub's counted checks")
        assertTrue(api.catalogue().size > 20)
        assertTrue(api.series("stepCount", weekAgo, todayKey).points.count { it.value != null } >= 6)
        assertTrue(api.dailyMetrics(weekAgo, todayKey).isNotEmpty())
    }

    @Test
    fun zonesFollowTheSampleCapacity() = runTest {
        val zone = api.dayZones(todayKey, todayKey).single()
        assertEquals(SampleHealth.zoneScore(today), zone.score)
    }

    @Test
    fun activityIsSeeded() = runTest {
        assertTrue(api.tasks().size >= 8)
        val start = today.atStartOfDay(DayKey.zone).toInstant()
        assertTrue(api.events(start, start.plusSeconds(86_400)).isNotEmpty())
        assertTrue(api.habits(includeArchived = false).isNotEmpty())
    }
}
