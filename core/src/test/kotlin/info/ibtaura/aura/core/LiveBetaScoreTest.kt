package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports of BetaScoreTests.swift's live classes (sessions, capacity, review, sleep).

private const val startedOK = "{\"session\":{\"id\":\"one\"},\"joined\":false}"
private const val ratedSession = "{\"session\":{\"id\":\"s-1\",\"kind\":\"focus\",\"mode\":\"timer\",\"state\":\"ended\",\"title\":\"Deep work\"," +
    "\"startedAt\":\"2030-03-17T09:00:00.000Z\",\"endedAt\":\"2030-03-17T09:52:00.000Z\",\"minutes\":52,\"activeMinutes\":50," +
    "\"outcome\":\"completed\",\"recoveryType\":null,\"difficulty\":\"hard\"," +
    "\"capacity\":{\"before\":80,\"after\":79.2,\"cost\":0.8,\"charged\":true}}}"
private const val loggedSession = "{\"session\":{\"id\":\"log-1\",\"kind\":\"recovery\",\"mode\":\"timer\",\"state\":\"ended\",\"title\":null," +
    "\"startedAt\":\"2030-03-17T12:00:00.000Z\",\"endedAt\":\"2030-03-17T12:20:00.000Z\",\"minutes\":20,\"activeMinutes\":20," +
    "\"outcome\":\"logged\",\"recoveryType\":\"walk\",\"difficulty\":null,\"capacity\":null},\"created\":true}"
private const val notFocus = "{\"error\":{\"code\":\"not_focus_session\",\"message\":\"Only a focus session is rated.\"}}"
private const val overlaps =
    "{\"error\":{\"code\":\"log_overlaps\",\"message\":\"That time overlaps another session.\",\"details\":{\"sessionId\":\"s-1\"}}}"
private const val consentNeeded = "{\"error\":{\"code\":\"consent_required\",\"message\":\"Health data consent is needed.\"}}"
private const val recoveryOptionsAnswer = "{\"options\":[" +
    "{\"id\":\"nap\",\"title\":\"Nap\",\"minutes\":20,\"maxPoints\":10,\"fadesAfterMinutes\":120,\"note\":\"A nap.\"}," +
    "{\"id\":\"walk\",\"title\":\"Walk\",\"minutes\":20,\"maxPoints\":9,\"fadesAfterMinutes\":50,\"note\":\"A walk.\"}," +
    "{\"id\":\"rest\",\"title\":\"Rest\",\"minutes\":20,\"maxPoints\":5,\"fadesAfterMinutes\":40,\"note\":\"Rest.\"}," +
    "{\"id\":\"outside\",\"title\":\"Time outside\",\"minutes\":20,\"maxPoints\":9,\"fadesAfterMinutes\":50,\"note\":\"Outside.\"}," +
    "{\"id\":\"social\",\"title\":\"Talk with someone\",\"minutes\":20,\"maxPoints\":5,\"fadesAfterMinutes\":40,\"note\":\"Talk.\"}," +
    "{\"id\":\"break\",\"title\":\"Proper break\",\"minutes\":20,\"maxPoints\":2.5,\"fadesAfterMinutes\":0,\"note\":\"A break.\"}]}"

