package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The Tasks page's routes against the fake network: create, edit (If-Match and its one retry), complete, reopen, delete.

private const val TASK_PATH = "/v1/tasks/t1"

private fun taskJson(id: String = "t1", version: Int = 3, extra: String = ""): String =
    "{\"task\":{\"id\":\"$id\",\"title\":\"Plan\",\"status\":\"pending\",\"version\":$version$extra}}"

private const val STALE = "{\"error\":{\"code\":\"version_mismatch\",\"message\":\"Changed.\"}}"

class LiveTasksTest {
    @Test
    fun createSendsAClientIdAndTheFields() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(201, taskJson("x", 1, ",\"priority\":\"high\",\"horizon\":\"week\"")))
        val draft = TaskDraft(
            title = "  Draft the update ", notes = "Two pages", priority = TaskPriority.high,
            dueDay = LocalDate.of(2030, 3, 18), dueTime = LocalTime.of(16, 30), estimateMinutes = 45,
            horizon = TaskHorizon.week, flagged = true,
        )
        val made = rig.api.createTask(draft)
        val call = rig.server.calls("/v1/tasks").single()
        assertEquals("POST", call.method)
        val id = assertNotNull(call.string("id"))
        assertNotNull(runCatching { UUID.fromString(id) }.getOrNull())
        assertEquals(id.lowercase(), id)
        assertEquals("Draft the update", call.string("title"))
        assertEquals("Two pages", call.string("notes"))
        assertEquals("high", call.string("priority"))
        assertEquals("week", call.string("horizon"))
        assertEquals(true, call.bool("flagged"))
        assertEquals(45, call.int("estimateMinutes"))
        assertEquals(true, call.bool("hasTime"))
        assertEquals(draft.dueAt(), WireDate.parse(call.string("dueAt")))
        assertEquals(TaskPriority.high, made.priority)
        assertEquals(TaskHorizon.week, made.horizon)
        assertEquals(1, made.version)
    }

    @Test
    fun createKeepsTheCallersIdAndLeavesEmptyFieldsOut() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(200, taskJson("fixed-id", 1)))
        rig.api.createTask(TaskDraft(title = "Call the bank"), id = "fixed-id")
        val call = rig.server.calls("/v1/tasks").single()
        assertEquals("fixed-id", call.string("id"))
        assertFalse(call.body.containsKey("notes"))
        assertFalse(call.body.containsKey("dueAt"))
        assertFalse(call.body.containsKey("hasTime"))
        assertFalse(call.body.containsKey("estimateMinutes"))
        assertEquals("today", call.string("horizon"))
    }

    @Test
    fun aDayWithoutATimeGoesAsItsMidnight() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(201, taskJson("x", 1)))
        val day = LocalDate.of(2030, 3, 18)
        rig.api.createTask(TaskDraft(title = "Pay rent", dueDay = day))
        val call = rig.server.calls("/v1/tasks").single()
        assertEquals(false, call.bool("hasTime"))
        assertEquals(day.atStartOfDay(DayKey.zone).toInstant(), WireDate.parse(call.string("dueAt")))
    }

    @Test
    fun updatePatchesOnlyTheChangesWithIfMatch() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(TASK_PATH, netJson(200, taskJson(version = 5, extra = ",\"flagged\":true")))
        val task = TaskItem("t1", "Plan", version = 4, notes = "Old notes", estimateMinutes = 30)
        val changes = TaskDraft(task).copy(title = "Plan the week", notes = "", estimateMinutes = null, flagged = true)
            .changes(task)
        val saved = rig.api.updateTask("t1", 4, changes)
        val call = rig.server.calls(TASK_PATH).single()
        assertEquals("PATCH", call.method)
        assertEquals("\"v4\"", call.header("If-Match"))
        assertEquals(listOf("estimateMinutes", "flagged", "notes", "title"), call.body.keys.sorted())
        assertEquals("Plan the week", call.string("title"))
        assertTrue(call.isNull("notes"))
        assertTrue(call.isNull("estimateMinutes"))
        assertEquals(true, call.bool("flagged"))
        assertEquals(5, saved?.version)
        assertEquals(true, saved?.flagged)
    }

    @Test
    fun clearingTheDueDateSendsNullAndNoTime() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(TASK_PATH, netJson(200, taskJson(version = 2)))
        val due = LocalDate.of(2030, 3, 18).atTime(9, 0).atZone(DayKey.zone).toInstant()
        val task = TaskItem("t1", "Plan", version = 1, dueAt = due, hasTime = true)
        rig.api.updateTask("t1", 1, TaskDraft(task).copy(dueDay = null, dueTime = null).changes(task))
        val call = rig.server.calls(TASK_PATH).single()
        assertEquals(listOf("dueAt", "hasTime"), call.body.keys.sorted())
        assertTrue(call.isNull("dueAt"))
        assertEquals(false, call.bool("hasTime"))
    }

    @Test
    fun nothingChangedSendsNothing() = runBlocking<Unit> {
        val rig = NetRig()
        val due = LocalDate.of(2030, 3, 18).atStartOfDay(DayKey.zone).toInstant()
        val task = TaskItem("t1", "Plan", version = 4, dueAt = due, notes = "  ", priority = TaskPriority.low)
        val changes = TaskDraft(task).changes(task)
        assertTrue(changes.isEmpty, "Unchanged fields: ${changes.fieldNames}")
        assertNull(rig.api.updateTask("t1", 4, changes))
        assertTrue(rig.server.allCalls.isEmpty())
    }

    @Test
    fun updateWithoutAVersionReadsOneFirst() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.respond(TASK_PATH) { call ->
            if (call.method == "GET") netJson(200, taskJson(version = 9)) else netJson(200, taskJson(version = 10))
        }
        rig.api.updateTask("t1", null, TaskChanges(priority = TaskPriority.urgent))
        val calls = rig.server.calls(TASK_PATH)
        assertEquals(listOf("GET", "PATCH"), calls.map { it.method })
        assertEquals("\"v9\"", calls[1].header("If-Match"))
        assertEquals("urgent", calls[1].string("priority"))
    }

    @Test
    fun a412ReadsTheTaskAgainAndTriesOnce() = runBlocking<Unit> {
        val rig = NetRig()
        var patches = 0
        rig.server.respond(TASK_PATH) { call ->
            when (call.method) {
                "GET" -> netJson(200, taskJson(version = 7))
                else -> {
                    patches += 1
                    if (patches == 1) netJson(412, STALE) else netJson(200, taskJson(version = 8))
                }
            }
        }
        val saved = rig.api.updateTask("t1", 4, TaskChanges(title = "New"))
        val calls = rig.server.calls(TASK_PATH)
        assertEquals(listOf("PATCH", "GET", "PATCH"), calls.map { it.method })
        assertEquals("\"v4\"", calls[0].header("If-Match"))
        assertEquals("\"v7\"", calls[2].header("If-Match"))
        assertEquals("New", calls[2].string("title"))
        assertEquals(8, saved?.version)
    }

    @Test
    fun aSecond412IsThrown() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.respond(TASK_PATH) { call ->
            if (call.method == "GET") netJson(200, taskJson(version = 7)) else netJson(412, STALE)
        }
        val error = netCaught { rig.api.updateTask("t1", 4, TaskChanges(title = "New")) }
        assertEquals(412, (error as? AuraError.Server)?.status)
        assertEquals(listOf("PATCH", "GET", "PATCH"), rig.server.calls(TASK_PATH).map { it.method })
    }

    @Test
    fun completePostsAnEmptyBodyAndReadsTheNextCopy() = runBlocking<Unit> {
        val rig = NetRig()
        val path = "/v1/tasks/t1/complete"
        val answer = "{\"task\":{\"id\":\"t1\",\"title\":\"Water plants\",\"status\":\"completed\"," +
            "\"completedAt\":\"2030-03-18T10:00:00Z\",\"version\":4}," +
            "\"next\":{\"id\":\"r-abc\",\"title\":\"Water plants\",\"status\":\"pending\",\"version\":1}}"
        rig.server.on(path, netJson(200, answer))
        val done = rig.api.completeTask("t1")
        val call = rig.server.calls(path).single()
        assertEquals("POST", call.method)
        assertEquals("{}", call.bodyText)
        assertNull(call.header("If-Match"))
        assertTrue(done.task.done)
        assertEquals(TaskStatus.completed, done.task.status)
        assertEquals(WireDate.parse("2030-03-18T10:00:00Z"), done.task.completedAt)
        assertEquals("r-abc", done.next?.id)
        assertFalse(done.next?.done ?: true)
    }

    @Test
    fun completeWithoutANextCopy() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks/t1/complete", netJson(200, taskJson(extra = ",\"status\":\"completed\"")))
        assertNull(rig.api.completeTask("t1").next)
    }

    @Test
    fun reopenPatchesStatusPending() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(TASK_PATH, netJson(200, taskJson(version = 6)))
        val reopened = rig.api.reopenTask("t1", 5)
        val call = rig.server.calls(TASK_PATH).single()
        assertEquals("PATCH", call.method)
        assertEquals("\"v5\"", call.header("If-Match"))
        assertEquals(listOf("status"), call.body.keys.toList())
        assertEquals("pending", call.string("status"))
        assertFalse(reopened.done)
    }

    @Test
    fun deleteSendsDeleteAndAGoneTaskCounts() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(TASK_PATH, netJson(204, ""), netJson(404, "{\"error\":{\"code\":\"not_found\",\"message\":\"No.\"}}"))
        rig.api.deleteTask("t1")
        rig.api.deleteTask("t1")
        val calls = rig.server.calls(TASK_PATH)
        assertEquals(listOf("DELETE", "DELETE"), calls.map { it.method })
    }

    @Test
    fun deleteEscapesTheId() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks/a b", netJson(204, ""))
        rig.api.deleteTask("a b")
        assertTrue(rig.server.calls("/v1/tasks/a b").single().address.endsWith("/v1/tasks/a%20b"))
    }

    @Test
    fun otherDeleteErrorsAreThrown() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(TASK_PATH, netJson(500, "{\"error\":{\"code\":\"internal\",\"message\":\"Oops.\"}}"))
        assertIs<AuraError.Server>(netCaught { rig.api.deleteTask("t1") })
    }

    @Test
    fun theListCarriesEveryPageField() = runBlocking<Unit> {
        val rig = NetRig()
        val page = """
            {"tasks":[{"id":"t1","title":"Report","status":"working","priority":"urgent","dueAt":"2030-03-18T16:00:00Z",
             "hasTime":true,"notes":"Q3","horizon":"month","flagged":true,"estimateMinutes":60,"plannedFor":"2030-03-18",
             "completedAt":null,"scheduledAt":null,"costPoints":0.5,"version":7,"autoAllocate":false},
             {"id":"t2","title":"Odd","priority":"someday","horizon":"year","status":"paused"}],"deleted":[],"cursor":null}
        """.trimIndent()
        rig.server.on("/v1/tasks", netJson(200, page))
        val tasks = rig.api.tasks()
        val due = WireDate.parse("2030-03-18T16:00:00Z")
        assertEquals(
            TaskItem(
                id = "t1", title = "Report", at = due, version = 7, status = TaskStatus.working,
                priority = TaskPriority.urgent, dueAt = due, hasTime = true, notes = "Q3", horizon = TaskHorizon.month,
                flagged = true, estimateMinutes = 60, plannedFor = "2030-03-18", costPoints = 0.5,
            ),
            tasks[0],
        )
        // Values this app doesn't know fall back to the defaults instead of failing the list.
        assertEquals(TaskPriority.none, tasks[1].priority)
        assertEquals(TaskHorizon.today, tasks[1].horizon)
        assertEquals(TaskStatus.pending, tasks[1].status)
    }
}

