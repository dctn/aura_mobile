import SwiftUI

/// A category's icon and colour, sampled from the Figma's Search frame.
struct SearchCategoryStyle {
    let symbol: String
    let color: Color

    static let orange = Color(hex: 0xFF9500)
    static let sky = Color(hex: 0x5AC8FA)
    static let blue = Color(hex: 0x007AFF)
    static let pink = Color(hex: 0xFF2D55)
    static let grey = Color(hex: 0x8E8E93)

    private static let fallback = SearchCategoryStyle(symbol: "square.grid.2x2.fill", color: grey)

    private static let styles: [String: SearchCategoryStyle] = [
        "movement": SearchCategoryStyle(symbol: "figure.walk", color: AuraColor.burnout),
        "running": SearchCategoryStyle(symbol: "figure.run", color: AuraColor.red),
        "cycling": SearchCategoryStyle(symbol: "figure.outdoor.cycle", color: AuraColor.green),
        "gym": SearchCategoryStyle(symbol: "figure.strengthtraining.traditional", color: AuraColor.burnout),
        "swimming": SearchCategoryStyle(symbol: "figure.pool.swim", color: sky),
        "winter": SearchCategoryStyle(symbol: "snowflake", color: blue),
        "body": SearchCategoryStyle(symbol: "figure.arms.open", color: blue),
        "heart": SearchCategoryStyle(symbol: "heart.fill", color: pink),
        "stress": SearchCategoryStyle(symbol: "brain.head.profile", color: orange),
        "sleep": SearchCategoryStyle(symbol: "bed.double.fill", color: AuraColor.indigo),
        "recovery": SearchCategoryStyle(symbol: "arrow.clockwise.heart.fill", color: AuraColor.green),
        "focus": SearchCategoryStyle(symbol: "target", color: blue),
        "screen": SearchCategoryStyle(symbol: "hourglass", color: sky),
        "meetings": SearchCategoryStyle(symbol: "person.2.fill", color: AuraColor.red),
        "mental": SearchCategoryStyle(symbol: "brain", color: orange),
        "respiration": SearchCategoryStyle(symbol: "lungs.fill", color: sky),
        "mobility": SearchCategoryStyle(symbol: "figure.walk.motion", color: AuraColor.burnout),
        "nutrition": SearchCategoryStyle(symbol: "fork.knife", color: orange),
        "cycle": SearchCategoryStyle(symbol: "drop.fill", color: pink),
        "hearing": SearchCategoryStyle(symbol: "ear.fill", color: blue),
        "symptoms": SearchCategoryStyle(symbol: "cross.circle.fill", color: AuraColor.red),
        "burnout": SearchCategoryStyle(symbol: "flame.fill", color: AuraColor.burnout),
        "habits": SearchCategoryStyle(symbol: "checklist", color: AuraColor.green),
        "progress": SearchCategoryStyle(symbol: "chart.line.uptrend.xyaxis", color: AuraColor.green),
        "other": SearchCategoryStyle(symbol: "square.grid.2x2.fill", color: grey),
    ]

    /// Real ids are one word ("gym"); the stub's are lowercased titles ("gym and strength").
    static func of(sectionId: String) -> SearchCategoryStyle {
        let key = sectionId.split(separator: " ").first.map(String.init) ?? sectionId
        return styles[key] ?? fallback
    }
}

/// The white card around a list of rows.
struct SearchGroupCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity)
            .background(AuraColor.card, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
    }
}

/// The thin line between rows, starting under the row's title.
struct SearchRowDivider: View {
    var leading: CGFloat = 72

    var body: some View {
        Rectangle()
            .fill(Color(hex: 0xDCDCDC))
            .frame(height: 1)
            .padding(.leading, leading)
            .padding(.trailing, 20)
    }
}

/// One row of a card: an optional icon, the title and a chevron.
struct SearchGroupRow: View {
    var symbol: String?
    var symbolColor: Color = AuraColor.green
    let title: String
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
                .minimumScaleFactor(0.75)
            Spacer(minLength: 8)
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AuraColor.footnote)
            }
        }
        .padding(.leading, symbol == nil ? 20 : 26)
        .padding(.trailing, 20)
        .frame(minHeight: 60)
        .contentShape(Rectangle())
    }
}

/// A calm message in place of a list or chart.
struct MetricEmptyState: View {
    var text = "No data yet"

    var body: some View {
        Text(text)
            .font(.system(size: 17, weight: .medium))
            .foregroundStyle(MetricText.grey)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 40)
    }
}

/// Shown when a load fails for any reason other than consent or a lost session.
struct MetricRetryState: View {
    let action: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text("This didn't load.")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(MetricText.grey)
            Button("Try again", action: action)
                .buttonStyle(.glass)
                .accessibilityIdentifier("metric-retry")
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }
}

/// The grey the Search and metric frames use for small text.
enum MetricText {
    static let grey = Color(hex: 0x9E9E9F)
}

extension View {
    /// The Figma's pushed pages: the native back button with no label, and a big title beside it on the same line.
    /// The clear item stops the bar also drawing `navigationTitle` in the middle. The title has a fixed width because
    /// the bar otherwise measures it at its smallest and cuts "Calendar" down to "C...". 280 pt leaves the back button
    /// room on the narrowest iPhones (375 pt), and long titles shrink to fit.
    func metricBackBar(_ title: String) -> some View {
        navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarRole(.editor)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Color.clear
                        .frame(width: 1, height: 1)
                        .accessibilityHidden(true)
                }
                .sharedBackgroundVisibility(.hidden)
                ToolbarItem(placement: .topBarLeading) {
                    Text(title)
                        .font(.system(size: 30, weight: .bold))
                        .tracking(-0.4)
                        .foregroundStyle(.black)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(width: 280, alignment: .leading)
                        .fixedSize()
                        .accessibilityAddTraits(.isHeader)
                }
                .sharedBackgroundVisibility(.hidden)
            }
    }

    /// Section titles and footnotes start 20 pt inside the cards' edge.
    func metricTitleInset() -> some View {
        padding(.horizontal, 20)
    }
}
