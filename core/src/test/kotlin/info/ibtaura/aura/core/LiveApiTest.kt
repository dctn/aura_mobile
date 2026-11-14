package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports of LiveAPITests.swift's range, decoding, body, time zone and error classes.

private const val noDays = "{\"days\":[],\"cursor\":null}"
private const val moodSaved = "{\"checkin\":{\"id\":\"x\"},\"created\":true}"
private const val profileStale = "{\"error\":{\"code\":\"precondition_failed\",\"message\":\"The profile changed since you read it.\"}}"

private fun day(key: String): Instant = assertNotNull(DayKey.date(key))

private fun assertClientId(call: NetCall) {
    val id = call.string("id") ?: ""
    assertNotNull(runCatching { UUID.fromString(id) }.getOrNull(), "Client ids are UUIDs: $id")
}

private fun eventJson(id: String, start: Instant, end: Instant): String =
    "{\"id\":\"$id\",\"title\":\"$id\",\"start\":\"${WireDate.string(start)}\",\"end\":\"${WireDate.string(end)}\"}"

class LiveRangeTest {
    @Test
    fun dailyMetricsFollowsTheCursor() = runBlocking<Unit> {
        val rig = NetRig()
        val first = "{\"days\":[{\"date\":\"2025-01-01\",\"status\":\"ready\",\"final\":true,\"metrics\":{\"burnoutIndexScore\":61.5," +
            "\"burnoutIndexRisk\":\"Moderate\",\"sleepScore\":80}}],\"cursor\":\"a+b/c=\"}"
        val second = "{\"days\":[{\"date\":\"2025-01-02\",\"status\":\"noData\",\"metrics\":{}}],\"cursor\":null}"
        rig.server.on("/v1/metrics/daily", netJson(200, first), netJson(200, second))
        val days = rig.api.dailyMetrics("2025-01-01", "2025-01-02")
        assertEquals(
            listOf(DailyMetrics("2025-01-01", burnoutIndexScore = 61.5, burnoutIndexRisk = "Moderate"), DailyMetrics("2025-01-02")),
            days,
        )
        val calls = rig.server.calls("/v1/metrics/daily")
        assertEquals(2, calls.size)
        assertNull(calls[0].query["cursor"])
        assertTrue(calls[1].address.contains("cursor=a%2Bb%2Fc%3D"), calls[1].address)
        assertEquals("burnoutIndexScore,burnoutIndexRisk", calls[0].query["fields"])
    }

