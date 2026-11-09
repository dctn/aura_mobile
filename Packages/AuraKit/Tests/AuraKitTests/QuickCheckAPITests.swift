import Foundation
import XCTest
@testable import AuraKit

private let checksPath = "/v1/checks"

/// A valid full check as Health Data answers it, with the day's scores after it.
private let fullAnswer = """
{"check":{"id":"check-1","takenAt":"2030-03-17T03:10:00.000Z","date":"2030-03-17","kind":"full","valid":true,
  "invalidReason":null,"minutesSinceWake":42,
  "attention":{"meanRtMs":291.4,"medianRtMs":284.5,"lapses":2,"falseStarts":1,"speed":3.52,"performance":0.9524},
  "qualifiesForNormal":true},
 "scores":{"date":"2030-03-17",
  "attention":{"value":96,"status":"ready","checksCounted":14,"checksNeeded":14,"vsNormal":"usual","at":"2030-03-17T03:10:00.000Z"},
  "memory":{"value":null,"status":"learning","checksCounted":3,"checksNeeded":14,"vsNormal":null,"at":null},
  "thinking":{"value":null,"status":"noData","checksCounted":0,"checksNeeded":14,"vsNormal":null,"at":null},
  "focus":{"value":null,"status":"noData","checksCounted":0,"checksNeeded":14,"vsNormal":null,"at":null}},
 "created":true}
"""

/// A run that was too short: not valid, with the server's reason.
private let invalidAnswer = """
{"check":{"id":"check-2","takenAt":"2030-03-17T03:10:00.000Z","date":"2030-03-17","kind":"pvtB","valid":false,
  "invalidReason":"not_3_minutes","minutesSinceWake":null,
  "attention":{"meanRtMs":301,"medianRtMs":299,"lapses":0,"falseStarts":0,"speed":3.3,"performance":1},
  "qualifiesForNormal":false},
 "scores":{"date":"2030-03-17",
  "attention":{"value":null,"status":"learning","checksCounted":2,"checksNeeded":14,"vsNormal":null,"at":null},
  "memory":{"value":null,"status":"noData","checksCounted":0,"checksNeeded":14,"vsNormal":null,"at":null},
  "thinking":{"value":null,"status":"noData","checksCounted":0,"checksNeeded":14,"vsNormal":null,"at":null},
  "focus":{"value":null,"status":"noData","checksCounted":0,"checksNeeded":14,"vsNormal":null,"at":null}},
 "created":false}
"""

private let consentRequiredAnswer = "{\"error\":{\"code\":\"consent_required\",\"message\":\"Health data consent is needed.\"}}"

/// A run of `answers` reactions of `ms` each.
private func makeRun(id: String = UUID().uuidString, takenAt: Date = Date(), seconds: Int = 180, answers: Int = 60,
                     ms: Double = 300, falseStarts: Int = 0, memory: SpanResult? = nil) -> QuickCheckRun {
    return QuickCheckRun(id: id, takenAt: takenAt, durationSeconds: seconds,
                         reactionTimesMs: [Double](repeating: ms, count: answers), falseStarts: falseStarts, memory: memory)
}

final class QuickCheckLiveTests: XCTestCase {
    // MARK: Request

    func testAReactionOnlyCheckIsPostedToHealthData() async throws {
        let rig = TestRig()
        rig.server.on(checksPath, [.json(201, fullAnswer)])
        let run = QuickCheckRun(id: "6f1c2a3e-1111-4222-8333-944455556666", takenAt: TestRig.now, durationSeconds: 180,
                                reactionTimesMs: [312.4, 287.6, 10_000], falseStarts: 1)
        _ = try await rig.api.submitCheck(run)

        let call = try XCTUnwrap(rig.server.calls(checksPath).first)
        XCTAssertEqual(call.request.httpMethod, "POST")
        XCTAssertEqual(call.request.url?.host, "health-data.stub.test")
        XCTAssertEqual(call.request.url?.path, "/v1/checks")
        XCTAssertEqual(call.body.keys.sorted(),
                       ["device", "id", "kind", "memory", "minutesSinceWake", "pvt", "switching", "takenAt"])
        XCTAssertEqual(call.body["id"] as? String, "6f1c2a3e-1111-4222-8333-944455556666")
        XCTAssertEqual(call.body["device"] as? String, "phone")
        XCTAssertEqual(call.body["kind"] as? String, "pvtB")
        XCTAssertTrue(call.body["minutesSinceWake"] is NSNull, "The key is there, as null")
        XCTAssertTrue(call.body["switching"] is NSNull, "The key is there, as null")
        XCTAssertTrue(call.body["memory"] is NSNull, "No memory part: null")

        let takenAt = try XCTUnwrap(call.body["takenAt"] as? String)
        XCTAssertEqual(WireDate.parse(takenAt), TestRig.now)
        XCTAssertTrue(takenAt.hasSuffix("Z") || takenAt.contains("+") || takenAt.dropFirst(19).contains("-"),
                      "An instant with an offset")

        let pvt = try XCTUnwrap(call.body["pvt"] as? [String: Any])
        XCTAssertEqual(pvt.keys.sorted(), ["durationSeconds", "falseStarts", "reactionTimesMs"])
        XCTAssertEqual(pvt["durationSeconds"] as? Int, 180)
        XCTAssertEqual(pvt["falseStarts"] as? Int, 1)
        XCTAssertEqual(pvt["reactionTimesMs"] as? [Int], [312, 288, 10_000], "Whole milliseconds")
    }

