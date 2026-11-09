import XCTest
@testable import AuraKit

/// Hands out scripted values in turn, round and round, so a test knows every gap and digit.
private final class Scripted<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private let values: [Value]
    private var index = 0

    init(_ values: [Value]) { self.values = values }

    func next() -> Value {
        lock.lock()
        defer { lock.unlock() }
        let value = values[index % values.count]
        index += 1
        return value
    }
}

/// A reaction test whose gaps come from `randoms` (gap = 1 + r * 3 with the standard config).
private func reaction(_ randoms: [Double], config: ReactionTest.Config = .standard) -> ReactionTest {
    let script = Scripted<Double>(randoms)
    return ReactionTest(config: config, random: { script.next() })
}

/// A span test whose digits come from `digits`.
private func span(_ digits: [Int], config: SpanTest.Config = .standard) -> SpanTest {
    let script = Scripted<Int>(digits)
    return SpanTest(config: config, digit: { script.next() })
}

/// Types a whole row; returns what the last digit returned.
@discardableResult
private func enter(_ row: [Int], into test: inout SpanTest) -> Bool? {
    var last: Bool?
    for digit in row { last = test.type(digit) }
    return last
}

final class QuickCheckEngineTests: XCTestCase {
    // MARK: Reaction test

    func testStandardConfig() {
        let config = ReactionTest.Config.standard
        XCTAssertEqual(config.duration, 180)
        XCTAssertEqual(config.minGap, 1)
        XCTAssertEqual(config.maxGap, 4)
        XCTAssertEqual(config.showResultFor, 1)
        XCTAssertEqual(config.timeout, 10)
        XCTAssertEqual(ReactionTest.Config.short(seconds: 8).duration, 8)
        XCTAssertEqual(ReactionTest.Config.short(seconds: 8).maxGap, 4)
    }

    func testFirstDotComesAfterTheGap() {
        var test = reaction([0.5])
        XCTAssertEqual(test.phase, .notStarted)
        test.start(at: 0)
        XCTAssertEqual(test.phase, .waiting)
        test.tick(at: 1)
        XCTAssertEqual(test.phase, .waiting)
        test.tick(at: 2.49)
        XCTAssertEqual(test.phase, .waiting)
        test.tick(at: 2.5)
        XCTAssertEqual(test.phase, .dot)

        var soonest = reaction([0])
        soonest.start(at: 0)
        soonest.tick(at: 0.99)
        XCTAssertEqual(soonest.phase, .waiting)
        soonest.tick(at: 1)
        XCTAssertEqual(soonest.phase, .dot)

        var latest = reaction([0.999])
        latest.start(at: 0)
        latest.tick(at: 3.99)
        XCTAssertEqual(latest.phase, .waiting)
        latest.tick(at: 4)
        XCTAssertEqual(latest.phase, .dot)
    }

    func testTapOnTheDotRecordsTheReaction() {
        var test = reaction([0.5])
        test.start(at: 0)
        test.tick(at: 2.5)
        let outcome = test.tap(at: 2.75)
        XCTAssertEqual(outcome, .reaction(ms: 250))
        XCTAssertEqual(test.phase, .result(ms: 250))
        XCTAssertEqual(test.reactionTimesMs, [250])
        XCTAssertEqual(test.falseStarts, 0)
        // The time shows for a second, then the card is dark until the next dot (2.75 + 2.5).
        test.tick(at: 3.5)
        XCTAssertEqual(test.phase, .result(ms: 250))
        test.tick(at: 3.75)
        XCTAssertEqual(test.phase, .waiting)
        test.tick(at: 5.0)
        XCTAssertEqual(test.phase, .waiting)
        test.tick(at: 5.25)
        XCTAssertEqual(test.phase, .dot)
    }

