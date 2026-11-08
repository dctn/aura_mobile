import Foundation

/// Everything the screens ask the backend. LiveAPI talks to dev us-west-1; StubAPI answers for tests and CI.
public protocol AuraAPI: Sendable {
    // Home
    func scoresToday() async throws -> ScoresToday
    func events(from: Date, to: Date) async throws -> [CalendarEvent]
    func tasks() async throws -> [TaskItem]
    func alerts(from: String, to: String) async throws -> [AlertItem]
    func markAlertDone(id: String) async throws
    /// A mood check-in, 1 (low) to 5.
    func logMood(_ mood: Int, at: Date) async throws
    func dailyMetrics(from: String, to: String) async throws -> [DailyMetrics]

    // Search and metrics
    func catalogue() async throws -> [CatalogueSection]
    func availability(from: String, to: String) async throws -> [FieldAvailability]
    func series(field: String, from: String, to: String) async throws -> MetricSeries

    // Sessions
    /// `id` is the caller's and is safe to resend: the same id answers with the session it made. The answer's id can
    /// still differ, when another session was already running and this joined it.
    func startSession(id: String, kind: String, mode: String, plannedMinutes: Int?, title: String) async throws -> StartedSession
    func sessionAction(id: String, action: SessionAction) async throws
    func sessionTrace(id: String) async throws -> SessionTrace

    // Things a mode adds
    func addEvent(title: String, start: Date, end: Date, kind: String) async throws
    /// A task meant for a day (a `DayKey`), with no time of its own.
    func addTask(title: String, plannedFor: String) async throws

    // Consent
    func grantHealthConsent() async throws

    // Profile
    /// Sets the profile's time zone when it has none. One already there is never changed.
    func setTimeZoneIfMissing(_ identifier: String) async throws
    /// The profile's work hours, or nil when none are set.
    func workSchedule() async throws -> WorkSchedule?
    /// Writes only the work hours to the profile. Every other profile field is kept.
    func setWorkSchedule(_ schedule: WorkSchedule) async throws

    // Day zones
    /// `from` and `to` are `DayKey`s, at most 31 days apart. One entry per day.
    func dayZones(from: String, to: String) async throws -> [ZoneDay]
}

public enum AuthState: Sendable, Equatable {
    case signedOut
    case signedIn(email: String?)
}

/// The step between asking for a code and typing it. Sign-in codes have 8 digits, sign-up codes 6.
public struct AuthStep: Sendable, Equatable {
    public enum Kind: Sendable { case signIn, confirmSignUp }
    public var kind: Kind
    public var email: String
    public var session: String?
    public var codeLength: Int
    /// An unconfirmed account can hide as a sign-in, so both code lengths are accepted (see cognito-sign-in.md).
    public var alsoConfirm: Bool
    public var destination: String
    public init(kind: Kind, email: String, session: String?, codeLength: Int, alsoConfirm: Bool = false, destination: String = "") {
        self.kind = kind; self.email = email; self.session = session
        self.codeLength = codeLength; self.alsoConfirm = alsoConfirm; self.destination = destination
    }
}

public protocol AuthService: Sendable {
    /// Reads the Keychain: signed in when a usable session is there.
    func restore() async -> AuthState
    func requestCode(email: String) async throws -> AuthStep
    func finish(step: AuthStep, code: String) async throws -> AuthState
    func resend(step: AuthStep) async throws -> AuthStep
    func signOut() async
}

/// Dates the backend reads as yyyy-MM-dd: Gregorian whatever the phone's calendar is, in the phone's time zone.
public enum DayKey {
    public static var calendar: Calendar {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = TimeZone.current
        gregorian.locale = Locale(identifier: "en_US_POSIX")
        return gregorian
    }

    public static func string(_ date: Date, calendar: Calendar = DayKey.calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    public static func date(_ key: String, calendar: Calendar = DayKey.calendar) -> Date? {
        let parts = key.prefix(10).split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }
}