    func testAFullCheckSendsTheMemoryPart() async throws {
        let rig = TestRig()
        rig.server.on(checksPath, [.json(201, fullAnswer)])
        let run = makeRun(takenAt: TestRig.now, memory: SpanResult(spanCorrect: 4, spanAttempts: 6))
        XCTAssertEqual(run.kind, "full")
        _ = try await rig.api.submitCheck(run)

        let call = try XCTUnwrap(rig.server.calls(checksPath).first)
        XCTAssertEqual(call.body.keys.sorted(),
                       ["device", "id", "kind", "memory", "minutesSinceWake", "pvt", "switching", "takenAt"])
        XCTAssertEqual(call.body["kind"] as? String, "full")
        XCTAssertEqual(call.body["id"] as? String, run.id)
        let memory = try XCTUnwrap(call.body["memory"] as? [String: Any])
        XCTAssertEqual(memory.keys.sorted(), ["spanAttempts", "spanCorrect"])
        XCTAssertEqual(memory["spanCorrect"] as? Int, 4)
        XCTAssertEqual(memory["spanAttempts"] as? Int, 6)
        XCTAssertTrue(call.body["minutesSinceWake"] is NSNull)
        XCTAssertTrue(call.body["switching"] is NSNull)
    }

    func testResendingSendsTheSameId() async throws {
        let rig = TestRig()
        rig.server.on(checksPath, [.json(201, fullAnswer), .json(200, fullAnswer)])
        let run = makeRun(takenAt: TestRig.now)
        let first = try await rig.api.submitCheck(run)
        let second = try await rig.api.submitCheck(run)
        XCTAssertEqual(first, second)
        let ids = rig.server.calls(checksPath).map { $0.body["id"] as? String }
        XCTAssertEqual(ids, [run.id, run.id])
    }

    // MARK: Answer

    func testAFullAnswerDecodes() async throws {
        let rig = TestRig()
        rig.server.on(checksPath, [.json(201, fullAnswer)])
        let result = try await rig.api.submitCheck(makeRun(takenAt: TestRig.now))
        let scores = ScoresToday(attention: Score(name: "Attention", value: 96, checksCounted: 14, checksNeeded: 14),
                                 memory: Score(name: "Memory", value: nil, checksCounted: 3, checksNeeded: 14),
                                 thinking: Score(name: "Thinking", value: nil, checksCounted: 0, checksNeeded: 14),
                                 focus: Score(name: "Focus", value: nil, checksCounted: 0, checksNeeded: 14))
        let expected = QuickCheckResult(id: "check-1", valid: true, invalidReason: nil, meanRtMs: 291.4, medianRtMs: 284.5,
                                        lapses: 2, falseStarts: 1, performance: 0.9524, qualifiesForNormal: true,
                                        scores: scores)
        XCTAssertEqual(result, expected)
    }

    func testAnInvalidAnswerCarriesItsReason() async throws {
        let rig = TestRig()
        rig.server.on(checksPath, [.json(200, invalidAnswer)])
        let result = try await rig.api.submitCheck(makeRun(takenAt: TestRig.now, seconds: 60))
        XCTAssertFalse(result.valid)
        XCTAssertEqual(result.invalidReason, "not_3_minutes")
        XCTAssertFalse(result.qualifiesForNormal)
        XCTAssertEqual(result.id, "check-2")
        XCTAssertEqual(result.meanRtMs, 301)
        XCTAssertEqual(result.performance, 1)
        XCTAssertEqual(result.scores.attention, Score(name: "Attention", value: nil, checksCounted: 2, checksNeeded: 14))
    }

