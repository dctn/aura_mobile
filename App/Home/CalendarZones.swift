import AuraKit
import SwiftUI

/// A zone's shade, name and meaning. Names and meanings are the website's; the shades are the Figma's blue and green.
struct ZoneStyle {
    let color: Color
    let name: String
    let meaning: String

    static func of(_ kind: ZoneKind) -> ZoneStyle {
        switch kind {
        case .heavyWork:
            return ZoneStyle(color: AuraColor.blue.opacity(0.28), name: "Heavy work", meaning: "Your hardest task")
        case .lightWork:
            return ZoneStyle(color: AuraColor.blue.opacity(0.10), name: "Light work", meaning: "Email, admin, routine")
        case .shortRecovery:
            return ZoneStyle(color: AuraColor.green.opacity(0.12), name: "Short recovery", meaning: "Step away for a moment")
        case .heavyRecovery:
            return ZoneStyle(color: AuraColor.green.opacity(0.30), name: "Heavy recovery", meaning: "Lunch or a proper rest")
        }
    }

    /// The same colours as `HomeEventRow`'s bar.
    static func eventColor(_ kind: String) -> Color {
        switch kind {
        case "recovery": return AuraColor.green
        case "meeting": return AuraColor.runMeeting
        default: return AuraColor.ringOuter
        }
    }
}

/// The words the zone screens show.
enum ZoneText {
    private static let dayNames = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

    private static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    /// "09:00" on the phone's clock, always 24-hour.
    static func clock(_ date: Date) -> String {
        formatter.timeZone = TimeZone.current
        return formatter.string(from: date)
    }

    /// "09:00–10:30".
    static func range(_ start: Date, _ end: Date) -> String {
        "\(clock(start))–\(clock(end))"
    }

    /// "2 h", "1 h 30 min", "45 min": the website's `hoursText`.
    static func hours(_ minutes: Int) -> String {
        let h = minutes / 60
        let m = minutes % 60
        if h == 0 { return "\(m) min" }
        return m == 0 ? "\(h) h" : "\(h) h \(m) min"
    }

    /// "Every day", "Mon–Fri", "Mon, Wed, Fri". Three or more days in a row become a range.
    static func days(_ workDays: [Int]) -> String {
        let sorted = Array(Set(workDays.filter { (1...7).contains($0) })).sorted()
        if sorted.count == 7 { return "Every day" }
        if sorted.isEmpty { return "No work days" }
        var runs: [[Int]] = []
        for day in sorted {
            if let last = runs.last?.last, last == day - 1 {
                runs[runs.count - 1].append(day)
            } else {
                runs.append([day])
            }
        }
        let parts = runs.map { run -> String in
            if run.count >= 3, let first = run.first, let last = run.last {
                return "\(dayNames[first - 1])–\(dayNames[last - 1])"
            }
            return run.map { dayNames[$0 - 1] }.joined(separator: ", ")
        }
        return parts.joined(separator: ", ")
    }

    /// "09:00–18:00 · Mon–Fri", or "Not set".
    static func schedule(_ schedule: WorkSchedule?) -> String {
        guard let schedule else { return "Not set" }
        return "\(schedule.workdayStart)–\(schedule.workdayEnd) · \(days(schedule.workDays))"
    }
}

/// The line at the top of Calendar: how much heavy work today holds, or why nothing is shaded.
struct ZoneLineCard: View {
    let day: ZoneDay
    let setHours: () -> Void

    private var title: String {
        switch day.status {
        case .ready:
            guard day.heavyMinutes > 0 else { return "Light work today" }
            let heavy = "\(ZoneText.hours(day.heavyMinutes)) heavy work"
            if let score = day.score { return "Score \(score) · \(heavy)" }
            return heavy
        case .dayOff: return "Day off"
        case .noScore: return "No score yet"
        case .needsSchedule: return "Set your work hours to see your zones."
        case .unknown: return ""
        }
    }

