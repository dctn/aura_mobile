import Foundation
import XCTest
@testable import AuraKit

private let startedOK = "{\"session\":{\"id\":\"one\"},\"joined\":false}"
private let ratedSession = """
{"session":{"id":"s-1","kind":"focus","mode":"timer","state":"ended","title":"Deep work",\
"startedAt":"2030-03-17T09:00:00.000Z","endedAt":"2030-03-17T09:52:00.000Z","minutes":52,"activeMinutes":50,\
"outcome":"completed","recoveryType":null,"difficulty":"hard",\
"capacity":{"before":80,"after":79.2,"cost":0.8,"charged":true}}}
"""
private let loggedSession = """
{"session":{"id":"log-1","kind":"recovery","mode":"timer","state":"ended","title":null,\
"startedAt":"2030-03-17T12:00:00.000Z","endedAt":"2030-03-17T12:20:00.000Z","minutes":20,"activeMinutes":20,\
"outcome":"logged","recoveryType":"walk","difficulty":null,"capacity":null},"created":true}
"""
private let notFocus = "{\"error\":{\"code\":\"not_focus_session\",\"message\":\"Only a focus session is rated.\"}}"
private let overlaps = """
{"error":{"code":"log_overlaps","message":"That time overlaps another session.","details":{"sessionId":"s-1"}}}
"""
private let consentNeeded = "{\"error\":{\"code\":\"consent_required\",\"message\":\"Health data consent is needed.\"}}"
private let recoveryOptionsAnswer = """
{"options":[\
{"id":"nap","title":"Nap","minutes":20,"maxPoints":10,"fadesAfterMinutes":120,"note":"A nap."},\
{"id":"walk","title":"Walk","minutes":20,"maxPoints":9,"fadesAfterMinutes":50,"note":"A walk."},\
{"id":"rest","title":"Rest","minutes":20,"maxPoints":5,"fadesAfterMinutes":40,"note":"Rest."},\
{"id":"outside","title":"Time outside","minutes":20,"maxPoints":9,"fadesAfterMinutes":50,"note":"Outside."},\
{"id":"social","title":"Talk with someone","minutes":20,"maxPoints":5,"fadesAfterMinutes":40,"note":"Talk."},\
{"id":"break","title":"Proper break","minutes":20,"maxPoints":2.5,"fadesAfterMinutes":0,"note":"A break."}]}
"""

/// Today at that time on the phone's calendar, as the stub builds its times.
private func today(_ hour: Int, _ minute: Int) -> Date {
    let now = Date()
    return DayKey.calendar.date(bySettingHour: hour, minute: minute, second: 0, of: now) ?? now
}

final class BetaSessionLiveTests: XCTestCase {
    func testStartSendsRecoveryTypeOnlyForARecoverySession() async throws {
        let rig = TestRig()
        rig.server.on("/v1/sessions", [.json(201, startedOK)])
        _ = try await rig.api.startSession(id: "a", kind: "focus", mode: "timer", plannedMinutes: 25, title: "Work",
                                           recoveryType: .walk)
        _ = try await rig.api.startSession(id: "b", kind: "recovery", mode: "timer", plannedMinutes: 20, title: "Nap",
                                           recoveryType: .nap)
        _ = try await rig.api.startSession(id: "c", kind: "recovery", mode: "timer", plannedMinutes: 20, title: "Rest",
                                           recoveryType: nil)
        let calls = rig.server.calls("/v1/sessions")
        XCTAssertEqual(calls.count, 3)
        XCTAssertFalse(calls[0].body.keys.contains("recoveryType"))
        XCTAssertEqual(calls[1].body["recoveryType"] as? String, "nap")
        XCTAssertFalse(calls[2].body.keys.contains("recoveryType"))
    }

    func testDifficultyIsPutAndTheSessionComesBack() async throws {
        let rig = TestRig()
        let path = "/v1/sessions/s-1/difficulty"
        rig.server.on(path, [.json(200, ratedSession)])
        let record = try await rig.api.setDifficulty(sessionId: "s-1", .veryHard)
        let call = try XCTUnwrap(rig.server.calls(path).first)
        XCTAssertEqual(call.request.httpMethod, "PUT")
        XCTAssertEqual(Array(call.body.keys), ["difficulty"])
        XCTAssertEqual(call.body["difficulty"] as? String, "veryHard")
        XCTAssertEqual(record.id, "s-1")
        XCTAssertTrue(record.isFocus)
        XCTAssertEqual(record.minutes, 50)
        XCTAssertEqual(record.difficulty, .hard)
        XCTAssertEqual(record.capacityCost, 0.8)
        XCTAssertEqual(record.outcome, "completed")
        XCTAssertEqual(record.startedAt, WireDate.parse("2030-03-17T09:00:00Z"))
    }