    func testMissingMeasuresReadAsZero() async throws {
        let rig = TestRig()
        rig.server.on(checksPath, [.json(201, "{\"check\":{\"id\":\"check-3\",\"valid\":true},\"created\":true}")])
        let result = try await rig.api.submitCheck(makeRun(takenAt: TestRig.now))
        XCTAssertTrue(result.valid)
        XCTAssertNil(result.invalidReason)
        XCTAssertEqual(result.lapses, 0)
        XCTAssertEqual(result.meanRtMs, 0)
        XCTAssertFalse(result.qualifiesForNormal)
        XCTAssertEqual(result.scores.attention, Score(name: "Attention", value: nil))
    }

    func testAnAnswerWithoutTheCheckIsABadResponse() async {
        let rig = TestRig()
        rig.server.on(checksPath, [.json(201, "{\"scores\":{},\"created\":true}")])
        let error = await caught { _ = try await rig.api.submitCheck(makeRun(takenAt: TestRig.now)) }
        XCTAssertEqual((error as? AuraError)?.serverCode, "bad_response")
    }

    func testAnAnswerWithoutValidIsABadResponse() async {
        let rig = TestRig()
        rig.server.on(checksPath, [.json(201, "{\"check\":{\"id\":\"check-4\"},\"created\":true}")])
        let error = await caught { _ = try await rig.api.submitCheck(makeRun(takenAt: TestRig.now)) }
        XCTAssertEqual((error as? AuraError)?.serverCode, "bad_response")
    }

    // MARK: Errors

    func testConsentRequiredComesThroughAsTheOtherCallsThrowIt() async {
        let rig = TestRig()
        rig.server.on(checksPath, [.json(403, consentRequiredAnswer)])
        let error = await caught { _ = try await rig.api.submitCheck(makeRun(takenAt: TestRig.now)) }
        XCTAssertEqual(error as? AuraError, AuraError.consentRequired)
        XCTAssertEqual(rig.server.calls(checksPath).count, 1, "No retry without a grant just made")
    }

    func testValidationErrorsComeThrough() async {
        let rig = TestRig()
        let answer = "{\"error\":{\"code\":\"validation_failed\",\"message\":\"pvt.durationSeconds is too small.\",\"details\":null}}"
        rig.server.on(checksPath, [.json(400, answer)])
        let error = await caught { _ = try await rig.api.submitCheck(makeRun(takenAt: TestRig.now, seconds: 5)) }
        XCTAssertEqual(error as? AuraError, AuraError.server(code: "validation_failed",
                                                             message: "pvt.durationSeconds is too small.", status: 400))
    }
}

final class QuickCheckStubTests: XCTestCase {
    // MARK: Scoring

    func testAThreeMinuteRunWithSixtyAnswersIsValid() async throws {
        let stub = StubAPI()
        let result = try await stub.submitCheck(makeRun(seconds: 180, answers: 60, ms: 300))
        XCTAssertTrue(result.valid)
        XCTAssertNil(result.invalidReason)
        XCTAssertEqual(result.meanRtMs, 300)
        XCTAssertEqual(result.medianRtMs, 300)
        XCTAssertEqual(result.lapses, 0)
        XCTAssertEqual(result.falseStarts, 0)
        XCTAssertEqual(result.performance, 1)
        XCTAssertTrue(result.qualifiesForNormal)
    }

    func testAOneMinuteRunIsNotValid() async throws {
        let stub = StubAPI()
        let result = try await stub.submitCheck(makeRun(seconds: 60, answers: 20))
        XCTAssertFalse(result.valid)
        XCTAssertEqual(result.invalidReason, "not_3_minutes")
        XCTAssertFalse(result.qualifiesForNormal)

        let tooLong = try await stub.submitCheck(makeRun(seconds: 201, answers: 60))
        XCTAssertEqual(tooLong.invalidReason, "not_3_minutes", "The length is checked before the answers")
    }

    func testTooFewAnswersIsNotValid() async throws {
        let result = try await StubAPI().submitCheck(makeRun(seconds: 180, answers: 29))
        XCTAssertFalse(result.valid)
        XCTAssertEqual(result.invalidReason, "too_few_responses")
    }

    func testTooManyFalseStartsIsNotValid() async throws {
        let stub = StubAPI()
        // 20 taps with no dot against 60 answers: more than 20% of 80.
        let tooMany = try await stub.submitCheck(makeRun(seconds: 180, answers: 60, falseStarts: 20))
        XCTAssertFalse(tooMany.valid)
        XCTAssertNotNil(tooMany.invalidReason)
        XCTAssertEqual(tooMany.invalidReason, "too_many_false_starts")
        XCTAssertEqual(tooMany.falseStarts, 20)

        // 12 of 72 is under 20%.
        let fine = try await stub.submitCheck(makeRun(seconds: 180, answers: 60, falseStarts: 12))
        XCTAssertTrue(fine.valid)
    }