class LiveBetaSessionTest {
    @Test
    fun startSendsRecoveryTypeOnlyForARecoverySession() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions", netJson(201, startedOK))
        rig.api.startSession("a", "focus", "timer", 25, "Work", RecoveryType.walk)
        rig.api.startSession("b", "recovery", "timer", 20, "Nap", RecoveryType.nap)
        rig.api.startSession("c", "recovery", "timer", 20, "Rest", null)
        val calls = rig.server.calls("/v1/sessions")
        assertEquals(3, calls.size)
        assertFalse(calls[0].body.containsKey("recoveryType"))
        assertEquals("nap", calls[1].string("recoveryType"))
        assertFalse(calls[2].body.containsKey("recoveryType"))
    }

    @Test
    fun difficultyIsPutAndTheSessionComesBack() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/sessions/s-1/difficulty"
        rig.server.on(path, netJson(200, ratedSession))
        val record = rig.api.setDifficulty("s-1", Difficulty.veryHard)
        val call = rig.server.calls(path).first()
        assertEquals("PUT", call.method)
        assertEquals(listOf("difficulty"), call.body.keys.toList())
        assertEquals("veryHard", call.string("difficulty"))
        assertEquals("s-1", record.id)
        assertTrue(record.isFocus)
        assertEquals(50, record.minutes)
        assertEquals(Difficulty.hard, record.difficulty)
        assertEquals(0.8, record.capacityCost)
        assertEquals("completed", record.outcome)
        assertEquals(WireDate.parse("2030-03-17T09:00:00Z"), record.startedAt)
    }

    @Test
    fun difficultyErrorsComeThroughWithTheirCode() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions/s-2/difficulty", netJson(409, notFocus))
        val error = netCaught { rig.api.setDifficulty("s-2", Difficulty.easy) }
        assertEquals("not_focus_session", (error as? AuraError)?.serverCode)
    }

    @Test
    fun logBreakBody() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions/log", netJson(201, loggedSession))
        val start = NetRig.now.minusSeconds(3600)
        val logged = rig.api.logBreak("log-1", RecoveryType.walk, start, 20)
        val call = rig.server.calls("/v1/sessions/log").first()
        assertEquals("POST", call.method)
        assertEquals("log-1", call.string("id"))
        assertEquals("recovery", call.string("kind"))
        assertEquals("walk", call.string("recoveryType"))
        assertEquals(20, call.int("minutes"))
        assertEquals("phone", call.string("device"))
        val sent = assertNotNull(call.string("startedAt"))
        assertTrue(sent.contains("T"))
        assertEquals(start, WireDate.parse(sent))
        assertTrue(logged.created)
        assertEquals("logged", logged.session.outcome)
        assertEquals(RecoveryType.walk, logged.session.recoveryType)
        assertEquals(20, logged.session.minutes)
        assertNull(logged.session.capacityCost)
    }

    @Test
    fun resentLogIsNotCreated() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions/log", netJson(200, loggedSession.replace("\"created\":true", "\"created\":false")))
        assertFalse(rig.api.logBreak("log-1", RecoveryType.walk, NetRig.now, 20).created)
    }

    @Test
    fun logOverlapComesThroughWithItsCode() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions/log", netJson(409, overlaps))
        val error = netCaught { rig.api.logBreak("log-2", RecoveryType.rest, NetRig.now, 15) }
        assertEquals("log_overlaps", (error as? AuraError)?.serverCode)
    }

    @Test
    fun sessionsFollowCursorsAndComeNewestFirst() = runBlocking<Unit> {
        val rig = NetRig()
        val first = "{\"sessions\":[{\"id\":\"early\",\"kind\":\"focus\",\"startedAt\":\"2030-03-16T08:00:00Z\",\"minutes\":30}," +
            "{\"id\":\"bad\"}],\"cursor\":\"page+2\"}"
        val second = "{\"sessions\":[{\"id\":\"late\",\"kind\":\"recovery\",\"recoveryType\":\"nap\",\"startedAt\":\"2030-03-17T14:00:00Z\"," +
            "\"minutes\":20,\"activeMinutes\":18,\"capacity\":{\"cost\":0.2,\"charged\":false}},{\"id\":\"mid\",\"kind\":\"meeting\"," +
            "\"startedAt\":\"2030-03-17T10:00:00Z\"}],\"cursor\":null}"
        rig.server.on("/v1/sessions", netJson(200, first), netJson(200, second))
        val records = rig.api.sessions("2030-03-16", "2030-03-17")
        assertEquals(listOf("late", "mid", "early", "bad"), records.map { it.id })
        assertEquals(RecoveryType.nap, records[0].recoveryType)
        assertEquals(18, records[0].minutes)
        assertNull(records[0].capacityCost, "Not charged yet")
        assertEquals(0, records[1].minutes)
        val calls = rig.server.calls("/v1/sessions")
        assertEquals(2, calls.size)
        assertEquals("GET", calls[0].method)
        assertEquals("2030-03-16", calls[0].query["from"])
        assertEquals("2030-03-17", calls[0].query["to"])
        assertNull(calls[0].query["cursor"])
        assertEquals("page+2", calls[1].query["cursor"])
    }

    @Test
    fun sessionsStopAfterFivePages() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions", netJson(200, "{\"sessions\":[],\"cursor\":\"more\"}"))
        assertTrue(rig.api.sessions("2030-03-17", "2030-03-17").isEmpty())
        assertEquals(5, rig.server.calls("/v1/sessions").size)
    }
}