    func testDifficultyErrorsComeThroughWithTheirCode() async {
        let rig = TestRig()
        rig.server.on("/v1/sessions/s-2/difficulty", [.json(409, notFocus)])
        let error = await caught { _ = try await rig.api.setDifficulty(sessionId: "s-2", .easy) }
        XCTAssertEqual((error as? AuraError)?.serverCode, "not_focus_session")
    }

    func testLogBreakBody() async throws {
        let rig = TestRig()
        rig.server.on("/v1/sessions/log", [.json(201, loggedSession)])
        let start = TestRig.now.addingTimeInterval(-3600)
        let logged = try await rig.api.logBreak(id: "log-1", type: .walk, startedAt: start, minutes: 20)
        let call = try XCTUnwrap(rig.server.calls("/v1/sessions/log").first)
        XCTAssertEqual(call.request.httpMethod, "POST")
        XCTAssertEqual(call.body["id"] as? String, "log-1")
        XCTAssertEqual(call.body["kind"] as? String, "recovery")
        XCTAssertEqual(call.body["recoveryType"] as? String, "walk")
        XCTAssertEqual(call.body["minutes"] as? Int, 20)
        XCTAssertEqual(call.body["device"] as? String, "phone")
        let sent = try XCTUnwrap(call.body["startedAt"] as? String)
        XCTAssertTrue(sent.contains("T"))
        XCTAssertEqual(WireDate.parse(sent), start)
        XCTAssertTrue(logged.created)
        XCTAssertEqual(logged.session.outcome, "logged")
        XCTAssertEqual(logged.session.recoveryType, .walk)
        XCTAssertEqual(logged.session.minutes, 20)
        XCTAssertNil(logged.session.capacityCost)
    }

    func testResentLogIsNotCreated() async throws {
        let rig = TestRig()
        let again = loggedSession.replacingOccurrences(of: "\"created\":true", with: "\"created\":false")
        rig.server.on("/v1/sessions/log", [.json(200, again)])
        let logged = try await rig.api.logBreak(id: "log-1", type: .walk, startedAt: TestRig.now, minutes: 20)
        XCTAssertFalse(logged.created)
    }

    func testLogOverlapComesThroughWithItsCode() async {
        let rig = TestRig()
        rig.server.on("/v1/sessions/log", [.json(409, overlaps)])
        let error = await caught {
            _ = try await rig.api.logBreak(id: "log-2", type: .rest, startedAt: TestRig.now, minutes: 15)
        }
        XCTAssertEqual((error as? AuraError)?.serverCode, "log_overlaps")
    }

    func testSessionsFollowCursorsAndComeNewestFirst() async throws {
        let rig = TestRig()
        let first = """
        {"sessions":[{"id":"early","kind":"focus","startedAt":"2030-03-16T08:00:00Z","minutes":30},\
        {"id":"bad"}],"cursor":"page+2"}
        """
        let second = """
        {"sessions":[{"id":"late","kind":"recovery","recoveryType":"nap","startedAt":"2030-03-17T14:00:00Z",\
        "minutes":20,"activeMinutes":18,"capacity":{"cost":0.2,"charged":false}},{"id":"mid","kind":"meeting",\
        "startedAt":"2030-03-17T10:00:00Z"}],"cursor":null}
        """
        rig.server.on("/v1/sessions", [.json(200, first), .json(200, second)])
        let records = try await rig.api.sessions(from: "2030-03-16", to: "2030-03-17")
        XCTAssertEqual(records.map { $0.id }, ["late", "mid", "early", "bad"])
        XCTAssertEqual(records[0].recoveryType, .nap)
        XCTAssertEqual(records[0].minutes, 18)
        XCTAssertNil(records[0].capacityCost, "Not charged yet")
        XCTAssertEqual(records[1].minutes, 0)
        let calls = rig.server.calls("/v1/sessions")
        XCTAssertEqual(calls.count, 2)
        XCTAssertEqual(calls[0].request.httpMethod, "GET")
        XCTAssertEqual(calls[0].query["from"], "2030-03-16")
        XCTAssertEqual(calls[0].query["to"], "2030-03-17")
        XCTAssertNil(calls[0].query["cursor"])
        XCTAssertEqual(calls[1].query["cursor"], "page+2")
    }

