import Foundation
import XCTest
@testable import AuraKit

private let allocatePath = "/v1/planner/allocate"
private let planPath = "/v1/planner/plan/2026-11-09"

private let meWithSchedule = """
{"sub":"sub-1","timezone":"Asia/Kolkata","version":8,
 "workSchedule":{"workdayStart":"08:30","workdayEnd":"17:00","lunchStart":"12:30","lunchMinutes":45,"workDays":[1,2,3,4,5]}}
"""
private let meWithoutSchedule = "{\"sub\":\"sub-1\",\"timezone\":\"Asia/Kolkata\",\"workSchedule\":null,\"version\":7}"

/// A plan as the Planner sends it with the recovery rhythm: a task split in two around a recovery zone, the break
/// after its first piece, one task left for later.
private let fullPlan = """
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
"""

/// A plan from a Planner deployed before the recovery rhythm: no kind, no part, no warnings.
private let olderPlan = """
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
"""

private let quickCheckRequired = """
{"error":{"code":"quick_check_required","message":"Take today's Quick check first, or plan without it.","details":null}}
"""

private func planInstant(_ text: String) throws -> Date {
    return try XCTUnwrap(ISO8601DateFormatter().date(from: text))
}

final class PlanLiveTests: XCTestCase {
    // MARK: Decoding

