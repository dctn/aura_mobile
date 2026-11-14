package info.ibtaura.aura.core

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A port of BetaStubTests (BetaScoreTests.swift) and StubAPITests (LiveAPITests.swift). The stub runs on the fixed
 * noon clock of `stubApi()`, so the seeded 09:00 session is always earlier than a run started "now".
 */
class StubBetaScoreTest {
    /** TestRig.now in Swift. */
    private val rigNow = Instant.ofEpochSecond(1_900_000_000)

    @Test
    fun seededSessionAndFinishedRunsComeNewestFirst() = runTest {
        val stub = stubApi()
        stub.startSession(id = "run-1", kind = "focus", mode = "timer", plannedMinutes = 25, title = "Write", recoveryType = null)
        stub.startSession(id = "run-2", kind = "recovery", mode = "timer", plannedMinutes = 10, title = "Nap", recoveryType = RecoveryType.nap)
        stub.sessionAction(id = "run-1", action = SessionAction.complete)
        val key = stubTodayKey
        val records = stub.sessions(from = key, to = key)
        // run-2 never ended, so it isn't listed yet. Noon is after 09:00.
        assertEquals(listOf("run-1", "s-deep"), records.map { it.id })
        val seeded = assertNotNull(records.firstOrNull { it.id == "s-deep" })
        assertEquals("focus", seeded.kind)
        assertEquals("Deep work", seeded.title)
        assertEquals(50, seeded.minutes)
        assertEquals("completed", seeded.outcome)
        assertNull(seeded.difficulty)
        assertEquals(0.5, seeded.capacityCost)
        assertEquals(stubToday(9, 0), seeded.startedAt)
        assertEquals(stubToday(9, 50), seeded.endedAt)
        val run = assertNotNull(records.firstOrNull { it.id == "run-1" })
        assertEquals("completed", run.outcome)
        assertEquals("Write", run.title)
        assertEquals(25, run.minutes)
    }

    @Test
    fun beforeNineTheSeededSessionComesFirst() = runTest {
        // Swift's other branch (`Date() <= today(9, 0)`), reached here with an early clock.
        val early = stubToday(8, 0)
        val stub = StubApi(now = { early })
        stub.startSession(id = "run-1", kind = "focus", mode = "timer", plannedMinutes = 25, title = "Write", recoveryType = null)
        stub.sessionAction(id = "run-1", action = SessionAction.complete)
        val records = stub.sessions(from = stubTodayKey, to = stubTodayKey)
        assertEquals(listOf("s-deep", "run-1"), records.map { it.id })
    }

    @Test
    fun ratingChangesTheCapacityLine() = runTest {
        val stub = stubApi()
        assertEquals("50 min of desk work at demand 60: -0.5.", stub.capacityLine(sessionId = "s-deep"))
        val rated = stub.setDifficulty(sessionId = "s-deep", difficulty = Difficulty.hard)
        assertEquals(Difficulty.hard, rated.difficulty)
        assertEquals(0.8, rated.capacityCost)
        assertEquals("50 min of desk work at demand 80: -0.8. You rated it Hard.", stub.capacityLine(sessionId = "s-deep"))
        stub.setDifficulty(sessionId = "s-deep", difficulty = Difficulty.veryHard)
        assertEquals(
            "50 min of desk work at demand 100: -1.3. You rated it Very hard.",
            stub.capacityLine(sessionId = "s-deep"),
        )
        val listed = stub.sessions(from = stubTodayKey, to = stubTodayKey)
        assertEquals(Difficulty.veryHard, listed.firstOrNull { it.id == "s-deep" }?.difficulty)
    }

    @Test
    fun anUnknownIdIsAFocusSessionJustMade() = runTest {
        val stub = stubApi()
        assertNull(stub.capacityLine(sessionId = "fresh"))
        val rated = stub.setDifficulty(sessionId = "fresh", difficulty = Difficulty.easy)
        assertTrue(rated.isFocus)
        assertEquals(Difficulty.easy, rated.difficulty)
        assertEquals("50 min of desk work at demand 20: -0.1. You rated it Easy.", stub.capacityLine(sessionId = "fresh"))
    }