    func testSessionsStopAfterFivePages() async throws {
        let rig = TestRig()
        rig.server.on("/v1/sessions", [.json(200, "{\"sessions\":[],\"cursor\":\"more\"}")])
        let records = try await rig.api.sessions(from: "2030-03-17", to: "2030-03-17")
        XCTAssertTrue(records.isEmpty)
        XCTAssertEqual(rig.server.calls("/v1/sessions").count, 5)
    }
}

final class BetaCapacityLiveTests: XCTestCase {
    func testCapacityLineIsTheSessionsWorkEntryToday() async throws {
        let rig = TestRig()
        let answer = """
        {"date":"2030-03-17","ledger":[\
        {"id":"l1","kind":"opening","source":"morning","label":"Morning","delta":80,"balance":80,"detail":"Opening.",\
        "estimated":false,"sourceId":null},\
        {"id":"l2","kind":"cost","source":"work","label":"Work","delta":-0.5,"balance":79.5,\
        "detail":"30 min of desk work at demand 60: -0.5.","estimated":false,"sourceId":"other"},\
        {"id":"l3","kind":"cost","source":"break","label":"Break","delta":0,"balance":79.5,"detail":"A break.",\
        "estimated":false,"sourceId":"s-1"},\
        {"id":"l4","kind":"cost","source":"work","label":"Work","delta":-1.0,"balance":78.5,\
        "detail":"60 min of desk work at demand 80: -1.0. You rated it Hard.","estimated":false,"sourceId":"s-1"}]}
        """
        rig.server.on("/v1/capacity/today", [.json(200, answer)])
        let line = try await rig.api.capacityLine(sessionId: "s-1")
        XCTAssertEqual(line, "60 min of desk work at demand 80: -1.0. You rated it Hard.")
        let missing = try await rig.api.capacityLine(sessionId: "nobody")
        XCTAssertNil(missing)
        let call = try XCTUnwrap(rig.server.calls("/v1/capacity/today").first)
        XCTAssertEqual(call.request.url?.host, "capacity.stub.test")
        XCTAssertEqual(call.query["date"], DayKey.string(TestRig.now))
    }

    func testRecoveryOptionsDecodeWithHints() async throws {
        let rig = TestRig()
        rig.server.on("/v1/capacity/recovery-options", [.json(200, recoveryOptionsAnswer)])
        let options = try await rig.api.recoveryOptions(minutes: 20)
        XCTAssertEqual(rig.server.calls("/v1/capacity/recovery-options").first?.query["minutes"], "20")
        XCTAssertEqual(options.map { $0.id }, ["nap", "walk", "rest", "outside", "social", "break"])
        XCTAssertEqual(options[0].type, .nap)
        XCTAssertEqual(options[0].hint, "+10, fades within 2 h")
        XCTAssertEqual(options[1].hint, "+9, fades within 50 min")
        XCTAssertEqual(options[1].title, "Walk")
        XCTAssertEqual(options[1].minutes, 20)
        XCTAssertEqual(options[1].note, "A walk.")
        XCTAssertNil(options[5].type)
        XCTAssertEqual(options[5].hint, "+2.5, does not fade")
    }

    func testHintKeepsOneDecimalOnlyWhenNotWhole() {
        let option = RecoveryOption(id: "rest", title: "Rest", minutes: 20, maxPoints: 4.25, fadesAfterMinutes: 90,
                                    note: "")
        XCTAssertEqual(option.hint, "+4.3, fades within 90 min")
        let whole = RecoveryOption(id: "nap", title: "Nap", minutes: 20, maxPoints: 10.0, fadesAfterMinutes: 180,
                                   note: "")
        XCTAssertEqual(whole.hint, "+10, fades within 3 h")
    }
}

