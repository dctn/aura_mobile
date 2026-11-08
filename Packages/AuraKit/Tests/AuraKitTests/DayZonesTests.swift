import Foundation
import XCTest
@testable import AuraKit

private let staleProfile = "{\"error\":{\"code\":\"precondition_failed\",\"message\":\"The profile changed since you read it.\"}}"
private let emptyZones = "{\"days\":[]}"

private func instant(_ text: String) throws -> Date {
    return try XCTUnwrap(ISO8601DateFormatter().date(from: text))
}

final class DayZonesLiveTests: XCTestCase {
    func testZonesDecode() async throws {
        let rig = TestRig()
        let sample = """
        {"days":[
          {"date":"2026-11-09","status":"ready","score":61.6,"level":"steady","heavyMinutes":120,
           "reason":"Your capacity score is 62, so about 2 h of heavy work today, first thing.",
           "zones":[
             {"kind":"heavyWork","start":"2026-11-09T03:30:00.000Z","end":"2026-11-09T05:00:00.000Z"},
             {"kind":"shortRecovery","start":"2026-11-09T05:00:00Z","end":"2026-11-09T05:15:00Z"},
             {"kind":"deepWork","start":"2026-11-09T05:15:00Z","end":"2026-11-09T05:45:00Z"},
             {"kind":"lightWork","start":"later","end":"2026-11-09T07:30:00Z"},
             {"kind":"heavyRecovery","start":"2026-11-09T07:30:00.000Z","end":"2026-11-09T08:30:00.000Z"},
             {"kind":"lightWork","start":"2026-11-09T08:30:00Z","end":"2026-11-09T12:30:00Z"}
           ]},
          {"date":"2026-11-10","status":"noScore","score":null,"level":null,"heavyMinutes":0,"reason":null,"zones":[]},
          {"date":"2026-11-11","status":"holiday","score":null,"level":null,"heavyMinutes":0,"reason":null,"zones":[]}
        ]}
        """
        rig.server.on("/v1/planner/zones", [.json(200, sample)])
        let days = try await rig.api.dayZones(from: "2026-11-09", to: "2026-11-11")
        XCTAssertEqual(days.count, 3)

        let ready = days[0]
        XCTAssertEqual(ready.date, "2026-11-09")
        XCTAssertEqual(ready.status, .ready)
        XCTAssertEqual(ready.score, 62)
        XCTAssertEqual(ready.level, "steady")
        XCTAssertEqual(ready.heavyMinutes, 120)
        XCTAssertEqual(ready.reason, "Your capacity score is 62, so about 2 h of heavy work today, first thing.")
        let expected = [
            Zone(kind: .heavyWork, start: try instant("2026-11-09T03:30:00Z"), end: try instant("2026-11-09T05:00:00Z")),
            Zone(kind: .shortRecovery, start: try instant("2026-11-09T05:00:00Z"), end: try instant("2026-11-09T05:15:00Z")),
            Zone(kind: .heavyRecovery, start: try instant("2026-11-09T07:30:00Z"), end: try instant("2026-11-09T08:30:00Z")),
            Zone(kind: .lightWork, start: try instant("2026-11-09T08:30:00Z"), end: try instant("2026-11-09T12:30:00Z")),
        ]
        XCTAssertEqual(ready.zones, expected)

        XCTAssertEqual(days[1], ZoneDay(date: "2026-11-10", status: .noScore))
        XCTAssertEqual(days[2], ZoneDay(date: "2026-11-11", status: .unknown))
    }

    func testMissingFieldsStillRead() async throws {
        let rig = TestRig()
        rig.server.on("/v1/planner/zones", [.json(200, "{\"days\":[{\"date\":\"2026-11-09\",\"status\":\"needsSchedule\"}]}")])
        let days = try await rig.api.dayZones(from: "2026-11-09", to: "2026-11-09")
        XCTAssertEqual(days, [ZoneDay(date: "2026-11-09", status: .needsSchedule)])
    }

    func testAsksThePlannerForTheRange() async throws {
        let rig = TestRig()
        rig.server.on("/v1/planner/zones", [.json(200, emptyZones)])
        _ = try await rig.api.dayZones(from: "2026-11-09", to: "2026-11-15")
        let call = try XCTUnwrap(rig.server.calls("/v1/planner/zones").first)
        XCTAssertEqual(call.request.httpMethod, "GET")
        XCTAssertEqual(call.request.url?.host, "planner.stub.test")
        XCTAssertEqual(call.query, ["from": "2026-11-09", "to": "2026-11-15"])
    }

