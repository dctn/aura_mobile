import AuraKit
import SwiftUI

/// Burnout: the newest risk of the last 14 days and its 12 dots.
struct HomeBurnoutSection: View {
    @Environment(AppModel.self) private var model
    @State private var latest: DailyMetrics?
    @State private var loaded = false
    @State private var failed = false
    @State private var retry = 0

    private var loadKey: String { "\(model.dataVersion)-\(retry)" }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SectionTitle("Burnout")
            card
            Footnote(text: HomeCopy.footnote, linkTitle: HomeCopy.privacyLink)
        }
        .task(id: loadKey) { await load() }
    }

    private var card: some View {
        AuraCard {
            VStack(alignment: .leading, spacing: 12) {
                header
                statusLine
                HomeBurnoutDots(level: MetricMaths.burnoutLevel(score: latest?.burnoutIndexScore))
            }
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "flame.fill")
                .font(.system(size: 17))
            Text("Burnout Risk")
                .font(.system(size: 15, weight: .medium))
        }
        .foregroundStyle(AuraColor.burnout)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var statusLine: some View {
        if failed {
            HomeRetryLine(text: "Couldn't load this.") { retry += 1 }
        } else {
            // The blank keeps the card's height while it loads.
            Text(loaded ? Self.line(for: latest) : " ")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.black)
        }
    }

    private func load() async {
        let today = Date()
        let start = Calendar.current.date(byAdding: .day, value: -13, to: today) ?? today
        do {
            let days = try await model.api.dailyMetrics(from: DayKey.string(start), to: DayKey.string(today))
            let newestFirst = days.sorted { $0.date > $1.date }
            latest = newestFirst.first { $0.burnoutIndexScore != nil || $0.burnoutIndexRisk != nil }
            loaded = true
            failed = false
        } catch {
            if Task.isCancelled { return }
            model.note(error)
            failed = !loaded
        }
    }

    /// The backend's risk word when it sends one, otherwise the score against the backend's own bands.
    private static func line(for day: DailyMetrics?) -> String {
        guard let day else { return "Not enough data yet" }
        let word = day.burnoutIndexRisk?.lowercased() ?? riskWord(score: day.burnoutIndexScore)
        switch word {
        case "critical": return "Highest possibility estimated"
        case "high": return "Higher possibility estimated"
        case "moderate": return "Moderate possibility estimated"
        case "low": return "Lower possibility estimated"
        default: return "Not enough data yet"
        }
    }

    private static func riskWord(score: Double?) -> String {
        guard let score else { return "" }
        if score >= 72 { return "critical" }
        if score >= 55 { return "high" }
        if score >= 32 { return "moderate" }
        return "low"
    }
}

/// The grey pill of 12 dots. The filled part is pale orange and its last dot is darker.
private struct HomeBurnoutDots: View {
    let level: Int

    private let dotSize: CGFloat = 11
    private let pillHeight: CGFloat = 38
    private let fillHeight: CGFloat = 32
    private let fillInset: CGFloat = 3
    /// From the pill's edge to the first dot's centre.
    private let edgeToDot: CGFloat = 22
    /// How far the fill runs past its last dot's centre.
    private let fillOverrun: CGFloat = 12.5

    private var dotCount: Int { MetricMaths.burnoutDots }

    var body: some View {
        GeometryReader { geometry in
            let step = stepWidth(geometry.size.width)
            ZStack(alignment: .leading) {
                if level > 0 {
                    Capsule()
                        .fill(AuraColor.burnoutFill)
                        .frame(width: fillWidth(step: step), height: fillHeight)
                        .padding(.leading, fillInset)
                }
                dots(step: step)
            }
            .frame(width: geometry.size.width, height: pillHeight, alignment: .leading)
            .background(AuraColor.background, in: Capsule())
        }
        .frame(height: pillHeight)
        .frame(maxWidth: 296)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Burnout level: \(level) of \(dotCount)")
    }

    private func dots(step: CGFloat) -> some View {
        HStack(spacing: step - dotSize) {
            ForEach(0..<dotCount, id: \.self) { index in
                Circle()
                    .fill(dotColor(index))
                    .frame(width: dotSize, height: dotSize)
            }
        }
        .padding(.leading, edgeToDot - dotSize / 2)
    }

    /// The distance between dot centres, for any pill width.
    private func stepWidth(_ width: CGFloat) -> CGFloat {
        let span = width - edgeToDot * 2
        let gaps = CGFloat(dotCount - 1)
        return max(dotSize, span / gaps)
    }

    private func fillWidth(step: CGFloat) -> CGFloat {
        let lastCentre = edgeToDot + CGFloat(level - 1) * step
        return lastCentre + fillOverrun - fillInset
    }

    private func dotColor(_ index: Int) -> Color {
        if index >= level { return Color(hex: 0xD9D9D9) }
        if index == level - 1 { return AuraColor.burnout }
        return Color(hex: 0xFBA280)
    }
}
