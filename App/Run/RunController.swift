import AuraKit
import Foundation
import Observation

/// The run in progress. Lives in AppModel so it survives switching tabs.
struct ActiveRun: Equatable {
    var mode: Mode
    var minutes: Int
    var title: String
    var engine: RunEngine
    var sessionId: String?
}

@MainActor
@Observable
final class RunController {
    private let api: any AuraAPI

    private(set) var active: ActiveRun?
    /// True while the full run screen shows; false leaves it in the bar above the tabs.
    var isExpanded = false

    init(api: any AuraAPI) {
        self.api = api
    }

    func start(mode: Mode, minutes: Int, title: String? = nil) {
        active = ActiveRun(mode: mode, minutes: minutes, title: title ?? mode.name,
                           engine: RunEngine(minutes: minutes, hasCountdown: mode.hasCountdown, now: Date()))
        isExpanded = true
    }

    func close() {
        active = nil
        isExpanded = false
    }
}