    func testARangeOver31DaysIsAskedInPieces() async throws {
        let rig = TestRig()
        rig.server.on("/v1/planner/zones", [.json(200, emptyZones)])
        _ = try await rig.api.dayZones(from: "2026-11-01", to: "2026-12-15")
        let calls = rig.server.calls("/v1/planner/zones")
        XCTAssertEqual(calls.map { $0.query["from"] ?? "" }, ["2026-11-01", "2026-12-02"])
        XCTAssertEqual(calls.map { $0.query["to"] ?? "" }, ["2026-12-01", "2026-12-15"])
    }
}

final class WorkScheduleLiveTests: XCTestCase {
    private let unset = "{\"sub\":\"sub-1\",\"timezone\":null,\"workSchedule\":null,\"version\":7}"
    private let hasSchedule = """
    {"sub":"sub-1","timezone":"Asia/Kolkata","version":8,
     "workSchedule":{"workdayStart":"08:30","workdayEnd":"17:00","lunchStart":"12:30","lunchMinutes":45,"workDays":[1,2,3,4,5]}}
    """

    func testNoScheduleIsNil() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, unset)])
        let schedule = try await rig.api.workSchedule()
        XCTAssertNil(schedule)
        XCTAssertEqual(rig.server.calls("/v1/me").first?.request.url?.host, "identity.stub.test")
    }

    func testAMissingScheduleIsNil() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, "{\"sub\":\"sub-1\",\"timezone\":null,\"version\":7}")])
        let schedule = try await rig.api.workSchedule()
        XCTAssertNil(schedule)
    }

    func testReadsTheSchedule() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, hasSchedule)])
        let schedule = try await rig.api.workSchedule()
        XCTAssertEqual(schedule, WorkSchedule(workdayStart: "08:30", workdayEnd: "17:00", lunchStart: "12:30",
                                              lunchMinutes: 45, workDays: [1, 2, 3, 4, 5]))
    }

    func testAScheduleItCantReadLeavesTheTimeZoneWorking() async throws {
        let rig = TestRig()
        let odd = "{\"sub\":\"sub-1\",\"timezone\":null,\"workSchedule\":{\"workDays\":\"weekdays\"},\"version\":7}"
        rig.server.on("/v1/me", [.json(200, odd)])
        rig.server.on("/v1/me/profile", [.json(200, odd)])
        let schedule = try await rig.api.workSchedule()
        XCTAssertNil(schedule)
        try await rig.api.setTimeZoneIfMissing("Europe/Paris")
        XCTAssertEqual(rig.server.calls("/v1/me/profile").first?.header("If-Match"), "\"v7\"")
    }

    func testSendsOnlyTheScheduleWithTheVersionRead() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, unset)])
        rig.server.on("/v1/me/profile", [.json(200, hasSchedule)])
        let schedule = WorkSchedule(workdayStart: "08:30", workdayEnd: "17:00", lunchStart: "12:30", lunchMinutes: 45,
                                    workDays: [1, 2, 3, 4, 5])
        try await rig.api.setWorkSchedule(schedule)
        let put = try XCTUnwrap(rig.server.calls("/v1/me/profile").first)
        XCTAssertEqual(put.request.httpMethod, "PUT")
        XCTAssertEqual(put.request.url?.host, "identity.stub.test")
        XCTAssertEqual(put.header("If-Match"), "\"v7\"")
        XCTAssertEqual(Array(put.body.keys), ["workSchedule"])
        let sent = try XCTUnwrap(put.body["workSchedule"] as? [String: Any])
        XCTAssertEqual(sent.keys.sorted(), ["lunchMinutes", "lunchStart", "workDays", "workdayEnd", "workdayStart"])
        XCTAssertEqual(sent["workdayStart"] as? String, "08:30")
        XCTAssertEqual(sent["workdayEnd"] as? String, "17:00")
        XCTAssertEqual(sent["lunchStart"] as? String, "12:30")
        XCTAssertEqual(sent["lunchMinutes"] as? Int, 45)
        XCTAssertEqual(sent["workDays"] as? [Int], [1, 2, 3, 4, 5])
        XCTAssertEqual(rig.server.calls("/v1/me").first?.request.httpMethod, "GET")
    }

    func testAStaleVersionIsReadAgainOnce() async throws {
        let rig = TestRig()
        let newer = "{\"sub\":\"sub-1\",\"timezone\":null,\"workSchedule\":null,\"version\":9}"
        rig.server.on("/v1/me", [.json(200, unset), .json(200, newer)])
        rig.server.on("/v1/me/profile", [.json(412, staleProfile), .json(200, hasSchedule)])
        try await rig.api.setWorkSchedule(.suggested)
        let puts = rig.server.calls("/v1/me/profile")
        XCTAssertEqual(puts.map { $0.header("If-Match") ?? "" }, ["\"v7\"", "\"v9\""])
        XCTAssertEqual(rig.server.calls("/v1/me").count, 2)
        let sent = try XCTUnwrap(puts.last?.body["workSchedule"] as? [String: Any])
        XCTAssertEqual(sent["workDays"] as? [Int], [1, 2, 3, 4, 5])
    }

    func testASecondStaleVersionThrows() async {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, unset)])
        rig.server.on("/v1/me/profile", [.json(412, staleProfile)])
        let error = await caught { try await rig.api.setWorkSchedule(.suggested) }
        XCTAssertEqual((error as? AuraError)?.serverCode, "precondition_failed")
        XCTAssertEqual(rig.server.calls("/v1/me/profile").count, 2)
    }
}

