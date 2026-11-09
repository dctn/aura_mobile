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

    /// Nil while the Keychain is being read.
    var authState: AuthState?
    /// True while the consent step shows.
    var needsConsent = false
    /// Loading screens use `.task(id: model.dataVersion)`, so they load again after consent is granted.
    var dataVersion = 0

    var pins: [PinnedMetric] = [] {
        didSet { savePins() }
    }

    @ObservationIgnored private let defaults: UserDefaults
    /// The day the screens last loaded for, so coming back on a new day loads them again.
    @ObservationIgnored private var loadedDay = DayKey.string(Date())
    @ObservationIgnored private var timeZoneSent = false
    private static let pinsKey = "pinnedMetrics"

    init(api: any AuraAPI, auth: any AuthService, modes: [Mode], defaults: UserDefaults = .standard) {
        self.api = api
        self.auth = auth
        self.modes = modes
        self.defaults = defaults
        self.run = RunController(api: api)
        if let data = defaults.data(forKey: Self.pinsKey),
           let saved = try? JSONDecoder().decode([PinnedMetric].self, from: data) {
            self.pins = saved
        }
        run.onError = { [weak self] error in self?.note(error) }
    }

    /// UI tests pass `--uitesting` for stub data with no network. The app itself reads dev us-west-1.
    static func make() -> AppModel {
        let modes = loadModes()
        if ProcessInfo.processInfo.arguments.contains("--uitesting") {
            // `--uitesting-no-schedule` starts with no work hours, as a new account does.
            // `--uitesting-joined-session`: a session is already running elsewhere, so a run's start joins it.
            // `--uitesting-needs-check`: Find a time for today asks for the Quick check first, until "Plan anyway".
            let arguments = ProcessInfo.processInfo.arguments
            let stub = StubAPI(workSchedule: arguments.contains("--uitesting-no-schedule") ? nil : StubAPI.defaultSchedule,
                               joinsRunningSession: arguments.contains("--uitesting-joined-session"),
                               needsQuickCheck: arguments.contains("--uitesting-needs-check"))
            return AppModel(api: stub, auth: StubAuth(), modes: modes, defaults: UserDefaults(suiteName: "uitesting") ?? .standard)
        }
        guard let url = Bundle.main.url(forResource: "aura-dev", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let live = try? LiveServices.make(configData: data) else {
            fatalError("aura-dev.json is missing from the app or can't be read")
        }
        return AppModel(api: live.api, auth: live.auth, modes: modes)
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
