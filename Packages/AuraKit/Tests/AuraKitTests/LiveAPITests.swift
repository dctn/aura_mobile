import Foundation
import XCTest
@testable import AuraKit

private let emptyTasks = "{\"tasks\":[],\"deleted\":[],\"cursor\":null}"
private let emptyDays = "{\"days\":[],\"cursor\":null}"
private let bare503 = "{\"message\":\"Service Unavailable\"}"
private let consentRequired = "{\"error\":{\"code\":\"consent_required\",\"message\":\"Health data consent is needed.\"}}"
private let checkinOK = "{\"checkin\":{\"id\":\"x\"},\"created\":true}"
private let consentView = """
{"policyVersion":null,"healthData":false,"aiAssistant":true,"productAnalytics":false,"decidedAt":null,"required":true,"currentPolicyVersion":"2026-10"}
"""

private let unauthorized = "{\"message\":\"Unauthorized\"}"
private let staleProfile = "{\"error\":{\"code\":\"precondition_failed\",\"message\":\"The profile changed since you read it.\"}}"

/// Tokens a test can swap from the stub's handler, so "another call refreshed meanwhile" needs no timing.
private final class SwappableTokens: AccessTokenSource, @unchecked Sendable {
    private let lock = NSLock()
    private var held: String

    init(_ first: String) {
        held = first
    }

    var current: String {
        lock.lock()
        defer { lock.unlock() }
        return held
    }

    func replace(with token: String) {
        lock.lock()
        defer { lock.unlock() }
        held = token
    }

    func accessToken() async throws -> String {
        return current
    }

    func refreshedToken(rejected: String) async throws -> String {
        replace(with: "b")
        return "b"
    }

    func tokenRejected(_ token: String) async -> Bool {
        return current == token
    }
}

final class LiveAPIRequestTests: XCTestCase {
    func testSendsBearerTimeZoneAndAppHeaders() async throws {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.json(200, emptyTasks)])
        _ = try await rig.api.tasks()
        let call = try XCTUnwrap(rig.server.calls("/v1/tasks").first)
        XCTAssertEqual(call.header("Authorization"), "Bearer access-1")
        XCTAssertEqual(call.header("X-Time-Zone"), TimeZone.current.identifier)
        XCTAssertEqual(call.header("X-Aura-App"), "ios/0.1.0")
        XCTAssertEqual(call.request.url?.host, "task.stub.test")
    }

    func testBaseURLWithStageAndTrailingSlash() async throws {
        var config = TestRig.config
        config.services["health-data"] = "https://health.stub.test/dev/"
        let rig = TestRig(config: config)
        rig.server.on("/dev/v1/alerts", [.json(200, "{\"alerts\":[]}")])
        let alerts = try await rig.api.alerts(from: "2025-01-01", to: "2025-01-02")
        XCTAssertEqual(alerts, [])
        XCTAssertEqual(rig.server.calls("/dev/v1/alerts").count, 1)
    }

    func testNeverMoreThanSixOpen() async throws {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.json(200, emptyTasks)], delay: 0.02)
        let api = rig.api
        try await runConcurrently(20) {
            _ = try await api.tasks()
        }
        XCTAssertEqual(rig.server.calls("/v1/tasks").count, 20)
        XCTAssertLessThanOrEqual(rig.server.peakOpen, 6)
    }

    func testNoAnswerIsOffline() async {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.fail(.notConnectedToInternet)])
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(error as? AuraError, AuraError.offline)
    }

    func testCancelledCallIsNotOffline() async {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.fail(.cancelled)])
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertTrue(error is CancellationError)
    }

    func testServerErrorKeepsCodeAndMessage() async {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.json(400, "{\"error\":{\"code\":\"bad_request\",\"message\":\"Not that.\"}}")])
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "bad_request", message: "Not that.", status: 400))
    }

    func testGatewayErrorHasOnlyAMessage() async {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.json(429, "{\"message\":\"Too Many Requests\"}")])
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "http_429", message: "Too Many Requests", status: 429))
    }

    func testAnswerItCantReadIsBadResponse() async {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.json(200, "not json")])
        let error = await caught { _ = try await rig.api.tasks() }
        guard case .server(let code, _, _)? = error as? AuraError else {
            XCTFail("Expected a server error")
            return
        }
        XCTAssertEqual(code, "bad_response")
    }
}