class LiveBetaCapacityTest {
    @Test
    fun capacityLineIsTheSessionsWorkEntryToday() = runBlocking<Unit> {
        val rig = NetRig()
        val answer = "{\"date\":\"2030-03-17\",\"ledger\":[" +
            "{\"id\":\"l1\",\"kind\":\"opening\",\"source\":\"morning\",\"label\":\"Morning\",\"delta\":80,\"balance\":80,\"detail\":\"Opening.\"," +
            "\"estimated\":false,\"sourceId\":null}," +
            "{\"id\":\"l2\",\"kind\":\"cost\",\"source\":\"work\",\"label\":\"Work\",\"delta\":-0.5,\"balance\":79.5," +
            "\"detail\":\"30 min of desk work at demand 60: -0.5.\",\"estimated\":false,\"sourceId\":\"other\"}," +
            "{\"id\":\"l3\",\"kind\":\"cost\",\"source\":\"break\",\"label\":\"Break\",\"delta\":0,\"balance\":79.5,\"detail\":\"A break.\"," +
            "\"estimated\":false,\"sourceId\":\"s-1\"}," +
            "{\"id\":\"l4\",\"kind\":\"cost\",\"source\":\"work\",\"label\":\"Work\",\"delta\":-1.0,\"balance\":78.5," +
            "\"detail\":\"60 min of desk work at demand 80: -1.0. You rated it Hard.\",\"estimated\":false,\"sourceId\":\"s-1\"}]}"
        rig.server.on("/v1/capacity/today", netJson(200, answer))
        assertEquals("60 min of desk work at demand 80: -1.0. You rated it Hard.", rig.api.capacityLine("s-1"))
        assertNull(rig.api.capacityLine("nobody"))
        val call = rig.server.calls("/v1/capacity/today").first()
        assertEquals("capacity.stub.test", call.host)
        assertEquals(DayKey.string(NetRig.now), call.query["date"])
    }

    @Test
    fun recoveryOptionsDecodeWithHints() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/capacity/recovery-options", netJson(200, recoveryOptionsAnswer))
        val options = rig.api.recoveryOptions(20)
        assertEquals("20", rig.server.calls("/v1/capacity/recovery-options").first().query["minutes"])
        assertEquals(listOf("nap", "walk", "rest", "outside", "social", "break"), options.map { it.id })
        assertEquals(RecoveryType.nap, options[0].type)
        assertEquals("+10, fades within 2 h", options[0].hint)
        assertEquals("+9, fades within 50 min", options[1].hint)
        assertEquals("Walk", options[1].title)
        assertEquals(20, options[1].minutes)
        assertEquals("A walk.", options[1].note)
        assertNull(options[5].type)
        assertEquals("+2.5, does not fade", options[5].hint)
    }

    @Test
    fun hintKeepsOneDecimalOnlyWhenNotWhole() {
        assertEquals("+4.3, fades within 90 min", RecoveryOption("rest", "Rest", 20, 4.25, 90, "").hint)
        assertEquals("+10, fades within 3 h", RecoveryOption("nap", "Nap", 20, 10.0, 180, "").hint)
    }
}

class LiveBetaReviewTest {
    private val meeting = ReviewItem(
        blockId = "b-1", date = "2030-03-17", title = "Team sync", kind = "meeting",
        start = NetRig.now.minusSeconds(7200), end = NetRig.now.minusSeconds(5400), minutes = 30,
    )
    private val nap = ReviewItem(
        blockId = "b-2", date = "2030-03-17", title = "", kind = "recovery",
        start = NetRig.now.minusSeconds(3600), end = NetRig.now.minusSeconds(3000), minutes = 10,
    )