class TaskDraftTest {
    private val zone: ZoneId = ZoneOffset.ofHours(5)

    @Test
    fun problems() {
        assertEquals("Give it a title.", TaskDraft(title = "   ").problem)
        assertEquals("The title is too long.", TaskDraft(title = "x".repeat(501)).problem)
        assertNull(TaskDraft(title = "x".repeat(500)).problem)
        assertEquals("The notes are too long.", TaskDraft(title = "a", notes = "n".repeat(20_001)).problem)
        assertNotNull(TaskDraft(title = "a", estimateMinutes = 0).problem)
        assertNotNull(TaskDraft(title = "a", estimateMinutes = 1441).problem)
        assertNull(TaskDraft(title = "a", estimateMinutes = 1440).problem)
    }

    @Test
    fun aTaskRoundTripsThroughTheDraft() {
        val due = LocalDate.of(2030, 3, 18).atTime(9, 15).atZone(zone).toInstant()
        val task = TaskItem("t", "Plan", dueAt = due, hasTime = true, priority = TaskPriority.medium, flagged = true)
        val draft = TaskDraft(task, zone)
        assertEquals(LocalDate.of(2030, 3, 18), draft.dueDay)
        assertEquals(LocalTime.of(9, 15), draft.dueTime)
        assertEquals(due, draft.dueAt(zone))
        assertTrue(draft.changes(task, zone).isEmpty)
    }