    func testTapWithNoDotIsAFalseStart() {
        var test = reaction([0.5, 0, 0])
        test.start(at: 0)
        let early = test.tap(at: 1)
        XCTAssertEqual(early, .falseStart)
        XCTAssertEqual(test.falseStarts, 1)
        XCTAssertEqual(test.phase, .tooSoon)
        XCTAssertTrue(test.reactionTimesMs.isEmpty)
        // The gap is drawn again from the tap (r = 0, so 1 s): the dot that was due at 2.5 is now due at 2.
        test.tick(at: 1.5)
        XCTAssertEqual(test.phase, .tooSoon)
        test.tick(at: 2)
        XCTAssertEqual(test.phase, .dot)
        let got1 = test.tap(at: 2.25)
        XCTAssertEqual(got1, .reaction(ms: 250))
        // A tap while the time shows is a false start too.
        let got2 = test.tap(at: 2.5)
        XCTAssertEqual(got2, .falseStart)
        XCTAssertEqual(test.falseStarts, 2)
        XCTAssertEqual(test.phase, .tooSoon)
        XCTAssertEqual(test.reactionTimesMs, [250])
    }

    func testUnansweredDotRecordsTheTimeout() {
        var test = reaction([0])
        test.start(at: 0)
        test.tick(at: 1)
        XCTAssertEqual(test.phase, .dot)
        test.tick(at: 10.99)
        XCTAssertEqual(test.phase, .dot)
        test.tick(at: 11)
        XCTAssertEqual(test.reactionTimesMs, [10_000])
        XCTAssertEqual(test.phase, .waiting)
        XCTAssertFalse(test.isFinished)
        // The run carries on: the next gap counts from the timeout.
        test.tick(at: 12)
        XCTAssertEqual(test.phase, .dot)
        let got3 = test.tap(at: 12.25)
        XCTAssertEqual(got3, .reaction(ms: 250))
        XCTAssertEqual(test.reactionTimesMs, [10_000, 250])
        XCTAssertEqual(test.falseStarts, 0)
    }

    func testRunEndsAtTheDuration() {
        var test = reaction([0], config: .short(seconds: 10))
        test.start(at: 0)
        XCTAssertEqual(test.remaining(at: 4), 6)
        test.tick(at: 4.5)
        XCTAssertEqual(test.durationSeconds, 4)
        test.tick(at: 9.5)
        XCTAssertEqual(test.phase, .dot)
        test.tick(at: 10)
        XCTAssertEqual(test.phase, .finished)
        XCTAssertTrue(test.isFinished)
        // The dot showing at the end is dropped.
        XCTAssertTrue(test.reactionTimesMs.isEmpty)
        let got4 = test.tap(at: 10.5)
        XCTAssertEqual(got4, .ignored)
        XCTAssertEqual(test.falseStarts, 0)
        test.tick(at: 30)
        XCTAssertEqual(test.phase, .finished)
        XCTAssertEqual(test.remaining(at: 12), 0)
        XCTAssertEqual(test.remaining(at: 9), 0)
        XCTAssertEqual(test.durationSeconds, 10)

        // A tap at the end, before any tick saw it, ends the run and is ignored.
        var late = reaction([0], config: .short(seconds: 10))
        late.start(at: 0)
        late.tick(at: 9.5)
        let got5 = late.tap(at: 10)
        XCTAssertEqual(got5, .ignored)
        XCTAssertTrue(late.isFinished)
        XCTAssertTrue(late.reactionTimesMs.isEmpty)
        XCTAssertEqual(late.durationSeconds, 10)
    }

    func testRemainingNeverGoesBelowZero() {
        var test = reaction([0.5])
        XCTAssertEqual(test.remaining(at: 0), 180)
        test.start(at: 100)
        XCTAssertEqual(test.remaining(at: 160), 120)
        XCTAssertEqual(test.remaining(at: 1_000), 0)
        let late = test.tap(at: 1_000)
        XCTAssertEqual(late, .ignored)
        XCTAssertEqual(test.remaining(at: 1_000), 0)
        XCTAssertEqual(test.durationSeconds, 180)
    }

