import Foundation

// What the screens work with. Field names follow the backend contracts in aws_docs (packages/contracts/src).

// MARK: Config

/// Public settings in App/Resources/aura-dev.json. Client ids and API URLs aren't secrets.
public struct AppConfig: Codable, Sendable, Equatable {
    public var stage: String
    public var region: String
    public var userPoolId: String
    public var clientId: String
    public var platform: String
    public var appVersion: String
    public var consentPolicyVersion: String
    public var services: [String: String]

    public init(stage: String, region: String, userPoolId: String, clientId: String, platform: String, appVersion: String,
                consentPolicyVersion: String, services: [String: String]) {
        self.stage = stage; self.region = region; self.userPoolId = userPoolId; self.clientId = clientId
        self.platform = platform; self.appVersion = appVersion; self.consentPolicyVersion = consentPolicyVersion
        self.services = services
    }

    public func baseURL(_ service: ServiceName) -> URL? {
        services[service.rawValue].flatMap(URL.init(string:))
    }
}

public enum ServiceName: String, Sendable, CaseIterable {
    case identity, healthData = "health-data", notification, calendar, capacity, content, habit, planner, session, task
}

// MARK: Home

public struct Score: Sendable, Equatable {
    public var name: String
    /// 100 is the person's normal; nil while the app is still learning it.
    public var value: Double?
    public var checksCounted: Int
    public var checksNeeded: Int
    public init(name: String, value: Double?, checksCounted: Int = 0, checksNeeded: Int = 0) {
        self.name = name; self.value = value; self.checksCounted = checksCounted; self.checksNeeded = checksNeeded
    }
}

public struct ScoresToday: Sendable, Equatable {
    public var attention: Score
    public var memory: Score
    public var thinking: Score
    public var focus: Score
    public init(attention: Score, memory: Score, thinking: Score, focus: Score) {
        self.attention = attention; self.memory = memory; self.thinking = thinking; self.focus = focus
    }
}

public struct CalendarEvent: Identifiable, Sendable, Equatable {
    public var id: String
    public var title: String
    public var start: Date
    public var end: Date
    public var kind: String
    public init(id: String, title: String, start: Date, end: Date, kind: String = "focus") {
        self.id = id; self.title = title; self.start = start; self.end = end; self.kind = kind
    }
}

public struct TaskItem: Identifiable, Sendable, Equatable {
    public var id: String
    public var title: String
    /// Its time when it has one (a block or a timed due date).
    public var at: Date?
    public var done: Bool
    public init(id: String, title: String, at: Date? = nil, done: Bool = false) {
        self.id = id; self.title = title; self.at = at; self.done = done
    }
}

public struct AlertItem: Identifiable, Sendable, Equatable {
    public var id: String
    public var featureId: String
    public var title: String
    public var detail: String?
    public var date: String
    public var done: Bool
    /// The app action the alert asks for, from `action.ref`, e.g. a mode id.
    public var actionRef: String?
    public init(id: String, featureId: String, title: String, detail: String? = nil, date: String, done: Bool = false, actionRef: String? = nil) {
        self.id = id; self.featureId = featureId; self.title = title; self.detail = detail
        self.date = date; self.done = done; self.actionRef = actionRef
    }
}

/// One day of `GET /v1/metrics/daily`.
public struct DailyMetrics: Sendable, Equatable {
    public var date: String
    public var burnoutIndexScore: Double?
    public var burnoutIndexRisk: String?
    public init(date: String, burnoutIndexScore: Double? = nil, burnoutIndexRisk: String? = nil) {
        self.date = date; self.burnoutIndexScore = burnoutIndexScore; self.burnoutIndexRisk = burnoutIndexRisk
    }
}

// MARK: Search and metrics

/// One of the 25 sections of `GET /v1/metrics/catalogue`.
public struct CatalogueSection: Identifiable, Sendable, Equatable {
    public var id: String
    public var title: String
    public var parameters: [CatalogueParameter]
    public init(id: String, title: String, parameters: [CatalogueParameter]) {
        self.id = id; self.title = title; self.parameters = parameters
    }
}

public struct CatalogueParameter: Identifiable, Sendable, Equatable {
    public var name: String
    /// Nil when nothing measures it.
    public var field: String?
    /// series, labels, profile, route or none.
    public var read: String
    public var unit: String
    public var format: String
    public var id: String { (field ?? "none") + "/" + name }
    /// Only `series` rows can draw the metric page today.
    public var hasSeries: Bool { read == "series" && field != nil }
    public init(name: String, field: String?, read: String, unit: String, format: String = "") {
        self.name = name; self.field = field; self.read = read; self.unit = unit; self.format = format
    }
}

public struct FieldAvailability: Sendable, Equatable {
    public var field: String
    public var latestDate: String
    /// Numbers only; words and yes/no values are shown as text.
    public var latestNumber: Double?
    public var latestText: String?
    public init(field: String, latestDate: String, latestNumber: Double? = nil, latestText: String? = nil) {
        self.field = field; self.latestDate = latestDate; self.latestNumber = latestNumber; self.latestText = latestText
    }
}

public struct MetricPoint: Sendable, Equatable {
    /// yyyy-MM-dd
    public var date: String
    public var value: Double?
    public init(date: String, value: Double?) { self.date = date; self.value = value }
}

public struct MetricSeries: Sendable, Equatable {
    public var field: String
    public var unit: String?
    public var points: [MetricPoint]
    public init(field: String, unit: String?, points: [MetricPoint]) {
        self.field = field; self.unit = unit; self.points = points
    }
}

// MARK: Sessions

public struct StartedSession: Sendable, Equatable {
    public var id: String
    /// True when a session was already running and this answered with it.
    public var joined: Bool
    public init(id: String, joined: Bool = false) { self.id = id; self.joined = joined }
}

public enum SessionAction: String, Sendable { case pause, resume, stop, complete }

public struct SessionTrace: Sendable, Equatable {
    /// pending until the day is computed; noData without band data.
    public var status: String
    public var stressDrop: Double?
    public init(status: String, stressDrop: Double? = nil) { self.status = status; self.stressDrop = stressDrop }
}

// MARK: Errors

public enum AuraError: Error, Equatable, Sendable {
    case offline
    case signedOut
    /// Health data consent is missing: the app shows the consent step, then retries.
    case consentRequired
    case server(code: String, message: String, status: Int)
}
