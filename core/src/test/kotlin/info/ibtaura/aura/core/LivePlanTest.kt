package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports of PlanLiveTests.swift.

private const val allocatePath = "/v1/planner/allocate"
private const val planPath = "/v1/planner/plan/2026-11-09"

private const val meWithSchedule = "{\"sub\":\"sub-1\",\"timezone\":\"Asia/Kolkata\",\"version\":8," +
    "\"workSchedule\":{\"workdayStart\":\"08:30\",\"workdayEnd\":\"17:00\",\"lunchStart\":\"12:30\",\"lunchMinutes\":45,\"workDays\":[1,2,3,4,5]}}"
private const val meWithoutSchedule = "{\"sub\":\"sub-1\",\"timezone\":\"Asia/Kolkata\",\"workSchedule\":null,\"version\":7}"

/** A plan with the recovery rhythm: a task split in two around a recovery zone, the break after its first piece. */
private val fullPlan = """
    {"plan":{
      "date":"2026-11-09",
      "generatedAt":"2026-11-09T03:05:12.000Z",
      "basis":{"quickCheckAt":"2026-11-09T02:58:40.000Z","attention":96,"sleepMinutes":412,
               "sharpestHours":{"startHour":9,"endHour":11},"capacityOpening":78.5,"workLimitMinutes":300},
      "placed":[
        {"taskId":"task-report","blockId":"plan-2026-11-09-task-report-p1","start":"2026-11-09T03:30:00.000Z",
         "end":"2026-11-09T04:30:00.000Z","demand":72,"workType":"deepFocus","reason":"Inside your sharpest hours.",
         "kind":"focus","part":{"index":1,"of":2}},
        {"taskId":null,"blockId":"plan-2026-11-09-task-report-rest","start":"2026-11-09T04:30:00.000Z",
         "end":"2026-11-09T04:40:00.000Z","demand":0,"workType":null,"reason":"A short rest after deep work.",
         "kind":"recovery","part":null},
        {"taskId":"task-report","blockId":"plan-2026-11-09-task-report-p2","start":"2026-11-09T05:15:00.000Z",
         "end":"2026-11-09T05:45:00.000Z","demand":72,"workType":"deepFocus","reason":"Inside your sharpest hours.",
         "kind":"focus","part":{"index":2,"of":2}}
      ],
      "unplaced":[{"taskId":"task-slides","reason":"dueLater","detail":"Planned for 2026-11-12."}],
      "warnings":{"committedOnRecovery":2,"recoveryMoved":1},
      "version":3
    }}
""".trimIndent()

/** A plan from a Planner deployed before the recovery rhythm: no kind, no part, no warnings. */
private val olderPlan = """
    {"plan":{
      "date":"2026-11-09",
      "generatedAt":"2026-11-09T03:05:12.000Z",
      "basis":{"quickCheckAt":null,"attention":null,"sleepMinutes":null,"sharpestHours":null,"capacityOpening":null,
               "workLimitMinutes":null},
      "placed":[
        {"taskId":"task-email","blockId":"plan-2026-11-09-task-email","start":"2026-11-09T06:00:00.000Z",
         "end":"2026-11-09T06:30:00.000Z","demand":20,"workType":"routine","reason":"A light task in a weaker hour."}
      ],
      "unplaced":[],
      "version":1
    }}
""".trimIndent()

private const val quickCheckRequired =
    "{\"error\":{\"code\":\"quick_check_required\",\"message\":\"Take today's Quick check first, or plan without it.\",\"details\":null}}"

private fun planAt(text: String): Instant = Instant.parse(text)

