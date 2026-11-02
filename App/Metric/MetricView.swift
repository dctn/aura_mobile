import AuraKit
import SwiftUI

/// Frame Movement _ Walk Distance. Home's Pins open this too, knowing only the name, field and unit.
struct MetricView: View {
    let name: String
    let field: String
    let unit: String
    /// The catalogue's number format. When nil it is looked up, because values such as metres need it to read right.
    private let knownFormat: String?
    /// The page below, shown beside the back button.
    private let backTitle: String

    @Environment(AppModel.self) private var model
    @State private var range = MetricRange.twoWeeks
    @State private var points: [MetricPoint]?
    @State private var format: String?
    /// The newest value when the chosen range has none.
    @State private var newest: FieldAvailability?
    @State private var selectedIndex: Int?
    @State private var failed = false
    @State private var retry = 0

    init(name: String, field: String, unit: String, format: String? = nil, backTitle: String = "Home") {
        self.name = name
        self.field = field
        self.unit = unit
        self.knownFormat = format
        self.backTitle = backTitle
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                topArea
                lowerArea
            }
        }
        .background(AuraColor.background)
        .metricBackBar(backTitle)
        .task(id: [model.dataVersion, retry, range.days]) { await load() }
        .onChange(of: range) { selectedIndex = nil }
    }

    // MARK: Top

    /// White right up behind the navigation bar. The tall background also covers a pull past the top.
    private var topArea: some View {
        VStack(alignment: .leading, spacing: 0) {
            MetricHero(name: name, value: bigValue, unit: shownUnit, line: heroLine)
                .padding(.horizontal, 42)
            MetricRangeSwitch(selection: $range)
                .padding(.horizontal, 22)
                .padding(.top, 22)
            chartArea
                .padding(.horizontal, 36)
                .padding(.top, 22)
                .padding(.bottom, 28)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(alignment: .bottom) {
            AuraColor.card.frame(height: 2000)
        }
    }

    @ViewBuilder
    private var chartArea: some View {
        if let points {
            MetricBarChart(name: name, points: points, selectedIndex: selectedIndex) { index in
                selectedIndex = selectedIndex == index ? nil : index
            }
            .overlay {
                if MetricMaths.latest(points) == nil {
                    MetricEmptyState()
                }
            }
        } else if failed {
            MetricRetryState { retry += 1 }
                .frame(height: MetricBarChart.height)
        } else {
            ProgressView()
                .frame(maxWidth: .infinity)
                .frame(height: MetricBarChart.height)
        }
    }

    // MARK: Lower

    private var lowerArea: some View {
        VStack(alignment: .leading, spacing: 15) {
            SectionTitle("Highlights")
                .metricTitleInset()
                .padding(.bottom, 8)
            if let trend {
                MetricTrendCard(name: name, percent: trend)
            }
            pinRow
            MetricLinksCard(name: name, field: field, unit: unit, format: resolvedFormat)
            MetricAboutCard(name: name)
            Footnote(text: "Your \(name) data is stored in your Aura Clarus account in the cloud.")
                .metricTitleInset()
        }
        .padding(.horizontal, 22)
        .padding(.top, 24)
        .padding(.bottom, 24)
    }

    private var pinRow: some View {
        let pinned = model.isPinned(field: field)
        return Button {
            model.togglePin(PinnedMetric(field: field, name: name, unit: unit))
        } label: {
            HStack {
                Text("Pin to Home")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(.black)
                Spacer()
                Image(systemName: pinned ? "pin.fill" : "pin")
                    .font(.system(size: 18))
                    .foregroundStyle(pinned ? AuraColor.blue : MetricText.grey)
            }
            .padding(.horizontal, 22)
            .frame(height: 60)
            .background(AuraColor.card, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("metric-pin")
        .accessibilityValue(Text(pinned ? "Pinned" : "Not pinned"))
    }

    // MARK: Numbers

    private var resolvedFormat: String? { format ?? knownFormat }
    private var shownFormat: String { resolvedFormat ?? "" }
    private var shownUnit: String { MetricMaths.shownUnit(format: shownFormat, unit: unit) }

    private var bigValue: String? {
        if let points, let value = MetricMaths.latest(points)?.value {
            return MetricMaths.display(value, format: shownFormat, unit: unit)
        }
        if let newest {
            return MetricMaths.latestText(newest, format: shownFormat, unit: unit)
        }
        return nil
    }

    private var heroLine: MetricHeroLine? {
        guard let points else { return nil }
        if let selectedIndex, selectedIndex < points.count, let value = points[selectedIndex].value {
            return MetricHeroLine(label: MetricMaths.shortDay(points[selectedIndex].date),
                                  value: MetricMaths.display(value, format: shownFormat, unit: unit),
                                  unit: shownUnit)
        }
        if MetricMaths.canAverage(format: shownFormat), let average = MetricMaths.average(points) {
            return MetricHeroLine(label: "Avg",
                                  value: MetricMaths.display(average, format: shownFormat, unit: unit),
                                  unit: shownUnit)
        }
        if MetricMaths.latest(points) == nil, let newest {
            return MetricHeroLine(label: "Latest", value: MetricMaths.shortDay(newest.latestDate), unit: "")
        }
        return nil
    }

    private var trend: Double? {
        guard let points, MetricMaths.canAverage(format: shownFormat) else { return nil }
        return MetricMaths.weekTrend(points)
    }

    // MARK: Loading

    private func load() async {
        failed = false
        do {
            let span = range.span(endingOn: Date())
            let found = try await lookedUpFormat()
            let series = try await model.api.series(field: field, from: span.from, to: span.to)
            let days = MetricMaths.dailyPoints(series.points, span: span)
            var fallback = newest
            if MetricMaths.latest(days) == nil && fallback == nil {
                fallback = await newestValue()
            }
            guard !Task.isCancelled else { return }
            format = found
            points = days
            newest = fallback
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            model.note(error)
            points = nil
            failed = true
        }
    }

    private func lookedUpFormat() async throws -> String {
        if let resolvedFormat { return resolvedFormat }
        return try await MetricFormatLookup.format(of: field, api: model.api)
    }

    /// Only for the hero line, so a failure here leaves the page as it is.
    private func newestValue() async -> FieldAvailability? {
        let span = MetricMaths.daySpan(days: MetricMaths.availabilityDays, endingOn: Date())
        let all = try? await model.api.availability(from: span.from, to: span.to)
        return all?.first { $0.field == field }
    }
}

/// A page opened from a pin knows no format, so it comes from the catalogue.
enum MetricFormatLookup {
    static func format(of field: String, api: any AuraAPI) async throws -> String {
        let sections = try await api.catalogue()
        for section in sections {
            if let row = section.parameters.first(where: { $0.field == field }) {
                return row.format
            }
        }
        return ""
    }
}

private struct MetricHeroLine {
    let label: String
    let value: String
    let unit: String
}

/// The name, the big newest value and the line under it.
private struct MetricHero: View {
    let name: String
    let value: String?
    let unit: String
    let line: MetricHeroLine?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(name)
                .font(.system(size: 27, weight: .bold))
                .tracking(-0.3)
                .foregroundStyle(.black)
            if let value {
                bigNumber(value)
            }
            if let line {
                lineView(line)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func bigNumber(_ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(value)
                .font(.system(size: 48, weight: .bold))
                .foregroundStyle(.black)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            if !unit.isEmpty {
                Text(unit)
                    .font(.system(size: 15))
                    .foregroundStyle(MetricText.grey)
            }
        }
    }

    private func lineView(_ line: MetricHeroLine) -> some View {
        HStack(spacing: 6) {
            Circle()
                .fill(AuraColor.burnout)
                .frame(width: 8, height: 8)
            Text(line.label)
                .font(.system(size: 13))
                .foregroundStyle(MetricText.grey)
            Text(line.value)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AuraColor.burnout)
            if !line.unit.isEmpty {
                Text(line.unit)
                    .font(.system(size: 13))
                    .foregroundStyle(MetricText.grey)
            }
        }
        .padding(.top, 2)
    }
}

/// "Trending Up", the change, and what the change compares.
private struct MetricTrendCard: View {
    let name: String
    let percent: Double

    var body: some View {
        AuraCard(padding: 22) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    Image(systemName: symbol)
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(AuraColor.burnout)
                    Text(MetricMaths.trendTitle(percent))
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.black)
                }
                HStack(alignment: .firstTextBaseline, spacing: 5) {
                    Text(MetricMaths.trendPercentText(percent))
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(.black)
                    Text("from last week")
                        .font(.system(size: 13))
                        .foregroundStyle(MetricText.grey)
                }
                Text(sentence)
                    .font(.system(size: 15))
                    .foregroundStyle(MetricText.grey)
            }
        }
    }

    private var symbol: String {
        switch MetricMaths.trendDirection(percent) {
        case .up: return "arrow.up.right"
        case .steady: return "arrow.right"
        case .down: return "arrow.down.right"
        }
    }

    private var sentence: String {
        switch MetricMaths.trendDirection(percent) {
        case .up: return "\(name) averaged higher over the last 7 days than the 7 days before."
        case .steady: return "\(name) stayed about level over the last 7 days compared with the 7 days before."
        case .down: return "\(name) averaged lower over the last 7 days than the 7 days before."
        }
    }
}

