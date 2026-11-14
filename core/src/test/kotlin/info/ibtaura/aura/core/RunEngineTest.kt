package info.ibtaura.aura.core

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A port of RunEngineTests.swift. */
class RunEngineTest {
    private val t0 = Instant.ofEpochSecond(1_000_000)

    private fun Instant.plus(seconds: Int): Instant = plusSeconds(seconds.toLong())

    @Test
    fun clockSurvivesTheBackground() {
        val run = RunEngine(minutes = 5, hasCountdown = false, now = t0)
        // Nothing ticks while the app is away; the time is worked out from the clock.
        assertEquals(180.0, run.snapshot(t0.plus(120)).remaining)
    }

    @Test
    fun pauseStopsTheClockAndMovesTheEnd() {
        var run = RunEngine(minutes = 5, hasCountdown = false, now = t0)
        run = run.pause(t0.plus(60))
        assertEquals(RunPhase.Paused, run.snapshot(t0.plus(600)).phase)
        assertEquals(60.0, run.elapsed(t0.plus(600)))
        assertNull(run.endDate())
        run = run.resume(t0.plus(120))
        assertEquals(120.0, run.elapsed(t0.plus(180)))
        assertEquals(t0.plus(360), run.endDate())
    }

    @Test
    fun countdownComesFirst() {
        val run = RunEngine(minutes = 25, hasCountdown = true, now = t0)
        assertEquals(RunPhase.Countdown(left = 2), run.snapshot(t0.plus(1)).phase)
        assertEquals(RunPhase.Running, run.snapshot(t0.plus(3)).phase)
        assertEquals(1.0, run.snapshot(t0.plus(4)).elapsed)
    }

    @Test
    fun timeUpAndOpenEnded() {
        val timed = RunEngine(minutes = 1, hasCountdown = false, now = t0)
        assertEquals(RunPhase.TimeUp, timed.snapshot(t0.plus(61)).phase)
        val open = RunEngine(minutes = 0, hasCountdown = false, now = t0)
        val later = open.snapshot(t0.plus(90_000))
        assertEquals(RunPhase.Running, later.phase)
        assertNull(later.remaining)
        assertNull(later.progress)
    }

    @Test
    fun boxBreathingPhases() {
        val box = BreathPattern(`in` = 4.0, hold = 4.0, out = 4.0, hold2 = 4.0)
        assertEquals(BreathPhase.smallest, BreathPhase.at(0.0, box).scale, 0.001)
        assertEquals(3, BreathPhase.at(1.0, box).left)
        val held = BreathPhase.at(5.0, box)
        assertEquals(BreathPhase.Word.hold, held.word)
        assertEquals(1.0, held.scale, 0.001)
        assertEquals(BreathPhase.Word.exhale, BreathPhase.at(9.0, box).word)
        val empty = BreathPhase.at(13.0, box)
        assertEquals(BreathPhase.Word.hold, empty.word)
        assertEquals(BreathPhase.smallest, empty.scale, 0.001)
        assertEquals(BreathPhase.Word.inhale, BreathPhase.at(16.0, box).word)
    }

    @Test
    fun noPatternIsCoherentAndAnEmptyOneIsTheSmallestCircle() {
        assertEquals(BreathPhase.Word.exhale, BreathPhase.at(6.0, null).word)
        val none = BreathPhase.at(3.0, BreathPattern(`in` = 0.0, out = 0.0))
        assertEquals(BreathPhase(BreathPhase.Word.inhale, 1, BreathPhase.smallest), none)
    }

    @Test
    fun promptsAdvanceEveryLengthOverCount() {
        val run = RunEngine(minutes = 6, hasCountdown = false, now = t0)
        assertEquals(0, run.promptIndex(t0, count = 6))
        assertEquals(1, run.promptIndex(t0.plus(61), count = 6))
        assertEquals(5, run.promptIndex(t0.plus(9_999), count = 6))
    }

    @Test
    fun clockText() {
        assertEquals("09:54", RunEngine.clock(594.0))
        assertEquals("1:02:03", RunEngine.clock(3723.0))
    }

    @Test
    fun endEarlyTitle() {
        val run = RunEngine(minutes = 25, hasCountdown = false, now = t0)
        // 18:42 left, as in frame 13
        assertEquals("End now? 19 minutes left.", run.endEarlyTitle(t0.plus(378)))
        assertEquals("End now? 1 minute left.", run.endEarlyTitle(t0.plus(1_440)))
        assertEquals("End now? Less than a minute left.", run.endEarlyTitle(t0.plus(1_470)))
        val open = RunEngine(minutes = 0, hasCountdown = false, now = t0)
        assertEquals("End now? 12:48 so far.", open.endEarlyTitle(t0.plus(768)))
    }

    @Test
    fun doneLengthText() {
        assertEquals("5 minutes", RunEngine.lengthText(300.0))
        assertEquals("1 minute", RunEngine.lengthText(60.0))
        assertEquals("Under a minute", RunEngine.lengthText(3.0))
    }