final class LiveAPIRetryTests: XCTestCase {
    func testBare503BacksOffThenSucceeds() async throws {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.json(503, bare503), .json(503, bare503), .json(200, emptyTasks)])
        _ = try await rig.api.tasks()
        XCTAssertEqual(rig.sleeps.values, [1, 2])
        XCTAssertEqual(rig.server.calls("/v1/tasks").count, 3)
    }

    func testBare503GivesUpAfterFourWaits() async {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.json(503, bare503)])
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(rig.sleeps.values, [1, 2, 4, 8])
        XCTAssertEqual(rig.server.calls("/v1/tasks").count, 5)
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "http_503", message: "Service Unavailable", status: 503))
    }

    func test503WithAnErrorBodyIsNotRetried() async {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.json(503, "{\"error\":{\"code\":\"google_down\",\"message\":\"Later.\"}}")])
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(rig.sleeps.values, [])
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "google_down", message: "Later.", status: 503))
    }

    func testConsentRequiredWithoutAGrantIsImmediate() async {
        let rig = TestRig()
        rig.server.on("/v1/checkins", [.json(403, consentRequired)])
        let error = await caught { try await rig.api.logMood(3, at: TestRig.now) }
        XCTAssertEqual(error as? AuraError, AuraError.consentRequired)
        XCTAssertEqual(rig.sleeps.values, [])
        XCTAssertEqual(rig.server.calls("/v1/checkins").count, 1)
    }

    func testAfterAGrantConsentRequiredIsRetried() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me/consent", [.json(200, consentView)])
        rig.server.on("/v1/checkins", [.json(403, consentRequired), .json(403, consentRequired), .json(201, checkinOK)])
        try await rig.api.grantHealthConsent()
        try await rig.api.logMood(3, at: TestRig.now)
        XCTAssertEqual(rig.sleeps.values, [1, 2])
        XCTAssertEqual(rig.server.calls("/v1/checkins").count, 3)
    }

    func testAfterAGrantConsentRetriesGiveUpAfterFourWaits() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me/consent", [.json(200, consentView)])
        rig.server.on("/v1/checkins", [.json(403, consentRequired)])
        try await rig.api.grantHealthConsent()
        let error = await caught { try await rig.api.logMood(3, at: TestRig.now) }
        XCTAssertEqual(error as? AuraError, AuraError.consentRequired)
        XCTAssertEqual(rig.sleeps.values, [1, 2, 3, 4])
        XCTAssertEqual(rig.server.calls("/v1/checkins").count, 5)
    }

    func testGrantKeepsTheOtherChoices() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me/consent", [.json(200, consentView)])
        try await rig.api.grantHealthConsent()
        let calls = rig.server.calls("/v1/me/consent")
        XCTAssertEqual(calls.map { $0.request.httpMethod ?? "" }, ["GET", "PUT"])
        let put = try XCTUnwrap(calls.last)
        XCTAssertEqual(put.body["policyVersion"] as? String, "2026-10")
        XCTAssertEqual(put.body["healthData"] as? Bool, true)
        XCTAssertEqual(put.body["aiAssistant"] as? Bool, true)
        XCTAssertEqual(put.body["productAnalytics"] as? Bool, false)
    }
}

final class LiveAPITokenTests: XCTestCase {
    func testRefreshesWithin120SecondsOfExpiry() async throws {
        let rig = TestRig(tokens: TestRig.tokens(expiresIn: 119))
        rig.server.on("InitiateAuth", [TestRig.refreshAnswer])
        rig.server.on("/v1/tasks", [.json(200, emptyTasks)])
        _ = try await rig.api.tasks()
        let refresh = try XCTUnwrap(rig.server.calls("InitiateAuth").first)
        XCTAssertEqual(refresh.body["AuthFlow"] as? String, "REFRESH_TOKEN_AUTH")
        XCTAssertEqual(refresh.nested("AuthParameters", "REFRESH_TOKEN"), "refresh-1")
        XCTAssertEqual(rig.server.calls("/v1/tasks").first?.header("Authorization"), "Bearer access-2")
        XCTAssertEqual(rig.store.tokens?.accessToken, "access-2")
        XCTAssertEqual(rig.store.tokens?.refreshToken, "refresh-1")
    }

    func testNoRefreshWithMoreThan120SecondsLeft() async throws {
        let rig = TestRig(tokens: TestRig.tokens(expiresIn: 121))
        rig.server.on("/v1/tasks", [.json(200, emptyTasks)])
        _ = try await rig.api.tasks()
        XCTAssertEqual(rig.server.calls("InitiateAuth").count, 0)
        XCTAssertEqual(rig.server.calls("/v1/tasks").first?.header("Authorization"), "Bearer access-1")
    }

    func testConcurrentCallersShareOneRefreshNearExpiry() async throws {
        let rig = TestRig(tokens: TestRig.tokens(expiresIn: 30))
        rig.server.on("InitiateAuth", [TestRig.refreshAnswer], delay: 0.05)
        rig.server.on("/v1/tasks", [.json(200, emptyTasks)])
        let api = rig.api
        try await runConcurrently(8) {
            _ = try await api.tasks()
        }
        XCTAssertEqual(rig.server.calls("InitiateAuth").count, 1)
        let tokens = rig.server.calls("/v1/tasks").map { $0.header("Authorization") ?? "" }
        XCTAssertEqual(tokens, Array(repeating: "Bearer access-2", count: 8))
    }

    func testConcurrent401sShareOneRefresh() async throws {
        let rig = TestRig()
        rig.server.on("InitiateAuth", [TestRig.refreshAnswer], delay: 0.05)
        rig.server.respond("/v1/tasks", delay: 0.01) { call in
            if call.header("Authorization") == "Bearer access-2" {
                return .json(200, emptyTasks)
            }
            return .json(401, "{\"message\":\"Unauthorized\"}")
        }
        let api = rig.api
        try await runConcurrently(8) {
            _ = try await api.tasks()
        }
        XCTAssertEqual(rig.server.calls("InitiateAuth").count, 1)
    }

    func test401RefreshesAndRetries() async throws {
        let rig = TestRig()
        rig.server.on("InitiateAuth", [TestRig.refreshAnswer])
        rig.server.on("/v1/tasks", [.json(401, "{\"message\":\"Unauthorized\"}"), .json(200, emptyTasks)])
        _ = try await rig.api.tasks()
        let calls = rig.server.calls("/v1/tasks")
        XCTAssertEqual(calls.map { $0.header("Authorization") ?? "" }, ["Bearer access-1", "Bearer access-2"])
    }

    func testSecond401EndsTheSession() async {
        let rig = TestRig()
        rig.server.on("InitiateAuth", [TestRig.refreshAnswer])
        rig.server.on("/v1/tasks", [.json(401, "{\"message\":\"Unauthorized\"}")])
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(error as? AuraError, AuraError.signedOut)
        XCTAssertEqual(rig.server.calls("InitiateAuth").count, 1)
        XCTAssertEqual(rig.server.calls("/v1/tasks").count, 2)
        XCTAssertNil(rig.store.tokens)
    }

