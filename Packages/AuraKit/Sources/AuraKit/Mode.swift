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
        let word: String
        switch shelf {
        case .focus: word = kind == "meeting" ? "MEETING" : "FOCUS"
        case .breathe: word = "BREATHE"
        case .meditate: word = "MEDITATE"
        case .sleep: word = run == .winddown ? "RECOVERY" : "NAP"
        case .movement: word = "MOVE"
        default: word = pattern != nil ? "BREATHE" : "RECOVERY"
        }
        return length == 0 ? "\(word) · OPEN" : "\(word) · \(length) MIN"
    }

    /// Picks the Now cards: not in avoided hours, best-hour modes first, at most `limit`.
    public static func nowCards(from modes: [Mode], hour: Int, limit: Int = 4) -> [Mode] {
        let open = modes.filter { $0.nowTitle != nil && !($0.avoidHours ?? []).contains(hour) }
        let best = open.filter { ($0.bestHours ?? []).contains(hour) }
        let rest = open.filter { !($0.bestHours ?? []).contains(hour) }
        return Array((best + rest).prefix(limit))
    }
}
