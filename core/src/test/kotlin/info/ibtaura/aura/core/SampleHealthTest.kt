package info.ibtaura.aura.core

import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SampleHealthTest {
    private val today = LocalDate.of(2026, 11, 14)
    private fun key(day: LocalDate) = DayKey.string(day)
    private fun lastDays(n: Long, end: LocalDate = today) = key(end.minusDays(n - 1)) to key(end)

    private fun values(field: String, days: Long = SampleHealth.historyDays.toLong(), end: LocalDate = today): List<Double> {
        val (from, to) = lastDays(days, end)
        return SampleHealth.series(field, from, to, end).points.mapNotNull { it.value }
    }

    private fun byDate(field: String): Map<String, Double> {
        val (from, to) = lastDays(SampleHealth.historyDays.toLong())
        return SampleHealth.series(field, from, to, today).points.filter { it.value != null }.associate { it.date to it.value!! }
    }

    private fun correlation(a: List<Double>, b: List<Double>): Double {
        val ma = a.average()
        val mb = b.average()
        val cov = a.indices.sumOf { (a[it] - ma) * (b[it] - mb) }
        val va = a.sumOf { (it - ma) * (it - ma) }
        val vb = b.sumOf { (it - mb) * (it - mb) }
        return cov / sqrt(va * vb)
    }

    /** Rows nothing measures for this person: they use no wheelchair. */
    private val notThisPerson = setOf("tierCPhoneJSON.pushCount", "tierCPhoneJSON.wheelchairDistanceM")

    @Test
    fun sameInputsGiveTheSameAnswers() {
        val (from, to) = lastDays(30)
        assertEquals(SampleHealth.series("stepCount", from, to, today), SampleHealth.series("stepCount", from, to, today))
        assertEquals(SampleHealth.availability(today, from, to), SampleHealth.availability(today, from, to))
        assertEquals(SampleHealth.dailyMetrics(from, to, today), SampleHealth.dailyMetrics(from, to, today))
        assertEquals(SampleHealth.scores(today, 2), SampleHealth.scores(today, 2))
        assertEquals(SampleHealth.alerts(from, to, today), SampleHealth.alerts(from, to, today))
        assertEquals(SampleHealth.zoneScore(today.plusDays(3)), SampleHealth.zoneScore(today.plusDays(3)))
        // A day reads the same whichever day is today.
        val day = key(today.minusDays(3))
        assertEquals(
            SampleHealth.series("totalSleepMin", day, day, today).points,
            SampleHealth.series("totalSleepMin", day, day, today.plusDays(1)).points,
        )
    }

    @Test
    fun nothingAfterTodayAndNothingBeforeTheHistory() {
        val from = key(today.minusDays(200))
        val to = key(today.plusDays(20))
        val first = today.minusDays(SampleHealth.historyDays - 1L)
        for (field in listOf("stepCount", "totalSleepMin", "burnoutIndexScore", "tierCModeJSON.swimDistanceM")) {
            val dates = SampleHealth.series(field, from, to, today).points.map { DayKey.localDate(it.date)!! }
            assertTrue(dates.isNotEmpty())
            assertTrue(dates.all { !it.isAfter(today) && !it.isBefore(first) }, field)
        }
        assertEquals(key(first), SampleHealth.series("stepCount", from, to, today).points.first().date)
        assertTrue(SampleHealth.dailyMetrics(from, to, today).all { !DayKey.localDate(it.date)!!.isAfter(today) })
        assertTrue(SampleHealth.availability(today, from, to).all { !DayKey.localDate(it.latestDate)!!.isAfter(today) })
        assertTrue(SampleHealth.alerts(from, to, today).all { !DayKey.localDate(it.date)!!.isAfter(today) })
        val future = key(today.plusDays(1))
        assertTrue(SampleHealth.series("stepCount", future, future, today).points.isEmpty())
        assertTrue(SampleHealth.availability(today, future, future).isEmpty())
        assertTrue(SampleHealth.series("stepCount", "nope", key(today), today).points.isEmpty())
    }

    @Test
    fun sleepStagesAddUpInsideTimeInBed() {
        val total = byDate("totalSleepMin")
        val deep = byDate("deepSleepMin")
        val rem = byDate("remSleepMin")
        val light = byDate("lightSleepMin")
        val inBed = byDate("timeInBedMin")
        assertEquals(SampleHealth.historyDays, total.size)
        for ((date, minutes) in total) {
            assertEquals(minutes, deep[date]!! + rem[date]!! + light[date]!!, date)
            assertTrue(inBed[date]!! > minutes, date)
            assertTrue(minutes in 340.0..490.0, "$date slept $minutes min")
        }
    }

    @Test
    fun everySeriesRowHasDataInAnyFortnight() {
        val fields = SampleHealth.catalogue().flatMap { it.parameters }.filter { it.hasSeries }.mapNotNull { it.field }.toSet()
        assertTrue(fields.size > 150)
        for (offset in 0L until 14L) {
            val end = today.minusDays(offset)
            for (field in fields - notThisPerson) {
                assertTrue(values(field, 14, end).isNotEmpty(), "$field has no data in the 14 days to $end")
            }
        }
        for (field in notThisPerson) assertTrue(values(field).isEmpty())
    }

    @Test
    fun catalogueMirrorsHealthData() {
        val sections = SampleHealth.catalogue()
        assertEquals(
            listOf(
                "movement", "running", "cycling", "gym", "swimming", "winter", "body", "heart", "stress", "sleep", "recovery",
                "focus", "screen", "meetings", "mental", "respiration", "mobility", "nutrition", "cycle", "hearing",
                "symptoms", "burnout", "habits", "progress", "other",
            ),
            sections.map { it.id },
        )
        // The old Mac page's 341 rows and the Quick check.
        assertEquals(342, sections.sumOf { it.parameters.size })
        for (section in sections) {
            if (section.id == "symptoms" || section.id == "habits") continue
            assertTrue(section.parameters.any { it.hasSeries }, "${section.id} has nothing to chart")
        }
        val movement = sections.first().parameters
        assertEquals(CatalogueParameter("Walk Distance", "walkDistanceM", "series", "km", "metresAsKm"), movement[1])
    }

    @Test
    fun fieldsTheAppReadsByNameHaveData() {
        val (from, to) = lastDays(400)
        val available = SampleHealth.availability(today, from, to).associateBy { it.field }
        val catalogued = SampleHealth.catalogue().flatMap { it.parameters }.mapNotNull { it.field }.toSet()
        // Goals' daily fields, Burnout's, Logbook's weight and the first rows of the Search pages.
        val named = listOf(
            "stepCount", "activeKcal", "standHours", "stepGoalProgressPct", "activeKcalGoalProgressPct",
            "standHoursGoalProgressPct", "burnoutIndexScore", "weightKg", "totalSleepMin", "restingHrBpm", "hrvRmssdMs",
            "recoveryScore", "stressScore", "spo2Pct", "focusScore", "attentionScore",
        )
        for (field in named) {
            assertTrue(field in catalogued, "$field isn't catalogued")
            val item = assertNotNull(available[field], "$field has no latest value")
            assertNotNull(item.latestNumber, field)
        }
        assertEquals(key(today), available.getValue("stepCount").latestDate)
        // Every catalogued row with a value shows under Available Data.
        for (field in catalogued - notThisPerson) {
            val read = SampleHealth.catalogue().flatMap { it.parameters }.first { it.field == field }.read
            if (read == "series" || read == "labels" || read == "profile") assertTrue(field in available, field)
        }
        val flag = available.getValue("hrvAboveBaseline")
        assertTrue(flag.latestText == "Yes" || flag.latestText == "No")
        assertEquals(null, flag.latestNumber)
        assertNotNull(available.getValue("dayMode").latestText)
    }

    @Test
    fun valuesStayInRealisticRanges() {
        assertTrue(values("stepCount").all { it in 4000.0..13000.0 })
        assertTrue(values("restingHrBpm").all { it in 56.0..66.0 })
        assertTrue(values("spo2Pct").all { it in 95.0..99.0 })
        assertTrue(values("stressScore").all { it in 20.0..70.0 })
        assertTrue(values("weightKg").all { it in 70.5..73.5 })
        assertTrue(values("totalKcal").all { it in 1800.0..3400.0 })
        // Calories follow movement.
        assertTrue(correlation(values("activeKcal"), values("exerciseMinutes")) > 0.6)
        // HRV falls as resting heart rate rises.
        assertTrue(correlation(values("hrvRmssdMs"), values("restingHrBpm")) < -0.5)
        // Some days have no workout.
        assertTrue(values("tierCModeJSON.gymDurationMin").size in 6..16)
    }

    @Test
    fun capacityFollowsSleep() {
        val scores = (0 until SampleHealth.historyDays).map { SampleHealth.zoneScore(today.minusDays(it.toLong())) }
        assertTrue(scores.all { it in 45..92 })
        assertTrue(scores.toSet().size > 10)
        val sleep = (0 until SampleHealth.historyDays).map { values("totalSleepMin", 1, today.minusDays(it.toLong())).single() }
        assertTrue(correlation(scores.map { it.toDouble() }, sleep) > 0.5)
        // Future days have a score too, for day zones and Find a time.
        assertTrue(SampleHealth.zoneScore(today.plusDays(10)) in 45..92)
    }

    @Test
    fun burnoutHasCalmAndElevatedDays() {
        val (from, to) = lastDays(SampleHealth.historyDays.toLong())
        val days = SampleHealth.dailyMetrics(from, to, today)
        assertEquals(SampleHealth.historyDays, days.size)
        val risks = days.mapNotNull { it.burnoutIndexRisk }.toSet()
        assertTrue("High" in risks, "$risks")
        assertTrue("Moderate" in risks, "$risks")
        for (day in days) assertEquals(SampleHealth.risk(day.burnoutIndexScore!!.toInt()), day.burnoutIndexRisk)
    }

    @Test
    fun scoresAreAgainstANormalOf100() {
        val scores = SampleHealth.scores(today, 3)
        for (score in listOf(scores.attention, scores.memory, scores.thinking, scores.focus)) {
            assertTrue(score.value!! in 75.0..120.0, "${score.name} ${score.value}")
        }
        assertEquals(3, scores.attention.checksCounted)
        assertEquals(scores.focus.value, values("focusScore", 1).single())
    }

    @Test
    fun alertsAreOnesTheAppCanStart() {
        val handled = setOf("nap", "recovery", "breathing", "move")
        for (offset in 0L until 30L) {
            val day = today.minusDays(offset)
            val todays = SampleHealth.alerts(key(day), key(day), day)
            assertTrue(todays.isNotEmpty() && todays.size <= 2)
            assertTrue(todays.all { it.date == key(day) && !it.done && it.actionRef in handled })
            val recent = SampleHealth.alerts(key(day.minusDays(10)), key(day), day)
            assertTrue(recent.size in 3..4, "${recent.size} alerts")
            assertEquals(recent.size, recent.map { it.id }.toSet().size)
        }
    }
}