final class WorkScheduleRuleTests: XCTestCase {
    private func changed(_ change: (inout WorkSchedule) -> Void) -> WorkSchedule {
        var schedule = WorkSchedule.suggested
        change(&schedule)
        return schedule
    }

    func testSuggestedIsFine() {
        XCTAssertNil(WorkSchedule.suggested.problem)
        XCTAssertNil(StubAPI.defaultSchedule.problem)
    }

    func testEveryTimeMustBeAClockTime() {
        XCTAssertEqual(changed { $0.workdayStart = "9:00" }.problem, "Choose every time.")
        XCTAssertEqual(changed { $0.workdayEnd = "24:00" }.problem, "Choose every time.")
        XCTAssertEqual(changed { $0.lunchStart = "ab:cd" }.problem, "Choose every time.")
        XCTAssertEqual(changed { $0.lunchStart = "" }.problem, "Choose every time.")
    }

    func testWorkMustEndAfterItStarts() {
        XCTAssertEqual(changed { $0.workdayEnd = "09:00" }.problem, "Work must end after it starts.")
        XCTAssertEqual(changed { $0.workdayEnd = "08:00" }.problem, "Work must end after it starts.")
    }

    func testLunchMustFitInsideWorkHours() {
        XCTAssertEqual(changed { $0.lunchStart = "08:30" }.problem, "Lunch must fit inside your work hours.")
        XCTAssertEqual(changed { $0.lunchStart = "17:30" }.problem, "Lunch must fit inside your work hours.")
        XCTAssertNil(changed { $0.lunchStart = "17:00" }.problem)
    }

    func testLunchLength() {
        XCTAssertEqual(changed { $0.lunchMinutes = 241 }.problem, "Lunch can be at most 4 hours.")
        XCTAssertEqual(changed { $0.lunchMinutes = -1 }.problem, "Lunch can't have a negative length.")
        XCTAssertNil(changed { $0.lunchMinutes = 0 }.problem)
        XCTAssertNil(changed { $0.lunchStart = "10:00"; $0.lunchMinutes = 240 }.problem)
    }

    func testWorkDays() {
        XCTAssertEqual(changed { $0.workDays = [] }.problem, "Choose at least one work day.")
        XCTAssertEqual(changed { $0.workDays = [1, 1] }.problem, "Choose each work day once.")
        XCTAssertEqual(changed { $0.workDays = [0, 1] }.problem, "Choose each work day once.")
        XCTAssertEqual(changed { $0.workDays = [8] }.problem, "Choose each work day once.")
    }

    func testClockHelpers() {
        XCTAssertEqual(WorkSchedule.minutes("09:30"), 570)
        XCTAssertEqual(WorkSchedule.minutes("23:59"), 1439)
        XCTAssertNil(WorkSchedule.minutes("+9:30"))
        XCTAssertNil(WorkSchedule.minutes("09:30:00"))
        XCTAssertEqual(WorkSchedule.clock(570), "09:30")
        XCTAssertEqual(WorkSchedule.clock(0), "00:00")
    }
}

