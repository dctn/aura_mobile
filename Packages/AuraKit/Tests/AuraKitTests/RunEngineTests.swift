import XCTest
@testable import AuraKit

final class RunEngineTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000_000)

    func testClockSurvivesTheBackground() {
        let run = RunEngine(minutes: 5, hasCountdown: false, now: t0)
        // Nothing ticks while the app is away; the time is worked out from the clock.
        XCTAssertEqual(run.snapshot(at: t0.addingTimeInterval(120)).remaining, 180)
    }

    func testPauseStopsTheClockAndMovesTheEnd() {
        var run = RunEngine(minutes: 5, hasCountdown: false, now: t0)
        run.pause(at: t0.addingTimeInterval(60))
        XCTAssertEqual(run.snapshot(at: t0.addingTimeInterval(600)).phase, .paused)
        XCTAssertEqual(run.elapsed(at: t0.addingTimeInterval(600)), 60)
        XCTAssertNil(run.endDate())
        run.resume(at: t0.addingTimeInterval(120))
        XCTAssertEqual(run.elapsed(at: t0.addingTimeInterval(180)), 120)
        XCTAssertEqual(run.endDate(), t0.addingTimeInterval(360))
    }

    func testCountdownComesFirst() {
        let run = RunEngine(minutes: 25, hasCountdown: true, now: t0)
        XCTAssertEqual(run.snapshot(at: t0.addingTimeInterval(1)).phase, .countdown(left: 2))
        XCTAssertEqual(run.snapshot(at: t0.addingTimeInterval(3)).phase, .running)
        XCTAssertEqual(run.snapshot(at: t0.addingTimeInterval(4)).elapsed, 1)
    }

    func testTimeUpAndOpenEnded() {
        let timed = RunEngine(minutes: 1, hasCountdown: false, now: t0)
        XCTAssertEqual(timed.snapshot(at: t0.addingTimeInterval(61)).phase, .timeUp)
        let open = RunEngine(minutes: 0, hasCountdown: false, now: t0)
        let later = open.snapshot(at: t0.addingTimeInterval(90_000))
        XCTAssertEqual(later.phase, .running)
        XCTAssertNil(later.remaining)
        XCTAssertNil(later.progress)
    }

    func testBoxBreathingPhases() {
        let box = BreathPattern(in: 4, hold: 4, out: 4, hold2: 4)
        XCTAssertEqual(BreathPhase.at(0, pattern: box).scale, BreathPhase.smallest, accuracy: 0.001)
        XCTAssertEqual(BreathPhase.at(1, pattern: box).left, 3)
        let held = BreathPhase.at(5, pattern: box)
        XCTAssertEqual(held.word, .hold)
        XCTAssertEqual(held.scale, 1, accuracy: 0.001)
        XCTAssertEqual(BreathPhase.at(9, pattern: box).word, .exhale)
        let empty = BreathPhase.at(13, pattern: box)
        XCTAssertEqual(empty.word, .hold)
        XCTAssertEqual(empty.scale, BreathPhase.smallest, accuracy: 0.001)
        XCTAssertEqual(BreathPhase.at(16, pattern: box).word, .inhale)
    }

    func testPromptsAdvanceEveryLengthOverCount() {
        let run = RunEngine(minutes: 6, hasCountdown: false, now: t0)
        XCTAssertEqual(run.promptIndex(at: t0, count: 6), 0)
        XCTAssertEqual(run.promptIndex(at: t0.addingTimeInterval(61), count: 6), 1)
        XCTAssertEqual(run.promptIndex(at: t0.addingTimeInterval(9_999), count: 6), 5)
    }

    func testClockText() {
        XCTAssertEqual(RunEngine.clock(594), "09:54")
        XCTAssertEqual(RunEngine.clock(3723), "1:02:03")
    }

    func testBurnoutDotsAndTrend() {
        XCTAssertEqual(MetricMaths.burnoutLevel(score: nil), 0)
        XCTAssertEqual(MetricMaths.burnoutLevel(score: 72), 9)
        XCTAssertEqual(MetricMaths.burnoutLevel(score: 100), 12)
        let old = (0..<7).map { MetricPoint(date: "d\($0)", value: 2) }
        let new = (7..<14).map { MetricPoint(date: "d\($0)", value: 3) }
        XCTAssertEqual(MetricMaths.weekTrend(old + new) ?? 0, 50, accuracy: 0.001)
        XCTAssertNil(MetricMaths.weekTrend(new))
    }
}