    private var detail: String? {
        switch day.status {
        case .ready:
            guard let reason = day.reason, !reason.isEmpty else { return nil }
            return reason
        case .dayOff: return "Today isn't one of your work days, so nothing is shaded."
        case .noScore: return "Zones start once your morning score is ready."
        case .needsSchedule, .unknown: return nil
        }
    }

    var body: some View {
        AuraCard(padding: 20) {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.black)
                        .fixedSize(horizontal: false, vertical: true)
                    if let detail {
                        Text(detail)
                            .font(.system(size: 15))
                            .foregroundStyle(Color.gray)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("calendar-zone-line")

                if day.status == .needsSchedule {
                    PillButton(title: "Set work hours", fills: true, action: setHours)
                        .accessibilityIdentifier("calendar-set-hours")
                }
            }
        }
    }
}

/// A bold title over a white card, like Calendar's day groups.
struct ZoneCardSection<Content: View>: View {
    let title: String
    var padding: CGFloat = 20
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.black)
                .accessibilityAddTraits(.isHeader)
            AuraCard(padding: padding) {
                VStack(alignment: .leading, spacing: 12) {
                    content
                }
            }
        }
    }
}

/// The small square of a zone's shade.
struct ZoneSwatch: View {
    let kind: ZoneKind

    var body: some View {
        let style = ZoneStyle.of(kind)
        RoundedRectangle(cornerRadius: 6, style: .continuous)
            .fill(style.color)
            .overlay(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .stroke(Color.black.opacity(0.08), lineWidth: 1)
            )
            .frame(width: 22, height: 22)
            .accessibilityLabel("\(style.name) shade")
    }
}

/// One zone kind: swatch, name and meaning, with an optional time on the right.
private struct ZoneRow: View {
    let kind: ZoneKind
    var time: String?

    private var spokenLabel: String {
        let style = ZoneStyle.of(kind)
        if let time { return "\(style.name), \(time), \(style.meaning)" }
        return "\(style.name), \(style.meaning)"
    }

    var body: some View {
        let style = ZoneStyle.of(kind)
        HStack(spacing: 12) {
            ZoneSwatch(kind: kind)
            VStack(alignment: .leading, spacing: 1) {
                Text(style.name)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(.black)
                    .lineLimit(1)
                Text(style.meaning)
                    .font(.system(size: 13))
                    .foregroundStyle(Color.gray)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            Spacer(minLength: 8)
            if let time {
                Text(time)
                    .font(.system(size: 15).monospacedDigit())
                    .foregroundStyle(Color.gray)
                    .lineLimit(1)
                    .fixedSize()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenLabel)
    }
}

/// "Your day": today's zones in order.
struct ZoneDayList: View {
    let zones: [Zone]

    var body: some View {
        ZoneCardSection(title: "Your day") {
            ForEach(Array(zones.enumerated()), id: \.element.id) { index, zone in
                if index > 0 { Divider() }
                ZoneRow(kind: zone.kind, time: ZoneText.range(zone.start, zone.end))
            }
        }
    }
}

/// "What each shade means".
struct ZoneLegend: View {
    var body: some View {
        ZoneCardSection(title: "What each shade means") {
            ForEach(Array(ZoneKind.allCases.enumerated()), id: \.element) { index, kind in
                if index > 0 { Divider() }
                ZoneRow(kind: kind)
            }
        }
    }
}

/// A white row with a title, a grey value under it and a chevron: Calendar's "Work hours" and Settings' "Work and lunch".
struct WorkHoursRow: View {
    let title: String
    let value: String?
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: "clock.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(AuraColor.blue)
                    .frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(.black)
                        .lineLimit(1)
                    if let value {
                        Text(value)
                            .font(.system(size: 15))
                            .foregroundStyle(Color.gray)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AuraColor.footnote)
            }
            .padding(.horizontal, 20)
            .frame(maxWidth: .infinity, minHeight: 66, alignment: .leading)
            .background(AuraColor.card, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
    }
}

/// Today's hours: the zone shades behind, the events on top and a red line at the current time.
struct ZoneDayGrid: View {
    let zones: [Zone]
    let events: [CalendarEvent]
    let schedule: WorkSchedule?
    let now: Date