    private func liveAPI(_ tokens: SwappableTokens) -> LiveAPI {
        return LiveAPI(config: TestRig.config, tokens: tokens, session: TestRig.stubSession(),
                       sleep: { (_: TimeInterval) in }, now: { TestRig.now })
    }

    func testSecond401OnAnOlderTokenRetriesWithTheNewerOne() async throws {
        let rig = TestRig()
        let tokens = SwappableTokens("a")
        rig.server.respond("/v1/tasks") { call in
            let sent = call.header("Authorization")
            if sent == "Bearer c" {
                return .json(200, emptyTasks)
            }
            if sent == "Bearer b" {
                tokens.replace(with: "c")
            }
            return .json(401, unauthorized)
        }
        _ = try await liveAPI(tokens).tasks()
        let headers = rig.server.calls("/v1/tasks").map { $0.header("Authorization") ?? "" }
        XCTAssertEqual(headers, ["Bearer a", "Bearer b", "Bearer c"])
    }

    func testStillOlderAfterTheRetryIsNotASignOut() async {
        let rig = TestRig()
        let tokens = SwappableTokens("a")
        rig.server.respond("/v1/tasks") { _ in
            tokens.replace(with: UUID().uuidString)
            return .json(401, unauthorized)
        }
        let live = liveAPI(tokens)
        let error = await caught { _ = try await live.tasks() }
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "http_401", message: "Unauthorized", status: 401))
        XCTAssertEqual(rig.server.calls("/v1/tasks").count, 3)
    }

    func testRefusedRefreshEndsTheSession() async {
        let rig = TestRig(tokens: TestRig.tokens(expiresIn: 10))
        rig.server.on("InitiateAuth", [TestRig.refused("NotAuthorizedException", "Refresh Token has been revoked")])
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(error as? AuraError, AuraError.signedOut)
        XCTAssertNil(rig.store.tokens)
    }

    func testOfflineRefreshKeepsTheSession() async {
        let rig = TestRig(tokens: TestRig.tokens(expiresIn: 10))
        rig.server.on("InitiateAuth", [.fail(.notConnectedToInternet)])
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(error as? AuraError, AuraError.offline)
        XCTAssertEqual(rig.store.tokens?.refreshToken, "refresh-1")
    }

    func testNoSessionIsSignedOut() async {
        let rig = TestRig(tokens: nil)
        let error = await caught { _ = try await rig.api.tasks() }
        XCTAssertEqual(error as? AuraError, AuraError.signedOut)
        XCTAssertEqual(rig.server.allCalls.count, 0)
    }
}

final class LiveAPIRangeTests: XCTestCase {
    func testDailyMetricsFollowsTheCursor() async throws {
        let rig = TestRig()
        let first = """
        {"days":[{"date":"2025-01-01","status":"ready","final":true,"metrics":{"burnoutIndexScore":61.5,"burnoutIndexRisk":"Moderate","sleepScore":80}}],"cursor":"a+b/c="}
        """
        let second = """
        {"days":[{"date":"2025-01-02","status":"noData","metrics":{}}],"cursor":null}
        """
        rig.server.on("/v1/metrics/daily", [.json(200, first), .json(200, second)])
        let days = try await rig.api.dailyMetrics(from: "2025-01-01", to: "2025-01-02")
        XCTAssertEqual(days, [DailyMetrics(date: "2025-01-01", burnoutIndexScore: 61.5, burnoutIndexRisk: "Moderate"),
                              DailyMetrics(date: "2025-01-02")])
        let calls = rig.server.calls("/v1/metrics/daily")
        XCTAssertEqual(calls.count, 2)
        XCTAssertNil(calls[0].query["cursor"])
        XCTAssertTrue(calls[1].address.contains("cursor=a%2Bb%2Fc%3D"))
        XCTAssertEqual(calls[0].query["fields"], "burnoutIndexScore,burnoutIndexRisk")
    }

    func testDailyMetricsSplitsAt400Days() async throws {
        let rig = TestRig()
        rig.server.on("/v1/metrics/daily", [.json(200, emptyDays)])
        _ = try await rig.api.dailyMetrics(from: "2024-01-01", to: "2025-06-01")
        let calls = rig.server.calls("/v1/metrics/daily")
        XCTAssertEqual(calls.count, 2)
        XCTAssertEqual(calls[0].query["from"], "2024-01-01")
        XCTAssertEqual(calls[0].query["to"], "2025-02-03")
        XCTAssertEqual(calls[1].query["from"], "2025-02-04")
        XCTAssertEqual(calls[1].query["to"], "2025-06-01")
    }

    func testAvailabilityClampsTo400Days() async throws {
        let rig = TestRig()
        rig.server.on("/v1/metrics/availability", [.json(200, "{\"fields\":[]}")])
        _ = try await rig.api.availability(from: "2020-01-01", to: "2025-06-01")
        let call = try XCTUnwrap(rig.server.calls("/v1/metrics/availability").first)
        XCTAssertEqual(call.query["from"], "2024-04-28")
        XCTAssertEqual(call.query["to"], "2025-06-01")
    }

    func testAShortRangeIsLeftAlone() {
        XCTAssertEqual(DayRanges.clampedStart(from: "2025-05-01", to: "2025-06-01", maxDays: 400), "2025-05-01")
        let pieces = DayRanges.split(from: "2025-05-01", to: "2025-05-01", maxDays: 400)
        XCTAssertEqual(pieces.count, 1)
        XCTAssertEqual(pieces[0].from, "2025-05-01")
        XCTAssertEqual(pieces[0].to, "2025-05-01")
    }

    func testARangeItCantReadGoesThroughWhole() {
        let pieces = DayRanges.split(from: "soon", to: "2025-05-01", maxDays: 400)
        XCTAssertEqual(pieces.count, 1)
        XCTAssertEqual(pieces[0].from, "soon")
    }