class LivePlanTest {
    @Test
    fun fullPlanDecodes() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, meWithSchedule))
        rig.server.on(allocatePath, netJson(200, fullPlan))
        val plan = rig.api.allocate("2026-11-09", dryRun = false, skipCheck = false, taskIds = null)

        val basis = PlanBasis(planAt("2026-11-09T02:58:40Z"), 96.0, 412.0, SharpestHours(9, 11), 78.5, 300)
        val placed = listOf(
            PlanBlock("task-report", "plan-2026-11-09-task-report-p1", planAt("2026-11-09T03:30:00Z"), planAt("2026-11-09T04:30:00Z"),
                72.0, "deepFocus", "Inside your sharpest hours.", "focus", PlanPart(1, 2)),
            PlanBlock(null, "plan-2026-11-09-task-report-rest", planAt("2026-11-09T04:30:00Z"), planAt("2026-11-09T04:40:00Z"),
                0.0, null, "A short rest after deep work.", "recovery", null),
            PlanBlock("task-report", "plan-2026-11-09-task-report-p2", planAt("2026-11-09T05:15:00Z"), planAt("2026-11-09T05:45:00Z"),
                72.0, "deepFocus", "Inside your sharpest hours.", "focus", PlanPart(2, 2)),
        )
        val expected = DayPlan(
            "2026-11-09", planAt("2026-11-09T03:05:12Z"), basis, placed,
            listOf(UnplacedTask("task-slides", "dueLater", "Planned for 2026-11-12.")),
            PlanWarnings(2, 1), 3,
        )
        assertEquals(expected, plan)
        assertTrue(plan.placed[1].isRecovery)
        assertEquals("Quarterly report (1/2)", plan.placed[0].title("Quarterly report"))
    }

    @Test
    fun anOlderPlanReadsWithDefaults() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(planPath, netJson(200, olderPlan))
        val plan = assertNotNull(rig.api.plan("2026-11-09"))
        assertEquals(PlanBasis(), plan.basis)
        assertEquals(1, plan.placed.size)
        assertEquals("focus", plan.placed.first().kind)
        assertNull(plan.placed.first().part)
        assertEquals(PlanWarnings(0, 0), plan.warnings)
        assertNull(plan.warnings.note)
        assertEquals(1, plan.version)
    }

    /** A block with a time it can't read is left out, an unknown reason is kept, and whole numbers may come as decimals. */
    @Test
    fun unreadablePiecesAreSkipped() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = """
            {"plan":{"date":"2026-11-09","generatedAt":"2026-11-09T03:05:12.000Z","basis":{"sharpestHours":{"startHour":9.0,"endHour":11.0}},
              "placed":[
                {"taskId":"late","blockId":"plan-2026-11-09-late","start":"2026-11-09T09:00:00.000Z","end":"2026-11-09T09:30:00.000Z",
                 "demand":40,"workType":"ideas","reason":"Later in the day.","kind":"focus","part":null},
                {"taskId":"odd","blockId":"plan-2026-11-09-odd","start":"soon","end":"2026-11-09T05:00:00.000Z","demand":40,
                 "workType":null,"reason":"x","kind":"focus","part":null},
                {"taskId":"early","blockId":"plan-2026-11-09-early-p1","start":"2026-11-09T04:00:00.000Z",
                 "end":"2026-11-09T04:30:00.000Z","demand":40,"workType":null,"reason":"Morning.","kind":"focus",
                 "part":{"index":1.0,"of":2.0}}
              ],
              "unplaced":[{"taskId":"t-new","reason":"calendarFull","detail":"No room on the calendar."},{"reason":"noFreeTime"}],
              "warnings":{"committedOnRecovery":1.0,"recoveryMoved":0},"version":4.0}}
        """.trimIndent()
        rig.server.on(planPath, netJson(200, sample))
        val plan = assertNotNull(rig.api.plan("2026-11-09"))
        assertEquals(listOf("early", "late"), plan.placed.map { it.taskId ?: "" }, "Unreadable dropped, the rest in time order")
        assertEquals(PlanPart(1, 2), plan.placed.first().part)
        assertEquals(SharpestHours(9, 11), plan.basis.sharpestHours)
        assertEquals(listOf(UnplacedTask("t-new", "calendarFull", "No room on the calendar.")), plan.unplaced)
        assertEquals(PlanWarnings(1, 0), plan.warnings)
        assertEquals(4, plan.version)
    }

    @Test
    fun anUnreadableGeneratedAtIsABadResponse() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = "{\"plan\":{\"date\":\"2026-11-09\",\"generatedAt\":\"this morning\",\"basis\":{},\"placed\":[],\"unplaced\":[],\"version\":1}}"
        rig.server.on(planPath, netJson(200, sample))
        val error = netCaught { rig.api.plan("2026-11-09") }
        assertEquals("bad_response", (error as? AuraError)?.serverCode)
    }

    @Test
    fun allocateSendsTheProfilesWorkHours() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, meWithSchedule))
        rig.server.on(allocatePath, netJson(200, fullPlan))
        rig.api.allocate("2026-11-09", dryRun = true, skipCheck = false, taskIds = null)

        assertEquals(listOf("/v1/me", allocatePath), rig.server.allCalls.map { it.key }, "Work hours are read first")
        val me = rig.server.calls("/v1/me").first()
        assertEquals("GET", me.method)
        assertEquals("identity.stub.test", me.host)

        val call = rig.server.calls(allocatePath).first()
        assertEquals("POST", call.method)
        assertEquals("planner.stub.test", call.host)
        assertEquals(listOf("date", "dryRun", "skipCheck", "workdayEnd", "workdayStart"), call.body.keys.sorted())
        assertEquals("2026-11-09", call.string("date"))
        assertEquals(true, call.bool("dryRun"))
        assertEquals(false, call.bool("skipCheck"))
        assertEquals("08:30", call.string("workdayStart"))
        assertEquals("17:00", call.string("workdayEnd"))
    }

    @Test
    fun allocateWithoutWorkHoursLeavesThemOut() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, meWithoutSchedule))
        rig.server.on(allocatePath, netJson(200, fullPlan))
        rig.api.allocate("2026-11-09", dryRun = false, skipCheck = false, taskIds = null)
        assertEquals(listOf("date", "dryRun", "skipCheck"), rig.server.calls(allocatePath).first().body.keys.sorted())
    }

    @Test
    fun allocateSendsTheTaskIdsAsked() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, meWithoutSchedule))
        rig.server.on(allocatePath, netJson(200, fullPlan))
        rig.api.allocate("2026-11-09", dryRun = false, skipCheck = false, taskIds = listOf("a", "b"))
        val call = rig.server.calls(allocatePath).first()
        assertEquals(listOf("date", "dryRun", "skipCheck", "taskIds"), call.body.keys.sorted())
        assertEquals(listOf("a", "b"), (call.body["taskIds"] as JsonArray).map { (it as JsonPrimitive).content })
    }

    /** Planning in the Planner's default hours when the person set others would put blocks in the wrong place. */
    @Test
    fun aFailedProfileReadStopsTheAllocate() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(500, "{\"error\":{\"code\":\"internal\",\"message\":\"Try again.\"}}"))
        rig.server.on(allocatePath, netJson(200, fullPlan))
        val error = netCaught { rig.api.allocate("2026-11-09", dryRun = false, skipCheck = false, taskIds = null) }
        assertEquals("internal", (error as? AuraError)?.serverCode)
        assertTrue(rig.server.calls(allocatePath).isEmpty())
    }

    @Test
    fun quickCheckRequiredThenSkipCheck() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, meWithSchedule))
        rig.server.on(allocatePath, netJson(409, quickCheckRequired), netJson(200, fullPlan))

        val error = netCaught { rig.api.allocate("2026-11-09", dryRun = false, skipCheck = false, taskIds = null) }
        val refused = assertNotNull(error as? AuraError.Server)
        assertEquals("quick_check_required", refused.serverCode)
        assertEquals(409, refused.status)

        val plan = rig.api.allocate("2026-11-09", dryRun = false, skipCheck = true, taskIds = null)
        assertEquals(3, plan.placed.size)
        assertEquals(listOf(false, true), rig.server.calls(allocatePath).map { it.bool("skipCheck") })
    }

    @Test
    fun dateInPastComesThrough() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, meWithSchedule))
        val answer = "{\"error\":{\"code\":\"date_in_past\",\"message\":\"That day has already gone.\",\"details\":null}}"
        rig.server.on(allocatePath, netJson(400, answer))
        val error = netCaught { rig.api.allocate("2026-11-08", dryRun = false, skipCheck = false, taskIds = null) }
        assertEquals(AuraError.Server("date_in_past", "That day has already gone.", 400), error)
    }

    @Test
    fun noPlanYetIsNull() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(planPath, netJson(404, "{\"error\":{\"code\":\"not_found\",\"message\":\"No plan for that day yet.\",\"details\":null}}"))
        assertNull(rig.api.plan("2026-11-09"))
        val call = rig.server.calls(planPath).first()
        assertEquals("GET", call.method)
        assertEquals("planner.stub.test", call.host)
        assertEquals("/v1/planner/plan/2026-11-09", call.url.encodedPath)
        assertTrue(rig.server.calls("/v1/me").isEmpty(), "Reading a plan needs no work hours")
    }

    @Test
    fun storedPlanIsRead() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(planPath, netJson(200, fullPlan))
        val plan = assertNotNull(rig.api.plan("2026-11-09"))
        assertEquals("2026-11-09", plan.date)
        assertEquals(3, plan.version)
        assertEquals("dueLater", plan.unplaced.first().reason)
    }

    @Test
    fun otherPlanErrorsAreThrown() = runBlocking<Unit> {
        val rig = NetRig()
        val answer = "{\"error\":{\"code\":\"dependency_unavailable\",\"message\":\"Calendar is not answering.\",\"details\":null}}"
        rig.server.on(planPath, netJson(503, answer))
        val error = netCaught { rig.api.plan("2026-11-09") }
        assertEquals(AuraError.Server("dependency_unavailable", "Calendar is not answering.", 503), error)
    }

    @Test
    fun tasksReadAutoAllocate() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = """
            {"tasks":[
              {"id":"task-report","title":"Quarterly report","status":"pending","dueAt":null,"hasTime":false,
               "plannedFor":"2026-11-09","scheduledAt":null,"completedAt":null,"estimateMinutes":90,"autoAllocate":true},
              {"id":"task-call","title":"Call the bank","status":"pending","dueAt":null,"hasTime":false,
               "plannedFor":"2026-11-09","scheduledAt":null,"completedAt":null}
            ],"deleted":[],"cursor":null}
        """.trimIndent()
        rig.server.on("/v1/tasks", netJson(200, sample))
        val tasks = rig.api.tasks()
        assertEquals(listOf(true, false), tasks.map { it.autoAllocate })
        assertEquals(TaskItem("task-report", "Quarterly report", autoAllocate = true), tasks[0])
    }
}
