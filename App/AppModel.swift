import AuraKit
import Foundation
import Observation

/// A metric pinned from its page, shown under Pins on Home. Saved on the phone only.
struct PinnedMetric: Codable, Identifiable, Equatable {
    var field: String
    var name: String
    var unit: String
    var id: String { field }
}

/// What every screen shares: reach it with `@Environment(AppModel.self)`.
@MainActor
@Observable
final class AppModel {
    let api: any AuraAPI
    let auth: any AuthService
    let modes: [Mode]
    let run: RunController
    /// UI tests run a few-second Quick check (`ReactionTest.Config.short`, `SpanTest.Config.short`); the app the full one.
    let quickCheckShort: Bool

    /// Nil while the Keychain is being read.
    var authState: AuthState?
    /// True while the consent step shows.
    var needsConsent = false
    /// Loading screens use `.task(id: model.dataVersion)`, so they load again after consent is granted.
    var dataVersion = 0

    var pins: [PinnedMetric] = [] {
        didSet { savePins() }
    }

    /// Apple Health, read for last night's sleep. UI tests use a stub.
    let sleepSource: any SleepSource
    /// Turned on from the morning check-in's "Use Apple Health for sleep". Saved on the phone.
    var appleHealthConnected = false {
        didSet { defaults.set(appleHealthConnected, forKey: Self.appleHealthKey) }
    }
    /// Last night went to the backend from Apple Health today, so the check-in doesn't ask for sleep times.
    private(set) var sleepSentToday = false
    /// The morning check-in went in today, so Home stops showing it.
    private(set) var morningCheckinSent = false

    @ObservationIgnored private let defaults: UserDefaults
    /// The day the screens last loaded for, so coming back on a new day loads them again.
    @ObservationIgnored private var loadedDay = DayKey.string(Date())
    @ObservationIgnored private var timeZoneSent = false
    @ObservationIgnored private var syncingSleep = false
    /// UI tests send the stub night at any hour, so the walk doesn't depend on when CI runs.
    @ObservationIgnored private let sleepAnyHour: Bool
    private static let pinsKey = "pinnedMetrics"
    private static let appleHealthKey = "appleHealthConnected"

    private static func sleepSentKey(_ day: String) -> String { "sleepSent-\(day)" }
    private static func checkinKey(_ day: String) -> String { "morningCheckin-\(day)" }

    init(api: any AuraAPI, auth: any AuthService, modes: [Mode], defaults: UserDefaults = .standard,
         quickCheckShort: Bool = false, sleepSource: any SleepSource = StubSleepSource(),
         sleepAnyHour: Bool = false) {
        self.api = api
        self.auth = auth
        self.modes = modes
        self.quickCheckShort = quickCheckShort
        self.defaults = defaults
        self.sleepSource = sleepSource
        self.sleepAnyHour = sleepAnyHour
        self.run = RunController(api: api)
        if let data = defaults.data(forKey: Self.pinsKey),
           let saved = try? JSONDecoder().decode([PinnedMetric].self, from: data) {
            self.pins = saved
        }
        self.appleHealthConnected = defaults.bool(forKey: Self.appleHealthKey)
        run.onError = { [weak self] error in self?.note(error) }
        refreshDayFlags()
    }