    private struct HourRange {
        let from: Int
        let to: Int
    }

    /// An event placed in its lane. Events that overlap sit side by side.
    private struct Block: Identifiable {
        let event: CalendarEvent
        let start: Double
        let end: Double
        var lane: Int
        var lanes: Int
        var id: String { event.id }
    }

    private static let hourHeight: CGFloat = 56
    private static let gutter: CGFloat = 46
    private static let pad: CGFloat = 10
    private static let minBlock: CGFloat = 26
    /// Only a zone this long has room for its name.
    private static let labelSeconds: TimeInterval = 30 * 60
    private static let lineColor = Color(hex: 0xE3E3E3)

    private var dayStart: Date { Calendar.current.startOfDay(for: now) }

    /// Minutes after today's midnight, kept inside the day.
    private func minutes(_ date: Date) -> Double {
        min(max(date.timeIntervalSince(dayStart) / 60, 0), 24 * 60)
    }

    private func y(_ minute: Double, _ range: HourRange) -> CGFloat {
        Self.pad + CGFloat((minute - Double(range.from * 60)) / 60) * Self.hourHeight
    }

    /// From the earlier of work start and the first event to the later of work end and the last event, on the hour.
    private var hourRange: HourRange {
        var starts: [Double] = []
        var ends: [Double] = []
        if let schedule, let start = WorkSchedule.minutes(schedule.workdayStart), let end = WorkSchedule.minutes(schedule.workdayEnd) {
            starts.append(Double(start))
            ends.append(Double(end))
        }
        for zone in zones {
            starts.append(minutes(zone.start))
            ends.append(minutes(zone.end))
        }
        for event in events {
            starts.append(minutes(event.start))
            ends.append(minutes(event.end))
        }
        guard let first = starts.min(), let last = ends.max() else { return HourRange(from: 8, to: 18) }
        let from = max(0, min(23, Int((first / 60).rounded(.down))))
        let to = min(24, max(from + 1, Int((last / 60).rounded(.up))))
        return HourRange(from: from, to: to)
    }

    /// Lanes for overlapping events. A short event counts as tall as its block, so blocks never cover each other.
    private var blocks: [Block] {
        let minMinutes = Double(Self.minBlock / Self.hourHeight * 60)
        var result: [Block] = []
        var group: [Int] = []
        var laneEnds: [Double] = []
        var groupEnd = -Double.infinity
        for event in events.sorted(by: { $0.start < $1.start }) {
            let start = minutes(event.start)
            let end = minutes(event.end)
            let shownEnd = max(end, start + minMinutes)
            if start >= groupEnd {
                for index in group { result[index].lanes = laneEnds.count }
                group = []
                laneEnds = []
            }
            let lane: Int
            if let free = laneEnds.firstIndex(where: { $0 <= start }) {
                lane = free
                laneEnds[free] = shownEnd
            } else {
                lane = laneEnds.count
                laneEnds.append(shownEnd)
            }
            result.append(Block(event: event, start: start, end: end, lane: lane, lanes: 1))
            group.append(result.count - 1)
            groupEnd = max(groupEnd, shownEnd)
        }
        for index in group { result[index].lanes = laneEnds.count }
        return result
    }

    private var spokenSummary: String {
        var parts = ["Today's hours"]
        parts += zones.map { "\(ZoneStyle.of($0.kind).name), \(ZoneText.clock($0.start)) to \(ZoneText.clock($0.end))" }
        parts += events.sorted(by: { $0.start < $1.start }).map {
            "\($0.title), \(ZoneText.clock($0.start)) to \(ZoneText.clock($0.end))"
        }
        return parts.joined(separator: ". ")
    }