final class BetaReviewLiveTests: XCTestCase {
    private let meeting = ReviewItem(blockId: "b-1", date: "2030-03-17", title: "Team sync", kind: "meeting",
                                     start: TestRig.now.addingTimeInterval(-7200),
                                     end: TestRig.now.addingTimeInterval(-5400), minutes: 30)
    private let nap = ReviewItem(blockId: "b-2", date: "2030-03-17", title: "", kind: "recovery",
                                 start: TestRig.now.addingTimeInterval(-3600),
                                 end: TestRig.now.addingTimeInterval(-3000), minutes: 10)

    func testInboxLeavesOutAnsweredItems() async throws {
        let rig = TestRig()
        let answer = """
        {"items":[\
        {"blockId":"b-1","date":"2030-03-17","title":"Team sync","kind":"meeting","start":"2030-03-17T10:00:00Z",\
        "end":"2030-03-17T10:30:00Z","minutes":30,"recordedMinutes":0,"reason":"modeOff","review":null},\
        {"blockId":"b-2","date":"2030-03-17","title":"10 Min nap","kind":"recovery","start":"2030-03-17T15:54:00Z",\
        "end":"2030-03-17T16:04:00Z","minutes":10,"recordedMinutes":0,"reason":null,"review":null},\
        {"blockId":"b-3","date":"2030-03-17","title":"Done","kind":"focus","start":"2030-03-17T11:00:00Z",\
        "end":"2030-03-17T12:00:00Z","minutes":60,"review":{"blockId":"b-3","happened":true}}]}
        """
        rig.server.on("/v1/reviews/inbox", [.json(200, answer)])
        let items = try await rig.api.reviewInbox(date: "2030-03-17", days: 7)
        XCTAssertEqual(items.map { $0.blockId }, ["b-1", "b-2"])
        XCTAssertFalse(items[0].isRecovery)
        XCTAssertTrue(items[1].isRecovery)
        XCTAssertEqual(items[1].minutes, 10)
        XCTAssertEqual(items[1].start, WireDate.parse("2030-03-17T15:54:00Z"))
        let call = try XCTUnwrap(rig.server.calls("/v1/reviews/inbox").first)
        XCTAssertEqual(call.query["date"], "2030-03-17")
        XCTAssertEqual(call.query["days"], "7")
    }

    func testReviewSendsEveryKeyWithNullsForEmptyAnswers() async throws {
        let rig = TestRig()
        rig.server.on("/v1/reviews/b-1", [.json(200, "{\"review\":{\"blockId\":\"b-1\"}}")])
        // A type on a meeting is never sent: the route refuses it on any kind but recovery.
        try await rig.api.submitReview(ReviewAnswer(item: meeting, happened: true, intensity: .steady,
                                                    recoveryType: .walk))
        let call = try XCTUnwrap(rig.server.calls("/v1/reviews/b-1").first)
        XCTAssertEqual(call.request.httpMethod, "PUT")
        XCTAssertEqual(call.body["date"] as? String, "2030-03-17")
        XCTAssertEqual(call.body["happened"] as? Bool, true)
        XCTAssertEqual(call.body["kind"] as? String, "meeting")
        XCTAssertEqual(call.body["title"] as? String, "Team sync")
        XCTAssertEqual(WireDate.parse(call.body["start"] as? String), meeting.start)
        XCTAssertEqual(WireDate.parse(call.body["end"] as? String), meeting.end)
        XCTAssertEqual(call.body["intensity"] as? String, "steady")
        XCTAssertTrue(call.body["drain"] is NSNull)
        XCTAssertTrue(call.body["recoveryType"] is NSNull)
    }

    func testRecoveryReviewSendsItsType() async throws {
        let rig = TestRig()
        rig.server.on("/v1/reviews/b-2", [.json(200, "{\"review\":{\"blockId\":\"b-2\"}}")])
        try await rig.api.submitReview(ReviewAnswer(item: nap, happened: false, recoveryType: .nap))
        let call = try XCTUnwrap(rig.server.calls("/v1/reviews/b-2").first)
        XCTAssertEqual(call.body["happened"] as? Bool, false)
        XCTAssertEqual(call.body["recoveryType"] as? String, "nap")
        XCTAssertTrue(call.body["intensity"] is NSNull)
        XCTAssertTrue(call.body["drain"] is NSNull)
        XCTAssertFalse(call.body.keys.contains("title"), "An empty title is left out")
    }
}

