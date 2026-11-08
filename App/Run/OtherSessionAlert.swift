import AuraKit
import SwiftUI

/// Shown when a run asked for a session and the backend answered with one already running, maybe on another device.
/// Only one session can run at a time, so the person picks: stop that one and start this, or keep it and drop this run.
struct OtherSessionAlert: ViewModifier {
    @Environment(AppModel.self) private var model
    /// The run screen covers everything while it shows, so the root view only shows the alert while it's hidden.
    let showsHere: Bool

    func body(content: Content) -> some View {
        let other = model.run.active?.otherSession
        content.alert(
            "Another session is running",
            isPresented: Binding(get: { showsHere && other != nil }, set: { _ in }),
            presenting: other
        ) { _ in
            Button("Stop it and start mine") { model.run.stopOtherAndStart() }
            Button("Keep it", role: .cancel) { model.run.keepOtherSession() }
        } message: { other in
            Text(Self.message(other))
        }
    }

    /// "“Deep work” on your computer is still going, until 16:30. …"
    static func message(_ other: OtherSession) -> String {
        let name: String
        if let title = other.title?.trimmingCharacters(in: .whitespacesAndNewlines), !title.isEmpty {
            name = "“\(title)”"
        } else if let kind = other.kind, !kind.isEmpty {
            name = "A \(kind) session"
        } else {
            name = "A session"
        }
        let place: String
        switch other.device {
        case "phone": place = " on your phone"
        case "desktop", "mac", "windows": place = " on your computer"
        case "web": place = " in your browser"
        case "watch": place = " on your watch"
        default: place = ""
        }
        let until = other.endsAt.map { ", until \(ZoneText.clock($0))" } ?? ""
        return "\(name)\(place) is still going\(until). Only one session can run at a time."
    }
}

extension View {
    func otherSessionAlert(showsHere: Bool) -> some View {
        modifier(OtherSessionAlert(showsHere: showsHere))
    }
}