    @Test
    fun inboxLeavesOutAnsweredItems() = runBlocking<Unit> {
        val rig = NetRig()
        val answer = "{\"items\":[" +
            "{\"blockId\":\"b-1\",\"date\":\"2030-03-17\",\"title\":\"Team sync\",\"kind\":\"meeting\",\"start\":\"2030-03-17T10:00:00Z\"," +
            "\"end\":\"2030-03-17T10:30:00Z\",\"minutes\":30,\"recordedMinutes\":0,\"reason\":\"modeOff\",\"review\":null}," +
            "{\"blockId\":\"b-2\",\"date\":\"2030-03-17\",\"title\":\"10 Min nap\",\"kind\":\"recovery\",\"start\":\"2030-03-17T15:54:00Z\"," +
            "\"end\":\"2030-03-17T16:04:00Z\",\"minutes\":10,\"recordedMinutes\":0,\"reason\":null,\"review\":null}," +
            "{\"blockId\":\"b-3\",\"date\":\"2030-03-17\",\"title\":\"Done\",\"kind\":\"focus\",\"start\":\"2030-03-17T11:00:00Z\"," +
            "\"end\":\"2030-03-17T12:00:00Z\",\"minutes\":60,\"review\":{\"blockId\":\"b-3\",\"happened\":true}}]}"
        rig.server.on("/v1/reviews/inbox", netJson(200, answer))
        val items = rig.api.reviewInbox("2030-03-17", 7)
        assertEquals(listOf("b-1", "b-2"), items.map { it.blockId })
        assertFalse(items[0].isRecovery)
        assertTrue(items[1].isRecovery)
        assertEquals(10, items[1].minutes)
        assertEquals(WireDate.parse("2030-03-17T15:54:00Z"), items[1].start)
        val call = rig.server.calls("/v1/reviews/inbox").first()
        assertEquals("2030-03-17", call.query["date"])
        assertEquals("7", call.query["days"])
    }

    @Test
    fun reviewSendsEveryKeyWithNullsForEmptyAnswers() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/reviews/b-1", netJson(200, "{\"review\":{\"blockId\":\"b-1\"}}"))
        // A type on a meeting is never sent: the route refuses it on any kind but recovery.
        rig.api.submitReview(ReviewAnswer(meeting, happened = true, intensity = ReviewIntensity.steady, recoveryType = RecoveryType.walk))
        val call = rig.server.calls("/v1/reviews/b-1").first()
        assertEquals("PUT", call.method)
        assertEquals("2030-03-17", call.string("date"))
        assertEquals(true, call.bool("happened"))
        assertEquals("meeting", call.string("kind"))
        assertEquals("Team sync", call.string("title"))
        assertEquals(meeting.start, WireDate.parse(call.string("start")))
        assertEquals(meeting.end, WireDate.parse(call.string("end")))
        assertEquals("steady", call.string("intensity"))
        assertTrue(call.isNull("drain"))
        assertTrue(call.isNull("recoveryType"))
    }

    @Test
    fun recoveryReviewSendsItsType() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/reviews/b-2", netJson(200, "{\"review\":{\"blockId\":\"b-2\"}}"))
        rig.api.submitReview(ReviewAnswer(nap, happened = false, recoveryType = RecoveryType.nap))
        val call = rig.server.calls("/v1/reviews/b-2").first()
        assertEquals(false, call.bool("happened"))
        assertEquals("nap", call.string("recoveryType"))
        assertTrue(call.isNull("intensity"))
        assertTrue(call.isNull("drain"))
        assertFalse(call.body.containsKey("title"), "An empty title is left out")
    }
}

class LiveBetaSleepTest {
    private fun night(awake: Int? = null, inBed: Int? = null) = SleepNight(
        date = "2030-03-17", timeZone = "Asia/Kolkata", bedtimeAt = NetRig.now.minusSeconds(8 * 3600),
        wakeAt = NetRig.now, totalSleepMin = 450, awakeMin = awake, inBedMin = inBed,
    )