    func testEventsSplitAt62DaysDedupeAndDropOutsiders() async throws {
        let rig = TestRig()
        let page = """
        {"events":[
          {"id":"late","title":"Late","start":"2025-02-20T10:00:00Z","end":"2025-02-20T11:00:00Z","allDay":false,"kind":"meeting","date":"2025-02-20"},
          {"id":"early","title":"Early","start":"2025-02-01T10:00:00.000Z","end":"2025-02-01T11:00:00.000Z","allDay":false,"kind":"focus"},
          {"id":"outside","title":"Outside","start":"2025-06-01T10:00:00Z","end":"2025-06-01T11:00:00Z"}
        ],"cursor":null}
        """
        rig.server.on("/v1/calendar/events", [.json(200, page)])
        let from = try XCTUnwrap(DayKey.date("2025-01-01"))
        let to = try XCTUnwrap(DayKey.date("2025-04-11"))
        let events = try await rig.api.events(from: from, to: to)
        XCTAssertEqual(events.map { $0.id }, ["early", "late"])
        let calls = rig.server.calls("/v1/calendar/events")
        XCTAssertEqual(calls.count, 2)
        XCTAssertEqual(calls[0].query["from"], "2024-12-31")
        XCTAssertEqual(calls[0].query["to"], "2025-03-02")
        XCTAssertEqual(calls[1].query["from"], "2025-03-03")
        XCTAssertEqual(calls[1].query["to"], "2025-04-11")
    }

    private func eventJSON(_ id: String, _ start: Date, _ end: Date) -> String {
        return "{\"id\":\"\(id)\",\"title\":\"\(id)\",\"start\":\"\(WireDate.string(start))\",\"end\":\"\(WireDate.string(end))\"}"
    }

    func testEventRunningPastMidnightIsFound() async throws {
        let rig = TestRig()
        let midnight = try XCTUnwrap(DayKey.date("2025-06-11"))
        let hour: TimeInterval = 3600
        let from = midnight.addingTimeInterval(hour)
        let to = midnight.addingTimeInterval(20 * hour)
        let overnight = eventJSON("overnight", midnight.addingTimeInterval(-2 * hour), midnight.addingTimeInterval(6 * hour))
        let finished = eventJSON("finished", midnight.addingTimeInterval(-10 * hour), midnight.addingTimeInterval(-8 * hour))
        let later = eventJSON("later", midnight.addingTimeInterval(9 * hour), midnight.addingTimeInterval(10 * hour))
        rig.server.on("/v1/calendar/events", [.json(200, "{\"events\":[\(overnight),\(finished),\(later)],\"cursor\":null}")])
        let events = try await rig.api.events(from: from, to: to)
        XCTAssertEqual(events.map { $0.id }, ["overnight", "later"])
        let call = try XCTUnwrap(rig.server.calls("/v1/calendar/events").first)
        XCTAssertEqual(call.query["from"], "2025-06-10")
        XCTAssertEqual(call.query["to"], "2025-06-11")
    }

    func testEventsAtOneInstantKeepOnlyWhatCoversIt() async throws {
        let rig = TestRig()
        let midnight = try XCTUnwrap(DayKey.date("2025-06-11"))
        let hour: TimeInterval = 3600
        let at = midnight.addingTimeInterval(hour)
        let covering = eventJSON("covering", midnight.addingTimeInterval(-2 * hour), midnight.addingTimeInterval(6 * hour))
        let finished = eventJSON("finished", midnight.addingTimeInterval(-10 * hour), midnight.addingTimeInterval(-8 * hour))
        rig.server.on("/v1/calendar/events", [.json(200, "{\"events\":[\(covering),\(finished)],\"cursor\":null}")])
        let events = try await rig.api.events(from: at, to: at)
        XCTAssertEqual(events.map { $0.id }, ["covering"])
    }

    func testSeriesSplitsAt400DaysAndJoins() async throws {
        let rig = TestRig()
        let first = "{\"field\":\"sleepScore\",\"unit\":\"score\",\"points\":[{\"date\":\"2024-01-01\",\"value\":70}]}"
        let second = "{\"field\":\"sleepScore\",\"unit\":\"score\",\"points\":[{\"date\":\"2025-06-01\",\"value\":null}]}"
        rig.server.on("/v1/metrics/series/sleepScore", [.json(200, first), .json(200, second)])
        let series = try await rig.api.series(field: "sleepScore", from: "2024-01-01", to: "2025-06-01")
        XCTAssertEqual(series, MetricSeries(field: "sleepScore", unit: "score",
                                            points: [MetricPoint(date: "2024-01-01", value: 70),
                                                     MetricPoint(date: "2025-06-01", value: nil)]))
        XCTAssertEqual(rig.server.calls("/v1/metrics/series/sleepScore").count, 2)
    }
}

final class LiveAPIDecodingTests: XCTestCase {
    func testScores() async throws {
        let rig = TestRig()
        let sample = """
        {"date":"2030-03-17","attention":{"value":96,"status":"ready","checksCounted":14,"checksNeeded":14,"vsNormal":-4,"at":"2030-03-17T09:00:00Z"},
        "memory":{"value":null,"status":"learning","checksCounted":3,"checksNeeded":14,"vsNormal":null,"at":null},
        "thinking":{"value":null,"status":"noData"},"extra":{"anything":[1,2]}}
        """
        rig.server.on("/v1/scores/today", [.json(200, sample)])
        let scores = try await rig.api.scoresToday()
        XCTAssertEqual(scores.attention, Score(name: "Attention", value: 96, checksCounted: 14, checksNeeded: 14))
        XCTAssertEqual(scores.memory, Score(name: "Memory", value: nil, checksCounted: 3, checksNeeded: 14))
        XCTAssertEqual(scores.thinking, Score(name: "Thinking", value: nil))
        XCTAssertEqual(scores.focus, Score(name: "Focus", value: nil))
        XCTAssertEqual(rig.server.calls("/v1/scores/today").first?.query["date"], DayKey.string(TestRig.now))
    }

