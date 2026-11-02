import AuraKit
import SwiftUI

/// Frame Search _ All categories.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var sections: [CatalogueSection]?
    @State private var failed = false
    @State private var retry = 0

    var body: some View {
        AuraPage {
            VStack(alignment: .leading, spacing: 23) {
                SearchCapsule(text: $query)
                    .accessibilityIdentifier("search-field")
                if isSearching {
                    SearchHitsList(query: query, sections: sections ?? [])
                } else {
                    categories
                    SearchPlacesList()
                }
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("Search")
        .toolbarTitleDisplayMode(.inlineLarge)
        .task(id: [model.dataVersion, retry]) { await load() }
    }

    private var isSearching: Bool {
        !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    @ViewBuilder
    private var categories: some View {
        if let sections {
            if sections.isEmpty {
                MetricEmptyState(text: "No categories yet")
            } else {
                SearchCategoriesCard(sections: sections)
            }
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
            let loaded = try await model.api.catalogue()
            guard !Task.isCancelled else { return }
            sections = loaded
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            model.note(error)
            failed = true
        }
    }
}

/// The 25 categories.
private struct SearchCategoriesCard: View {
    let sections: [CatalogueSection]

    var body: some View {
        SearchGroupCard {
            ForEach(Array(sections.enumerated()), id: \.element.id) { index, section in
                if index > 0 { SearchRowDivider() }
                SearchCategoryLink(section: section)
            }
        }
    }
}

private struct SearchCategoryLink: View {
    let section: CatalogueSection

    var body: some View {
        let style = SearchCategoryStyle.of(sectionId: section.id)
        NavigationLink {
            SearchCategoryView(section: section)
        } label: {
            SearchGroupRow(symbol: style.symbol, symbolColor: style.color, title: section.title)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("category-\(section.id)")
    }
}

/// The other places Search reaches, under the categories.
private struct SearchPlace: Identifiable {
    enum Destination { case notDesigned, modes, settings }

    let title: String
    let symbol: String
    let color: Color
    var destination: Destination = .notDesigned
    var id: String { title }

    static let group = [
        SearchPlace(title: "Logging", symbol: "square.and.pencil", color: SearchCategoryStyle.orange),
        SearchPlace(title: "Goals", symbol: "target", color: AuraColor.green),
        SearchPlace(title: "Sessions", symbol: "clock.arrow.circlepath", color: AuraColor.indigo),
        SearchPlace(title: "Modes", symbol: "bolt.circle.fill", color: AuraColor.red, destination: .modes),
        SearchPlace(title: "Calorie Tracker", symbol: "fork.knife", color: SearchCategoryStyle.orange),
    ]
    static let settings = SearchPlace(title: "Settings", symbol: "gearshape.fill", color: SearchCategoryStyle.grey, destination: .settings)
    static let documents = SearchPlace(title: "Clinical Documents", symbol: "doc.text.fill", color: SearchCategoryStyle.blue)
    static let all = group + [settings, documents]
}

private struct SearchPlaceDestination: View {
    let place: SearchPlace

    var body: some View {
        switch place.destination {
        case .notDesigned:
            NotDesignedYet(title: place.title)
                .toolbarRole(.editor)
        case .modes:
            RecoveryView()
                .toolbarRole(.editor)
        case .settings:
            SettingsView()
                .toolbarRole(.editor)
        }
    }
}

private struct SearchPlaceRow: View {
    let place: SearchPlace

    var body: some View {
        NavigationLink {
            SearchPlaceDestination(place: place)
        } label: {
            SearchGroupRow(symbol: place.symbol, symbolColor: place.color, title: place.title)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("search-place-\(place.title)")
    }
}

/// A place on its own white capsule: Settings and Clinical Documents.
private struct SearchPlaceCapsule: View {
    let place: SearchPlace

    var body: some View {
        NavigationLink {
            SearchPlaceDestination(place: place)
        } label: {
            ListRowCard(symbol: place.symbol, symbolColor: place.color, title: place.title)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("search-place-\(place.title)")
    }
}

private struct SearchPlacesList: View {
    var body: some View {
        SearchGroupCard {
            ForEach(Array(SearchPlace.group.enumerated()), id: \.element.id) { index, place in
                if index > 0 { SearchRowDivider() }
                SearchPlaceRow(place: place)
            }
        }
        SearchPlaceCapsule(place: SearchPlace.settings)
        SearchPlaceCapsule(place: SearchPlace.documents)
    }
}

/// What typing shows: matching categories, metrics and places.
private struct SearchHitsList: View {
    let query: String
    let sections: [CatalogueSection]

    private var hits: MetricSearchHits { MetricMaths.search(query, in: sections) }
    private var places: [SearchPlace] { SearchPlace.all.filter { MetricMaths.matches(query, $0.title) } }

    var body: some View {
        let found = hits
        let foundPlaces = places
        if found.categories.isEmpty && found.metrics.isEmpty && foundPlaces.isEmpty {
            MetricEmptyState(text: "No results")
        }
        if !found.categories.isEmpty {
            SearchCategoriesCard(sections: found.categories)
        }
        if !found.metrics.isEmpty {
            SearchGroupCard {
                ForEach(Array(found.metrics.enumerated()), id: \.element.id) { index, hit in
                    if index > 0 { SearchRowDivider() }
                    SearchMetricHitRow(hit: hit)
                }
            }
        }
        if !foundPlaces.isEmpty {
            SearchGroupCard {
                ForEach(Array(foundPlaces.enumerated()), id: \.element.id) { index, place in
                    if index > 0 { SearchRowDivider() }
                    SearchPlaceRow(place: place)
                }
            }
        }
    }
}

private struct SearchMetricHitRow: View {
    let hit: MetricSearchHit

    var body: some View {
        let style = SearchCategoryStyle.of(sectionId: hit.sectionId)
        if hit.parameter.hasSeries, let field = hit.parameter.field {
            NavigationLink {
                MetricView(name: hit.parameter.name, field: field, unit: hit.parameter.unit,
                           format: hit.parameter.format, backTitle: "Search")
            } label: {
                SearchGroupRow(symbol: style.symbol, symbolColor: style.color, title: hit.parameter.name)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("metric-\(field)")
        } else {
            SearchGroupRow(symbol: style.symbol, symbolColor: style.color, title: hit.parameter.name, showsChevron: false)
        }
    }
}