    /// As the server: reactions under 100 ms are false starts, 355 ms or slower are lapses.
    func testMeasuresFollowTheServer() {
        let run = QuickCheckRun(takenAt: Date(), durationSeconds: 180, reactionTimesMs: [250, 400, 90, 300], falseStarts: 1)
        let measured = StubCheck.measure(run, shortChecksCount: false)
        XCTAssertEqual(measured.falseStarts, 2)
        XCTAssertEqual(measured.lapses, 1)
        XCTAssertEqual(measured.meanRtMs, 316.7)
        XCTAssertEqual(measured.medianRtMs, 300)
        XCTAssertEqual(measured.performance, 0.4)
        XCTAssertEqual(measured.invalidReason, "too_few_responses")

        let none = StubCheck.measure(QuickCheckRun(takenAt: Date(), durationSeconds: 180, reactionTimesMs: [50],
                                                   falseStarts: 0), shortChecksCount: false)
        XCTAssertEqual(none.meanRtMs, 0)
        XCTAssertEqual(none.performance, 0)
        XCTAssertEqual(none.falseStarts, 1)
    }

    func testShortChecksCountMakesAFiveSecondRunValid() async throws {
        let short = makeRun(seconds: 5, answers: 2, ms: 320)
        let strict = try await StubAPI().submitCheck(short)
        XCTAssertFalse(strict.valid)

        let stub = StubAPI(shortChecksCount: true)
        let result = try await stub.submitCheck(short)
        XCTAssertTrue(result.valid)
        XCTAssertNil(result.invalidReason)
        XCTAssertEqual(result.meanRtMs, 320)
    }

    // MARK: Gate and plan

    func testAValidCheckClearsTheQuickCheckGate() async throws {
        let stub = StubAPI(needsQuickCheck: true)
        let today = DayKey.string(Date())
        let before = await caught { _ = try await stub.allocate(date: today, dryRun: true, skipCheck: false, taskIds: nil) }
        XCTAssertEqual((before as? AuraError)?.serverCode, "quick_check_required")

        // A check that doesn't count leaves the gate shut.
        _ = try await stub.submitCheck(makeRun(seconds: 60))
        let stillShut = await caught { _ = try await stub.allocate(date: today, dryRun: true, skipCheck: false, taskIds: nil) }
        XCTAssertEqual((stillShut as? AuraError)?.serverCode, "quick_check_required")

        let takenAt = Date()
        let result = try await stub.submitCheck(makeRun(takenAt: takenAt))
        XCTAssertTrue(result.valid)
        let plan = try await stub.allocate(date: today, dryRun: true, skipCheck: false, taskIds: nil)
        XCTAssertEqual(plan.basis.quickCheckAt, takenAt)
        XCTAssertFalse(plan.placed.isEmpty)

        // A later valid check doesn't replace the day's first.
        _ = try await stub.submitCheck(makeRun(takenAt: takenAt.addingTimeInterval(1)))
        let again = try await stub.allocate(date: today, dryRun: true, skipCheck: false, taskIds: nil)
        XCTAssertEqual(again.basis.quickCheckAt, takenAt)
    }

    func testValidChecksRaiseAttentionsCount() async throws {
        let stub = StubAPI()
        let start = try await stub.scoresToday()
        XCTAssertEqual(start.attention.checksCounted, 0)

        let result = try await stub.submitCheck(makeRun())
        XCTAssertEqual(result.scores.attention.checksCounted, 1)
        XCTAssertEqual(result.scores.attention.value, 75)
        _ = try await stub.submitCheck(makeRun(seconds: 60))
        let after = try await stub.scoresToday()
        XCTAssertEqual(after.attention.checksCounted, 1, "A check that isn't valid doesn't count")
    }

    func testResendingTheSameIdReturnsTheSameResult() async throws {
        let stub = StubAPI()
        let run = makeRun(id: "check-same")
        let first = try await stub.submitCheck(run)
        let second = try await stub.submitCheck(run)
        XCTAssertEqual(first, second)
        XCTAssertEqual(second.id, "check-same")
        let scores = try await stub.scoresToday()
        XCTAssertEqual(scores.attention.checksCounted, 1, "Stored once")
    }
}
