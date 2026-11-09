import Foundation
import XCTest
@testable import AuraKit

final class PlanStubTests: XCTestCase {
    /// A Monday well ahead of the clock, so the plan never hits the past-date rule.
    private let monday = "2030-03-18"

    private func time(_ key: String, _ hour: Int, _ minute: Int = 0) throws -> Date {
        let day = try XCTUnwrap(DayKey.date(key))
        return try XCTUnwrap(DayKey.calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day))
    }

    private func nextDay(_ key: String) throws -> String {
        let day = try XCTUnwrap(DayKey.date(key))
        return DayKey.string(try XCTUnwrap(DayKey.calendar.date(byAdding: .day, value: 1, to: day)))
    }

    private func titles(_ plan: DayPlan, _ tasks: [TaskItem]) -> [String] {
        return plan.placed.map { block in
            block.title(taskTitle: tasks.first(where: { $0.id == block.taskId })?.title ?? "")
        }
    }

    func testTodaysDryRunFollowsTheRecoveryRhythm() async throws {
        let stub = StubAPI()
        let today = DayKey.string(Date())
        let plan = try await stub.allocate(date: today, dryRun: true, skipCheck: false, taskIds: nil)
        let tasks = try await stub.tasks()

        XCTAssertEqual(plan.date, today)
        XCTAssertEqual(plan.version, 1)
        XCTAssertEqual(titles(plan, tasks), ["Write the review (1/2)", "Write the review (2/2)", "Recovery", "Reply to Ana"])
        XCTAssertEqual(plan.placed.map { $0.taskId }, ["t3", "t3", nil, "t1"])
        XCTAssertEqual(plan.placed.map { $0.blockId }, ["plan-\(today)-t3-p1", "plan-\(today)-t3-p2", "plan-\(today)-t3-rest",
                                                        "plan-\(today)-t1"])
        XCTAssertEqual(plan.placed.map { $0.start }, [try time(today, 9), try time(today, 10, 45), try time(today, 11, 15),
                                                      try time(today, 14)])
        XCTAssertEqual(plan.placed.map { $0.end }, [try time(today, 10, 30), try time(today, 11, 15), try time(today, 11, 25),
                                                    try time(today, 14, 30)])
        for (earlier, later) in zip(plan.placed, plan.placed.dropFirst()) {
            XCTAssertLessThanOrEqual(earlier.end, later.start, "Blocks are in time order with no overlap")
        }
        XCTAssertEqual(plan.placed.map { $0.part }, [PlanPart(index: 1, of: 2), PlanPart(index: 2, of: 2), nil, nil])
        XCTAssertEqual(plan.placed.map { $0.kind }, ["focus", "focus", "recovery", "focus"])

        let rest = plan.placed[2]
        XCTAssertTrue(rest.isRecovery)
        XCTAssertNil(rest.taskId)
        XCTAssertNil(rest.workType)
        XCTAssertEqual(rest.demand, 0)
        XCTAssertEqual(rest.reason, "A short rest after deep work.")
        XCTAssertEqual(plan.placed[0].demand, 78)
        XCTAssertEqual(plan.placed[0].workType, "deepFocus")
        XCTAssertEqual(plan.placed[3].workType, "routine")

        let tomorrow = try nextDay(today)
        XCTAssertEqual(plan.unplaced, [UnplacedTask(taskId: "t2", reason: "dueLater", detail: "Planned for \(tomorrow).")])
        XCTAssertEqual(plan.warnings, PlanWarnings(committedOnRecovery: 1, recoveryMoved: 1))
        XCTAssertEqual(plan.basis.quickCheckAt, try time(today, 8, 40))
        XCTAssertEqual(plan.basis.attention, 71)
        XCTAssertEqual(plan.basis.sleepMinutes, 432)
        XCTAssertNil(plan.basis.sharpestHours)
        XCTAssertEqual(plan.basis.capacityOpening, 72)
        XCTAssertNil(plan.basis.workLimitMinutes)

        // A dry run writes nothing.
        let from = try time(today, 0)
        let to = try XCTUnwrap(DayKey.calendar.date(byAdding: .day, value: 1, to: from))
        let events = try await stub.events(from: from, to: to)
        XCTAssertFalse(events.contains { $0.id.hasPrefix("plan-") })
        XCTAssertFalse(events.contains { $0.title.hasPrefix("Write the review") || $0.title == "Reply to Ana" })
        let stored = try await stub.plan(date: today)
        XCTAssertNil(stored)
    }

    func testARealRunIsKeptAndShownOnTheCalendar() async throws {
        let stub = StubAPI()
        let first = try await stub.allocate(date: monday, dryRun: false, skipCheck: false, taskIds: nil)
        let stored = try await stub.plan(date: monday)
        XCTAssertEqual(stored, first)

        let from = try time(monday, 0)
        let to = try XCTUnwrap(DayKey.calendar.date(byAdding: .day, value: 1, to: from))
        let events = try await stub.events(from: from, to: to)
        let planned = events.filter { $0.id.hasPrefix("plan-") }
        XCTAssertEqual(planned.map { $0.title }, ["Write the review (1/2)", "Write the review (2/2)", "Recovery", "Reply to Ana"])
        XCTAssertEqual(planned.map { $0.kind }, ["focus", "focus", "recovery", "focus"])
        XCTAssertEqual(planned.map { $0.id }, ["plan-\(monday)-t3-p1", "plan-\(monday)-t3-p2", "plan-\(monday)-t3-rest",
                                               "plan-\(monday)-t1"])
        XCTAssertEqual(planned.map { $0.start }, first.placed.map { $0.start })
        XCTAssertEqual(planned.map { $0.end }, first.placed.map { $0.end })
        // The fixed events are still there.
        XCTAssertTrue(events.contains { $0.id == "e1" })
        XCTAssertTrue(events.contains { $0.id == "e2" })

        // Another day's window doesn't show them.
        let dayAfter = try time("2030-03-19", 0)
        let later = try await stub.events(from: dayAfter, to: dayAfter.addingTimeInterval(24 * 60 * 60))
        XCTAssertFalse(later.contains { $0.id.hasPrefix("plan-") })

        let second = try await stub.allocate(date: monday, dryRun: false, skipCheck: false, taskIds: nil)
        XCTAssertEqual(second.version, 2)
        let restored = try await stub.plan(date: monday)
        XCTAssertEqual(restored?.version, 2)

        // A dry run after it answers the next version but keeps the stored one.
        let preview = try await stub.allocate(date: monday, dryRun: true, skipCheck: false, taskIds: nil)
        XCTAssertEqual(preview.version, 3)
        let kept = try await stub.plan(date: monday)
        XCTAssertEqual(kept?.version, 2)
    }

    func testTodayNeedsTheQuickCheckUnlessSkipped() async throws {
        let stub = StubAPI(needsQuickCheck: true)
        let today = DayKey.string(Date())
        do {
            _ = try await stub.allocate(date: today, dryRun: true, skipCheck: false, taskIds: nil)
            XCTFail("Expected quick_check_required")
        } catch let error as AuraError {
            XCTAssertEqual(error.serverCode, "quick_check_required")
            XCTAssertEqual(error, AuraError.server(code: "quick_check_required",
                                                   message: "Take today's Quick check first, or send skipCheck to plan without it.",
                                                   status: 409))
        }

        let plan = try await stub.allocate(date: today, dryRun: true, skipCheck: true, taskIds: nil)
        XCTAssertNil(plan.basis.quickCheckAt)
        XCTAssertEqual(plan.placed.count, 4)

        // Only today asks for it.
        let later = try await stub.allocate(date: monday, dryRun: true, skipCheck: false, taskIds: nil)
        XCTAssertEqual(later.placed.count, 4)
    }

    func testAPastDateIsRefused() async throws {
        let stub = StubAPI()
        let yesterday = DayKey.string(try XCTUnwrap(DayKey.calendar.date(byAdding: .day, value: -1, to: Date())))
        do {
            _ = try await stub.allocate(date: yesterday, dryRun: true, skipCheck: true, taskIds: nil)
            XCTFail("Expected date_in_past")
        } catch let error as AuraError {
            XCTAssertEqual(error.serverCode, "date_in_past")
            XCTAssertEqual(error, AuraError.server(code: "date_in_past", message: "Plan today or a later day.", status: 400))
        }
    }

    func testNoScheduleGivesThePlainPlan() async throws {
        let stub = StubAPI(workSchedule: nil)
        let plan = try await stub.allocate(date: monday, dryRun: true, skipCheck: false, taskIds: nil)
        let tasks = try await stub.tasks()
        XCTAssertEqual(titles(plan, tasks), ["Write the review", "Reply to Ana"])
        XCTAssertFalse(plan.placed.contains { $0.isRecovery })
        XCTAssertTrue(plan.placed.allSatisfy { $0.part == nil })
        XCTAssertEqual(plan.placed.map { $0.start }, [try time(monday, 9), try time(monday, 11, 15)])
        XCTAssertEqual(plan.placed.map { $0.end }, [try time(monday, 11), try time(monday, 11, 45)])
        XCTAssertEqual(plan.warnings, PlanWarnings())
        XCTAssertNil(plan.warnings.note)
        XCTAssertEqual(plan.unplaced, [UnplacedTask(taskId: "t2", reason: "dueLater", detail: "Planned for 2030-03-19.")])
    }

    func testADayOffGivesThePlainPlan() async throws {
        let stub = StubAPI(workSchedule: .suggested)
        // 16 Mar 2030 is a Saturday.
        let plan = try await stub.allocate(date: "2030-03-16", dryRun: true, skipCheck: false, taskIds: nil)
        XCTAssertEqual(plan.placed.count, 2)
        XCTAssertFalse(plan.placed.contains { $0.isRecovery })
        XCTAssertEqual(plan.warnings, PlanWarnings())
    }

    func testTaskIdsNarrowThePlan() async throws {
        let stub = StubAPI()
        let plan = try await stub.allocate(date: monday, dryRun: true, skipCheck: false, taskIds: ["t1"])
        let tasks = try await stub.tasks()
        XCTAssertEqual(titles(plan, tasks), ["Reply to Ana"])
        XCTAssertEqual(plan.placed.map { $0.taskId }, ["t1"])
        XCTAssertFalse(plan.placed.contains { $0.isRecovery })
        XCTAssertEqual(plan.unplaced, [])
    }

    func testAllTasksAreForFindATime() async throws {
        let tasks = try await StubAPI().tasks()
        XCTAssertEqual(tasks.map { $0.id }, ["t1", "t2", "t3"])
        XCTAssertEqual(tasks.map { $0.title }, ["Reply to Ana", "Book the dentist", "Write the review"])
        XCTAssertTrue(tasks.allSatisfy { $0.autoAllocate })
    }

    func testBlockTitles() {
        let now = Date()
        let focus = PlanBlock(taskId: "t3", blockId: "b1", start: now, end: now, demand: 78, reason: "")
        XCTAssertEqual(focus.title(taskTitle: "Write the review"), "Write the review")
        let piece = PlanBlock(taskId: "t3", blockId: "b2", start: now, end: now, demand: 78, reason: "",
                              part: PlanPart(index: 2, of: 3))
        XCTAssertEqual(piece.title(taskTitle: "Write the review"), "Write the review (2/3)")
        let rest = PlanBlock(taskId: nil, blockId: "b3", start: now, end: now, demand: 0, reason: "", kind: "recovery")
        XCTAssertTrue(rest.isRecovery)
        XCTAssertEqual(rest.title(taskTitle: ""), "Recovery")
        XCTAssertFalse(focus.isRecovery)
    }

    func testWarningNotes() {
        XCTAssertNil(PlanWarnings(committedOnRecovery: 0, recoveryMoved: 0).note)
        XCTAssertEqual(PlanWarnings(committedOnRecovery: 1, recoveryMoved: 1).note,
                       "1 of your blocks was on recovery time; the recovery moved to free time.")
        XCTAssertEqual(PlanWarnings(committedOnRecovery: 2, recoveryMoved: 1).note,
                       "2 of your blocks were on recovery time; 1 moved to free time.")
        XCTAssertEqual(PlanWarnings(committedOnRecovery: 2, recoveryMoved: 0).note,
                       "2 of your blocks were on recovery time; no free time to move the recovery.")
        XCTAssertEqual(PlanWarnings(committedOnRecovery: 2, recoveryMoved: 2).note,
                       "2 of your blocks were on recovery time; the recoveries moved to free time.")
    }
}