    @Test
    fun sleepNightLeavesOutEmptyOptionalKeys() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/sleep/nights/2030-03-17"
        rig.server.on(path, netJson(201, "{\"night\":{\"date\":\"2030-03-17\",\"source\":\"appleHealth\"},\"created\":true}"))
        assertEquals(SleepNightResult(created = true, source = "appleHealth"), rig.api.putSleepNight(night()))
        val call = rig.server.calls(path).first()
        assertEquals("PUT", call.method)
        assertEquals("health-data.stub.test", call.host)
        assertEquals(setOf("timeZone", "bedtimeAt", "wakeAt", "totalSleepMin"), call.body.keys)
        assertEquals("Asia/Kolkata", call.string("timeZone"))
        assertEquals(450, call.int("totalSleepMin"))
        assertEquals(NetRig.now, WireDate.parse(call.string("wakeAt")))
        assertEquals(NetRig.now.minusSeconds(8 * 3600), WireDate.parse(call.string("bedtimeAt")))
    }

    @Test
    fun sleepNightSendsOptionalKeysWhenSet() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/sleep/nights/2030-03-17"
        rig.server.on(path, netJson(200, "{\"night\":{\"date\":\"2030-03-17\",\"source\":\"band\"},\"created\":false}"))
        assertEquals(SleepNightResult(created = false, source = "band"), rig.api.putSleepNight(night(awake = 12, inBed = 470)))
        val call = rig.server.calls(path).first()
        assertEquals(12, call.int("awakeMin"))
        assertEquals(470, call.int("inBedMin"))
    }

    @Test
    fun sleepNightWithoutConsentAsksForIt() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sleep/nights/2030-03-17", netJson(403, consentNeeded))
        assertEquals(AuraError.ConsentRequired, netCaught { rig.api.putSleepNight(night()) })
    }

    @Test
    fun morningCheckinSendsBothTimes() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/checkins", netJson(201, "{\"checkin\":{\"id\":\"x\"},\"created\":true}"))
        rig.api.morningCheckin(6, "23:10", "07:05", NetRig.now)
        val call = rig.server.calls("/v1/checkins").first()
        assertEquals("POST", call.method)
        assertNotNull(runCatching { UUID.fromString(call.string("id")) }.getOrNull())
        assertEquals("morning", call.string("kind"))
        assertEquals(6, call.int("alertness"))
        assertEquals("23:10", call.string("fellAsleepAt"))
        assertEquals("07:05", call.string("wokeAt"))
        assertEquals(NetRig.now, WireDate.parse(call.string("takenAt")))
    }

    @Test
    fun morningCheckinWithOneTimeSendsNeither() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/checkins", netJson(201, "{\"checkin\":{\"id\":\"x\"},\"created\":true}"))
        rig.api.morningCheckin(null, "23:10", null, NetRig.now)
        rig.api.morningCheckin(3, null, "07:05", NetRig.now)
        val calls = rig.server.calls("/v1/checkins")
        assertEquals(2, calls.size)
        for (call in calls) {
            assertFalse(call.body.containsKey("fellAsleepAt"))
            assertFalse(call.body.containsKey("wokeAt"))
        }
        assertFalse(calls[0].body.containsKey("alertness"))
        assertEquals(3, calls[1].int("alertness"))
    }

    @Test
    fun sleepScheduleFromTheProfile() = runBlocking<Unit> {
        val rig = NetRig()
        val full = "{\"sub\":\"sub-1\",\"timezone\":\"Asia/Kolkata\",\"version\":7," +
            "\"healthGoals\":{\"sleepNeedMinutes\":480,\"bedtime\":\"23:00\",\"wakeTime\":\"07:00\",\"stepsPerDay\":null}}"
        val half = "{\"sub\":\"sub-1\",\"version\":7,\"healthGoals\":{\"bedtime\":\"23:00\",\"wakeTime\":null}}"
        val none = "{\"sub\":\"sub-1\",\"version\":7}"
        val odd = "{\"sub\":\"sub-1\",\"version\":7,\"healthGoals\":\"not an object\"}"
        rig.server.on("/v1/me", netJson(200, full), netJson(200, half), netJson(200, none), netJson(200, odd))
        assertEquals(SleepSchedule("23:00", "07:00"), rig.api.sleepSchedule())
        assertNull(rig.api.sleepSchedule())
        assertNull(rig.api.sleepSchedule())
        assertNull(rig.api.sleepSchedule())
        assertEquals("identity.stub.test", rig.server.calls("/v1/me").first().host)
    }
}
