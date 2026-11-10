import Foundation
import XCTest
@testable import AuraKit

/// An instant written with its offset, e.g. "2030-03-16T23:00:00+05:30".
private func at(_ text: String) -> Date {
    return WireDate.parse(text) ?? Date.distantPast
}

private func sample(_ start: String, _ end: String, _ kind: SleepSample.Kind) -> SleepSample {
    return SleepSample(start: at(start), end: at(end), kind: kind)
}

final class SleepNightBuilderTests: XCTestCase {
    private func kolkata() throws -> TimeZone {
        return try XCTUnwrap(TimeZone(identifier: "Asia/Kolkata"))
    }

    func testNoAsleepSampleIsNoNight() throws {
        let zone = try kolkata()
        XCTAssertNil(SleepNightBuilder.build(samples: [], timeZone: zone))
        let inBedOnly = [sample("2030-03-16T23:00:00+05:30", "2030-03-17T07:00:00+05:30", .inBed),
                         sample("2030-03-17T03:00:00+05:30", "2030-03-17T03:10:00+05:30", .awake)]
        XCTAssertNil(SleepNightBuilder.build(samples: inBedOnly, timeZone: zone))
    }

    func testOverlappingSourcesAreMerged() throws {
        let zone = try kolkata()
        let samples = [
            sample("2030-03-16T23:00:00+05:30", "2030-03-17T03:00:00+05:30", .asleepCore),
            sample("2030-03-16T23:30:00+05:30", "2030-03-17T02:00:00+05:30", .asleepUnspecified),
            sample("2030-03-17T02:30:00+05:30", "2030-03-17T04:00:00+05:30", .asleepDeep),
            sample("2030-03-17T04:00:00+05:30", "2030-03-17T06:30:00+05:30", .asleepREM),
        ]
        let night = try XCTUnwrap(SleepNightBuilder.build(samples: samples, timeZone: zone))
        XCTAssertEqual(night.bedtimeAt, at("2030-03-16T23:00:00+05:30"))
        XCTAssertEqual(night.wakeAt, at("2030-03-17T06:30:00+05:30"))
        XCTAssertEqual(night.totalSleepMin, 450)
        XCTAssertNil(night.awakeMin)
        XCTAssertNil(night.inBedMin)
        XCTAssertEqual(night.date, "2030-03-17")
        XCTAssertEqual(night.timeZone, "Asia/Kolkata")
    }

    func testAwakeCountsOnlyInsideTheNight() throws {
        let zone = try kolkata()
        let samples = [
            sample("2030-03-16T22:30:00+05:30", "2030-03-16T22:50:00+05:30", .awake),
            sample("2030-03-16T23:00:00+05:30", "2030-03-17T02:00:00+05:30", .asleepCore),
            sample("2030-03-17T02:00:00+05:30", "2030-03-17T02:20:00+05:30", .awake),
            sample("2030-03-17T02:10:00+05:30", "2030-03-17T02:20:00+05:30", .awake),
            sample("2030-03-17T02:20:00+05:30", "2030-03-17T06:00:00+05:30", .asleepCore),
            sample("2030-03-17T06:30:00+05:30", "2030-03-17T06:40:00+05:30", .awake),
        ]
        let night = try XCTUnwrap(SleepNightBuilder.build(samples: samples, timeZone: zone))
        XCTAssertEqual(night.totalSleepMin, 400)
        XCTAssertEqual(night.awakeMin, 20)
    }

    func testInBedIsClampedToTheNightAndDroppedBelowTheSleep() throws {
        let zone = try kolkata()
        let asleep = sample("2030-03-16T23:00:00+05:30", "2030-03-17T07:00:00+05:30", .asleepCore)
        let short = [asleep, sample("2030-03-16T23:00:00+05:30", "2030-03-17T05:00:00+05:30", .inBed)]
        let shortNight = try XCTUnwrap(SleepNightBuilder.build(samples: short, timeZone: zone))
        XCTAssertEqual(shortNight.totalSleepMin, 480)
        XCTAssertNil(shortNight.inBedMin, "Less in bed than asleep is left out")

        let long = [asleep, sample("2030-03-16T22:30:00+05:30", "2030-03-17T07:30:00+05:30", .inBed)]
        let longNight = try XCTUnwrap(SleepNightBuilder.build(samples: long, timeZone: zone))
        XCTAssertEqual(longNight.inBedMin, 480, "Clamped to bedtime ... wake")
    }

    func testAGapOfThreeHoursSplitsAndTheLatestSleepIsTheNight() throws {
        let zone = try kolkata()
        let samples = [
            sample("2030-03-16T14:00:00+05:30", "2030-03-16T15:00:00+05:30", .asleepUnspecified),
            sample("2030-03-16T23:00:00+05:30", "2030-03-17T01:00:00+05:30", .asleepCore),
            sample("2030-03-17T04:00:00+05:30", "2030-03-17T07:00:00+05:30", .asleepCore),
        ]
        let night = try XCTUnwrap(SleepNightBuilder.build(samples: samples, timeZone: zone))
        XCTAssertEqual(night.bedtimeAt, at("2030-03-17T04:00:00+05:30"))
        XCTAssertEqual(night.wakeAt, at("2030-03-17T07:00:00+05:30"))
        XCTAssertEqual(night.totalSleepMin, 180)
    }

    func testAGapUnderThreeHoursStaysOneNight() throws {
        let zone = try kolkata()
        let samples = [
            sample("2030-03-16T23:00:00+05:30", "2030-03-17T01:00:00+05:30", .asleepCore),
            sample("2030-03-17T03:59:00+05:30", "2030-03-17T07:00:00+05:30", .asleepCore),
        ]
        let night = try XCTUnwrap(SleepNightBuilder.build(samples: samples, timeZone: zone))
        XCTAssertEqual(night.bedtimeAt, at("2030-03-16T23:00:00+05:30"))
        XCTAssertEqual(night.totalSleepMin, 120 + 181)
    }

    func testANightOverSixteenHoursIsNone() throws {
        let zone = try kolkata()
        let samples = [
            sample("2030-03-16T08:00:00+05:30", "2030-03-16T20:00:00+05:30", .asleepCore),
            sample("2030-03-16T21:00:00+05:30", "2030-03-17T01:00:00+05:30", .asleepCore),
        ]
        XCTAssertNil(SleepNightBuilder.build(samples: samples, timeZone: zone))
    }

    func testTheDateIsTheWakesDayInTheGivenZone() throws {
        // 01:30-04:30 in Kolkata on the 17th is still the 16th in UTC.
        let samples = [sample("2030-03-16T20:00:00Z", "2030-03-16T23:00:00Z", .asleepCore)]
        let inKolkata = try XCTUnwrap(SleepNightBuilder.build(samples: samples, timeZone: try kolkata()))
        XCTAssertEqual(inKolkata.date, "2030-03-17")
        XCTAssertEqual(inKolkata.timeZone, "Asia/Kolkata")
        XCTAssertEqual(inKolkata.totalSleepMin, 180)
        let utc = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let inUTC = try XCTUnwrap(SleepNightBuilder.build(samples: samples, timeZone: utc))
        XCTAssertEqual(inUTC.date, "2030-03-16")
    }
}
