import AuraKit
import SwiftUI

/// A push onto the Recovery stack. `Mode` isn't Hashable, so the route carries its id.
struct RecoveryRoute: Hashable {
    let modeId: String
    /// The Now card's title when opened from one.
    let headline: String?
}

/// Frame iPhone 18 Pro - 5: search, the Now carousel and the shelves.
struct RecoveryView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var pageWidth: CGFloat = 402
    @State private var takingBreak = false
    @State private var loggingBreak = false
    /// The break the sheet asked for. It starts once the sheet has gone, since the run screen can't cover a sheet.
    @State private var pendingBreak: RecoveryBreakChoice? = nil

    /// Figma's order, with Focus added first.
    private static let shelves: [Shelf] = [.focus, .sleep, .movement, .breathe, .meditate, .rest, .mind, .outside, .people, .fuel]

    private var isSearching: Bool {
        return !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Exactly two tiles fill the width, with the third just past the edge.
    private var tileWidth: CGFloat {
        return max(150, (pageWidth - 66) / 2)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SearchCapsule(text: $query)
                    .padding(.horizontal, 22)
                    .padding(.top, 8)
                if isSearching {
                    RecoverySearchResults(modes: model.modes.filter { $0.matches(search: query) }, tileWidth: tileWidth)
                } else {
                    breakButtons
                    nowSection
                    ForEach(Self.shelves) { shelf in
                        RecoveryShelf(shelf: shelf, modes: model.modes.filter { $0.shelf == shelf }, tileWidth: tileWidth)
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.immediately)
        .background(AuraColor.background)
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }, action: { pageWidth = $0 })
        .toolbar { toolbarItems }
        .sheet(isPresented: $takingBreak, onDismiss: startPendingBreak) {
            RecoveryBreakSheet { choice in pendingBreak = choice }
        }
        .sheet(isPresented: $loggingBreak) {
            LogBreakSheet()
        }
        .navigationDestination(for: RecoveryRoute.self) { route in
            if let mode = model.mode(id: route.modeId) {
                ModeDetailView(mode: mode, headline: route.headline)
            }
        }
    }

    /// Native glass. No frame exists for these two yet, so they do nothing.
    @ToolbarContentBuilder
    private var toolbarItems: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button("Edit") {}
                .tint(.black)
                .accessibilityHint("Not available yet")
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button {} label: {
                Image(systemName: "line.3.horizontal.decrease")
            }
            .tint(.black)
            .accessibilityLabel("Filter")
            .accessibilityHint("Not available yet")
        }
    }

    /// "Take a recovery break" and "Add a break I took", between search and Now.
    private var breakButtons: some View {
        HStack(spacing: 10) {
            RecoveryBreakButton(title: "Take a recovery break", symbol: "leaf.fill") {
                takingBreak = true
            }
            .accessibilityIdentifier("recovery-take-break")
            RecoveryBreakButton(title: "Add a break I took", symbol: "plus") {
                loggingBreak = true
            }
            .accessibilityIdentifier("recovery-log-break")
        }
        .padding(.horizontal, 22)
        .padding(.top, 14)
    }

    /// Runs the Recovery break mode under the picked type's name.
    private func startPendingBreak() {
        guard let choice = pendingBreak else { return }
        pendingBreak = nil
        guard var mode = model.mode(id: "recovery-break") else { return }
        if !choice.note.isEmpty { mode.note = choice.note }
        model.run.start(mode: mode, minutes: choice.minutes, title: choice.title, recoveryType: choice.type)
    }

    private var nowSection: some View {
        let hour = Calendar.current.component(.hour, from: Date())
        let cards = Mode.nowCards(from: model.modes, hour: hour, limit: 4)
        return VStack(alignment: .leading, spacing: 14) {
            SectionTitle("Now").padding(.leading, 42)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(cards) { mode in
                        NavigationLink(value: RecoveryRoute(modeId: mode.id, headline: mode.nowTitle)) {
                            RecoveryNowCard(mode: mode)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("now-card-\(mode.id)")
                    }
                }
                .padding(.horizontal, 22)
            }
        }
        .padding(.top, 19)
        .padding(.bottom, 13)
    }
}

/// One of the two break pills: an icon and a title that may take two lines.
private struct RecoveryBreakButton: View {
    let title: String
    let symbol: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AuraColor.blue)
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Color.black)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(AuraColor.card, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// A tile that opens its mode.
private struct RecoveryTileLink: View {
    let mode: Mode
    let width: CGFloat

    var body: some View {
        NavigationLink(value: RecoveryRoute(modeId: mode.id, headline: nil)) {
            ModeTile(mode: mode)
                .frame(width: width, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("mode-\(mode.id)")
    }
}

private struct RecoveryShelf: View {
    let shelf: Shelf
    let modes: [Mode]
    let tileWidth: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SectionTitle(shelf.rawValue).padding(.leading, 42)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 22) {
                    ForEach(modes) { mode in
                        RecoveryTileLink(mode: mode, width: tileWidth)
                    }
                }
                .scrollTargetLayout()
                .padding(.horizontal, 22)
            }
            .scrollTargetBehavior(.viewAligned)
        }
        .padding(.top, 20)
    }
}

/// While searching, the matches show together in two columns.
private struct RecoverySearchResults: View {
    let modes: [Mode]
    let tileWidth: CGFloat

    private var columns: [GridItem] {
        return [GridItem(.fixed(tileWidth), spacing: 22, alignment: .leading),
                GridItem(.fixed(tileWidth), alignment: .leading)]
    }

    var body: some View {
        if modes.isEmpty {
            ContentUnavailableView.search
                .padding(.top, 40)
        } else {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 18) {
                ForEach(modes) { mode in
                    RecoveryTileLink(mode: mode, width: tileWidth)
                }
            }
            .padding(.horizontal, 22)
            .padding(.top, 24)
        }
    }
}