    @Test
    fun aTimeWithoutADayIsNoDueDate() {
        val draft = TaskDraft(title = "a", dueTime = LocalTime.NOON)
        assertNull(draft.dueAt())
        assertFalse(draft.hasTime)
        assertFalse(draft.createBody().containsKey("dueAt"))
    }

    @Test
    fun droppingTheTimeKeepsTheDay() {
        val due = LocalDate.of(2030, 3, 18).atTime(9, 15).atZone(zone).toInstant()
        val task = TaskItem("t", "Plan", dueAt = due, hasTime = true)
        val changes = TaskDraft(task, zone).copy(dueTime = null).changes(task, zone)
        assertEquals(Change(LocalDate.of(2030, 3, 18).atStartOfDay(zone).toInstant()), changes.dueAt)
        assertEquals(false, changes.hasTime)
        assertEquals(setOf("dueAt", "hasTime"), changes.json().keys)
    }

    @Test
    fun appliedChangesMoveTheTime() {
        val due = LocalDate.of(2030, 3, 18).atTime(9, 0).atZone(zone).toInstant()
        val task = TaskItem("t", "Plan")
        val moved = TaskChanges(dueAt = Change(due), hasTime = true).appliedTo(task)
        assertEquals(due, moved.at)
        val cleared = TaskChanges(dueAt = Change(null), hasTime = false).appliedTo(moved)
        assertNull(cleared.at)
    }

