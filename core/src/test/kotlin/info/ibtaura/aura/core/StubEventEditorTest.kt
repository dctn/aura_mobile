package info.ibtaura.aura.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A port of StubEventEditorTests (EventEditorTests.swift): the editor's three routes against the stub. */
class StubEventEditorTest {
    @Test
    fun createChangeAndDelete() = runTest {
        val api = stubApi()
        val now = stubNoon
        val from = stubTime(stubTodayKey, 0)
        val to = from.plusSeconds(8 * 86_400L)
        val start = now.plusSeconds(3 * 3600L)
        api.createEvent(EventDraft(title = "Walk with Ana", start = start, end = start.plusSeconds(1800), kind = "recovery"))
        val listed = api.events(from, to)
        var made = assertNotNull(listed.firstOrNull { it.title == "Walk with Ana" })
        assertEquals("recovery", made.kind)

        val draft = EventDraft(made).copy(title = "Walk with Ana and Sam")
        api.updateEvent(made, draft)
        val relisted = api.events(from, to)
        val madeId = made.id
        made = assertNotNull(relisted.firstOrNull { it.id == madeId })
        assertEquals("Walk with Ana and Sam", made.title)

        api.deleteEvent(made.id)
        val after = api.events(from, to)
        assertFalse(after.any { it.id == madeId })
    }

    @Test
    fun aFixedEventCanBeDeleted() = runTest {
        val api = stubApi()
        val now = stubNoon
        api.deleteEvent("e1")
        val events = api.events(now, now.plusSeconds(86_400))
        assertFalse(events.any { it.id == "e1" })
        assertTrue(events.any { it.id == "e2" })
    }
}