    @Test
    fun dailyMetricsSplitsAt400Days() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/metrics/daily", netJson(200, noDays))
        rig.api.dailyMetrics("2024-01-01", "2025-06-01")
        val calls = rig.server.calls("/v1/metrics/daily")
        assertEquals(2, calls.size)
        assertEquals("2024-01-01", calls[0].query["from"])
        assertEquals("2025-02-03", calls[0].query["to"])
        assertEquals("2025-02-04", calls[1].query["from"])
        assertEquals("2025-06-01", calls[1].query["to"])
    }

    @Test
    fun availabilityClampsTo400Days() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/metrics/availability", netJson(200, "{\"fields\":[]}"))
        rig.api.availability("2020-01-01", "2025-06-01")
        val call = rig.server.calls("/v1/metrics/availability").first()
        assertEquals("2024-04-28", call.query["from"])
        assertEquals("2025-06-01", call.query["to"])
    }

    @Test
    fun aShortRangeIsLeftAlone() {
        assertEquals("2025-05-01", DayRanges.clampedStart("2025-05-01", "2025-06-01", 400))
        assertEquals(listOf("2025-05-01" to "2025-05-01"), DayRanges.split("2025-05-01", "2025-05-01", 400))
    }

    @Test
    fun aRangeItCantReadGoesThroughWhole() {
        val pieces = DayRanges.split("soon", "2025-05-01", 400)
        assertEquals(1, pieces.size)
        assertEquals("soon", pieces[0].first)
    }

    @Test
    fun eventsSplitAt62DaysDedupeAndDropOutsiders() = runBlocking<Unit> {
        val rig = NetRig()
        val page = """
            {"events":[
              {"id":"late","title":"Late","start":"2025-02-20T10:00:00Z","end":"2025-02-20T11:00:00Z","allDay":false,"kind":"meeting","date":"2025-02-20"},
              {"id":"early","title":"Early","start":"2025-02-01T10:00:00.000Z","end":"2025-02-01T11:00:00.000Z","allDay":false,"kind":"focus"},
              {"id":"outside","title":"Outside","start":"2025-06-01T10:00:00Z","end":"2025-06-01T11:00:00Z"}
            ],"cursor":null}
        """.trimIndent()
        rig.server.on("/v1/calendar/events", netJson(200, page))
        val events = rig.api.events(day("2025-01-01"), day("2025-04-11"))
        assertEquals(listOf("early", "late"), events.map { it.id })
        val calls = rig.server.calls("/v1/calendar/events")
        assertEquals(2, calls.size)
        assertEquals("2024-12-31", calls[0].query["from"])
        assertEquals("2025-03-02", calls[0].query["to"])
        assertEquals("2025-03-03", calls[1].query["from"])
        assertEquals("2025-04-11", calls[1].query["to"])
    }

    @Test
    fun eventRunningPastMidnightIsFound() = runBlocking<Unit> {
        val rig = NetRig()
        val midnight = day("2025-06-11")
        val hour = Duration.ofHours(1)
        val overnight = eventJson("overnight", midnight - hour.multipliedBy(2), midnight + hour.multipliedBy(6))
        val finished = eventJson("finished", midnight - hour.multipliedBy(10), midnight - hour.multipliedBy(8))
        val later = eventJson("later", midnight + hour.multipliedBy(9), midnight + hour.multipliedBy(10))
        rig.server.on("/v1/calendar/events", netJson(200, "{\"events\":[$overnight,$finished,$later],\"cursor\":null}"))
        val events = rig.api.events(midnight + hour, midnight + hour.multipliedBy(20))
        assertEquals(listOf("overnight", "later"), events.map { it.id })
        val call = rig.server.calls("/v1/calendar/events").first()
        assertEquals("2025-06-10", call.query["from"])
        assertEquals("2025-06-11", call.query["to"])
    }

    @Test
    fun eventsAtOneInstantKeepOnlyWhatCoversIt() = runBlocking<Unit> {
        val rig = NetRig()
        val midnight = day("2025-06-11")
        val hour = Duration.ofHours(1)
        val covering = eventJson("covering", midnight - hour.multipliedBy(2), midnight + hour.multipliedBy(6))
        val finished = eventJson("finished", midnight - hour.multipliedBy(10), midnight - hour.multipliedBy(8))
        rig.server.on("/v1/calendar/events", netJson(200, "{\"events\":[$covering,$finished],\"cursor\":null}"))
        val at = midnight + hour
        assertEquals(listOf("covering"), rig.api.events(at, at).map { it.id })
    }

    @Test
    fun seriesSplitsAt400DaysAndJoins() = runBlocking<Unit> {
        val rig = NetRig()
        val first = "{\"field\":\"sleepScore\",\"unit\":\"score\",\"points\":[{\"date\":\"2024-01-01\",\"value\":70}]}"
        val second = "{\"field\":\"sleepScore\",\"unit\":\"score\",\"points\":[{\"date\":\"2025-06-01\",\"value\":null}]}"
        rig.server.on("/v1/metrics/series/sleepScore", netJson(200, first), netJson(200, second))
        val series = rig.api.series("sleepScore", "2024-01-01", "2025-06-01")
        assertEquals(
            MetricSeries("sleepScore", "score", listOf(MetricPoint("2024-01-01", 70.0), MetricPoint("2025-06-01", null))),
            series,
        )
        assertEquals(2, rig.server.calls("/v1/metrics/series/sleepScore").size)
    }
}