final class BetaSleepLiveTests: XCTestCase {
    private func night(awake: Int? = nil, inBed: Int? = nil) -> SleepNight {
        return SleepNight(date: "2030-03-17", timeZone: "Asia/Kolkata", bedtimeAt: TestRig.now.addingTimeInterval(-8 * 3600),
                          wakeAt: TestRig.now, totalSleepMin: 450, awakeMin: awake, inBedMin: inBed)
    }

    func testSleepNightLeavesOutEmptyOptionalKeys() async throws {
        let rig = TestRig()
        let path = "/v1/sleep/nights/2030-03-17"
        rig.server.on(path, [.json(201, "{\"night\":{\"date\":\"2030-03-17\",\"source\":\"appleHealth\"},\"created\":true}")])
        let result = try await rig.api.putSleepNight(night())
        XCTAssertEqual(result, SleepNightResult(created: true, source: "appleHealth"))
        let call = try XCTUnwrap(rig.server.calls(path).first)
        XCTAssertEqual(call.request.httpMethod, "PUT")
        XCTAssertEqual(call.request.url?.host, "health-data.stub.test")
        XCTAssertEqual(Set(call.body.keys), ["timeZone", "bedtimeAt", "wakeAt", "totalSleepMin"])
        XCTAssertEqual(call.body["timeZone"] as? String, "Asia/Kolkata")
        XCTAssertEqual(call.body["totalSleepMin"] as? Int, 450)
        XCTAssertEqual(WireDate.parse(call.body["wakeAt"] as? String), TestRig.now)
        XCTAssertEqual(WireDate.parse(call.body["bedtimeAt"] as? String), TestRig.now.addingTimeInterval(-8 * 3600))
    }

    func testSleepNightSendsOptionalKeysWhenSet() async throws {
        let rig = TestRig()
        let path = "/v1/sleep/nights/2030-03-17"
        rig.server.on(path, [.json(200, "{\"night\":{\"date\":\"2030-03-17\",\"source\":\"band\"},\"created\":false}")])
        let result = try await rig.api.putSleepNight(night(awake: 12, inBed: 470))
        XCTAssertEqual(result, SleepNightResult(created: false, source: "band"))
        let call = try XCTUnwrap(rig.server.calls(path).first)
        XCTAssertEqual(call.body["awakeMin"] as? Int, 12)
        XCTAssertEqual(call.body["inBedMin"] as? Int, 470)
    }

    func testSleepNightWithoutConsentAsksForIt() async {
        let rig = TestRig()
        rig.server.on("/v1/sleep/nights/2030-03-17", [.json(403, consentNeeded)])
        let error = await caught { _ = try await rig.api.putSleepNight(self.night()) }
        XCTAssertEqual(error as? AuraError, AuraError.consentRequired)
    }

    func testMorningCheckinSendsBothTimes() async throws {
        let rig = TestRig()
        rig.server.on("/v1/checkins", [.json(201, "{\"checkin\":{\"id\":\"x\"},\"created\":true}")])
        try await rig.api.morningCheckin(alertness: 6, fellAsleepAt: "23:10", wokeAt: "07:05", at: TestRig.now)
        let call = try XCTUnwrap(rig.server.calls("/v1/checkins").first)
        XCTAssertEqual(call.request.httpMethod, "POST")
        XCTAssertNotNil(UUID(uuidString: call.body["id"] as? String ?? ""))
        XCTAssertEqual(call.body["kind"] as? String, "morning")
        XCTAssertEqual(call.body["alertness"] as? Int, 6)
        XCTAssertEqual(call.body["fellAsleepAt"] as? String, "23:10")
        XCTAssertEqual(call.body["wokeAt"] as? String, "07:05")
        XCTAssertEqual(WireDate.parse(call.body["takenAt"] as? String), TestRig.now)
    }

    func testMorningCheckinWithOneTimeSendsNeither() async throws {
        let rig = TestRig()
        rig.server.on("/v1/checkins", [.json(201, "{\"checkin\":{\"id\":\"x\"},\"created\":true}")])
        try await rig.api.morningCheckin(alertness: nil, fellAsleepAt: "23:10", wokeAt: nil, at: TestRig.now)
        try await rig.api.morningCheckin(alertness: 3, fellAsleepAt: nil, wokeAt: "07:05", at: TestRig.now)
        let calls = rig.server.calls("/v1/checkins")
        XCTAssertEqual(calls.count, 2)
        for call in calls {
            XCTAssertFalse(call.body.keys.contains("fellAsleepAt"))
            XCTAssertFalse(call.body.keys.contains("wokeAt"))
        }
        XCTAssertFalse(calls[0].body.keys.contains("alertness"))
        XCTAssertEqual(calls[1].body["alertness"] as? Int, 3)
    }

