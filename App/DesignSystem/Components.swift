import AuraKit
import SwiftUI

/// A white rounded card, the Figma's main surface.
struct AuraCard<Content: View>: View {
    var padding: CGFloat = 24
    @ViewBuilder var content: Content

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .background(AuraColor.card, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
    }
}

/// "Insights", "Today", "Sleep": the big bold section title.
struct SectionTitle: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.system(size: 30, weight: .bold))
            .tracking(-0.4)
            .foregroundStyle(.black)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// The small grey line under a card. Pass `linkTitle` for the blue words at its end.
struct Footnote: View {
    let text: String
    var linkTitle: String?

    var body: some View {
        Group {
            if let linkTitle {
                Text("\(text) \(Text(linkTitle).foregroundStyle(AuraColor.blue))")
            } else {
                Text(text)
            }
        }
        .font(.system(size: 15))
        .foregroundStyle(AuraColor.footnote)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Start (blue) and Skip (clear): native glass pills.
struct PillButton: View {
    enum Style { case primary, secondary }

    let title: String
    var style: Style = .primary
    /// Takes the row's whole width.
    var fills = false
    let action: () -> Void

    var body: some View {
        switch style {
        case .primary:
            Button(action: action) { label }
                .buttonStyle(.glassProminent)
                .tint(AuraColor.startBlue)
        case .secondary:
            Button(action: action) { label }
                .buttonStyle(.glass)
                .tint(.black)
        }
    }

    private var label: some View {
        Text(title)
            .font(.system(size: 19, weight: .medium))
            .frame(maxWidth: fills ? .infinity : nil)
            .padding(.vertical, 6)
            .padding(.horizontal, fills ? 0 : 12)
    }
}

/// The white capsule row: icon, title, an optional value and a chevron ("Calendar", "Step Count").
struct ListRowCard: View {
    let symbol: String?
    var symbolColor: Color = AuraColor.green
    let title: String
    var value: String?
    var unit: String?
    var showsChevron = true

    var body: some View {
        HStack(spacing: 16) {
            if let symbol {
                Image(systemName: symbol)
                    .font(.system(size: 24))
                    .foregroundStyle(symbolColor)
                    .frame(width: 30)
            }
            Text(title)
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(.black)
                .lineLimit(1)
            Spacer(minLength: 8)
            if let value {
                HStack(alignment: .firstTextBaseline, spacing: 1) {
                    Text(value).font(.system(size: 20, weight: .medium)).foregroundStyle(.black)
                    if let unit, !unit.isEmpty {
                        Text(unit).font(.system(size: 15)).foregroundStyle(AuraColor.footnote)
                    }
                }
            }
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AuraColor.footnote)
            }
        }
        .padding(.horizontal, 26)
        .frame(minHeight: 62)
        .background(AuraColor.card, in: Capsule())
    }
}

/// The grey search capsule with a magnifier and a microphone.
struct SearchCapsule: View {
    @Binding var text: String
    var prompt = "Search"

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField(prompt, text: $text)
                .font(.system(size: 18))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            Image(systemName: "mic.fill").foregroundStyle(.secondary)
        }
        .padding(.horizontal, 14)
        .frame(height: 44)
        .background(AuraColor.searchField, in: Capsule())
    }
}

/// A rounded white square holding a mode's icon.
struct IconTile: View {
    let symbol: String
    let color: Color
    var size: CGFloat = 66

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.46))
            .foregroundStyle(color)
            .frame(width: size, height: size)
            .background(AuraColor.card, in: RoundedRectangle(cornerRadius: size * 0.27, style: .continuous))
    }
}

/// One tile on a Recovery shelf: icon, coloured word, then its length.
struct ModeTile: View {
    let mode: Mode

    private var lengthText: String {
        if mode.minutes == 0 { return "Open" }
        if mode.minutes >= 120 { return "\(mode.minutes / 60) Hours" }
        return String(format: "%02d Minutes", mode.minutes)
    }

    var body: some View {
        HStack(spacing: 10) {
            IconTile(symbol: mode.shelf.symbol, color: mode.shelf.color)
            VStack(alignment: .leading, spacing: 3) {
                Text(mode.word)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(mode.shelf.color)
                    .lineLimit(1)
                Text(lengthText)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.black)
                    .lineLimit(1)
            }
        }
    }
}

/// The page behind everything.
struct AuraPage<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) { content }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 22)
                .padding(.vertical, 12)
        }
        .background(AuraColor.background)
    }
}

/// For screens the Figma doesn't have yet.
struct NotDesignedYet: View {
    let title: String

    var body: some View {
        ContentUnavailableView(title, systemImage: "paintbrush", description: Text("Not designed yet."))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(AuraColor.background)
            .navigationTitle(title)
    }
}
