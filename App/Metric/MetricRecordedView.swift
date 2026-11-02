import AuraKit
import SwiftUI

/// Frame Walk Distance _ All Recorded Data. The backend has daily points only, so rows show dates, not times.
struct MetricRecordedView: View {
    let name: String
    let field: String
    let unit: String
    private let knownFormat: String?

    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var rows: [MetricPoint]?
    @State private var format = ""
    @State private var failed = false
    @State private var retry = 0

    init(name: String, field: String, unit: String, format: String?) {
        self.name = name
        self.field = field
        self.unit = unit
        self.knownFormat = format
    }

    var body: some View {
        AuraPage {
            SearchCapsule(text: $query)
                .accessibilityIdentifier("recorded-search-field")
            SectionTitle("All Recorded Data")
                .metricTitleInset()
            list
            Footnote(text: "One \(name) value a day from the last 12 months, newest first, stored in your Aura Clarus account in the cloud.")
                .metricTitleInset()
        }
        .scrollDismissesKeyboard(.interactively)
        .metricBackBar(name)
        .task(id: [model.dataVersion, retry]) { await load() }
    }

    private func text(_ point: MetricPoint) -> String {
        let number = MetricMaths.display(point.value ?? 0, format: format, unit: unit)
        let shownUnit = MetricMaths.shownUnit(format: format, unit: unit)
        return shownUnit.isEmpty ? number : number + " " + shownUnit
    }

    private func matching(_ rows: [MetricPoint]) -> [MetricPoint] {
        rows.filter { point in
            MetricMaths.matches(query, MetricMaths.shortDay(point.date) + " " + text(point))
        }
    }

    @ViewBuilder
    private var list: some View {
        if let rows {
            let shown = matching(rows)
            if shown.isEmpty {
                MetricEmptyState(text: rows.isEmpty ? "No data yet" : "No results")
            } else {
                recordCard(shown)
            }
        } else if failed {
            MetricRetryState { retry += 1 }
        } else {
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, 40)
        }
    }

    private func recordCard(_ shown: [MetricPoint]) -> some View {
        SearchGroupCard {
            LazyVStack(spacing: 0) {
                ForEach(Array(shown.enumerated()), id: \.element.date) { index, point in
                    if index > 0 { SearchRowDivider(leading: 20) }
                    MetricRecordRow(day: MetricMaths.shortDay(point.date), value: text(point))
                }
            }
        }
    }

    private func load() async {
        failed = false
        do {
            let span = MetricMaths.daySpan(days: MetricMaths.recordedDays, endingOn: Date())
            let found: String
            if let knownFormat {
                found = knownFormat
            } else {
                found = try await MetricFormatLookup.format(of: field, api: model.api)
            }
            let series = try await model.api.series(field: field, from: span.from, to: span.to)
            guard !Task.isCancelled else { return }
            format = found
            rows = MetricMaths.recordedRows(series.points)
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            model.note(error)
            failed = true
        }
    }
}

private struct MetricRecordRow: View {
    let day: String
    let value: String

    var body: some View {
        HStack {
            Text(day)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(.black)
            Spacer(minLength: 8)
            Text(value)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(AuraColor.burnout)
                .lineLimit(1)
        }
        .padding(.horizontal, 20)
        .frame(minHeight: 60)
    }
}