    @Test
    fun aRecoverySessionHasNoLineAndCantBeRated() = runTest {
        val stub = stubApi()
        stub.startSession(id = "rest-1", kind = "recovery", mode = "timer", plannedMinutes = 10, title = "Rest", recoveryType = RecoveryType.rest)
        stub.sessionAction(id = "rest-1", action = SessionAction.complete)
        assertNull(stub.capacityLine(sessionId = "rest-1"))
        val error = caught { stub.setDifficulty(sessionId = "rest-1", difficulty = Difficulty.easy) }
        assertEquals("not_focus_session", (error as? AuraError)?.serverCode)
    }

    @Test
    fun loggedBreaksOverlapAndResend() = runTest {
        val stub = stubApi()
        val overlapSeeded = caught { stub.logBreak(id = "x", type = RecoveryType.walk, startedAt = stubToday(9, 30), minutes = 20) }
        assertEquals(
            AuraError.Server(code = "log_overlaps", message = "This break overlaps another session.", status = 409),
            overlapSeeded,
        )
        val first = stub.logBreak(id = "b1", type = RecoveryType.walk, startedAt = stubToday(9, 50), minutes = 20)
        assertTrue(first.created)
        assertEquals("logged", first.session.outcome)
        assertEquals("recovery", first.session.kind)
        assertEquals(RecoveryType.walk, first.session.recoveryType)
        assertEquals(20, first.session.minutes)
        val overlapLogged = caught { stub.logBreak(id = "b2", type = RecoveryType.rest, startedAt = stubToday(10, 0), minutes = 15) }
        assertEquals("log_overlaps", (overlapLogged as? AuraError)?.serverCode)
        val again = stub.logBreak(id = "b1", type = RecoveryType.walk, startedAt = stubToday(9, 50), minutes = 20)
        assertFalse(again.created)
        assertEquals(first.session, again.session)
        val listed = stub.sessions(from = stubTodayKey, to = stubTodayKey)
        assertEquals(1, listed.count { it.id == "b1" })
    }

    @Test
    fun inboxDropsAnsweredItems() = runTest {
        val stub = stubApi()
        val key = stubTodayKey
        val items = stub.reviewInbox(date = key, days = 7)
        assertEquals(listOf("b-sync", "e1-review"), items.map { it.blockId })
        assertEquals("meeting", items[0].kind)
        assertEquals(stubToday(10, 0), items[0].start)
        assertEquals("10 Min nap", items[1].title)
        assertTrue(items[1].isRecovery)
        assertEquals(stubToday(15, 54), items[1].start)
        assertEquals(stubToday(16, 4), items[1].end)
        // A recovery block without a type is still taken.
        stub.submitReview(ReviewAnswer(item = items[1], happened = true))
        val left = stub.reviewInbox(date = key, days = 7)
        assertEquals(listOf("b-sync"), left.map { it.blockId })
        assertEquals(true, stub.savedReviews()["e1-review"]?.happened)
    }

    @Test
    fun recoveryOptionsAreTheRealValues() = runTest {
        val options = stubApi().recoveryOptions(minutes = 20)
        assertEquals(listOf("nap", "walk", "rest", "outside", "social"), options.map { it.id })
        assertEquals(
            listOf(
                "+10, fades within 2 h", "+9, fades within 50 min", "+5, fades within 40 min",
                "+9, fades within 50 min", "+5, fades within 40 min",
            ),
            options.map { it.hint },
        )
        assertEquals(listOf("Nap", "Walk", "Rest", "Time outside", "Talk with someone"), options.map { it.title })
        assertFalse(options.any { it.note.isEmpty() })
    }

    @Test
    fun sleepAndCheckin() = runTest {
        val stub = stubApi()
        assertEquals(SleepSchedule(bedtime = "23:00", wakeTime = "07:00"), stub.sleepSchedule())
        val night = SleepNight(
            date = "2030-03-17", timeZone = "UTC", bedtimeAt = rigNow.minusSeconds(28_800), wakeAt = rigNow, totalSleepMin = 450,
        )
        assertEquals(SleepNightResult(created = true, source = "appleHealth"), stub.putSleepNight(night))
        assertEquals(SleepNightResult(created = false, source = "appleHealth"), stub.putSleepNight(night))
        assertEquals(night, stub.savedNights()["2030-03-17"])
        stub.morningCheckin(alertness = 5, fellAsleepAt = "23:30", wokeAt = "07:10", at = rigNow)
        assertEquals(
            StubMorningCheckin(alertness = 5, fellAsleepAt = "23:30", wokeAt = "07:10", at = rigNow),
            stub.lastMorningCheckin(),
        )
    }