    func testFullPlanDecodes() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, meWithSchedule)])
        rig.server.on(allocatePath, [.json(200, fullPlan)])
        let plan = try await rig.api.allocate(date: "2026-11-09", dryRun: false, skipCheck: false, taskIds: nil)

        let basis = PlanBasis(quickCheckAt: try planInstant("2026-11-09T02:58:40Z"), attention: 96, sleepMinutes: 412,
                              sharpestHours: SharpestHours(startHour: 9, endHour: 11), capacityOpening: 78.5,
                              workLimitMinutes: 300)
        let placed = [
            PlanBlock(taskId: "task-report", blockId: "plan-2026-11-09-task-report-p1",
                      start: try planInstant("2026-11-09T03:30:00Z"), end: try planInstant("2026-11-09T04:30:00Z"),
                      demand: 72, workType: "deepFocus", reason: "Inside your sharpest hours.", kind: "focus",
                      part: PlanPart(index: 1, of: 2)),
            PlanBlock(taskId: nil, blockId: "plan-2026-11-09-task-report-rest",
                      start: try planInstant("2026-11-09T04:30:00Z"), end: try planInstant("2026-11-09T04:40:00Z"),
                      demand: 0, workType: nil, reason: "A short rest after deep work.", kind: "recovery", part: nil),
            PlanBlock(taskId: "task-report", blockId: "plan-2026-11-09-task-report-p2",
                      start: try planInstant("2026-11-09T05:15:00Z"), end: try planInstant("2026-11-09T05:45:00Z"),
                      demand: 72, workType: "deepFocus", reason: "Inside your sharpest hours.", kind: "focus",
                      part: PlanPart(index: 2, of: 2)),
        ]
        let expected = DayPlan(date: "2026-11-09", generatedAt: try planInstant("2026-11-09T03:05:12Z"), basis: basis,
                               placed: placed,
                               unplaced: [UnplacedTask(taskId: "task-slides", reason: "dueLater",
                                                       detail: "Planned for 2026-11-12.")],
                               warnings: PlanWarnings(committedOnRecovery: 2, recoveryMoved: 1), version: 3)
        XCTAssertEqual(plan, expected)
        XCTAssertTrue(plan.placed[1].isRecovery)
        XCTAssertEqual(plan.placed[0].title(taskTitle: "Quarterly report"), "Quarterly report (1/2)")
    }

    func testAnOlderPlanReadsWithDefaults() async throws {
        let rig = TestRig()
        rig.server.on(planPath, [.json(200, olderPlan)])
        let found = try await rig.api.plan(date: "2026-11-09")
        let plan = try XCTUnwrap(found)
        XCTAssertEqual(plan.basis, PlanBasis())
        XCTAssertEqual(plan.placed.count, 1)
        XCTAssertEqual(plan.placed.first?.kind, "focus")
        XCTAssertNil(plan.placed.first?.part)
        XCTAssertEqual(plan.warnings, PlanWarnings(committedOnRecovery: 0, recoveryMoved: 0))
        XCTAssertNil(plan.warnings.note)
        XCTAssertEqual(plan.version, 1)
    }

    /// A block with a time it can't read is left out, an unknown reason is kept as sent, and whole numbers may come
    /// as decimals.
    func testUnreadablePiecesAreSkipped() async throws {
        let rig = TestRig()
        let sample = """
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
        """
        rig.server.on(planPath, [.json(200, sample)])
        let found = try await rig.api.plan(date: "2026-11-09")
        let plan = try XCTUnwrap(found)
        XCTAssertEqual(plan.placed.map { $0.taskId ?? "" }, ["early", "late"], "Unreadable dropped, the rest in time order")
        XCTAssertEqual(plan.placed.first?.part, PlanPart(index: 1, of: 2))
        XCTAssertEqual(plan.basis.sharpestHours, SharpestHours(startHour: 9, endHour: 11))
        XCTAssertEqual(plan.unplaced, [UnplacedTask(taskId: "t-new", reason: "calendarFull", detail: "No room on the calendar.")])
        XCTAssertEqual(plan.warnings, PlanWarnings(committedOnRecovery: 1, recoveryMoved: 0))
        XCTAssertEqual(plan.version, 4)
    }

    func testAnUnreadableGeneratedAtIsABadResponse() async {
        let rig = TestRig()
        let sample = """
        {"plan":{"date":"2026-11-09","generatedAt":"this morning","basis":{},"placed":[],"unplaced":[],"version":1}}
        """
        rig.server.on(planPath, [.json(200, sample)])
        let error = await caught { _ = try await rig.api.plan(date: "2026-11-09") }
        XCTAssertEqual((error as? AuraError)?.serverCode, "bad_response")
    }

    // MARK: Allocate request

    func testAllocateSendsTheProfilesWorkHours() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, meWithSchedule)])
        rig.server.on(allocatePath, [.json(200, fullPlan)])
        _ = try await rig.api.allocate(date: "2026-11-09", dryRun: true, skipCheck: false, taskIds: nil)

        XCTAssertEqual(rig.server.allCalls.map { $0.key }, ["/v1/me", allocatePath], "Work hours are read first")
        let me = try XCTUnwrap(rig.server.calls("/v1/me").first)
        XCTAssertEqual(me.request.httpMethod, "GET")
        XCTAssertEqual(me.request.url?.host, "identity.stub.test")

        let call = try XCTUnwrap(rig.server.calls(allocatePath).first)
        XCTAssertEqual(call.request.httpMethod, "POST")
        XCTAssertEqual(call.request.url?.host, "planner.stub.test")
        XCTAssertEqual(call.body.keys.sorted(), ["date", "dryRun", "skipCheck", "workdayEnd", "workdayStart"])
        XCTAssertEqual(call.body["date"] as? String, "2026-11-09")
        XCTAssertEqual(call.body["dryRun"] as? Bool, true)
        XCTAssertEqual(call.body["skipCheck"] as? Bool, false)
        XCTAssertEqual(call.body["workdayStart"] as? String, "08:30")
        XCTAssertEqual(call.body["workdayEnd"] as? String, "17:00")
        XCTAssertNil(call.body["taskIds"])
    }

    func testAllocateWithoutWorkHoursLeavesThemOut() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, meWithoutSchedule)])
        rig.server.on(allocatePath, [.json(200, fullPlan)])
        _ = try await rig.api.allocate(date: "2026-11-09", dryRun: false, skipCheck: false, taskIds: nil)
        let call = try XCTUnwrap(rig.server.calls(allocatePath).first)
        XCTAssertEqual(call.body.keys.sorted(), ["date", "dryRun", "skipCheck"])
        XCTAssertNil(call.body["workdayStart"])
        XCTAssertNil(call.body["workdayEnd"])
    }

    func testAllocateSendsTheTaskIdsAsked() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, meWithoutSchedule)])
        rig.server.on(allocatePath, [.json(200, fullPlan)])
        _ = try await rig.api.allocate(date: "2026-11-09", dryRun: false, skipCheck: false, taskIds: ["a", "b"])
        let call = try XCTUnwrap(rig.server.calls(allocatePath).first)
        XCTAssertEqual(call.body.keys.sorted(), ["date", "dryRun", "skipCheck", "taskIds"])
        XCTAssertEqual(call.body["taskIds"] as? [String], ["a", "b"])
    }

    /// Planning in the Planner's default hours when the person set others would put blocks in the wrong place.
    func testAFailedProfileReadStopsTheAllocate() async {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(500, "{\"error\":{\"code\":\"internal\",\"message\":\"Try again.\"}}")])
        rig.server.on(allocatePath, [.json(200, fullPlan)])
        let error = await caught {
            _ = try await rig.api.allocate(date: "2026-11-09", dryRun: false, skipCheck: false, taskIds: nil)
        }
        XCTAssertEqual((error as? AuraError)?.serverCode, "internal")
        XCTAssertTrue(rig.server.calls(allocatePath).isEmpty)
    }

    // MARK: Allocate errors

    func testQuickCheckRequiredThenSkipCheck() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, meWithSchedule)])
        rig.server.on(allocatePath, [.json(409, quickCheckRequired), .json(200, fullPlan)])

        let error = await caught {
            _ = try await rig.api.allocate(date: "2026-11-09", dryRun: false, skipCheck: false, taskIds: nil)
        }
        let refused = try XCTUnwrap(error as? AuraError)
        XCTAssertEqual(refused.serverCode, "quick_check_required")
        guard case .server(_, _, let status) = refused else { return XCTFail("Expected a server error") }
        XCTAssertEqual(status, 409)

        let plan = try await rig.api.allocate(date: "2026-11-09", dryRun: false, skipCheck: true, taskIds: nil)
        XCTAssertEqual(plan.placed.count, 3)
        let bodies = rig.server.calls(allocatePath).map { $0.body["skipCheck"] as? Bool }
        XCTAssertEqual(bodies, [false, true])
    }

    func testDateInPastComesThrough() async {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, meWithSchedule)])
        let answer = "{\"error\":{\"code\":\"date_in_past\",\"message\":\"That day has already gone.\",\"details\":null}}"
        rig.server.on(allocatePath, [.json(400, answer)])
        let error = await caught {
            _ = try await rig.api.allocate(date: "2026-11-08", dryRun: false, skipCheck: false, taskIds: nil)
        }
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "date_in_past", message: "That day has already gone.",
                                                             status: 400))
    }

    // MARK: Stored plan

    func testNoPlanYetIsNil() async throws {
        let rig = TestRig()
        rig.server.on(planPath, [.json(404, "{\"error\":{\"code\":\"not_found\",\"message\":\"No plan for that day yet.\",\"details\":null}}")])
        let plan = try await rig.api.plan(date: "2026-11-09")
        XCTAssertNil(plan)
        let call = try XCTUnwrap(rig.server.calls(planPath).first)
        XCTAssertEqual(call.request.httpMethod, "GET")
        XCTAssertEqual(call.request.url?.host, "planner.stub.test")
        XCTAssertEqual(call.request.url?.path, "/v1/planner/plan/2026-11-09")
        XCTAssertTrue(rig.server.calls("/v1/me").isEmpty, "Reading a plan needs no work hours")
    }

    func testStoredPlanIsRead() async throws {
        let rig = TestRig()
        rig.server.on(planPath, [.json(200, fullPlan)])
        let found = try await rig.api.plan(date: "2026-11-09")
        let plan = try XCTUnwrap(found)
        XCTAssertEqual(plan.date, "2026-11-09")
        XCTAssertEqual(plan.version, 3)
        XCTAssertEqual(plan.unplaced.first?.reason, "dueLater")
    }

    func testOtherPlanErrorsAreThrown() async {
        let rig = TestRig()
        let answer = "{\"error\":{\"code\":\"dependency_unavailable\",\"message\":\"Calendar is not answering.\",\"details\":null}}"
        rig.server.on(planPath, [.json(503, answer)])
        let error = await caught { _ = try await rig.api.plan(date: "2026-11-09") }
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "dependency_unavailable",
                                                             message: "Calendar is not answering.", status: 503))
    }

    // MARK: Tasks

    func testTasksReadAutoAllocate() async throws {
        let rig = TestRig()
        let sample = """
        {"tasks":[
          {"id":"task-report","title":"Quarterly report","status":"pending","dueAt":null,"hasTime":false,
           "plannedFor":"2026-11-09","scheduledAt":null,"completedAt":null,"estimateMinutes":90,"autoAllocate":true},
          {"id":"task-call","title":"Call the bank","status":"pending","dueAt":null,"hasTime":false,
           "plannedFor":"2026-11-09","scheduledAt":null,"completedAt":null}
        ],"deleted":[],"cursor":null}
        """
        rig.server.on("/v1/tasks", [.json(200, sample)])
        let tasks = try await rig.api.tasks()
        XCTAssertEqual(tasks.map { $0.autoAllocate }, [true, false])
        XCTAssertEqual(tasks[0], TaskItem(id: "task-report", title: "Quarterly report", autoAllocate: true))
    }
}