    func testSleepScheduleFromTheProfile() async throws {
        let rig = TestRig()
        let full = """
        {"sub":"sub-1","timezone":"Asia/Kolkata","version":7,\
        "healthGoals":{"sleepNeedMinutes":480,"bedtime":"23:00","wakeTime":"07:00","stepsPerDay":null}}
        """
        let half = "{\"sub\":\"sub-1\",\"version\":7,\"healthGoals\":{\"bedtime\":\"23:00\",\"wakeTime\":null}}"
        let none = "{\"sub\":\"sub-1\",\"version\":7}"
        let odd = "{\"sub\":\"sub-1\",\"version\":7,\"healthGoals\":\"not an object\"}"
        rig.server.on("/v1/me", [.json(200, full), .json(200, half), .json(200, none), .json(200, odd)])
        let schedule = try await rig.api.sleepSchedule()
        XCTAssertEqual(schedule, SleepSchedule(bedtime: "23:00", wakeTime: "07:00"))
        let missingWake = try await rig.api.sleepSchedule()
        XCTAssertNil(missingWake)
        let noGoals = try await rig.api.sleepSchedule()
        XCTAssertNil(noGoals)
        let unreadable = try await rig.api.sleepSchedule()
        XCTAssertNil(unreadable)
        XCTAssertEqual(rig.server.calls("/v1/me").first?.request.url?.host, "identity.stub.test")
    }
}

/// A file in App/Resources, found from this file's own path.
private func betaResource(_ name: String, file: String = #filePath) throws -> Data {
    let url = URL(fileURLWithPath: file)
        .deletingLastPathComponent()
        .appendingPathComponent("../../../../App/Resources/\(name)")
        .standardized
    return try Data(contentsOf: url)
}

final class ModeRecoveryTypeTests: XCTestCase {
    func testTheMappingRules() {
        XCTAssertEqual(Mode(id: "nap-20", name: "Nap", word: "Nap", shelf: .sleep, minutes: 20).recoveryType, .nap)
        XCTAssertEqual(Mode(id: "caffeine-nap", name: "C", word: "C", shelf: .rest, minutes: 20).recoveryType, .nap)
        XCTAssertEqual(Mode(id: "green-sit", name: "G", word: "G", shelf: .outside, minutes: 20).recoveryType, .outside)
        XCTAssertEqual(Mode(id: "social-break", name: "S", word: "S", shelf: .people, minutes: 15).recoveryType, .social)
        XCTAssertEqual(Mode(id: "stretch", name: "S", word: "S", shelf: .movement, minutes: 10).recoveryType, .walk)
        XCTAssertEqual(Mode(id: "box", name: "B", word: "B", shelf: .breathe, minutes: 5).recoveryType, .rest)
        XCTAssertEqual(Mode(id: "hydration", name: "H", word: "H", shelf: .fuel, minutes: 5).recoveryType, .rest)
        XCTAssertNil(Mode(id: "timer", name: "T", word: "T", shelf: .focus, minutes: 25, kind: "focus").recoveryType)
        XCTAssertNil(Mode(id: "urban-walk", name: "W", word: "W", shelf: .movement, minutes: 20, kind: "walk").recoveryType)
        XCTAssertNil(Mode(id: "nap-meeting", name: "M", word: "M", shelf: .focus, minutes: 20, kind: "meeting").recoveryType)
    }

    func testEveryRecoveryModeInTheCatalogueHasAType() throws {
        let modes = try JSONDecoder().decode([Mode].self, from: betaResource("modes.json"))
        for mode in modes {
            if mode.kind == "recovery" {
                XCTAssertNotNil(mode.recoveryType, "\(mode.id) has no recovery type")
            } else {
                XCTAssertNil(mode.recoveryType, "\(mode.id) is \(mode.kind) and sends no recovery type")
            }
        }
        let byId = Dictionary(uniqueKeysWithValues: modes.map { ($0.id, $0) })
        XCTAssertEqual(byId["nap-10"]?.recoveryType, .nap)
        XCTAssertEqual(byId["outdoor-break"]?.recoveryType, .outside)
        XCTAssertEqual(byId["call-a-friend"]?.recoveryType, .social)
        XCTAssertEqual(byId["recovery-break"]?.recoveryType, .rest)
    }
}