class LiveDecodingTest {
    @Test
    fun scores() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = """
            {"date":"2030-03-17","attention":{"value":96,"status":"ready","checksCounted":14,"checksNeeded":14,"vsNormal":-4,"at":"2030-03-17T09:00:00Z"},
            "memory":{"value":null,"status":"learning","checksCounted":3,"checksNeeded":14,"vsNormal":null,"at":null},
            "thinking":{"value":null,"status":"noData"},"extra":{"anything":[1,2]}}
        """.trimIndent()
        rig.server.on("/v1/scores/today", netJson(200, sample))
        val scores = rig.api.scoresToday()
        assertEquals(Score("Attention", 96.0, checksCounted = 14, checksNeeded = 14), scores.attention)
        assertEquals(Score("Memory", null, checksCounted = 3, checksNeeded = 14), scores.memory)
        assertEquals(Score("Thinking", null), scores.thinking)
        assertEquals(Score("Focus", null), scores.focus)
        assertEquals(DayKey.string(NetRig.now), rig.server.calls("/v1/scores/today").first().query["date"])
    }

    @Test
    fun tasks() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = """
            {"tasks":[
              {"id":"a","title":"Timed","status":"pending","dueAt":"2025-02-01T09:30:00Z","hasTime":true,"plannedFor":null,"completedAt":null,"scheduledAt":null,"estimateMinutes":30},
              {"id":"b","title":"Day only","status":"working","dueAt":"2025-02-01T00:00:00Z","hasTime":false},
              {"id":"c","title":"Blocked","status":"pending","dueAt":null,"scheduledAt":"2025-02-01T14:00:00.250+05:30"},
              {"id":"d","title":"Finished","status":"completed","completedAt":"2025-02-01T10:00:00Z"},
              {"id":"e"}
            ],"deleted":["z"],"cursor":null}
        """.trimIndent()
        rig.server.on("/v1/tasks", netJson(200, sample))
        val tasks = rig.api.tasks()
        assertEquals(5, tasks.size)
        assertEquals(WireDate.parse("2025-02-01T09:30:00Z"), tasks[0].at)
        assertNotNull(tasks[0].at)
        assertNull(tasks[1].at)
        assertEquals(WireDate.parse("2025-02-01T08:30:00.250Z"), tasks[2].at)
        assertNotNull(tasks[2].at)
        assertFalse(tasks[0].done)
        assertTrue(tasks[3].done)
        assertEquals(TaskItem("e", ""), tasks[4])
    }

    @Test
    fun taskPlannedForADayHasNoTime() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = "{\"tasks\":[{\"id\":\"p\",\"title\":\"For tomorrow\",\"status\":\"pending\",\"dueAt\":null,\"hasTime\":false," +
            "\"plannedFor\":\"2030-03-18\",\"scheduledAt\":null,\"completedAt\":null}],\"deleted\":[],\"cursor\":null}"
        rig.server.on("/v1/tasks", netJson(200, sample))
        val tasks = rig.api.tasks()
        assertEquals(listOf(TaskItem("p", "For tomorrow")), tasks)
        assertNull(tasks[0].at)
    }

    @Test
    fun alerts() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = """
            {"alerts":[
              {"id":"alert-2025-02-01-DX-01","featureId":"DX-01","date":"2025-02-01","title":"Take a nap","body":"You slept little.","priority":"high",
               "action":{"type":"mode","service":"app","ref":"nap-10"},"holdable":true,"sendAt":"2025-02-01T13:00:00Z","expiresAt":null,"done":false},
              {"id":"alert-2025-02-01-DX-02","featureId":"DX-02","date":"2025-02-01","title":"Done one","body":null,"action":null,"done":true}
            ]}
        """.trimIndent()
        rig.server.on("/v1/alerts", netJson(200, sample))
        val alerts = rig.api.alerts("2025-02-01", "2025-02-01")
        assertEquals(
            AlertItem("alert-2025-02-01-DX-01", "DX-01", "Take a nap", detail = "You slept little.", date = "2025-02-01",
                done = false, actionRef = "nap-10"),
            alerts[0],
        )
        assertEquals(true, alerts[1].done)
        assertNull(alerts[1].detail)
        assertNull(alerts[1].actionRef)
    }

    @Test
    fun catalogue() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = """
            {"categories":[{"id":"body"}],"sections":[{"id":"sleep","title":"Sleep","symbol":"moon","color":"blue","parameters":[
              {"name":"Sleep score","field":"sleepScore","owner":"health-data","status":"live","read":"series","route":null,"unit":"score","format":"int","chart":"line","range":{"min":0,"max":100},"note":null},
              {"name":"Not measured","field":null,"owner":null,"status":"planned","read":"none","route":null,"unit":null,"format":null}
            ]}]}
        """.trimIndent()
        rig.server.on("/v1/metrics/catalogue", netJson(200, sample))
        val sections = rig.api.catalogue()
        assertEquals(1, sections.size)
        assertEquals(CatalogueParameter("Sleep score", "sleepScore", "series", "score", "int"), sections[0].parameters[0])
        assertTrue(sections[0].parameters[0].hasSeries)
        assertEquals(CatalogueParameter("Not measured", null, "none", "", ""), sections[0].parameters[1])
    }

    @Test
    fun availabilityValueKinds() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = """
            {"from":"2025-01-01","to":"2025-02-01","fields":[
              {"field":"sleepScore","latestDate":"2025-02-01","latestValue":81.5},
              {"field":"burnoutIndexRisk","latestDate":"2025-02-01","latestValue":"Low"},
              {"field":"tierCPhoneJSON.charging","latestDate":"2025-01-30","latestValue":true},
              {"field":"listThing","latestDate":"2025-01-29","latestValue":[1,2]}
            ]}
        """.trimIndent()
        rig.server.on("/v1/metrics/availability", netJson(200, sample))
        val fields = rig.api.availability("2025-01-01", "2025-02-01")
        assertEquals(FieldAvailability("sleepScore", "2025-02-01", latestNumber = 81.5), fields[0])
        assertEquals(FieldAvailability("burnoutIndexRisk", "2025-02-01", latestText = "Low"), fields[1])
        assertEquals("Yes", fields[2].latestText)
        assertNull(fields[3].latestNumber)
        assertNull(fields[3].latestText)
    }

    @Test
    fun traceAndMissingFields() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/trends/sessions/session-1"
        val ready = "{\"sessionId\":\"session-1\",\"date\":\"2025-02-01\",\"status\":\"ready\",\"hr\":[],\"stress\":[],\"avgHr\":60,\"stressDrop\":12.5}"
        rig.server.on(path, netJson(200, ready), netJson(200, "{}"))
        assertEquals(SessionTrace("ready", 12.5), rig.api.sessionTrace("session-1"))
        assertEquals(SessionTrace("noData", null), rig.api.sessionTrace("session-1"))
    }

    @Test
    fun datesWithAndWithoutFractions() {
        val plain = assertNotNull(WireDate.parse("2025-02-01T10:00:00Z"))
        assertEquals(plain, WireDate.parse("2025-02-01T10:00:00.000Z"))
        assertEquals(plain, WireDate.parse("2025-02-01T15:30:00+05:30"))
        assertEquals(plain.plusMillis(500), WireDate.parse("2025-02-01T10:00:00.5Z"))
        assertNull(WireDate.parse("2025-02-01"))
        assertNull(WireDate.parse(null))
    }

    @Test
    fun outgoingDatesAreWholeSecondsWithAnOffset() {
        val text = WireDate.string(Instant.parse("2025-02-01T10:00:00.750Z"))
        assertEquals(Instant.parse("2025-02-01T10:00:00Z"), WireDate.parse(text))
        assertFalse(text.contains("."), text)
    }
}

