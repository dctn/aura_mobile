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

    var pins: [PinnedMetric] = [] {
        didSet { savePins() }
    }

    @ObservationIgnored private let defaults: UserDefaults
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
    }

    /// UI tests pass `--uitesting` for stub data with no network. The app itself reads dev us-west-1.
    static func make() -> AppModel {
        let modes = loadModes()
        if ProcessInfo.processInfo.arguments.contains("--uitesting") {
            return AppModel(api: StubAPI(), auth: StubAuth(), modes: modes, defaults: UserDefaults(suiteName: "uitesting") ?? .standard)
        }
        // Replaced by LiveAPI and the Cognito sign-in once they're in AuraKit.
        return AppModel(api: StubAPI(), auth: StubAuth(), modes: modes)
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
        await auth.signOut()
        authState = .signedOut
    }

    func mode(id: String) -> Mode? {
        modes.first { $0.id == id }
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