/// Show All Data, Data Sources & Access and Units.
private struct MetricLinksCard: View {
    let name: String
    let field: String
    let unit: String
    let format: String?

    var body: some View {
        SearchGroupCard {
            NavigationLink {
                MetricRecordedView(name: name, field: field, unit: unit, format: format)
            } label: {
                SearchGroupRow(title: "Show All Data")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("metric-row-all-data")
            SearchRowDivider(leading: 20)
            NavigationLink {
                MetricSourcesView(name: name)
            } label: {
                SearchGroupRow(title: "Data Sources & Access")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("metric-row-sources")
            SearchRowDivider(leading: 20)
            NavigationLink {
                NotDesignedYet(title: "Units")
                    .toolbarRole(.editor)
            } label: {
                SearchGroupRow(title: "Units")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("metric-row-units")
        }
    }
}

private struct MetricAboutCard: View {
    let name: String

    var body: some View {
        AuraCard(padding: 22) {
            VStack(alignment: .leading, spacing: 8) {
                Text("About \(name)")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.black)
                Text("\(name) is one value a day from your Aura Clarus account. The big number is the newest value, and Avg is the average of the days with data in the range you pick.")
                    .font(.system(size: 15))
                    .foregroundStyle(MetricText.grey)
            }
        }
    }
}