class LiveBodyTest {
    @Test
    fun moodCheckin() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/checkins", netJson(201, moodSaved))
        rig.api.logMood(4, NetRig.now)
        val call = rig.server.calls("/v1/checkins").first()
        assertClientId(call)
        assertEquals("POST", call.method)
        assertEquals("mood", call.string("kind"))
        assertEquals(4, call.int("mood"))
        assertEquals(NetRig.now, WireDate.parse(call.string("takenAt")))
    }

    @Test
    fun alertDone() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/alerts/alert-2025-02-01-DX-01/done"
        rig.server.on(path, netJson(200, "{\"id\":\"alert-2025-02-01-DX-01\",\"done\":true}"))
        rig.api.markAlertDone("alert-2025-02-01-DX-01")
        assertEquals("POST", rig.server.calls(path).first().method)
    }

    @Test
    fun flowtimeSessionSendsNullMinutes() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions", netJson(201, "{\"session\":{\"id\":\"server-id\",\"status\":\"running\"},\"joined\":true}"))
        val started = rig.api.startSession("chosen-1", "focus", "flowtime", 25, "Flow", null)
        assertEquals(StartedSession("server-id", joined = true), started)
        val call = rig.server.calls("/v1/sessions").first()
        assertEquals("chosen-1", call.string("id"))
        assertTrue(call.isNull("plannedMinutes"))
        assertEquals("phone", call.string("device"))
        assertEquals("focus", call.string("kind"))
        assertEquals("flowtime", call.string("mode"))
        assertEquals("Flow", call.string("title"))
    }

    @Test
    fun timerSessionSendsMinutesWithinTheLimit() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions", netJson(201, "{\"session\":{\"id\":\"one\"}}"))
        val started = rig.api.startSession("one", "recovery", "timer", 25, "Nap", RecoveryType.nap)
        assertEquals(StartedSession("one", joined = false), started)
        rig.api.startSession("two", "recovery", "timer", 500, "Long", RecoveryType.nap)
        val calls = rig.server.calls("/v1/sessions")
        assertEquals(25, calls[0].int("plannedMinutes"))
        assertEquals(240, calls[1].int("plannedMinutes"))
    }

    @Test
    fun sessionAnswersWithTheMinutesGranted() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions", netJson(201, "{\"session\":{\"id\":\"one\",\"plannedMinutes\":15},\"joined\":false}"))
        val started = rig.api.startSession("one", "focus", "timer", 25, "Work", null)
        assertEquals(StartedSession("one", joined = false, plannedMinutes = 15), started)
    }

    @Test
    fun joinedSessionCarriesTheRunningSession() = runBlocking<Unit> {
        val rig = NetRig()
        val answer = "{\"session\":{\"id\":\"desk-1\",\"kind\":\"focus\",\"mode\":\"timer\",\"state\":\"running\",\"device\":\"desktop\"," +
            "\"title\":\"Deep work\",\"plannedMinutes\":50,\"startedAt\":\"2030-03-17T17:21:40.000Z\",\"endsAt\":\"2030-03-17T18:11:40.000Z\"}," +
            "\"joined\":true}"
        rig.server.on("/v1/sessions", netJson(201, answer))
        val started = rig.api.startSession("mine", "focus", "timer", 25, "Work", null)
        assertEquals(
            StartedSession("desk-1", joined = true, plannedMinutes = 50, title = "Deep work", kind = "focus", device = "desktop",
                endsAt = NetRig.now.plusSeconds(25 * 60)),
            started,
        )
    }

    @Test
    fun startAnswerWithoutTheNewFieldsStillDecodes() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions", netJson(201, "{\"session\":{\"id\":\"one\",\"title\":null,\"endsAt\":null},\"joined\":false}"))
        val started = rig.api.startSession("one", "focus", "flowtime", null, "Flow", null)
        assertEquals(StartedSession("one", joined = false), started)
        assertNull(started.title)
        assertNull(started.kind)
        assertNull(started.device)
        assertNull(started.endsAt)
    }

    @Test
    fun openEndedSessionHasNoMinutes() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions", netJson(201, "{\"session\":{\"id\":\"one\",\"plannedMinutes\":null},\"joined\":false}"))
        assertNull(rig.api.startSession("one", "focus", "flowtime", null, "Flow", null).plannedMinutes)
    }

    @Test
    fun resendingASessionSendsTheSameId() = runBlocking<Unit> {
        val rig = NetRig()
        val stored = "{\"session\":{\"id\":\"same\",\"plannedMinutes\":25},\"joined\":false}"
        rig.server.on("/v1/sessions", netJson(201, stored), netJson(200, stored))
        val first = rig.api.startSession("same", "focus", "timer", 25, "Work", null)
        val second = rig.api.startSession("same", "focus", "timer", 25, "Work", null)
        assertEquals(first, second)
        assertEquals(listOf("same", "same"), rig.server.calls("/v1/sessions").map { it.string("id") })
    }

    @Test
    fun recoveryTypeIsSentOnlyForRecovery() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/sessions", netJson(201, "{\"session\":{\"id\":\"one\"}}"))
        rig.api.startSession("one", "recovery", "timer", 10, "Walk", RecoveryType.walk)
        rig.api.startSession("two", "focus", "timer", 10, "Work", RecoveryType.walk)
        val calls = rig.server.calls("/v1/sessions")
        assertEquals("walk", calls[0].string("recoveryType"))
        assertFalse(calls[1].body.containsKey("recoveryType"))
    }

    @Test
    fun sessionAction() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/sessions/session-1/actions"
        rig.server.on(path, netJson(200, "{\"session\":{\"id\":\"session-1\"}}"))
        rig.api.sessionAction("session-1", SessionAction.complete)
        assertEquals("complete", rig.server.calls(path).first().string("action"))
    }

    @Test
    fun eventKinds() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/calendar/events", netJson(201, "{\"event\":{\"id\":\"x\"}}"))
        val end = NetRig.now.plusSeconds(600)
        rig.api.addEvent("Walk", NetRig.now, end, "walk")
        rig.api.addEvent("Plan", NetRig.now, end, "meeting")
        rig.api.addEvent("Other", NetRig.now, end, "something")
        val calls = rig.server.calls("/v1/calendar/events")
        assertClientId(calls[0])
        assertEquals("recovery", calls[0].string("kind"))
        assertEquals("meeting", calls[1].string("kind"))
        assertFalse(calls[2].body.containsKey("kind"))
        assertEquals(end, WireDate.parse(calls[0].string("end")))
    }

    @Test
    fun taskPlannedForADaySendsNoTime() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(201, "{\"task\":{\"id\":\"x\"}}"))
        rig.api.addTask("Tomorrow", "2030-03-18")
        val call = rig.server.calls("/v1/tasks").first()
        assertClientId(call)
        assertEquals("Tomorrow", call.string("title"))
        assertEquals("2030-03-18", call.string("plannedFor"))
        assertFalse(call.body.containsKey("dueAt"))
        assertFalse(call.body.containsKey("hasTime"))
    }
}

