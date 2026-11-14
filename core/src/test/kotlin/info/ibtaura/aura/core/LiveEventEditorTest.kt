package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        assertEquals(1, rig.server.calls(path).size)
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
}