    func testRecentHoldsTheNewestThree() {
        var test = reaction([0])
        test.start(at: 0)
        XCTAssertEqual(test.recent, [])
        test.tick(at: 1)
        test.tap(at: 1.25)
        XCTAssertEqual(test.recent, [250])
        // Each gap is 1 s, the same as the time showing, so the next dot is due as the time goes.
        test.tick(at: 2.25)
        XCTAssertEqual(test.phase, .dot)
        test.tap(at: 2.375)
        test.tick(at: 3.375)
        test.tap(at: 3.75)
        test.tick(at: 4.75)
        test.tap(at: 5.25)
        XCTAssertEqual(test.reactionTimesMs, [250, 125, 375, 500])
        XCTAssertEqual(test.recent, [500, 375, 125])
    }

    func testAFullRunHasAPlausibleNumberOfDots() {
        var test = reaction([0.1, 0.5, 0.9, 0.3, 0.7])
        test.start(at: 0)
        var dotSeenAt: TimeInterval?
        // 100 ticks a second; each dot is tapped about 300 ms after it shows.
        for i in 1...18_100 {
            let t = Double(i) / 100
            test.tick(at: t)
            if test.phase == .dot, dotSeenAt == nil { dotSeenAt = t }
            if let seen = dotSeenAt, t >= seen + 0.3 {
                test.tap(at: t)
                dotSeenAt = nil
            }
        }
        XCTAssertTrue(test.isFinished)
        XCTAssertEqual(test.durationSeconds, 180)
        XCTAssertEqual(test.falseStarts, 0)
        let count = test.reactionTimesMs.count
        XCTAssertTrue((36...90).contains(count), "\(count) answers")
        XCTAssertTrue(test.reactionTimesMs.allSatisfy { (290...320).contains($0) })
    }

    // MARK: Span test

    func testSpanConfigs() {
        XCTAssertEqual(SpanTest.Config.standard, SpanTest.Config(startLength: 3, maxLength: 9, showPerDigit: 1))
        XCTAssertEqual(SpanTest.Config.short, SpanTest.Config(startLength: 3, maxLength: 3, showPerDigit: 1))
    }

    func testBeginShowsThreeDigitsWithNoEqualNeighbours() {
        var test = span([4, 4, 7, 7, 2])
        test.begin()
        XCTAssertEqual(test.phase, .showing)
        XCTAssertEqual(test.digits, [4, 7, 2])
        XCTAssertEqual(test.showDuration, 3)
        // Typing while the row shows is ignored.
        let got6 = test.type(4)
        XCTAssertNil(got6)
        XCTAssertEqual(test.entered, [])
        test.hideDigits()
        XCTAssertEqual(test.phase, .entering)

        // A source stuck on one digit still makes a row.
        var stuck = span([5])
        stuck.begin()
        XCTAssertEqual(stuck.digits.count, 3)
        XCTAssertNotEqual(stuck.digits[0], stuck.digits[1])
        XCTAssertNotEqual(stuck.digits[1], stuck.digits[2])
    }

    func testRightAnswerGrowsTheRow() {
        var test = span([1, 2, 3, 4, 5, 6, 7, 8, 9, 0])
        test.begin()
        XCTAssertEqual(test.digits, [1, 2, 3])
        test.hideDigits()
        let got7 = test.type(1)
        XCTAssertNil(got7)
        let got8 = test.type(2)
        XCTAssertNil(got8)
        let got9 = test.type(3)
        XCTAssertEqual(got9, true)
        XCTAssertEqual(test.phase, .showing)
        XCTAssertEqual(test.digits, [4, 5, 6, 7])
        XCTAssertEqual(test.entered, [])
        XCTAssertEqual(test.spanCorrect, 1)
        XCTAssertEqual(test.spanAttempts, 1)
        XCTAssertEqual(test.bestSpan, 3)
    }

