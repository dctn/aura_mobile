import XCTest
@testable import AuraKit

final class MetricMathsTests: XCTestCase {
    private var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        return calendar
    }

    private func day(_ year: Int, _ month: Int, _ day: Int) -> Date {
        utc.date(from: DateComponents(year: year, month: month, day: day)) ?? Date()
    }

    func testRangesEndTodayAndCountToday() {
        let today = day(2026, 3, 1)
        XCTAssertEqual(MetricRange.twoWeeks.span(endingOn: today, calendar: utc), MetricDaySpan(from: "2026-02-16", to: "2026-03-01"))
        XCTAssertEqual(MetricRange.oneMonth.span(endingOn: today, calendar: utc).from, "2026-01-31")
        XCTAssertEqual(MetricRange.twoMonths.span(endingOn: today, calendar: utc).from, "2026-01-01")
        XCTAssertEqual(MetricMaths.daySpan(days: 1, endingOn: today, calendar: utc).from, "2026-03-01")
    }

    func testMissingDaysBecomeGaps() {
        let span = MetricDaySpan(from: "2026-02-27", to: "2026-03-02")
        let filled = MetricMaths.dailyPoints([MetricPoint(date: "2026-02-28", value: 2),
                                              MetricPoint(date: "2026-03-01", value: nil)], span: span, calendar: utc)
        XCTAssertEqual(filled.map(\.date), ["2026-02-27", "2026-02-28", "2026-03-01", "2026-03-02"])
        XCTAssertEqual(filled.map(\.value), [nil, 2, nil, nil])
    }

    func testAverageSkipsGapsAndTrendReadsFilledDays() {
        let span = MetricDaySpan(from: "2026-03-01", to: "2026-03-14")
        // Only the first and last day came back; filling keeps them in their own weeks.
        let filled = MetricMaths.dailyPoints([MetricPoint(date: "2026-03-01", value: 2),
                                              MetricPoint(date: "2026-03-14", value: 3)], span: span, calendar: utc)
        XCTAssertEqual(filled.count, 14)
        XCTAssertEqual(MetricMaths.average(filled) ?? 0, 2.5, accuracy: 0.001)
        XCTAssertEqual(MetricMaths.weekTrend(filled) ?? 0, 50, accuracy: 0.001)
        XCTAssertNil(MetricMaths.average([MetricPoint(date: "2026-03-01", value: nil)]))
    }

    func testRecordedRowsAreNewestFirstWithoutGaps() {
        let rows = MetricMaths.recordedRows([MetricPoint(date: "2026-03-01", value: 1),
                                             MetricPoint(date: "2026-03-02", value: nil),
                                             MetricPoint(date: "2026-03-03", value: 0)])
        XCTAssertEqual(rows.map(\.date), ["2026-03-03", "2026-03-01"])
    }

    func testBarsScaleToTheHighestAndKeepGaps() {
        let bars = MetricMaths.barFractions([2.5, nil, 5, 0])
        XCTAssertEqual(bars[0] ?? -1, 0.5, accuracy: 0.001)
        XCTAssertNil(bars[1])
        XCTAssertEqual(bars[2] ?? -1, 1, accuracy: 0.001)
        XCTAssertEqual(bars[3] ?? -1, 0, accuracy: 0.001)
    }

    func testBarsWithNegativesFlatValuesAndNothing() {
        let bars = MetricMaths.barFractions([-1, 1])
        XCTAssertEqual(bars[0] ?? -1, 0, accuracy: 0.001)
        XCTAssertEqual(bars[1] ?? -1, 1, accuracy: 0.001)
        let flat: [Double?] = MetricMaths.barFractions([0, 0])
        XCTAssertEqual(flat, [0, 0])
        let nothing: [Double?] = MetricMaths.barFractions([nil, nil])
        XCTAssertEqual(nothing, [nil, nil])
    }

    func testFormatsFollowTheContract() {
        XCTAssertEqual(MetricMaths.display(8200.4, format: "int"), "8,200")
        XCTAssertEqual(MetricMaths.display(32.2, format: "minutes"), "32")
        XCTAssertEqual(MetricMaths.display(96.6, format: "percent"), "97")
        XCTAssertEqual(MetricMaths.display(3.44, format: "decimal1"), "3.4")
        XCTAssertEqual(MetricMaths.display(3.446, format: "decimal2"), "3.45")
        XCTAssertEqual(MetricMaths.display(450, format: "hoursFromMinutes"), "7.5")
        XCTAssertEqual(MetricMaths.display(3400, format: "metresAsKm"), "3.4")
        XCTAssertEqual(MetricMaths.display(330, format: "hhmm"), "5:30")
        XCTAssertEqual(MetricMaths.display(10, format: "hourOfDay"), "10:00")
        XCTAssertEqual(MetricMaths.display(1, format: "bool"), "Yes")
        XCTAssertEqual(MetricMaths.display(0, format: "bool"), "No")
    }

    func testNoFormatIsWholeForCountsAndOneDecimalOtherwise() {
        XCTAssertEqual(MetricMaths.display(8200, format: "", unit: "steps"), "8,200")
        XCTAssertEqual(MetricMaths.display(420.4, format: "", unit: "kcal"), "420")
        XCTAssertEqual(MetricMaths.display(3.4, format: "", unit: "km"), "3.4")
        XCTAssertEqual(MetricMaths.display(-0.01, format: "", unit: "C"), "0.0")
        XCTAssertEqual(MetricMaths.display(1234567, format: "int"), "1,234,567")
        XCTAssertEqual(MetricMaths.display(-1200, format: "int"), "-1,200")
    }

    func testUnitsAndAveragesForWords() {
        XCTAssertFalse(MetricMaths.canAverage(format: "bool"))
        XCTAssertFalse(MetricMaths.canAverage(format: "text"))
        XCTAssertTrue(MetricMaths.canAverage(format: "metresAsKm"))
        XCTAssertEqual(MetricMaths.shownUnit(format: "bool", unit: "x"), "")
        XCTAssertEqual(MetricMaths.shownUnit(format: "metresAsKm", unit: "km"), "km")
    }

    func testLatestTextPrefersTheNumber() {
        let number = FieldAvailability(field: "a", latestDate: "2026-03-01", latestNumber: 3400)
        XCTAssertEqual(MetricMaths.latestText(number, format: "metresAsKm", unit: "km"), "3.4")
        let words = FieldAvailability(field: "a", latestDate: "2026-03-01", latestText: "Moderate")
        XCTAssertEqual(MetricMaths.latestText(words, format: "text", unit: ""), "Moderate")
        XCTAssertNil(MetricMaths.latestText(FieldAvailability(field: "a", latestDate: "2026-03-01"), format: "", unit: ""))
    }

    func testShortDay() {
        XCTAssertEqual(MetricMaths.shortDay("2026-11-01"), "Nov 1")
        XCTAssertEqual(MetricMaths.shortDay("2026-01-31"), "Jan 31")
        XCTAssertEqual(MetricMaths.shortDay("soon"), "soon")
    }

    func testTrendWords() {
        XCTAssertEqual(MetricMaths.trendDirection(8), .up)
        XCTAssertEqual(MetricMaths.trendDirection(-8), .down)
        XCTAssertEqual(MetricMaths.trendDirection(1.9), .steady)
        XCTAssertEqual(MetricMaths.trendTitle(8), "Trending Up")
        XCTAssertEqual(MetricMaths.trendTitle(-8), "Trending Down")
        XCTAssertEqual(MetricMaths.trendTitle(0), "Steady")
        XCTAssertEqual(MetricMaths.trendPercentText(8.4), "+8%")
        XCTAssertEqual(MetricMaths.trendPercentText(-10.2), "-10%")
        XCTAssertEqual(MetricMaths.trendPercentText(0), "+0%")
    }

    private let walk = CatalogueParameter(name: "Walk Distance", field: "walkDistanceM", read: "series", unit: "km", format: "metresAsKm")
    private let mood = CatalogueParameter(name: "Mood Word", field: "moodWord", read: "labels", unit: "", format: "text")
    private let push = CatalogueParameter(name: "Push Count", field: nil, read: "none", unit: "")
    // Not called `run`: XCTestCase already has a run() method.
    private let runDistance = CatalogueParameter(name: "Run Distance", field: "runDistanceM", read: "series", unit: "km", format: "metresAsKm")

    func testCategorySplitKeepsOrderAndNeedsAValue() {
        let rows = MetricMaths.categoryRows([walk, mood, push, runDistance], availability: [
            FieldAvailability(field: "moodWord", latestDate: "2026-03-01", latestText: "Calm"),
            FieldAvailability(field: "walkDistanceM", latestDate: "2026-03-01", latestNumber: 3400),
            FieldAvailability(field: "runDistanceM", latestDate: "2026-03-01"),
        ])
        XCTAssertEqual(rows.available.map(\.parameter.name), ["Walk Distance", "Mood Word"])
        XCTAssertEqual(rows.empty.map(\.name), ["Push Count", "Run Distance"])
    }

    func testSearchFindsCategoriesAndMetricsOnce() {
        let movement = CatalogueSection(id: "movement", title: "Movement", parameters: [walk, runDistance])
        let running = CatalogueSection(id: "running", title: "Running", parameters: [runDistance, push])
        let hits = MetricMaths.search(" run ", in: [movement, running])
        XCTAssertEqual(hits.categories.map(\.id), ["running"])
        XCTAssertEqual(hits.metrics.map(\.parameter.name), ["Run Distance"])
        XCTAssertEqual(hits.metrics.first?.sectionId, "movement")
        XCTAssertTrue(MetricMaths.search("nothing like this", in: [movement]).metrics.isEmpty)
        XCTAssertTrue(MetricMaths.matches("  ", "Anything"))
        XCTAssertTrue(MetricMaths.matches("WALK", "Walk Distance"))
    }
}