    func testTasks() async throws {
        let rig = TestRig()
        let sample = """
        {"tasks":[
          {"id":"a","title":"Timed","status":"pending","dueAt":"2025-02-01T09:30:00Z","hasTime":true,"plannedFor":null,"completedAt":null,"scheduledAt":null,"estimateMinutes":30},
          {"id":"b","title":"Day only","status":"working","dueAt":"2025-02-01T00:00:00Z","hasTime":false},
          {"id":"c","title":"Blocked","status":"pending","dueAt":null,"scheduledAt":"2025-02-01T14:00:00.250+05:30"},
          {"id":"d","title":"Finished","status":"completed","completedAt":"2025-02-01T10:00:00Z"},
          {"id":"e"}
        ],"deleted":["z"],"cursor":null}
        """
        rig.server.on("/v1/tasks", [.json(200, sample)])
        let tasks = try await rig.api.tasks()
        XCTAssertEqual(tasks.count, 5)
        XCTAssertEqual(tasks[0].at, WireDate.parse("2025-02-01T09:30:00Z"))
        XCTAssertNotNil(tasks[0].at)
        XCTAssertNil(tasks[1].at)
        XCTAssertEqual(tasks[2].at, WireDate.parse("2025-02-01T08:30:00.250Z"))
        XCTAssertNotNil(tasks[2].at)
        XCTAssertFalse(tasks[0].done)
        XCTAssertTrue(tasks[3].done)
        XCTAssertEqual(tasks[4], TaskItem(id: "e", title: ""))
    }

    func testTaskPlannedForADayHasNoTime() async throws {
        let rig = TestRig()
        let sample = """
        {"tasks":[{"id":"p","title":"For tomorrow","status":"pending","dueAt":null,"hasTime":false,"plannedFor":"2030-03-18","scheduledAt":null,"completedAt":null}],"deleted":[],"cursor":null}
        """
        rig.server.on("/v1/tasks", [.json(200, sample)])
        let tasks = try await rig.api.tasks()
        XCTAssertEqual(tasks, [TaskItem(id: "p", title: "For tomorrow")])
        XCTAssertNil(tasks[0].at)
    }

    func testAlerts() async throws {
        let rig = TestRig()
        let sample = """
        {"alerts":[
          {"id":"alert-2025-02-01-DX-01","featureId":"DX-01","date":"2025-02-01","title":"Take a nap","body":"You slept little.","priority":"high",
           "action":{"type":"mode","service":"app","ref":"nap-10"},"holdable":true,"sendAt":"2025-02-01T13:00:00Z","expiresAt":null,"done":false},
          {"id":"alert-2025-02-01-DX-02","featureId":"DX-02","date":"2025-02-01","title":"Done one","body":null,"action":null,"done":true}
        ]}
        """
        rig.server.on("/v1/alerts", [.json(200, sample)])
        let alerts = try await rig.api.alerts(from: "2025-02-01", to: "2025-02-01")
        XCTAssertEqual(alerts[0], AlertItem(id: "alert-2025-02-01-DX-01", featureId: "DX-01", title: "Take a nap",
                                            detail: "You slept little.", date: "2025-02-01", done: false, actionRef: "nap-10"))
        XCTAssertEqual(alerts[1].done, true)
        XCTAssertNil(alerts[1].detail)
        XCTAssertNil(alerts[1].actionRef)
    }

    func testCatalogue() async throws {
        let rig = TestRig()
        let sample = """
        {"categories":[{"id":"body"}],"sections":[{"id":"sleep","title":"Sleep","symbol":"moon","color":"blue","parameters":[
          {"name":"Sleep score","field":"sleepScore","owner":"health-data","status":"live","read":"series","route":null,"unit":"score","format":"int","chart":"line","range":{"min":0,"max":100},"note":null},
          {"name":"Not measured","field":null,"owner":null,"status":"planned","read":"none","route":null,"unit":null,"format":null}
        ]}]}
        """
        rig.server.on("/v1/metrics/catalogue", [.json(200, sample)])
        let sections = try await rig.api.catalogue()
        XCTAssertEqual(sections.count, 1)
        XCTAssertEqual(sections[0].parameters[0], CatalogueParameter(name: "Sleep score", field: "sleepScore", read: "series", unit: "score", format: "int"))
        XCTAssertTrue(sections[0].parameters[0].hasSeries)
        XCTAssertEqual(sections[0].parameters[1], CatalogueParameter(name: "Not measured", field: nil, read: "none", unit: "", format: ""))
    }

    func testAvailabilityValueKinds() async throws {
        let rig = TestRig()
        let sample = """
        {"from":"2025-01-01","to":"2025-02-01","fields":[
          {"field":"sleepScore","latestDate":"2025-02-01","latestValue":81.5},
          {"field":"burnoutIndexRisk","latestDate":"2025-02-01","latestValue":"Low"},
          {"field":"tierCPhoneJSON.charging","latestDate":"2025-01-30","latestValue":true},
          {"field":"listThing","latestDate":"2025-01-29","latestValue":[1,2]}
        ]}
        """
        rig.server.on("/v1/metrics/availability", [.json(200, sample)])
        let fields = try await rig.api.availability(from: "2025-01-01", to: "2025-02-01")
        XCTAssertEqual(fields[0], FieldAvailability(field: "sleepScore", latestDate: "2025-02-01", latestNumber: 81.5))
        XCTAssertEqual(fields[1], FieldAvailability(field: "burnoutIndexRisk", latestDate: "2025-02-01", latestText: "Low"))
        XCTAssertEqual(fields[2].latestText, "Yes")
        XCTAssertNil(fields[3].latestNumber)
        XCTAssertNil(fields[3].latestText)
    }