    @Test
    fun statusKeepsDoneAndCompletedAtInStep() {
        val now = NetRig.now
        val done = TaskItem("t", "Plan").withStatus(TaskStatus.completed, now)
        assertTrue(done.done)
        assertEquals(now, done.completedAt)
        val open = done.withStatus(TaskStatus.pending, now)
        assertFalse(open.done)
        assertNull(open.completedAt)
        assertEquals(TaskStatus.completed, TaskItem("t", "x", done = true).status)
    }

    @Test
    fun clearedFieldsAreJsonNull() {
        val body = TaskChanges(notes = Change(null), plannedFor = Change(null), estimateMinutes = Change(20)).json()
        assertEquals(JsonNull, body["notes"])
        assertEquals(JsonNull, body["plannedFor"])
        assertEquals(JsonPrimitive(20), body["estimateMinutes"])
    }
}

class StubTasksTest {
    @Test
    fun createEditCompleteReopenDelete() = runTest {
        val api = stubApi()
        val seeded = api.tasks()
        assertEquals(listOf("t1", "t2", "t3"), seeded.map { it.id })

        val made = api.createTask(TaskDraft(title = "Buy milk", priority = TaskPriority.low), id = "n1")
        assertEquals(1, made.version)
        // The same id answers with the task already there.
        assertEquals(made, api.createTask(TaskDraft(title = "Something else"), id = "n1"))
        assertEquals(4, api.tasks().size)

        val edited = assertNotNull(api.updateTask("n1", 1, TaskChanges(title = "Buy oat milk", flagged = true)))
        assertEquals("Buy oat milk", edited.title)
        assertEquals(2, edited.version)

        val stale = netCaught { api.updateTask("n1", 1, TaskChanges(title = "Old")) }
        assertEquals(412, (stale as? AuraError.Server)?.status)

        val done = api.completeTask("n1")
        assertTrue(done.task.done)
        assertTrue(api.completeTask("n1").task.done, "Completing twice is fine")
        val reopened = api.reopenTask("n1", done.task.version)
        assertFalse(reopened.done)

        api.deleteTask("n1")
        api.deleteTask("n1")
        assertFalse(api.tasks().any { it.id == "n1" })
        val missing = netCaught { api.completeTask("n1") }
        assertEquals(404, (missing as? AuraError.Server)?.status)
    }

    @Test
    fun aDraftThatCantBeSavedIsRefused() = runTest {
        val error = netCaught { stubApi().createTask(TaskDraft(title = " ")) }
        assertEquals(400, (error as? AuraError.Server)?.status)
    }

    @Test
    fun theWindDownListLandsInTheTasks() = runTest {
        val api = stubApi()
        api.addTask("Tomorrow's thing", "2030-03-18")
        assertEquals("2030-03-18", api.tasks().single { it.title == "Tomorrow's thing" }.plannedFor)
    }
}
