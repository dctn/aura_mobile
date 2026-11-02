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

    func testEndEarlyTitle() {
        let run = RunEngine(minutes: 25, hasCountdown: false, now: t0)
        // 18:42 left, as in frame 13
        XCTAssertEqual(run.endEarlyTitle(at: t0.addingTimeInterval(378)), "End now? 19 minutes left.")
        XCTAssertEqual(run.endEarlyTitle(at: t0.addingTimeInterval(1_440)), "End now? 1 minute left.")
        XCTAssertEqual(run.endEarlyTitle(at: t0.addingTimeInterval(1_470)), "End now? Less than a minute left.")
        let open = RunEngine(minutes: 0, hasCountdown: false, now: t0)
        XCTAssertEqual(open.endEarlyTitle(at: t0.addingTimeInterval(768)), "End now? 12:48 so far.")
    }

    func testDoneLengthText() {
        XCTAssertEqual(RunEngine.lengthText(300), "5 minutes")
        XCTAssertEqual(RunEngine.lengthText(60), "1 minute")
        XCTAssertEqual(RunEngine.lengthText(3), "Under a minute")
    }

    func testPausedRunSurvivesARestart() throws {
        var run = RunEngine(minutes: 5, hasCountdown: false, now: t0)
        run.pause(at: t0.addingTimeInterval(60))
        run.resume(at: t0.addingTimeInterval(90))
        let saved = try JSONEncoder().encode(run)
        let back = try JSONDecoder().decode(RunEngine.self, from: saved)
        XCTAssertEqual(back, run)
        XCTAssertEqual(back.endDate(), t0.addingTimeInterval(330))
    }

    func testKickerByKind() {
        let walk = Mode(id: "w", name: "Walk", word: "Walk", shelf: .outside, minutes: 20, kind: "walk")
        XCTAssertEqual(walk.kicker(minutes: 20), "MOVE · 20 MIN")
        let nap = Mode(id: "n", name: "Nap", word: "Nap", shelf: .sleep, minutes: 10, night: true)
        XCTAssertEqual(nap.kicker(minutes: 10), "NAP · 10 MIN")
        let wind = Mode(id: "d", name: "Wind-down", word: "Wind Down", shelf: .sleep, minutes: 10, run: .winddown)
        XCTAssertEqual(wind.kicker(minutes: 10), "RECOVERY · 10 MIN")
        let flow = Mode(id: "f", name: "Flow", word: "Flow", shelf: .focus, minutes: 0, kind: "focus", run: .flow)
        XCTAssertEqual(flow.kicker(minutes: 0), "FOCUS · OPEN")
    }

    func testLengthAndBreathText() {
        XCTAssertEqual(Mode.lengthText(10), "10m")
        XCTAssertEqual(Mode.lengthText(480), "8h")
        XCTAssertEqual(Mode.lengthText(90), "1h 30m")
        XCTAssertEqual(Mode.lengthText(0), "Open")
        XCTAssertEqual(BreathPattern(in: 4, hold: 4, out: 4, hold2: 4).line, "In 4 · Hold 4 · Out 4 · Hold 4")
        XCTAssertEqual(BreathPattern(in: 4, hold: 7, out: 8).line, "In 4 · Hold 7 · Out 8")
    }

    func testPlanSlotIsTodayWhileAheadElseTomorrow() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        let noon = calendar.date(from: DateComponents(year: 2026, month: 11, day: 2, hour: 12)) ?? t0
        let evening = Mode(id: "alcohol-free-evening", name: "E", word: "E", shelf: .fuel, minutes: 240, run: .plan)
        let slot = evening.planSlot(now: noon, calendar: calendar)
        XCTAssertEqual(slot.start, calendar.date(from: DateComponents(year: 2026, month: 11, day: 2, hour: 18)))
        XCTAssertEqual(slot.end, calendar.date(from: DateComponents(year: 2026, month: 11, day: 2, hour: 22)))
        let dayOff = Mode(id: "day-off", name: "D", word: "D", shelf: .rest, minutes: 480, run: .plan)
        let late = dayOff.planSlot(now: noon, calendar: calendar)
        XCTAssertEqual(late.start, calendar.date(from: DateComponents(year: 2026, month: 11, day: 3, hour: 9)))
    }

    func testSearchMatchesNameAndWord() {
        let nap = Mode(id: "nap-10", name: "Ten-minute nap", word: "Nap", shelf: .sleep, minutes: 10)
        XCTAssertTrue(nap.matches(search: "TEN"))
        XCTAssertTrue(nap.matches(search: " nap "))
        XCTAssertTrue(nap.matches(search: ""))
        XCTAssertFalse(nap.matches(search: "walk"))
    }

    /// modes.json is the app's resource, so it's read from the repo here.
    private func loadModes() throws -> [Mode] {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf: root.appendingPathComponent("App/Resources/modes.json"))
        return try JSONDecoder().decode([Mode].self, from: data)
    }

    func testTheSixtyFiveModes() throws {
        let modes = try loadModes()
        XCTAssertEqual(modes.count, 65)
        XCTAssertEqual(Set(modes.map { $0.id }).count, 65)
        XCTAssertEqual(Set(modes.map { $0.shelf }).count, Shelf.allCases.count)
        XCTAssertFalse(modes.contains { $0.kind == "nap" })
        XCTAssertTrue(modes.filter { $0.run == .breathe }.allSatisfy { $0.pattern != nil })
        XCTAssertTrue(modes.filter { $0.run == .guided }.allSatisfy { $0.prompts?.count == 6 })
        XCTAssertEqual(modes.filter { $0.run == .plan }.count, 2)
        let nap = modes.first { $0.id == "nap-10" }
        XCTAssertEqual(nap?.night, true)
    }

    func testNowAlwaysHasCards() throws {
        let modes = try loadModes()
        for hour in 0..<24 {
            let cards = Mode.nowCards(from: modes, hour: hour, limit: 4)
            XCTAssertEqual(cards.count, 4, "hour \(hour)")
            let avoided = cards.contains { ($0.avoidHours ?? []).contains(hour) }
            XCTAssertFalse(avoided, "hour \(hour)")
        }
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
