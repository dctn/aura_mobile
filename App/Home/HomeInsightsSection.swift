import AuraKit
import SwiftUI

/// Insights: three rings and the next two events of today.
struct HomeInsightsSection: View {
    @Environment(AppModel.self) private var model
    @State private var scores: ScoresToday?
    @State private var scoresFailed = false
    @State private var events: [CalendarEvent]?
    @State private var eventsFailed = false
    @State private var retry = 0

    private var loadKey: String { "\(model.dataVersion)-\(retry)" }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SectionTitle("Insights")
            card
            Footnote(text: HomeCopy.footnote, linkTitle: HomeCopy.privacyLink)
        }
        .task(id: loadKey) { await loadScores() }
        .task(id: loadKey) { await loadEvents() }
    }

    private var card: some View {
        AuraCard(padding: 20) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 32) {
                    HomeRings(scores: scores)
                        .padding(.leading, 9)
                    HomeUpcomingList(events: upcoming)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if scoresFailed || eventsFailed {
                    HomeRetryLine(text: "Couldn't load all of this.") { retry += 1 }
                }
            }
        }
    }

    /// Nil until the events have loaded, so "Nothing else today" doesn't flash first.
    private var upcoming: [CalendarEvent]? {
        guard let events else { return nil }
        let now = Date()
        let dayEnd = Self.endOfToday(now)
        let open = events.filter { $0.end > now && $0.start < dayEnd }
        let sorted = open.sorted { $0.start < $1.start }
        return Array(sorted.prefix(2))
    }

    private static func endOfToday(_ now: Date) -> Date {
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: now)
        return calendar.date(byAdding: .day, value: 1, to: start) ?? now
    }

    private func loadScores() async {
        do {
            let loaded = try await model.api.scoresToday()
            scores = loaded
            scoresFailed = false
        } catch {
            if Task.isCancelled { return }
            model.note(error)
            scoresFailed = scores == nil
        }
    }

    private func loadEvents() async {
        let now = Date()
        do {
            let loaded = try await model.api.events(from: now, to: Self.endOfToday(now))
            events = loaded
            eventsFailed = false
        } catch {
            if Task.isCancelled { return }
            model.note(error)
            eventsFailed = events == nil
        }
    }
}

/// Outer attention, middle memory, inner thinking: `/v1/scores` returns four and the Figma draws three.
private struct HomeRings: View {
    let scores: ScoresToday?

    private let outerSize: CGFloat = 127

    var body: some View {
        ZStack {
            HomeRing(title: "Attention", value: scores?.attention.value, color: AuraColor.ringOuter, diameter: 127)
            HomeRing(title: "Memory", value: scores?.memory.value, color: AuraColor.ringMiddle, diameter: 91)
            HomeRing(title: "Thinking", value: scores?.thinking.value, color: AuraColor.ringInner, diameter: 53)
        }
        .frame(width: outerSize, height: outerSize)
        .accessibilityElement(children: .contain)
    }
}

/// One ring on a pale track of its own colour. As in the Figma, the fill starts at nine o'clock and runs clockwise.
private struct HomeRing: View {
    let title: String
    let value: Double?
    let color: Color
    let diameter: CGFloat

    private let lineWidth: CGFloat = 10

    private var pathSize: CGFloat { diameter - lineWidth }

    /// 100 is the person's normal, so a full ring means at or above it.
    private var fill: CGFloat {
        guard let value else { return 0 }
        return CGFloat(min(1, max(0, value / 100)))
    }

    private var spokenLabel: String {
        guard let value else { return "\(title): not enough data yet" }
        return "\(title): \(Int(value.rounded())), where 100 is your normal"
    }

    var body: some View {
        ZStack {
            Circle()
                .stroke(color.opacity(0.2), lineWidth: lineWidth)
            if fill > 0 {
                Circle()
                    .trim(from: 0, to: fill)
                    .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(180))
            }
        }
        .frame(width: pathSize, height: pathSize)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenLabel)
    }
}

/// The blue "Upcoming" header and up to two events.
private struct HomeUpcomingList: View {
    let events: [CalendarEvent]?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "checklist")
                    .font(.system(size: 16))
                Text("Upcoming")
                    .font(.system(size: 15, weight: .medium))
            }
            .foregroundStyle(AuraColor.blue)
            .accessibilityElement(children: .combine)

            if let events {
                if events.isEmpty {
                    Text("Nothing else today")
                        .font(.system(size: 15))
                        .foregroundStyle(AuraColor.footnote)
                } else {
                    ForEach(events) { event in
                        HomeEventRow(event: event)
                    }
                }
            }
        }
    }
}

/// A coloured bar, the time range and the title. Also used by the Calendar list.
struct HomeEventRow: View {
    let event: CalendarEvent

    private var barColor: Color {
        switch event.kind {
        case "recovery": return AuraColor.green
        case "meeting": return AuraColor.runMeeting
        default: return AuraColor.ringOuter
        }
    }

    private var timeRange: String {
        // Always 24-hour: a 12-hour locale with the AM/PM left off showed 15:51 as "03:51".
        "\(ZoneText.clock(event.start)) - \(ZoneText.clock(event.end))"
    }

    private var spokenLabel: String {
        let start = event.start.formatted(date: .omitted, time: .shortened)
        let end = event.end.formatted(date: .omitted, time: .shortened)
        return "\(event.title), \(start) to \(end)"
    }

    var body: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 1.5)
                .fill(barColor)
                .frame(width: 3, height: 28)
            VStack(alignment: .leading, spacing: 1) {
                Text(timeRange)
                    .font(.system(size: 11))
                    .foregroundStyle(Color.gray)
                Text(event.title)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.black)
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenLabel)
    }
}