class LiveTimeZoneTest {
    private val noZone = "{\"sub\":\"sub-1\",\"timezone\":null,\"displayName\":\"Kept\",\"version\":7}"
    private val hasZone = "{\"sub\":\"sub-1\",\"timezone\":\"Asia/Kolkata\",\"version\":8}"
    private val saved = "{\"sub\":\"sub-1\",\"timezone\":\"Europe/Paris\",\"version\":8}"

    @Test
    fun setsTheZoneAloneWithTheVersionRead() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, noZone))
        rig.server.on("/v1/me/profile", netJson(200, saved))
        rig.api.setTimeZoneIfMissing("Europe/Paris")
        val put = rig.server.calls("/v1/me/profile").first()
        assertEquals("PUT", put.method)
        assertEquals("\"v7\"", put.header("If-Match"))
        assertEquals(listOf("timezone"), put.body.keys.toList())
        assertEquals("Europe/Paris", put.string("timezone"))
        assertEquals("GET", rig.server.calls("/v1/me").first().method)
    }

    @Test
    fun neverOverwritesAZone() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, hasZone))
        rig.api.setTimeZoneIfMissing("Europe/Paris")
        assertEquals(0, rig.server.calls("/v1/me/profile").size)
    }

    @Test
    fun aZoneSetInBetweenIsLeftAlone() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, noZone), netJson(200, hasZone))
        rig.server.on("/v1/me/profile", netJson(412, profileStale))
        rig.api.setTimeZoneIfMissing("Europe/Paris")
        assertEquals(2, rig.server.calls("/v1/me").size)
        assertEquals(1, rig.server.calls("/v1/me/profile").size)
    }

    @Test
    fun aStaleVersionIsReadAgainOnce() = runBlocking<Unit> {
        val rig = NetRig()
        val newer = "{\"sub\":\"sub-1\",\"timezone\":null,\"version\":9}"
        rig.server.on("/v1/me", netJson(200, noZone), netJson(200, newer))
        rig.server.on("/v1/me/profile", netJson(412, profileStale), netJson(200, saved))
        rig.api.setTimeZoneIfMissing("Europe/Paris")
        assertEquals(listOf("\"v7\"", "\"v9\""), rig.server.calls("/v1/me/profile").map { it.header("If-Match") })
    }

    @Test
    fun aSecondStaleVersionThrows() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, noZone))
        rig.server.on("/v1/me/profile", netJson(412, profileStale))
        val error = netCaught { rig.api.setTimeZoneIfMissing("Europe/Paris") }
        assertEquals("precondition_failed", (error as? AuraError)?.serverCode)
        assertEquals(2, rig.server.calls("/v1/me/profile").size)
    }
}

class LiveAuraErrorTest {
    @Test
    fun serverCode() {
        assertEquals("work_limit_reached", AuraError.Server("work_limit_reached", "Later.", 409).serverCode)
        assertNull(AuraError.Offline.serverCode)
        assertNull(AuraError.SignedOut.serverCode)
        assertNull(AuraError.ConsentRequired.serverCode)
    }
}
