import Foundation

/// Answers without a network, with the Figma's numbers, for UI tests and CI. Holds no real data.
public final class StubAPI: AuraAPI, @unchecked Sendable {
    public init() {}

    private func day(_ offset: Int) -> String {
        DayKey.string(Calendar.current.date(byAdding: .day, value: offset, to: Date()) ?? Date())
    }

    private func today(hour: Int, minute: Int) -> Date {
        Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: Date()) ?? Date()
    }

    public func scoresToday() async throws -> ScoresToday {
        ScoresToday(attention: Score(name: "Attention", value: 96), memory: Score(name: "Memory", value: 82),
                    thinking: Score(name: "Thinking", value: 90), focus: Score(name: "Focus", value: 71))
    }

    public func events(from: Date, to: Date) async throws -> [CalendarEvent] {
        [CalendarEvent(id: "e1", title: "10 Min nap", start: today(hour: 12, minute: 15), end: today(hour: 12, minute: 30), kind: "recovery"),
         CalendarEvent(id: "e2", title: "Xcode Building", start: today(hour: 12, minute: 30), end: today(hour: 13, minute: 30), kind: "focus")]
    }

    public func tasks() async throws -> [TaskItem] {
        [TaskItem(id: "t1", title: "Reply to Ana"), TaskItem(id: "t2", title: "Book the dentist")]
    }

    public func alerts(from: String, to: String) async throws -> [AlertItem] {
        [AlertItem(id: "a1", featureId: "DX-04", title: "Take a 10 minutes nap", date: day(0), actionRef: "nap-10")]
    }

    public func markAlertDone(id: String) async throws {}
    public func logMood(_ mood: Int, at: Date) async throws {}

    public func dailyMetrics(from: String, to: String) async throws -> [DailyMetrics] {
        [DailyMetrics(date: day(0), burnoutIndexScore: 72, burnoutIndexRisk: "high")]
    }

    private func row(_ name: String, _ field: String?, _ unit: String) -> CatalogueParameter {
        CatalogueParameter(name: name, field: field, read: field == nil ? "none" : "series", unit: unit)
    }

    public func catalogue() async throws -> [CatalogueSection] {
        let titles = ["Running", "Cycling", "Gym and Strength", "Swimming", "Winter Sports", "Body Metrics", "Heart", "Stress",
                      "Sleep", "Recovery", "Focus", "Screen Wellness", "Meetings", "Mental Wellness", "Respiration", "Mobility",
                      "Nutrition", "Cycle Tracking", "Hearing", "Symptoms", "Burnout", "Habits", "Progress", "Other"]
        let movement = CatalogueSection(id: "movement", title: "Movement", parameters: [
            row("Step Count", "stepCount", "steps"), row("Walk Distance", "walkDistanceKm", "km"),
            row("Active Calories", "activeCalories", "kcal"), row("Resting Calories", "restingCalories", "kcal"),
            row("Total Calories", "totalCalories", "kcal"), row("Exercise Duration", "exerciseMinutes", "min"),
            row("Run Distance", nil, "km"), row("Active Minutes", nil, "min"), row("Standing Hours", nil, "h"),
            row("Stairs Climbed", nil, "floors"), row("Physical Effort Score", nil, ""), row("Workout Effort Rating", nil, ""),
            row("Movement Time", nil, "min"), row("Push Count", nil, ""), row("Wheelchair Distance", nil, "km"),
            row("Daily Step Goal Progress", nil, "%"), row("Active Calorie Goal Progress", nil, "%"),
            row("Stand Hours Goal Progress", nil, "%"), row("Inactivity Minutes", nil, "min"), row("Move Reminder Count", nil, ""),
        ])
        return [movement] + titles.map { CatalogueSection(id: $0.lowercased(), title: $0, parameters: []) }
    }

    public func availability(from: String, to: String) async throws -> [FieldAvailability] {
        let latest: [(String, Double)] = [("stepCount", 8200), ("walkDistanceKm", 3.4), ("activeCalories", 420),
                                          ("restingCalories", 1500), ("totalCalories", 1920), ("exerciseMinutes", 32)]
        return latest.map { FieldAvailability(field: $0.0, latestDate: day(0), latestNumber: $0.1) }
    }

    /// The Walk Distance bars from the Figma, repeated across the range.
    public func series(field: String, from: String, to: String) async throws -> MetricSeries {
        let bars = [2.3, 3.8, 3.1, 4.5, 3.6, 2.2, 3.0, 4.2, 5.0, 3.5, 3.1, 4.0, 4.5, 3.4]
        let start = DayKey.date(from) ?? Date()
        let end = DayKey.date(to) ?? Date()
        let count = max(1, (Calendar.current.dateComponents([.day], from: start, to: end).day ?? 0) + 1)
        let points = (0..<count).map { i -> MetricPoint in
            let date = Calendar.current.date(byAdding: .day, value: i, to: start) ?? start
            return MetricPoint(date: DayKey.string(date), value: bars[(bars.count - count % bars.count + i) % bars.count])
        }
        return MetricSeries(field: field, unit: "km", points: points)
    }

    public func startSession(kind: String, mode: String, plannedMinutes: Int?, title: String) async throws -> StartedSession {
        StartedSession(id: "stub-session")
    }

    public func sessionAction(id: String, action: SessionAction) async throws {}
    public func sessionTrace(id: String) async throws -> SessionTrace { SessionTrace(status: "noData") }
    public func addEvent(title: String, start: Date, end: Date, kind: String) async throws {}
    public func addTask(title: String, due: Date?) async throws {}
    public func grantHealthConsent() async throws {}
}

/// Always signed in, for UI tests and CI.
public struct StubAuth: AuthService {
    public init() {}
    public func restore() async -> AuthState { .signedIn(email: nil) }
    public func requestCode(email: String) async throws -> AuthStep {
        AuthStep(kind: .signIn, email: email, session: "stub", codeLength: 8)
    }
    public func finish(step: AuthStep, code: String) async throws -> AuthState { .signedIn(email: step.email) }
    public func resend(step: AuthStep) async throws -> AuthStep { step }
    public func signOut() async {}
}