    func testTraceAndMissingFields() async throws {
        let rig = TestRig()
        let path = "/v1/trends/sessions/session-1"
        let ready = "{\"sessionId\":\"session-1\",\"date\":\"2025-02-01\",\"status\":\"ready\",\"hr\":[],\"stress\":[],\"avgHr\":60,\"stressDrop\":12.5}"
        rig.server.on(path, [.json(200, ready), .json(200, "{}")])
        let first = try await rig.api.sessionTrace(id: "session-1")
        XCTAssertEqual(first, SessionTrace(status: "ready", stressDrop: 12.5))
        let second = try await rig.api.sessionTrace(id: "session-1")
        XCTAssertEqual(second, SessionTrace(status: "noData", stressDrop: nil))
    }

    func testDatesWithAndWithoutFractions() {
        let plain = WireDate.parse("2025-02-01T10:00:00Z")
        XCTAssertNotNil(plain)
        XCTAssertEqual(WireDate.parse("2025-02-01T10:00:00.000Z"), plain)
        XCTAssertEqual(WireDate.parse("2025-02-01T15:30:00+05:30"), plain)
        let fractional = WireDate.parse("2025-02-01T10:00:00.5Z")
        XCTAssertEqual(fractional?.timeIntervalSince1970, plain.map { $0.timeIntervalSince1970 + 0.5 })
        XCTAssertNil(WireDate.parse("2025-02-01"))
        XCTAssertNil(WireDate.parse(nil))
    }
}

final class LiveAPIBodyTests: XCTestCase {
    private func assertClientId(_ call: StubCall, file: StaticString = #filePath, line: UInt = #line) {
        let id = call.body["id"] as? String ?? ""
        XCTAssertNotNil(UUID(uuidString: id), "Client ids are UUIDs", file: file, line: line)
    }

    func testMoodCheckin() async throws {
        let rig = TestRig()
        rig.server.on("/v1/checkins", [.json(201, checkinOK)])
        try await rig.api.logMood(4, at: TestRig.now)
        let call = try XCTUnwrap(rig.server.calls("/v1/checkins").first)
        assertClientId(call)
        XCTAssertEqual(call.request.httpMethod, "POST")
        XCTAssertEqual(call.body["kind"] as? String, "mood")
        XCTAssertEqual(call.body["mood"] as? Int, 4)
        XCTAssertEqual(WireDate.parse(call.body["takenAt"] as? String), TestRig.now)
    }

    func testAlertDone() async throws {
        let rig = TestRig()
        let path = "/v1/alerts/alert-2025-02-01-DX-01/done"
        rig.server.on(path, [.json(200, "{\"id\":\"alert-2025-02-01-DX-01\",\"done\":true}")])
        try await rig.api.markAlertDone(id: "alert-2025-02-01-DX-01")
        XCTAssertEqual(rig.server.calls(path).first?.request.httpMethod, "POST")
    }

    func testFlowtimeSessionSendsNullMinutes() async throws {
        let rig = TestRig()
        rig.server.on("/v1/sessions", [.json(201, "{\"session\":{\"id\":\"server-id\",\"status\":\"running\"},\"joined\":true}")])
        let started = try await rig.api.startSession(id: "chosen-1", kind: "focus", mode: "flowtime", plannedMinutes: 25, title: "Flow")
        XCTAssertEqual(started, StartedSession(id: "server-id", joined: true))
        let call = try XCTUnwrap(rig.server.calls("/v1/sessions").first)
        XCTAssertEqual(call.body["id"] as? String, "chosen-1")
        XCTAssertTrue(call.body["plannedMinutes"] is NSNull)
        XCTAssertEqual(call.body["device"] as? String, "phone")
        XCTAssertEqual(call.body["kind"] as? String, "focus")
        XCTAssertEqual(call.body["mode"] as? String, "flowtime")
        XCTAssertEqual(call.body["title"] as? String, "Flow")
    }

    func testTimerSessionSendsMinutesWithinTheLimit() async throws {
        let rig = TestRig()
        rig.server.on("/v1/sessions", [.json(201, "{\"session\":{\"id\":\"one\"}}")])
        let started = try await rig.api.startSession(id: "one", kind: "recovery", mode: "timer", plannedMinutes: 25, title: "Nap")
        XCTAssertEqual(started, StartedSession(id: "one", joined: false))
        _ = try await rig.api.startSession(id: "two", kind: "recovery", mode: "timer", plannedMinutes: 500, title: "Long")
        let calls = rig.server.calls("/v1/sessions")
        XCTAssertEqual(calls[0].body["plannedMinutes"] as? Int, 25)
        XCTAssertEqual(calls[1].body["plannedMinutes"] as? Int, 240)
    }

    func testSessionAnswersWithTheMinutesGranted() async throws {
        let rig = TestRig()
        rig.server.on("/v1/sessions", [.json(201, "{\"session\":{\"id\":\"one\",\"plannedMinutes\":15},\"joined\":false}")])
        let started = try await rig.api.startSession(id: "one", kind: "focus", mode: "timer", plannedMinutes: 25, title: "Work")
        XCTAssertEqual(started, StartedSession(id: "one", joined: false, plannedMinutes: 15))
    }

