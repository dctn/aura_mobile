package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports of EventEditorTests.swift's LiveAPIEventEditorTests.

class LiveEventEditorTest {
    private val start = NetRig.now
    private val end = start.plusSeconds(3600)

    @Test
    fun createSendsAClientIdAndTheFields() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/calendar/events", netJson(201, "{\"event\":{\"id\":\"x\"}}"))
        rig.api.createEvent(EventDraft("Walk with Ana", start, end, kind = "recovery", location = "Park"))
        val call = rig.server.calls("/v1/calendar/events").first()
        assertEquals("POST", call.method)
        assertNotNull(runCatching { UUID.fromString(call.string("id")) }.getOrNull())
        assertEquals("Walk with Ana", call.string("title"))
        assertEquals("recovery", call.string("kind"))
        assertEquals("Park", call.string("location"))
        assertFalse(call.body.containsKey("notes"))
        assertEquals(start, WireDate.parse(call.string("start")))
    }

    @Test
    fun updatePatchesTheChangesWithTheVersionRead() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/e1"
        rig.server.on(path, netJson(200, "{\"event\":{\"id\":\"e1\"}}"))
        val event = CalendarEvent("e1", "Plan", start, end, version = 4)
        rig.api.updateEvent(event, EventDraft(event).copy(title = "Plan the week"))
        val calls = rig.server.calls(path)
        assertEquals(1, calls.size)
        assertEquals("PATCH", calls[0].method)
        assertEquals("\"v4\"", calls[0].header("If-Match"))
        assertEquals(listOf("title"), calls[0].body.keys.sorted())
    }

    @Test
    fun updateWithoutAVersionReadsOneFirst() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/e1"
        val read = "{\"event\":{\"id\":\"e1\",\"title\":\"Plan\",\"start\":\"${WireDate.string(start)}\",\"end\":\"${WireDate.string(end)}\",\"version\":9}}"
        rig.server.on(path, netJson(200, read), netJson(200, read))
        val event = CalendarEvent("e1", "Plan", start, end)
        rig.api.updateEvent(event, EventDraft(event).copy(kind = "meeting"))
        val calls = rig.server.calls(path)
        assertEquals(listOf("GET", "PATCH"), calls.map { it.method })
        assertEquals("\"v9\"", calls[1].header("If-Match"))
    }

    @Test
    fun nothingChangedSendsNothing() = runBlocking<Unit> {
        val rig = NetRig()
        val event = CalendarEvent("e1", "Plan", start, end, version = 4)
        rig.api.updateEvent(event, EventDraft(event))
        assertTrue(rig.server.calls("/v1/calendar/events/e1").isEmpty())
    }

    @Test
    fun changedElsewhereIs412() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/e1"
        rig.server.on(path, netJson(412, "{\"error\":{\"code\":\"version_mismatch\",\"message\":\"Changed.\"}}"))
        val event = CalendarEvent("e1", "Plan", start, end, version = 4)
        val error = netCaught { rig.api.updateEvent(event, EventDraft(event).copy(title = "New")) }
        assertEquals(412, (error as? AuraError.Server)?.status, "A 412 should throw")
        // One re-read for the latest version (it answers 412 here too), and nothing more.
        assertEquals(listOf("PATCH", "GET"), rig.server.calls(path).map { it.method })
    }

    @Test
    fun deleteEscapesAnOccurrenceId() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/s1@2030-03-17"
        rig.server.on(path, netJson(204, ""))
        rig.api.deleteEvent("s1@2030-03-17")
        val call = rig.server.calls(path).first()
        assertEquals("DELETE", call.method)
        assertTrue(call.address.endsWith("/v1/calendar/events/s1%402030-03-17"), call.address)
    }

    @Test
    fun eventsCarryWhatTheEditorNeeds() = runBlocking<Unit> {
        val rig = NetRig()
        val page = "{\"events\":[{\"id\":\"s1@2030-03-17\",\"title\":\"Standup\",\"start\":\"${WireDate.string(start)}\"," +
            "\"end\":\"${WireDate.string(end)}\",\"kind\":\"meeting\",\"notes\":\"Agenda\",\"location\":\"Room 2\",\"source\":\"app\"," +
            "\"seriesId\":\"s1\",\"version\":3}],\"cursor\":null}"
        rig.server.on("/v1/calendar/events", netJson(200, page))
        val event = rig.api.events(start, end).first()
        assertEquals(
            CalendarEvent("s1@2030-03-17", "Standup", start, end, kind = "meeting", notes = "Agenda", location = "Room 2",
                source = "app", seriesId = "s1", version = 3),
            event,
        )
    }

    // MARK: Events (editor round 2)

    private fun eventJson(id: String, version: Int): String =
        "{\"event\":{\"id\":\"$id\",\"title\":\"Plan\",\"start\":\"${WireDate.string(start)}\"," +
            "\"end\":\"${WireDate.string(end)}\",\"version\":$version}}"

    private val conflict = netJson(412, "{\"error\":{\"code\":\"precondition_failed\",\"message\":\"Changed.\"," +
        "\"details\":{\"currentVersion\":7}}}")

    @Test
    fun anUnknownKindIsNotRewritten() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/e1"
        rig.server.on(path, netJson(200, eventJson("e1", 5)))
        val event = CalendarEvent("e1", "Plan", start, end, kind = "travel", version = 4)
        val draft = EventDraft(event)
        assertEquals("travel", draft.kind)
        rig.api.updateEvent(event, draft.copy(title = "Plan the week"))
        assertEquals(listOf("title"), rig.server.calls(path).single().body.keys.sorted())
    }

    @Test
    fun a412ReadsTheLatestVersionAndSendsTheSameChangesOnce() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/e1"
        rig.server.on(path, conflict, netJson(200, eventJson("e1", 7)), netJson(200, eventJson("e1", 8)))
        val event = CalendarEvent("e1", "Plan", start, end, version = 4)
        rig.api.updateEvent(event, EventDraft(event).copy(notes = "Agenda", workType = "ideas"))
        val calls = rig.server.calls(path)
        assertEquals(listOf("PATCH", "GET", "PATCH"), calls.map { it.method })
        assertEquals("\"v4\"", calls[0].header("If-Match"))
        assertEquals("\"v7\"", calls[2].header("If-Match"))
        assertEquals(listOf("notes", "workType"), calls[2].body.keys.sorted())
        assertEquals(calls[0].bodyText, calls[2].bodyText)
    }

    @Test
    fun aSecond412IsThrown() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/e1"
        rig.server.respond(path) { call -> if (call.method == "GET") netJson(200, eventJson("e1", 9)) else conflict }
        val event = CalendarEvent("e1", "Plan", start, end, version = 4)
        val error = netCaught { rig.api.updateEvent(event, EventDraft(event).copy(title = "New")) }
        assertEquals(412, (error as? AuraError.Server)?.status)
        assertEquals(listOf("PATCH", "GET", "PATCH"), rig.server.calls(path).map { it.method })
    }

    @Test
    fun anAppleEventSendsOnlyKindWorkTypeAndHighStakes() {
        val apple = CalendarEvent("a1", "Board", start, end, kind = "meeting", source = "apple", version = 2)
        val body = EventDraft(apple).copy(title = "Other", workType = "detailHeavy", highStakes = true).changes(apple)
        assertEquals(setOf("workType", "highStakes"), body.keys)
        val typed = CalendarEvent("e1", "Plan", start, end, workType = "ideas", version = 2)
        assertTrue(EventDraft(typed).copy(workType = null).changes(typed)["workType"] is JsonNull)
    }

    @Test
    fun moveSendsATarget() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/e1/move"
        rig.server.on(path, netJson(200, eventJson("e1", 5)))
        val moved = rig.api.moveEvent("e1", to = EventMoveTargets.NEXT_FREE)
        val call = rig.server.calls(path).single()
        assertEquals("POST", call.method)
        assertNull(call.header("If-Match"))
        assertEquals(setOf("to"), call.body.keys)
        assertEquals("nextFree", call.string("to"))
        assertEquals(5, moved.version)
    }

    @Test
    fun moveSendsAStartAndEnd() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/s1@2030-03-17/move"
        rig.server.on(path, netJson(200, eventJson("s1@2030-03-17", 2)))
        val newStart = start.plusSeconds(86_400)
        rig.api.moveEvent("s1@2030-03-17", start = newStart, end = newStart.plusSeconds(1800))
        val call = rig.server.calls(path).single()
        assertEquals(setOf("start", "end"), call.body.keys)
        assertEquals(newStart, WireDate.parse(call.string("start")))
        assertEquals(newStart.plusSeconds(1800), WireDate.parse(call.string("end")))
        assertTrue(call.address.endsWith("/v1/calendar/events/s1%402030-03-17/move"), call.address)
    }

    @Test
    fun moveSendsAStartAloneToKeepTheLength() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/e1/move"
        rig.server.on(path, netJson(200, eventJson("e1", 5)))
        rig.api.moveEvent("e1", start = start)
        assertEquals(setOf("start"), rig.server.calls(path).single().body.keys)
    }

    @Test
    fun aWorkLimitErrorCarriesItsMinutes() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/calendar/events/e1/move"
        rig.server.on(path, netJson(409, "{\"error\":{\"code\":\"work_limit_reached\",\"message\":\"Over.\"," +
            "\"details\":{\"date\":\"2030-03-17\",\"maxWorkMinutes\":480,\"plannedWorkMinutes\":450,\"blockMinutes\":60}}}"))
        val error = assertNotNull(netCaught { rig.api.moveEvent("e1", to = EventMoveTargets.TOMORROW) })
        val limit = assertNotNull(WorkLimitDetails.of(error))
        assertEquals(WorkLimitDetails("2030-03-17", 480, 450, 60), limit)
        assertEquals(510, limit.wouldBeMinutes)
    }

    @Test
    fun eventsCarryTaskWorkTypeKindSourceAndHighStakes() = runBlocking<Unit> {
        val rig = NetRig()
        val times = "\"start\":\"${WireDate.string(start)}\",\"end\":\"${WireDate.string(end)}\""
        val page = "{\"events\":[{\"id\":\"plan-2030-03-17-t1\",\"title\":\"Reply\",$times,\"kind\":\"focus\"," +
            "\"source\":\"planner\",\"taskId\":\"t1\",\"workType\":\"deepFocus\",\"kindSource\":\"auto\"," +
            "\"highStakes\":true,\"allDay\":false,\"recurrence\":\"none\",\"version\":1}," +
            "{\"id\":\"s1\",\"title\":\"Standup\",$times,\"seriesId\":\"s1\",\"recurrence\":\"daily\"," +
            "\"taskId\":null,\"workType\":null}],\"cursor\":null}"
        rig.server.on("/v1/calendar/events", netJson(200, page))
        val events = rig.api.events(start, end)
        val block = events.first { it.id == "plan-2030-03-17-t1" }
        assertEquals("t1", block.taskId)
        assertEquals("deepFocus", block.workType)
        assertEquals("auto", block.kindSource)
        assertTrue(block.highStakes)
        assertTrue(block.isPlannerBlock)
        assertTrue(block.canMove)
        assertFalse(block.repeats, "recurrence \"none\" is a one-off")
        val series = events.first { it.id == "s1" }
        assertNull(series.taskId)
        assertTrue(series.isSeries)
        assertFalse(series.isOccurrence)
        assertFalse(series.canMove)
    }

    @Test
    fun unpinReadsTheTaskThenPatchesAutoAllocateOff() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/tasks/t1"
        val read = "{\"task\":{\"id\":\"t1\",\"title\":\"Reply\",\"autoAllocate\":true,\"version\":3}}"
        val written = "{\"task\":{\"id\":\"t1\",\"title\":\"Reply\",\"autoAllocate\":false,\"version\":4}}"
        rig.server.on(path, netJson(200, read), netJson(200, written))
        rig.api.unpinTaskFromPlanner("t1")
        val calls = rig.server.calls(path)
        assertEquals(listOf("GET", "PATCH"), calls.map { it.method })
        assertEquals("\"v3\"", calls[1].header("If-Match"))
        assertEquals("{\"autoAllocate\":false}", calls[1].bodyText)
    }

    @Test
    fun unpinTriesOnceMoreAfterA412() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/tasks/t1"
        rig.server.on(
            path,
            netJson(200, "{\"task\":{\"id\":\"t1\",\"version\":3}}"),
            conflict,
            netJson(200, "{\"task\":{\"id\":\"t1\",\"version\":5}}"),
            netJson(200, "{\"task\":{\"id\":\"t1\",\"version\":6}}"),
        )
        rig.api.unpinTaskFromPlanner("t1")
        val calls = rig.server.calls(path)
        assertEquals(listOf("GET", "PATCH", "GET", "PATCH"), calls.map { it.method })
        assertEquals("\"v5\"", calls[3].header("If-Match"))
    }

    @Test
    fun aGoneTaskIsNullAndUnpinsQuietly() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks/t9", netJson(404, "{\"error\":{\"code\":\"task_not_found\",\"message\":\"Gone.\"}}"))
        assertNull(rig.api.task("t9"))
        assertNull(netCaught { rig.api.unpinTaskFromPlanner("t9") })
    }

    @Test
    fun taskReadsOneById() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks/t1", netJson(200, "{\"task\":{\"id\":\"t1\",\"title\":\"Reply to Ana\",\"version\":2}}"))
        val task = assertNotNull(rig.api.task("t1"))
        assertEquals("Reply to Ana", task.title)
        assertEquals(2, task.version)
    }
}