final class BetaStubTests: XCTestCase {
    func testSeededSessionAndFinishedRunsComeNewestFirst() async throws {
        let stub = StubAPI()
        _ = try await stub.startSession(id: "run-1", kind: "focus", mode: "timer", plannedMinutes: 25, title: "Write",
                                        recoveryType: nil)
        _ = try await stub.startSession(id: "run-2", kind: "recovery", mode: "timer", plannedMinutes: 10, title: "Nap",
                                        recoveryType: .nap)
        try await stub.sessionAction(id: "run-1", action: .complete)
        let key = DayKey.string(Date())
        let records = try await stub.sessions(from: key, to: key)
        // run-2 never ended, so it isn't listed yet.
        let expected: [String] = Date() > today(9, 0) ? ["run-1", "s-deep"] : ["s-deep", "run-1"]
        XCTAssertEqual(records.map { $0.id }, expected)
        let seeded = try XCTUnwrap(records.first(where: { $0.id == "s-deep" }))
        XCTAssertEqual(seeded.kind, "focus")
        XCTAssertEqual(seeded.title, "Deep work")
        XCTAssertEqual(seeded.minutes, 50)
        XCTAssertEqual(seeded.outcome, "completed")
        XCTAssertNil(seeded.difficulty)
        XCTAssertEqual(seeded.capacityCost, 0.5)
        XCTAssertEqual(seeded.startedAt, today(9, 0))
        XCTAssertEqual(seeded.endedAt, today(9, 50))
        let run = try XCTUnwrap(records.first(where: { $0.id == "run-1" }))
        XCTAssertEqual(run.outcome, "completed")
        XCTAssertEqual(run.title, "Write")
        XCTAssertEqual(run.minutes, 25)
    }

    func testRatingChangesTheCapacityLine() async throws {
        let stub = StubAPI()
        let before = try await stub.capacityLine(sessionId: "s-deep")
        XCTAssertEqual(before, "50 min of desk work at demand 60: -0.5.")
        let rated = try await stub.setDifficulty(sessionId: "s-deep", .hard)
        XCTAssertEqual(rated.difficulty, .hard)
        XCTAssertEqual(rated.capacityCost, 0.8)
        let after = try await stub.capacityLine(sessionId: "s-deep")
        XCTAssertEqual(after, "50 min of desk work at demand 80: -0.8. You rated it Hard.")
        _ = try await stub.setDifficulty(sessionId: "s-deep", .veryHard)
        let changed = try await stub.capacityLine(sessionId: "s-deep")
        XCTAssertEqual(changed, "50 min of desk work at demand 100: -1.3. You rated it Very hard.")
        let key = DayKey.string(Date())
        let listed = try await stub.sessions(from: key, to: key)
        XCTAssertEqual(listed.first(where: { $0.id == "s-deep" })?.difficulty, .veryHard)
    }

    func testAnUnknownIdIsAFocusSessionJustMade() async throws {
        let stub = StubAPI()
        let none = try await stub.capacityLine(sessionId: "fresh")
        XCTAssertNil(none)
        let rated = try await stub.setDifficulty(sessionId: "fresh", .easy)
        XCTAssertTrue(rated.isFocus)
        XCTAssertEqual(rated.difficulty, .easy)
        let line = try await stub.capacityLine(sessionId: "fresh")
        XCTAssertEqual(line, "50 min of desk work at demand 20: -0.1. You rated it Easy.")
    }

    func testARecoverySessionHasNoLineAndCantBeRated() async throws {
        let stub = StubAPI()
        _ = try await stub.startSession(id: "rest-1", kind: "recovery", mode: "timer", plannedMinutes: 10, title: "Rest",
                                        recoveryType: .rest)
        try await stub.sessionAction(id: "rest-1", action: .complete)
        let line = try await stub.capacityLine(sessionId: "rest-1")
        XCTAssertNil(line)
        let error = await caught { _ = try await stub.setDifficulty(sessionId: "rest-1", .easy) }
        XCTAssertEqual((error as? AuraError)?.serverCode, "not_focus_session")
    }

