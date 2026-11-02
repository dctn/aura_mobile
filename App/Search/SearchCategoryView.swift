import AuraKit
import SwiftUI

/// Frame Search _ Movement: rows with data, the About text the Figma has, then the rows with none.
struct SearchCategoryView: View {
    let section: CatalogueSection

    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var availability: [FieldAvailability]?
    @State private var failed = false
    @State private var retry = 0

    init(section: CatalogueSection) {
        self.section = section
    }

    var body: some View {
        AuraPage {
            SearchCapsule(text: $query)
                .accessibilityIdentifier("category-search-field")
            content
        }
        .scrollDismissesKeyboard(.interactively)
        .metricBackBar("Search")
        .task(id: [model.dataVersion, retry]) { await load() }
    }

    private var isFiltering: Bool {
        !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var rows: MetricCategoryRows {
        let wanted = section.parameters.filter { MetricMaths.matches(query, $0.name) }
        return MetricMaths.categoryRows(wanted, availability: availability ?? [])
    }

    @ViewBuilder
    private var content: some View {
        if availability != nil {
            SearchCategoryLists(section: section, rows: rows, isFiltering: isFiltering)
        } else if failed {
            MetricRetryState { retry += 1 }
        } else {
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, 40)
        }
    }

    private func load() async {
        failed = false
        do {
            let span = MetricMaths.daySpan(days: MetricMaths.availabilityDays, endingOn: Date())
            let loaded = try await model.api.availability(from: span.from, to: span.to)
            guard !Task.isCancelled else { return }
            availability = loaded
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            model.note(error)
            failed = true
        }
    }
}

private struct SearchCategoryLists: View {
    let section: CatalogueSection
    let rows: MetricCategoryRows
    let isFiltering: Bool

    var body: some View {
        if rows.available.isEmpty && rows.empty.isEmpty {
            MetricEmptyState(text: isFiltering ? "No results" : "No data yet")
        }
        if !rows.available.isEmpty {
            SectionTitle("Available Data").metricTitleInset()
            SearchAvailableList(section: section, rows: rows.available)
        }
        if !isFiltering {
            SearchCategoryAbout(section: section)
        }
        if !rows.empty.isEmpty {
            SectionTitle("No Data Available").metricTitleInset()
            SearchNoDataList(sectionTitle: section.title, rows: rows.empty)
            Footnote(text: "These parameters have no recorded data yet. They move up into the section above once data comes in.")
                .metricTitleInset()
        }
    }
}

/// Only the Movement frame has About text, so other categories show none.
private struct SearchCategoryAbout: View {
    let section: CatalogueSection

    var body: some View {
        if section.id == "movement" {
            AuraCard(padding: 22) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("About Movement")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.black)
                    Text("Movement tracks how much you move through the day, including steps, distance, active energy, exercise minutes, and time on your feet. These signals combine into your daily activity picture and feed your trends.")
                        .font(.system(size: 15))
                        .foregroundStyle(MetricText.grey)
                }
            }
            Footnote(text: "Movement data is stored in your Aura Clarus account in the cloud, one value a day.")
                .metricTitleInset()
        }
    }
}

private struct SearchAvailableList: View {
    let section: CatalogueSection
    let rows: [MetricAvailableRow]

    var body: some View {
        SearchGroupCard {
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                if index > 0 { SearchRowDivider() }
                SearchAvailableLink(section: section, row: row)
            }
        }
    }
}

private struct SearchAvailableLink: View {
    let section: CatalogueSection
    let row: MetricAvailableRow

    var body: some View {
        if row.parameter.hasSeries, let field = row.parameter.field {
            NavigationLink {
                MetricView(name: row.parameter.name, field: field, unit: row.parameter.unit,
                           format: row.parameter.format, backTitle: section.title)
            } label: {
                label(showsChevron: true)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("metric-\(field)")
        } else {
            label(showsChevron: false)
        }
    }

    private func label(showsChevron: Bool) -> SearchGroupRow {
        let style = SearchCategoryStyle.of(sectionId: section.id)
        return SearchGroupRow(symbol: style.symbol,
                              symbolColor: style.color,
                              title: row.parameter.name,
                              showsChevron: showsChevron)
    }
}

private struct SearchNoDataList: View {
    let sectionTitle: String
    let rows: [CatalogueParameter]

    var body: some View {
        SearchGroupCard {
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, parameter in
                if index > 0 { SearchRowDivider(leading: 20) }
                SearchNoDataRow(sectionTitle: sectionTitle, parameter: parameter)
            }
        }
    }
}

private struct SearchNoDataRow: View {
    let sectionTitle: String
    let parameter: CatalogueParameter

    var body: some View {
        if parameter.hasSeries, let field = parameter.field {
            NavigationLink {
                MetricView(name: parameter.name, field: field, unit: parameter.unit,
                           format: parameter.format, backTitle: sectionTitle)
            } label: {
                SearchGroupRow(title: parameter.name)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("metric-\(field)")
        } else {
            SearchGroupRow(title: parameter.name, showsChevron: false)
        }
    }
}