    @Test
    fun pausedRunSurvivesARestart() {
        var run = RunEngine(minutes = 5, hasCountdown = false, now = t0)
        run = run.pause(t0.plus(60))
        run = run.resume(t0.plus(90))
        val saved = Json.encodeToString(RunEngine.serializer(), run)
        val back = Json.decodeFromString(RunEngine.serializer(), saved)
        assertEquals(run, back)
        assertEquals(t0.plus(330), back.endDate())

        // A run saved while paused comes back paused.
        val paused = RunEngine(minutes = 5, hasCountdown = true, now = t0).pause(t0.plus(10))
        val pausedBack = Json.decodeFromString(RunEngine.serializer(), Json.encodeToString(RunEngine.serializer(), paused))
        assertEquals(paused, pausedBack)
        assertTrue(pausedBack.isPaused)
    }

    @Test
    fun kickerByKind() {
        val walk = Mode(id = "w", name = "Walk", word = "Walk", shelf = Shelf.outside, minutes = 20, kind = "walk")
        assertEquals("MOVE · 20 MIN", walk.kicker(20))
        val nap = Mode(id = "n", name = "Nap", word = "Nap", shelf = Shelf.sleep, minutes = 10, night = true)
        assertEquals("NAP · 10 MIN", nap.kicker(10))
        val wind = Mode(id = "d", name = "Wind-down", word = "Wind Down", shelf = Shelf.sleep, minutes = 10, run = RunKind.winddown)
        assertEquals("RECOVERY · 10 MIN", wind.kicker(10))
        val flow = Mode(id = "f", name = "Flow", word = "Flow", shelf = Shelf.focus, minutes = 0, kind = "focus", run = RunKind.flow)
        assertEquals("FOCUS · OPEN", flow.kicker(0))
    }

    @Test
    fun lengthAndBreathText() {
        assertEquals("10m", Mode.lengthText(10))
        assertEquals("8h", Mode.lengthText(480))
        assertEquals("1h 30m", Mode.lengthText(90))
        assertEquals("Open", Mode.lengthText(0))
        assertEquals("In 4 · Hold 4 · Out 4 · Hold 4", BreathPattern(`in` = 4.0, hold = 4.0, out = 4.0, hold2 = 4.0).line)
        assertEquals("In 4 · Hold 7 · Out 8", BreathPattern(`in` = 4.0, hold = 7.0, out = 8.0).line)
    }

    @Test
    fun planSlotIsTodayWhileAheadElseTomorrow() {
        val utc = ZoneOffset.UTC
        fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 11, day, hour, 0).toInstant(utc)
        val noon = at(2, 12)
        val evening = Mode(id = "alcohol-free-evening", name = "E", word = "E", shelf = Shelf.fuel, minutes = 240, run = RunKind.plan)
        val slot = evening.planSlot(noon, utc)
        assertEquals(at(2, 18), slot.start)
        assertEquals(at(2, 22), slot.end)
        val dayOff = Mode(id = "day-off", name = "D", word = "D", shelf = Shelf.rest, minutes = 480, run = RunKind.plan)
        val late = dayOff.planSlot(noon, utc)
        assertEquals(at(3, 9), late.start)
    }

    @Test
    fun searchMatchesNameAndWord() {
        val nap = Mode(id = "nap-10", name = "Ten-minute nap", word = "Nap", shelf = Shelf.sleep, minutes = 10)
        assertTrue(nap.matches("TEN"))
        assertTrue(nap.matches(" nap "))
        assertTrue(nap.matches(""))
        assertFalse(nap.matches("walk"))
    }

    @Test
    fun theSixtyFiveModes() {
        val modes = Mode.loadCatalogue()
        assertEquals(65, modes.size)
        assertEquals(65, modes.map { it.id }.toSet().size)
        assertEquals(Shelf.entries.size, modes.map { it.shelf }.toSet().size)
        assertFalse(modes.any { it.kind == "nap" })
        assertTrue(modes.filter { it.run == RunKind.breathe }.all { it.pattern != null })
        assertTrue(modes.filter { it.run == RunKind.guided }.all { it.prompts?.size == 6 })
        assertEquals(2, modes.count { it.run == RunKind.plan })
        assertEquals(true, modes.firstOrNull { it.id == "nap-10" }?.night)
    }

    @Test
    fun nowAlwaysHasCards() {
        val modes = Mode.loadCatalogue()
        for (hour in 0 until 24) {
            val cards = Mode.nowCards(modes, hour, limit = 4)
            assertEquals(4, cards.size, "hour $hour")
            assertFalse(cards.any { (it.avoidHours ?: emptyList()).contains(hour) }, "hour $hour")
        }
    }

    @Test
    fun burnoutDotsAndTrend() {
        assertEquals(0, MetricMaths.burnoutLevel(null))
        assertEquals(9, MetricMaths.burnoutLevel(72.0))
        assertEquals(12, MetricMaths.burnoutLevel(100.0))
        val old = (0 until 7).map { MetricPoint(date = "d$it", value = 2.0) }
        val new = (7 until 14).map { MetricPoint(date = "d$it", value = 3.0) }
        assertEquals(50.0, MetricMaths.weekTrend(old + new) ?: 0.0, 0.001)
        assertNull(MetricMaths.weekTrend(new))
    }
}