    // MARK: Android: Health Connect days

    private fun healthDay(date: String, recordedAt: Instant, steps: Long? = 1000): HealthDay =
        DailyTotalsBuilder.build(
            DailyHealthInput(
                day = requireNotNull(DayKey.localDate(date)), zone = DayKey.zone, recordedAt = recordedAt,
                sources = listOf("test"), steps = steps,
            ),
        )

    @Test
    fun aHealthDayIsKeptUnlessANewerOneIsThere() = runTest {
        val stub = stubApi()
        val older = healthDay("2030-03-17", rigNow, steps = 1000)
        val newer = healthDay("2030-03-17", rigNow.plusSeconds(60), steps = 2000)
        assertTrue(stub.putHealthDay(older))
        assertTrue(stub.putHealthDay(newer))
        assertFalse(stub.putHealthDay(older), "An older reading doesn't replace a newer one")
        assertEquals(newer, stub.savedHealthDays()["2030-03-17"])
    }

    @Test
    fun aBatchOfHealthDaysIsOneToThirtyOne() = runTest {
        val stub = stubApi()
        val empty = caught { stub.putHealthDays(emptyList()) }
        assertEquals("validation_failed", (empty as? AuraError)?.serverCode)
        val tooMany = (1..32).map { healthDay(DayKey.string(java.time.LocalDate.of(2030, 1, 1).plusDays(it.toLong())), rigNow) }
        assertEquals("validation_failed", (caught { stub.putHealthDays(tooMany) } as? AuraError)?.serverCode)
        val two = listOf(healthDay("2030-03-16", rigNow), healthDay("2030-03-17", rigNow))
        assertEquals(listOf("2030-03-16" to true, "2030-03-17" to true), stub.putHealthDays(two))
        assertEquals(setOf("2030-03-16", "2030-03-17"), stub.savedHealthDays().keys)
    }

    // MARK: StubAPITests

    @Test
    fun startSessionEchoesWithoutShortening() = runTest {
        val stub = stubApi()
        val timer = stub.startSession(id = "chosen", kind = "focus", mode = "timer", plannedMinutes = 25, title = "Work", recoveryType = null)
        assertEquals(StartedSession(id = "chosen", joined = false, plannedMinutes = 25), timer)
        val flow = stub.startSession(id = "open", kind = "focus", mode = "flowtime", plannedMinutes = 25, title = "Flow", recoveryType = null)
        assertEquals(StartedSession(id = "open"), flow)
    }

    @Test
    fun joinsTheSessionRunningElsewhereUntilItIsStopped() = runTest {
        val stub = stubApi(joinsRunningSession = true)
        val joined = stub.startSession(id = "mine", kind = "focus", mode = "timer", plannedMinutes = 25, title = "Work", recoveryType = null)
        assertEquals("running-elsewhere", joined.id)
        assertTrue(joined.joined)
        assertEquals(50, joined.plannedMinutes)
        assertEquals("Deep work", joined.title)
        assertEquals("focus", joined.kind)
        assertEquals("desktop", joined.device)
        assertEquals(stubNoon.plusSeconds(25 * 60), joined.endsAt)

        // Pausing it, or acting on another id, leaves it running.
        stub.sessionAction(id = "running-elsewhere", action = SessionAction.pause)
        stub.sessionAction(id = "mine", action = SessionAction.stop)
        val still = stub.startSession(id = "mine", kind = "focus", mode = "timer", plannedMinutes = 25, title = "Work", recoveryType = null)
        assertTrue(still.joined)

        stub.sessionAction(id = "running-elsewhere", action = SessionAction.stop)
        val mine = stub.startSession(id = "mine", kind = "focus", mode = "timer", plannedMinutes = 25, title = "Work", recoveryType = null)
        assertEquals(StartedSession(id = "mine", joined = false, plannedMinutes = 25), mine)
    }

    @Test
    fun newCallsDoNothing() = runTest {
        val stub = stubApi()
        stub.addTask(title = "Tomorrow", plannedFor = "2030-03-18")
        stub.setTimeZoneIfMissing("Europe/Paris")
    }
}