    func testJoinedSessionCarriesTheRunningSession() async throws {
        let rig = TestRig()
        let answer = """
        {"session":{"id":"desk-1","kind":"focus","mode":"timer","state":"running","device":"desktop","title":"Deep work",\
        "plannedMinutes":50,"startedAt":"2030-03-17T17:21:40.000Z","endsAt":"2030-03-17T18:11:40.000Z"},"joined":true}
        """
        rig.server.on("/v1/sessions", [.json(201, answer)])
        let started = try await rig.api.startSession(id: "mine", kind: "focus", mode: "timer", plannedMinutes: 25, title: "Work")
        XCTAssertEqual(started, StartedSession(id: "desk-1", joined: true, plannedMinutes: 50, title: "Deep work",
                                               kind: "focus", device: "desktop",
                                               endsAt: TestRig.now.addingTimeInterval(25 * 60)))
    }

    func testStartAnswerWithoutTheNewFieldsStillDecodes() async throws {
        let rig = TestRig()
        rig.server.on("/v1/sessions", [.json(201, "{\"session\":{\"id\":\"one\",\"title\":null,\"endsAt\":null},\"joined\":false}")])
        let started = try await rig.api.startSession(id: "one", kind: "focus", mode: "flowtime", plannedMinutes: nil, title: "Flow")
        XCTAssertEqual(started, StartedSession(id: "one", joined: false))
        XCTAssertNil(started.title)
        XCTAssertNil(started.kind)
        XCTAssertNil(started.device)
        XCTAssertNil(started.endsAt)
    }

    func testOpenEndedSessionHasNoMinutes() async throws {
        let rig = TestRig()
        rig.server.on("/v1/sessions", [.json(201, "{\"session\":{\"id\":\"one\",\"plannedMinutes\":null},\"joined\":false}")])
        let started = try await rig.api.startSession(id: "one", kind: "focus", mode: "flowtime", plannedMinutes: nil, title: "Flow")
        XCTAssertNil(started.plannedMinutes)
    }

    func testResendingASessionSendsTheSameId() async throws {
        let rig = TestRig()
        let stored = "{\"session\":{\"id\":\"same\",\"plannedMinutes\":25},\"joined\":false}"
        rig.server.on("/v1/sessions", [.json(201, stored), .json(200, stored)])
        let first = try await rig.api.startSession(id: "same", kind: "focus", mode: "timer", plannedMinutes: 25, title: "Work")
        let second = try await rig.api.startSession(id: "same", kind: "focus", mode: "timer", plannedMinutes: 25, title: "Work")
        XCTAssertEqual(first, second)
        let ids = rig.server.calls("/v1/sessions").map { $0.body["id"] as? String ?? "" }
        XCTAssertEqual(ids, ["same", "same"])
    }

    func testSessionAction() async throws {
        let rig = TestRig()
        let path = "/v1/sessions/session-1/actions"
        rig.server.on(path, [.json(200, "{\"session\":{\"id\":\"session-1\"}}")])
        try await rig.api.sessionAction(id: "session-1", action: .complete)
        XCTAssertEqual(rig.server.calls(path).first?.body["action"] as? String, "complete")
    }

    func testEventKinds() async throws {
        let rig = TestRig()
        rig.server.on("/v1/calendar/events", [.json(201, "{\"event\":{\"id\":\"x\"}}")])
        let end = TestRig.now.addingTimeInterval(600)
        try await rig.api.addEvent(title: "Walk", start: TestRig.now, end: end, kind: "walk")
        try await rig.api.addEvent(title: "Plan", start: TestRig.now, end: end, kind: "meeting")
        try await rig.api.addEvent(title: "Other", start: TestRig.now, end: end, kind: "something")
        let calls = rig.server.calls("/v1/calendar/events")
        assertClientId(calls[0])
        XCTAssertEqual(calls[0].body["kind"] as? String, "recovery")
        XCTAssertEqual(calls[1].body["kind"] as? String, "meeting")
        XCTAssertNil(calls[2].body["kind"])
        XCTAssertEqual(WireDate.parse(calls[0].body["end"] as? String), end)
    }

    func testTaskPlannedForADaySendsNoTime() async throws {
        let rig = TestRig()
        rig.server.on("/v1/tasks", [.json(201, "{\"task\":{\"id\":\"x\"}}")])
        try await rig.api.addTask(title: "Tomorrow", plannedFor: "2030-03-18")
        let call = try XCTUnwrap(rig.server.calls("/v1/tasks").first)
        assertClientId(call)
        XCTAssertEqual(call.body["title"] as? String, "Tomorrow")
        XCTAssertEqual(call.body["plannedFor"] as? String, "2030-03-18")
        XCTAssertNil(call.body["dueAt"])
        XCTAssertNil(call.body["hasTime"])
    }
}

final class LiveAPITimeZoneTests: XCTestCase {
    private let noZone = "{\"sub\":\"sub-1\",\"timezone\":null,\"displayName\":\"Kept\",\"version\":7}"
    private let hasZone = "{\"sub\":\"sub-1\",\"timezone\":\"Asia/Kolkata\",\"version\":8}"
    private let saved = "{\"sub\":\"sub-1\",\"timezone\":\"Europe/Paris\",\"version\":8}"