    /// UI tests pass `--uitesting` for stub data with no network. The app itself reads dev us-west-1.
    static func make() -> AppModel {
        let modes = loadModes()
        if ProcessInfo.processInfo.arguments.contains("--uitesting") {
            // `--uitesting-no-schedule` starts with no work hours, as a new account does.
            // `--uitesting-joined-session`: a session is already running elsewhere, so a run's start joins it.
            // `--uitesting-needs-check`: Find a time for today asks for the Quick check first, until "Plan anyway".
            // `--uitesting-short-check`: the Quick check runs for seconds, and any check with a reaction counts.
            // `--uitesting-health-night`: Apple Health is connected and has last night, so launch sends it.
            // `--uitesting-checkin`: the morning check-in shows on Home. Other walks start with it already sent, so
            // Home looks as it did before the check-in existed.
            let arguments = ProcessInfo.processInfo.arguments
            let shortCheck = arguments.contains("--uitesting-short-check")
            let healthNight = arguments.contains("--uitesting-health-night")
            let stub = StubAPI(workSchedule: arguments.contains("--uitesting-no-schedule") ? nil : StubAPI.defaultSchedule,
                               joinsRunningSession: arguments.contains("--uitesting-joined-session"),
                               needsQuickCheck: arguments.contains("--uitesting-needs-check"),
                               shortChecksCount: shortCheck)
            let defaults = UserDefaults(suiteName: "uitesting") ?? .standard
            // Every launch starts the morning afresh, so one walk's check-in doesn't hide the card from the next.
            clearDayFlags(in: defaults)
            defaults.set(healthNight, forKey: appleHealthKey)
            if !arguments.contains("--uitesting-checkin") && !healthNight {
                defaults.set(true, forKey: checkinKey(DayKey.string(Date())))
            }
            return AppModel(api: stub, auth: StubAuth(), modes: modes, defaults: defaults,
                            quickCheckShort: shortCheck, sleepSource: StubSleepSource(hasNight: healthNight),
                            sleepAnyHour: healthNight)
        }
        guard let url = Bundle.main.url(forResource: "aura-dev", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let live = try? LiveServices.make(configData: data) else {
            fatalError("aura-dev.json is missing from the app or can't be read")
        }
        return AppModel(api: live.api, auth: live.auth, modes: modes, sleepSource: HealthKitSleepSource())
    }

    /// Forgets today's check-in, today's sent night and the Apple Health choice.
    private static func clearDayFlags(in defaults: UserDefaults) {
        let today = DayKey.string(Date())
        defaults.removeObject(forKey: sleepSentKey(today))
        defaults.removeObject(forKey: checkinKey(today))
        defaults.removeObject(forKey: appleHealthKey)
    }

    static func loadModes(bundle: Bundle = .main) -> [Mode] {
        guard let url = bundle.url(forResource: "modes", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let modes = try? JSONDecoder().decode([Mode].self, from: data) else { return [] }
        return modes
    }

    func restore() async {
        authState = await auth.restore()
    }

    func signOut() async {
        // First, while the token still works.
        await run.endForSignOut()
        await auth.signOut()
        clearForSignedOut()
    }

    /// Pins, the run and its notification are saved per phone, so the next account mustn't inherit them. Safe to repeat:
    /// `run.close()` makes no backend call, so nothing here can come back through `run.onError` and `note`.
    private func clearForSignedOut() {
        run.close()
        pins = []
        needsConsent = false
        timeZoneSent = false
        // The next account mustn't get this account's sleep, nor skip its own check-in.
        Self.clearDayFlags(in: defaults)
        appleHealthConnected = false
        refreshDayFlags()
        authState = .signedOut
    }

    /// Screens send every caught error here. Missing consent opens the consent step, a lost session the sign-in.
    func note(_ error: Error) {
        guard let error = error as? AuraError else { return }
        switch error {
        case .consentRequired: needsConsent = true
        case .signedOut: clearForSignedOut()
        default: break
        }
    }

    /// Once per sign-in, and it doesn't matter if it fails.
    func sendTimeZoneIfNeeded() {
        guard case .some(.signedIn) = authState, !timeZoneSent else { return }
        timeZoneSent = true
        let api = self.api
        let identifier = TimeZone.current.identifier
        Task {
            try? await api.setTimeZoneIfMissing(identifier)
        }
    }

    /// Screens only load when `dataVersion` changes, so without this Home would still show yesterday after midnight.
    func reloadIfNewDay() {
        refreshDayFlags()
        let today = DayKey.string(Date())
        guard today != loadedDay else { return }
        loadedDay = today
        dataVersion += 1
    }

    /// Grants consent, then makes every screen load again through `dataVersion`.
    func grantConsent() async {
        do {
            try await api.grantHealthConsent()
            needsConsent = false
            dataVersion += 1
        } catch {
            note(error)
            return
        }
        // A night refused for missing consent goes now.
        await syncSleepIfNeeded()
    }

    // MARK: Sleep and the morning check-in

    /// Reads today's flags again, so a new day asks for the check-in and last night once more.
    private func refreshDayFlags() {
        let today = DayKey.string(Date())
        let sleep = defaults.bool(forKey: Self.sleepSentKey(today))
        if sleep != sleepSentToday { sleepSentToday = sleep }
        let checkin = defaults.bool(forKey: Self.checkinKey(today))
        if checkin != morningCheckinSent { morningCheckinSent = checkin }
    }

    func markMorningCheckinSent() {
        defaults.set(true, forKey: Self.checkinKey(DayKey.string(Date())))
        morningCheckinSent = true
    }

    /// Asks Apple Health for sleep, then sends last night. Throws when HealthKit can't ask.
    func connectAppleHealth() async throws {
        try await sleepSource.requestAccess()
        appleHealthConnected = true
        await syncSleepIfNeeded()
    }

    /// Sends last night from Apple Health once a day, from 04:00, when it ended this morning. Runs after sign-in and
    /// each time the app comes to the front. A missing consent opens the consent step; other failures try next time.
    func syncSleepIfNeeded() async {
        guard case .some(.signedIn) = authState, appleHealthConnected, !syncingSleep else { return }
        refreshDayFlags()
        guard !sleepSentToday else { return }
        let now = Date()
        let calendar = DayKey.calendar
        guard sleepAnyHour || calendar.component(.hour, from: now) >= 4 else { return }
        let today = calendar.startOfDay(for: now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today) ?? today
        let from = calendar.date(byAdding: .hour, value: 18, to: yesterday) ?? yesterday
        let todayKey = DayKey.string(now)

        syncingSleep = true
        defer { syncingSleep = false }
        do {
            let samples = try await sleepSource.samples(from: from, to: now)
            guard let night = SleepNightBuilder.build(samples: samples, timeZone: TimeZone.current),
                  night.date == todayKey else { return }
            _ = try await api.putSleepNight(night)
            defaults.set(true, forKey: Self.sleepSentKey(todayKey))
            sleepSentToday = true
        } catch {
            note(error)
        }
    }

    func mode(id: String) -> Mode? {
        modes.first { $0.id == id }
    }

    /// The mode an alert's `action.ref` starts. Only actions that are a timed run have one; the rest show Skip alone.
    func mode(alertRef ref: String) -> Mode? {
        let modeIds = ["nap": "nap-10", "recovery": "recovery-break", "breathing": "coherent", "move": "stand-reset"]
        return mode(id: modeIds[ref] ?? ref)
    }

    func isPinned(field: String) -> Bool {
        pins.contains { $0.field == field }
    }

    func togglePin(_ metric: PinnedMetric) {
        if isPinned(field: metric.field) {
            pins.removeAll { $0.field == metric.field }
        } else {
            pins.append(metric)
        }
    }

    private func savePins() {
        if let data = try? JSONEncoder().encode(pins) {
            defaults.set(data, forKey: Self.pinsKey)
        }
    }
}