    var body: some View {
        let range = hourRange
        let height = CGFloat(range.to - range.from) * Self.hourHeight + 2 * Self.pad
        GeometryReader { geometry in
            let width = geometry.size.width
            ZStack(alignment: .topLeading) {
                shades(range, width: width)
                hourLines(range, width: width)
                eventBlocks(range, width: width)
                nowLine(range, width: width)
            }
            .frame(width: width, height: height, alignment: .topLeading)
        }
        .frame(height: height)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenSummary)
        .accessibilityIdentifier("calendar-day-grid")
    }

    private func shades(_ range: HourRange, width: CGFloat) -> some View {
        ForEach(zones) { zone in
            let top = y(minutes(zone.start), range)
            let bottom = y(minutes(zone.end), range)
            let style = ZoneStyle.of(zone.kind)
            Rectangle()
                .fill(style.color)
                .frame(width: max(0, width - Self.gutter), height: max(0, bottom - top))
                .overlay(alignment: .topTrailing) {
                    if zone.end.timeIntervalSince(zone.start) >= Self.labelSeconds {
                        Text(style.name)
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(Color.black.opacity(0.45))
                            .lineLimit(1)
                            .padding(.top, 3)
                            .padding(.trailing, 6)
                    }
                }
                .offset(x: Self.gutter, y: top)
        }
    }

    private func hourLines(_ range: HourRange, width: CGFloat) -> some View {
        ForEach(Array(range.from...range.to), id: \.self) { hour in
            let top = y(Double(hour * 60), range)
            if hour < 24 {
                Text(WorkSchedule.clock(hour * 60))
                    .font(.system(size: 11).monospacedDigit())
                    .foregroundStyle(Color.gray)
                    .frame(width: Self.gutter - 6, alignment: .leading)
                    .offset(x: 0, y: top - 7)
            }
            Rectangle()
                .fill(Self.lineColor)
                .frame(width: max(0, width - Self.gutter), height: 0.5)
                .offset(x: Self.gutter, y: top)
        }
    }

    private func eventBlocks(_ range: HourRange, width: CGFloat) -> some View {
        let laneArea = max(0, width - Self.gutter - 4)
        return ForEach(blocks) { block in
            let laneWidth = laneArea / CGFloat(max(1, block.lanes))
            let top = y(block.start, range)
            let height = max(Self.minBlock, y(block.end, range) - top)
            ZoneEventBlock(event: block.event, height: height)
                .frame(width: max(0, laneWidth - 3), height: height)
                .offset(x: Self.gutter + 4 + laneWidth * CGFloat(block.lane), y: top)
        }
    }

    @ViewBuilder
    private func nowLine(_ range: HourRange, width: CGFloat) -> some View {
        let minute = now.timeIntervalSince(dayStart) / 60
        if minute >= Double(range.from * 60) && minute <= Double(range.to * 60) {
            let top = y(minute, range)
            Rectangle()
                .fill(AuraColor.red)
                .frame(width: max(0, width - Self.gutter), height: 1.5)
                .offset(x: Self.gutter, y: top - 0.75)
            Circle()
                .fill(AuraColor.red)
                .frame(width: 8, height: 8)
                .offset(x: Self.gutter - 4, y: top - 4)
        }
    }
}

/// An event on the hour grid: its colour bar, then the title and time, or the title alone when it's short.
private struct ZoneEventBlock: View {
    let event: CalendarEvent
    let height: CGFloat

    var body: some View {
        let color = ZoneStyle.eventColor(event.kind)
        HStack(alignment: .top, spacing: 0) {
            Rectangle()
                .fill(color)
                .frame(width: 4)
            VStack(alignment: .leading, spacing: 1) {
                if height >= 40 {
                    Text(event.title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.black)
                        .lineLimit(1)
                    Text(ZoneText.range(event.start, event.end))
                        .font(.system(size: 11))
                        .foregroundStyle(Color.gray)
                        .lineLimit(1)
                } else {
                    Text("\(event.title) · \(ZoneText.clock(event.start))")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.black)
                        .lineLimit(1)
                }
            }
            .padding(.leading, 6)
            .padding(.trailing, 4)
            .padding(.top, 4)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(color.opacity(0.2))
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}