    func testLoggedBreaksOverlapAndResend() async throws {
        let stub = StubAPI()
        let overlapSeeded = await caught {
            _ = try await stub.logBreak(id: "x", type: .walk, startedAt: today(9, 30), minutes: 20)
        }
        XCTAssertEqual(overlapSeeded as? AuraError,
                       AuraError.server(code: "log_overlaps", message: "This break overlaps another session.", status: 409))
        let first = try await stub.logBreak(id: "b1", type: .walk, startedAt: today(9, 50), minutes: 20)
        XCTAssertTrue(first.created)
        XCTAssertEqual(first.session.outcome, "logged")
        XCTAssertEqual(first.session.kind, "recovery")
        XCTAssertEqual(first.session.recoveryType, .walk)
        XCTAssertEqual(first.session.minutes, 20)
        let overlapLogged = await caught {
            _ = try await stub.logBreak(id: "b2", type: .rest, startedAt: today(10, 0), minutes: 15)
        }
        XCTAssertEqual((overlapLogged as? AuraError)?.serverCode, "log_overlaps")
        let again = try await stub.logBreak(id: "b1", type: .walk, startedAt: today(9, 50), minutes: 20)
        XCTAssertFalse(again.created)
        XCTAssertEqual(again.session, first.session)
        let key = DayKey.string(Date())
        let listed = try await stub.sessions(from: key, to: key)
        XCTAssertEqual(listed.filter { $0.id == "b1" }.count, 1)
    }

    func testInboxDropsAnsweredItems() async throws {
        let stub = StubAPI()
        let key = DayKey.string(Date())
        let items = try await stub.reviewInbox(date: key, days: 7)
        XCTAssertEqual(items.map { $0.blockId }, ["b-sync", "e1-review"])
        XCTAssertEqual(items[0].kind, "meeting")
        XCTAssertEqual(items[0].start, today(10, 0))
        XCTAssertEqual(items[1].title, "10 Min nap")
        XCTAssertTrue(items[1].isRecovery)
        XCTAssertEqual(items[1].start, today(15, 54))
        XCTAssertEqual(items[1].end, today(16, 4))
        // A recovery block without a type is still taken.
        try await stub.submitReview(ReviewAnswer(item: items[1], happened: true))
        let left = try await stub.reviewInbox(date: key, days: 7)
        XCTAssertEqual(left.map { $0.blockId }, ["b-sync"])
        XCTAssertEqual(stub.savedReviews["e1-review"]?.happened, true)
    }

    func testRecoveryOptionsAreTheRealValues() async throws {
        let options = try await StubAPI().recoveryOptions(minutes: 20)
        XCTAssertEqual(options.map { $0.id }, ["nap", "walk", "rest", "outside", "social"])
        XCTAssertEqual(options.map { $0.hint }, ["+10, fades within 2 h", "+9, fades within 50 min",
                                                 "+5, fades within 40 min", "+9, fades within 50 min",
                                                 "+5, fades within 40 min"])
        XCTAssertEqual(options.map { $0.title }, ["Nap", "Walk", "Rest", "Time outside", "Talk with someone"])
        XCTAssertFalse(options.contains(where: { $0.note.isEmpty }))
    }

    func testSleepAndCheckin() async throws {
        let stub = StubAPI()
        let schedule = try await stub.sleepSchedule()
        XCTAssertEqual(schedule, SleepSchedule(bedtime: "23:00", wakeTime: "07:00"))
        let night = SleepNight(date: "2030-03-17", timeZone: "UTC", bedtimeAt: TestRig.now.addingTimeInterval(-28_800),
                               wakeAt: TestRig.now, totalSleepMin: 450)
        let first = try await stub.putSleepNight(night)
        XCTAssertEqual(first, SleepNightResult(created: true, source: "appleHealth"))
        let second = try await stub.putSleepNight(night)
        XCTAssertEqual(second, SleepNightResult(created: false, source: "appleHealth"))
        XCTAssertEqual(stub.savedNights["2030-03-17"], night)
        try await stub.morningCheckin(alertness: 5, fellAsleepAt: "23:30", wokeAt: "07:10", at: TestRig.now)
        XCTAssertEqual(stub.lastMorningCheckin,
                       StubMorningCheckin(alertness: 5, fellAsleepAt: "23:30", wokeAt: "07:10", at: TestRig.now))
    }
}