    func testWrongAnswerRetriesThenTwoWrongsFinish() {
        var test = span([1, 2, 3, 4, 5, 6, 7, 8, 9, 0])
        test.begin()
        test.hideDigits()
        let got10 = enter([1, 2, 9], into: &test)
        XCTAssertEqual(got10, false)
        // One more try at the same length, with new digits.
        XCTAssertEqual(test.phase, .showing)
        XCTAssertEqual(test.digits, [4, 5, 6])
        XCTAssertEqual(test.spanAttempts, 1)
        XCTAssertEqual(test.spanCorrect, 0)
        XCTAssertEqual(test.bestSpan, 0)
        test.hideDigits()
        let got11 = enter([4, 5, 6], into: &test)
        XCTAssertEqual(got11, true)
        XCTAssertEqual(test.digits, [7, 8, 9, 0])
        // The wrong at length 3 doesn't count against length 4.
        test.hideDigits()
        let got12 = enter([0, 0, 0, 0], into: &test)
        XCTAssertEqual(got12, false)
        XCTAssertEqual(test.phase, .showing)
        XCTAssertEqual(test.digits, [1, 2, 3, 4])
        test.hideDigits()
        let got13 = enter([4, 3, 2, 1], into: &test)
        XCTAssertEqual(got13, false)
        XCTAssertEqual(test.phase, .finished)
        XCTAssertTrue(test.isFinished)
        XCTAssertEqual(test.spanCorrect, 1)
        XCTAssertEqual(test.spanAttempts, 4)
        XCTAssertEqual(test.bestSpan, 3)
        XCTAssertEqual(test.result, SpanResult(spanCorrect: 1, spanAttempts: 4))
        let got14 = test.type(1)
        XCTAssertNil(got14)
        XCTAssertEqual(test.spanAttempts, 4)
    }

    func testDeleteLast() {
        var test = span([1, 2, 3, 4, 5, 6, 7, 8, 9, 0])
        test.begin()
        test.hideDigits()
        test.deleteLast()
        XCTAssertEqual(test.entered, [])
        test.type(1)
        test.type(9)
        test.deleteLast()
        XCTAssertEqual(test.entered, [1])
        test.type(2)
        let got15 = test.type(3)
        XCTAssertEqual(got15, true)
        XCTAssertEqual(test.spanCorrect, 1)
    }

    func testRightAnswerAtMaxLengthFinishes() {
        let four = SpanTest.Config(startLength: 3, maxLength: 4, showPerDigit: 1)
        var test = span([1, 2, 3, 4, 5, 6, 7, 8, 9, 0], config: four)
        test.begin()
        test.hideDigits()
        let got16 = enter([1, 2, 3], into: &test)
        XCTAssertEqual(got16, true)
        test.hideDigits()
        let got17 = enter([4, 5, 6, 7], into: &test)
        XCTAssertEqual(got17, true)
        XCTAssertEqual(test.phase, .finished)
        XCTAssertEqual(test.bestSpan, 4)
        XCTAssertEqual(test.result, SpanResult(spanCorrect: 2, spanAttempts: 2))
    }

    func testShortIsASingleRound() {
        var right = span([1, 2, 3, 4, 5, 6, 7, 8, 9, 0], config: .short)
        right.begin()
        right.hideDigits()
        let got18 = enter([1, 2, 3], into: &right)
        XCTAssertEqual(got18, true)
        XCTAssertTrue(right.isFinished)
        XCTAssertEqual(right.result, SpanResult(spanCorrect: 1, spanAttempts: 1))
        XCTAssertEqual(right.bestSpan, 3)

        var wrong = span([1, 2, 3, 4, 5, 6, 7, 8, 9, 0], config: .short)
        wrong.begin()
        wrong.hideDigits()
        let got19 = enter([0, 0, 0], into: &wrong)
        XCTAssertEqual(got19, false)
        XCTAssertFalse(wrong.isFinished)
        wrong.hideDigits()
        let got20 = enter([0, 0, 0], into: &wrong)
        XCTAssertEqual(got20, false)
        XCTAssertTrue(wrong.isFinished)
        XCTAssertEqual(wrong.result, SpanResult(spanCorrect: 0, spanAttempts: 2))
        XCTAssertEqual(wrong.bestSpan, 0)
    }
}
