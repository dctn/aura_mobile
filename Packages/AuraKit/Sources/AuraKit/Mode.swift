import Foundation

public enum Shelf: String, Codable, Sendable, CaseIterable, Identifiable {
    case focus = "Focus", breathe = "Breathe", meditate = "Meditate", sleep = "Sleep", rest = "Rest"
    case movement = "Movement", mind = "Mind", outside = "Outside", people = "People", fuel = "Fuel"
    public var id: String { rawValue }
}

public enum RunKind: String, Codable, Sendable { case timer, flow, breathe, guided, winddown, plan }

/// One breath in seconds: in, an optional hold, out, an optional hold after.
public struct BreathPattern: Codable, Sendable, Equatable {
    public var `in`: Double
    public var hold: Double?
    public var out: Double
    public var hold2: Double?
    public init(in inhale: Double, hold: Double? = nil, out: Double, hold2: Double? = nil) {
        self.in = inhale; self.hold = hold; self.out = out; self.hold2 = hold2
    }
    public static let coherent = BreathPattern(in: 5, out: 5)

    /// "In 4 · Hold 4 · Out 4 · Hold 4", leaving out empty holds.
    public var line: String {
        var parts = ["In \(Self.seconds(self.in))"]
        if let first = hold, first > 0 { parts.append("Hold \(Self.seconds(first))") }
        parts.append("Out \(Self.seconds(out))")
        if let second = hold2, second > 0 { parts.append("Hold \(Self.seconds(second))") }
        return parts.joined(separator: " · ")
    }

    private static func seconds(_ value: Double) -> String {
        return value == value.rounded() ? String(Int(value)) : String(value)
    }
}

/// One entry of App/Resources/modes.json (65 modes).
public struct Mode: Codable, Sendable, Identifiable, Equatable {
    public var id: String
    public var name: String
    /// The short tile label.
    public var word: String
    public var shelf: Shelf
    /// The default length; 0 is open-ended (Flow).
    public var minutes: Int
    public var choices: [Int]?
    /// The session kind it saves: recovery, walk, focus or meeting.
    public var kind: String
    public var run: RunKind
    /// One short plain line, shown on the detail page and the run.
    public var note: String
    /// The "Why this Works" text; nil hides that card.
    public var evidence: String?
    public var pattern: BreathPattern?
    public var prompts: [String]?
    /// Hours of the day the mode shouldn't be suggested in.
    public var avoidHours: [Int]?
    /// Hours it suits best.
    public var bestHours: [Int]?
    /// Copy for the Now carousel card; nil keeps it out of Now.
    public var nowTitle: String?
    /// True for a run with a black screen.
    public var night: Bool?

    public init(id: String, name: String, word: String, shelf: Shelf, minutes: Int, choices: [Int]? = nil, kind: String = "recovery",
                run: RunKind = .timer, note: String = "", evidence: String? = nil, pattern: BreathPattern? = nil, prompts: [String]? = nil,
                avoidHours: [Int]? = nil, bestHours: [Int]? = nil, nowTitle: String? = nil, night: Bool? = nil) {
        self.id = id; self.name = name; self.word = word; self.shelf = shelf; self.minutes = minutes; self.choices = choices
        self.kind = kind; self.run = run; self.note = note; self.evidence = evidence; self.pattern = pattern; self.prompts = prompts
        self.avoidHours = avoidHours; self.bestHours = bestHours; self.nowTitle = nowTitle; self.night = night
    }

    /// The backend's session mode for this mode's run.
    public var sessionMode: String {
        switch (kind, run) {
        case (_, .flow): return "flowtime"
        case ("meeting", _): return "meeting"
        case ("walk", _): return "walk"
        default: return "timer"
        }
    }

    /// The Focus shelf is the only one with the 3-2-1 first (frame 02).
    public var hasCountdown: Bool { shelf == .focus }

    /// The small caps line over a run's title, e.g. "BREATHE · 5 MIN".
    public func kicker(minutes length: Int) -> String {
        return length == 0 ? "\(runWord) · OPEN" : "\(runWord) · \(length) MIN"
    }

    /// Frames 03 to 11: said by what the run does, so a walk on any shelf says MOVE.
    private var runWord: String {
        if shelf == .focus { return kind == "meeting" ? "MEETING" : "FOCUS" }
        if run == .breathe || pattern != nil { return "BREATHE" }
        if run == .guided { return "MEDITATE" }
        if night == true { return "NAP" }
        if kind == "walk" { return "MOVE" }
        return "RECOVERY"
    }

    /// The length pill: "10m", "8h", "1h 30m", "Open".
    public static func lengthText(_ minutes: Int) -> String {
        if minutes <= 0 { return "Open" }
        if minutes < 60 { return "\(minutes)m" }
        let hours = minutes / 60
        let rest = minutes % 60
        return rest == 0 ? "\(hours)h" : "\(hours)h \(rest)m"
    }

    /// True when the search text is in the name or the tile word. Empty text matches everything.
    public func matches(search text: String) -> Bool {
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty { return true }
        return name.localizedCaseInsensitiveContains(query) || word.localizedCaseInsensitiveContains(query)
    }

    /// Where a plan mode's calendar block goes: its fixed start today while that's still ahead, otherwise tomorrow.
    /// A day off starts at 09:00 and an evening without a drink at 18:00, as on the website.
    public func planSlot(now: Date, calendar: Calendar = .current) -> (start: Date, end: Date) {
        let hour = id == "alcohol-free-evening" ? 18 : 9
        let parts = calendar.dateComponents([.hour, .minute], from: now)
        let minuteNow = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        let today = calendar.startOfDay(for: now)
        let daysAhead = minuteNow < hour * 60 ? 0 : 1
        let day = calendar.date(byAdding: .day, value: daysAhead, to: today) ?? today
        let start = calendar.date(bySettingHour: hour, minute: 0, second: 0, of: day) ?? day
        let length = minutes > 0 ? minutes : 60
        return (start: start, end: start.addingTimeInterval(TimeInterval(length) * 60))
    }

    /// Picks the Now cards: not in avoided hours, best-hour modes first, at most `limit`.
    public static func nowCards(from modes: [Mode], hour: Int, limit: Int = 4) -> [Mode] {
        let open = modes.filter { $0.nowTitle != nil && !($0.avoidHours ?? []).contains(hour) }
        let best = open.filter { ($0.bestHours ?? []).contains(hour) }
        let rest = open.filter { !($0.bestHours ?? []).contains(hour) }
        return Array((best + rest).prefix(limit))
    }
}