    func testSetsTheZoneAloneWithTheVersionRead() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, noZone)])
        rig.server.on("/v1/me/profile", [.json(200, saved)])
        try await rig.api.setTimeZoneIfMissing("Europe/Paris")
        let put = try XCTUnwrap(rig.server.calls("/v1/me/profile").first)
        XCTAssertEqual(put.request.httpMethod, "PUT")
        XCTAssertEqual(put.header("If-Match"), "\"v7\"")
        XCTAssertEqual(Array(put.body.keys), ["timezone"])
        XCTAssertEqual(put.body["timezone"] as? String, "Europe/Paris")
        XCTAssertEqual(rig.server.calls("/v1/me").first?.request.httpMethod, "GET")
    }

    func testNeverOverwritesAZone() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, hasZone)])
        try await rig.api.setTimeZoneIfMissing("Europe/Paris")
        XCTAssertEqual(rig.server.calls("/v1/me/profile").count, 0)
    }

    func testAZoneSetInBetweenIsLeftAlone() async throws {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, noZone), .json(200, hasZone)])
        rig.server.on("/v1/me/profile", [.json(412, staleProfile)])
        try await rig.api.setTimeZoneIfMissing("Europe/Paris")
        XCTAssertEqual(rig.server.calls("/v1/me").count, 2)
        XCTAssertEqual(rig.server.calls("/v1/me/profile").count, 1)
    }

    func testAStaleVersionIsReadAgainOnce() async throws {
        let rig = TestRig()
        let newer = "{\"sub\":\"sub-1\",\"timezone\":null,\"version\":9}"
        rig.server.on("/v1/me", [.json(200, noZone), .json(200, newer)])
        rig.server.on("/v1/me/profile", [.json(412, staleProfile), .json(200, saved)])
        try await rig.api.setTimeZoneIfMissing("Europe/Paris")
        let puts = rig.server.calls("/v1/me/profile")
        XCTAssertEqual(puts.map { $0.header("If-Match") ?? "" }, ["\"v7\"", "\"v9\""])
    }

    func testASecondStaleVersionThrows() async {
        let rig = TestRig()
        rig.server.on("/v1/me", [.json(200, noZone)])
        rig.server.on("/v1/me/profile", [.json(412, staleProfile)])
        let error = await caught { try await rig.api.setTimeZoneIfMissing("Europe/Paris") }
        XCTAssertEqual((error as? AuraError)?.serverCode, "precondition_failed")
        XCTAssertEqual(rig.server.calls("/v1/me/profile").count, 2)
    }
}

final class DayKeyTests: XCTestCase {
    func testCalendarIsGregorianInThePhonesZone() {
        let calendar = DayKey.calendar
        XCTAssertEqual(calendar.identifier, Calendar.Identifier.gregorian)
        XCTAssertEqual(calendar.timeZone.identifier, TimeZone.current.identifier)
        XCTAssertEqual(calendar.locale?.identifier, "en_US_POSIX")
    }

    func testStringAndDateUseTheGregorianYear() throws {
        let noon = try XCTUnwrap(DayKey.calendar.date(from: DateComponents(year: 2025, month: 3, day: 7, hour: 12)))
        XCTAssertEqual(DayKey.string(noon), "2025-03-07")
        let day = try XCTUnwrap(DayKey.date("2025-03-07"))
        XCTAssertEqual(DayKey.string(day), "2025-03-07")
    }

    func testAnotherCalendarWouldNameAnotherYear() throws {
        // Why the default isn't the phone's calendar: this is the year a Buddhist phone used to send.
        var buddhist = Calendar(identifier: .buddhist)
        buddhist.timeZone = TimeZone.current
        let noon = try XCTUnwrap(DayKey.calendar.date(from: DateComponents(year: 2025, month: 3, day: 7, hour: 12)))
        XCTAssertEqual(DayKey.string(noon, calendar: buddhist), "2568-03-07")
    }
}

final class AuraErrorTests: XCTestCase {
    func testServerCode() {
        XCTAssertEqual(AuraError.server(code: "work_limit_reached", message: "Later.", status: 409).serverCode, "work_limit_reached")
        XCTAssertNil(AuraError.offline.serverCode)
        XCTAssertNil(AuraError.signedOut.serverCode)
        XCTAssertNil(AuraError.consentRequired.serverCode)
    }
}

final class StubAPITests: XCTestCase {
    func testStartSessionEchoesWithoutShortening() async throws {
        let stub = StubAPI()
        let timer = try await stub.startSession(id: "chosen", kind: "focus", mode: "timer", plannedMinutes: 25, title: "Work")
        XCTAssertEqual(timer, StartedSession(id: "chosen", joined: false, plannedMinutes: 25))
        let flow = try await stub.startSession(id: "open", kind: "focus", mode: "flowtime", plannedMinutes: 25, title: "Flow")
        XCTAssertEqual(flow, StartedSession(id: "open"))
    }

    func testJoinsTheSessionRunningElsewhereUntilItIsStopped() async throws {
        let stub = StubAPI(joinsRunningSession: true)
        let before = Date()
        let joined = try await stub.startSession(id: "mine", kind: "focus", mode: "timer", plannedMinutes: 25, title: "Work")
        XCTAssertEqual(joined.id, "running-elsewhere")
        XCTAssertTrue(joined.joined)
        XCTAssertEqual(joined.plannedMinutes, 50)
        XCTAssertEqual(joined.title, "Deep work")
        XCTAssertEqual(joined.kind, "focus")
        XCTAssertEqual(joined.device, "desktop")
        let endsAt = try XCTUnwrap(joined.endsAt)
        XCTAssertGreaterThanOrEqual(endsAt.timeIntervalSince(before), 25 * 60)
        XCTAssertLessThan(endsAt.timeIntervalSince(before), 26 * 60)

        // Pausing it, or acting on another id, leaves it running.
        try await stub.sessionAction(id: "running-elsewhere", action: .pause)
        try await stub.sessionAction(id: "mine", action: .stop)
        let still = try await stub.startSession(id: "mine", kind: "focus", mode: "timer", plannedMinutes: 25, title: "Work")
        XCTAssertTrue(still.joined)

        try await stub.sessionAction(id: "running-elsewhere", action: .stop)
        let mine = try await stub.startSession(id: "mine", kind: "focus", mode: "timer", plannedMinutes: 25, title: "Work")
        XCTAssertEqual(mine, StartedSession(id: "mine", joined: false, plannedMinutes: 25))
    }

    func testNewCallsDoNothing() async throws {
        let stub = StubAPI()
        try await stub.addTask(title: "Tomorrow", plannedFor: "2030-03-18")
        try await stub.setTimeZoneIfMissing("Europe/Paris")
    }
}