final class DayZonesStubTests: XCTestCase {
    private func time(_ key: String, _ hour: Int, _ minute: Int = 0) throws -> Date {
        let day = try XCTUnwrap(DayKey.date(key))
        return try XCTUnwrap(DayKey.calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day))
    }

    func testDefaultScheduleGivesAReadyToday() async throws {
        let stub = StubAPI()
        let today = DayKey.string(Date())
        let days = try await stub.dayZones(from: today, to: today)
        XCTAssertEqual(days.count, 1)
        let day = try XCTUnwrap(days.first)
        XCTAssertEqual(day.date, today)
        XCTAssertEqual(day.status, .ready)
        XCTAssertEqual(day.score, 62)
        XCTAssertEqual(day.level, "steady")
        XCTAssertEqual(day.heavyMinutes, 120)
        XCTAssertEqual(day.reason, "Your capacity score is 62, so about 2 h of heavy work today, first thing.")

        let zones = day.zones
        XCTAssertEqual(zones.first?.start, try time(today, 9))
        XCTAssertEqual(zones.last?.end, try time(today, 18))
        for (earlier, later) in zip(zones, zones.dropFirst()) {
            XCTAssertEqual(earlier.end, later.start, "Zones touch, with no gap and no overlap")
            XCTAssertLessThan(earlier.start, earlier.end)
        }
        let heavy = zones.filter { $0.kind == .heavyWork }.reduce(0.0) { $0 + $1.end.timeIntervalSince($1.start) }
        XCTAssertEqual(heavy, 120 * 60)
        XCTAssertTrue(zones.contains(Zone(kind: .heavyRecovery, start: try time(today, 13), end: try time(today, 14))))
        XCTAssertEqual(zones.map { $0.kind }, [.heavyWork, .shortRecovery, .heavyWork, .lightWork, .heavyRecovery,
                                               .lightWork, .shortRecovery, .lightWork])
        XCTAssertEqual(zones.map { $0.start }, [try time(today, 9), try time(today, 10, 30), try time(today, 10, 45),
                                                try time(today, 11, 15), try time(today, 13), try time(today, 14),
                                                try time(today, 15, 45), try time(today, 16)])
    }

    func testOneDayPerDate() async throws {
        let days = try await StubAPI().dayZones(from: "2026-11-09", to: "2026-11-15")
        XCTAssertEqual(days.map { $0.date }, ["2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13",
                                              "2026-11-14", "2026-11-15"])
        XCTAssertTrue(days.allSatisfy { $0.status == .ready })
    }

    func testNoScheduleNeedsOne() async throws {
        let stub = StubAPI(workSchedule: nil)
        let schedule = try await stub.workSchedule()
        XCTAssertNil(schedule)
        let today = DayKey.string(Date())
        let days = try await stub.dayZones(from: today, to: today)
        XCTAssertEqual(days, [ZoneDay(date: today, status: .needsSchedule)])
    }

    func testWeekendIsOffAfterSettingWeekdays() async throws {
        let stub = StubAPI(workSchedule: nil)
        try await stub.setWorkSchedule(.suggested)
        let schedule = try await stub.workSchedule()
        XCTAssertEqual(schedule, WorkSchedule.suggested)
        // 13 Nov 2026 is a Friday.
        let days = try await stub.dayZones(from: "2026-11-13", to: "2026-11-16")
        XCTAssertEqual(days.map { $0.status }, [.ready, .dayOff, .dayOff, .ready])
        XCTAssertEqual(days[1], ZoneDay(date: "2026-11-14", status: .dayOff))
        XCTAssertEqual(days[2], ZoneDay(date: "2026-11-15", status: .dayOff))
    }

    func testNoLunchHasNoHeavyRecovery() async throws {
        let stub = StubAPI(workSchedule: WorkSchedule(workdayStart: "09:00", workdayEnd: "12:00", lunchStart: "12:00",
                                                      lunchMinutes: 0, workDays: [1, 2, 3, 4, 5, 6, 7]))
        let days = try await stub.dayZones(from: "2026-11-09", to: "2026-11-09")
        let zones = try XCTUnwrap(days.first?.zones)
        XCTAssertFalse(zones.contains { $0.kind == .heavyRecovery })
        XCTAssertEqual(zones.first?.start, try time("2026-11-09", 9))
        XCTAssertEqual(zones.last?.end, try time("2026-11-09", 12))
        XCTAssertEqual(days.first?.heavyMinutes, 120)
    }
}
