import AuraKit
import SwiftUI

/// Makes or changes one calendar event, as a sheet opened from Calendar. The Figma has no frame for it, so it follows
/// the Work hours form. An Apple Calendar event only takes a new kind here; the Mac owns the rest.
struct EventEditor: View {
    /// The event being changed, or nil for a new one.
    let event: CalendarEvent?

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var draft: EventDraft
    @State private var saving = false
    @State private var errorText: String?
    @State private var confirmingDelete = false

    private static let kindNames = ["focus": "Focus", "meeting": "Meeting", "recovery": "Recovery"]

    init(event: CalendarEvent?, now: Date = Date()) {
        self.event = event
        _draft = State(initialValue: event.map { EventDraft($0) } ?? EventDraft.new(after: now))
    }

    private var isNew: Bool { event == nil }
    private var appleOnly: Bool { event?.isFromApple == true }
    /// An Apple event's other fields aren't checked: only its kind is sent.
    private var problem: String? { appleOnly ? nil : draft.problem }

    var body: some View {
        AuraPage {
            SectionTitle(isNew ? "New event" : "Edit event")
                .padding(.top, 8)
            if let note {
                Text(note)
                    .font(.system(size: 15))
                    .foregroundStyle(Color.gray)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("event-editor-note")
            }

            AuraCard(padding: 20) {
                VStack(spacing: 0) {
                    TextField("Title", text: $draft.title)
                        .font(.system(size: 17, weight: .medium))
                        .submitLabel(.done)
                        .frame(minHeight: 50)
                        .accessibilityIdentifier("event-title")
                    Divider()
                    dateRow("Starts", selection: $draft.start, identifier: "event-start")
                    Divider()
                    dateRow("Ends", selection: $draft.end, identifier: "event-end")
                }
            }
            .disabled(appleOnly || saving)

            kindCard

            AuraCard(padding: 20) {
                VStack(spacing: 0) {
                    TextField("Location", text: $draft.location)
                        .font(.system(size: 17))
                        .submitLabel(.done)
                        .frame(minHeight: 50)
                        .accessibilityIdentifier("event-location")
                    Divider()
                    TextField("Notes", text: $draft.notes, axis: .vertical)
                        .font(.system(size: 17))
                        .lineLimit(3...8)
                        .padding(.vertical, 14)
                        .accessibilityIdentifier("event-notes")
                }
            }
            .disabled(appleOnly || saving)

            if let problem {
                Text(problem)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AuraColor.burnout)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("event-problem")
            }
            if let errorText {
                Text(errorText)
                    .font(.system(size: 15))
                    .foregroundStyle(AuraColor.red)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("event-error")
            }
            buttons
            if !isNew && !appleOnly {
                deleteButton
            }
        }
        .interactiveDismissDisabled(saving)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .onChange(of: draft.start) { old, new in
            // Moving the start keeps the length, as Calendar apps do.
            let length = draft.end.timeIntervalSince(old)
            if length > 0 { draft.end = new.addingTimeInterval(length) }
        }
        .confirmationDialog(deleteQuestion, isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button(event?.isOccurrence == true ? "Delete this day" : "Delete event", role: .destructive) {
                delete()
            }
            .accessibilityIdentifier("event-delete-confirm")
            Button("Cancel", role: .cancel) {}
        }
    }

    /// Why some of it can't change, or how far a change reaches.
    private var note: String? {
        if appleOnly { return "From Apple Calendar on your Mac. Change it there; here you can set its kind." }
        if event?.isOccurrence == true { return "This repeats. Changes here apply to this day only." }
        if event?.source == "planner" { return "Find a time placed this. You can still change it." }
        return nil
    }

    private var deleteQuestion: String {
        event?.isOccurrence == true ? "Delete this day of the event?" : "Delete this event?"
    }

    private func dateRow(_ title: String, selection: Binding<Date>, identifier: String) -> some View {
        DatePicker(title, selection: selection, displayedComponents: [.date, .hourAndMinute])
            .font(.system(size: 17, weight: .medium))
            .tint(AuraColor.blue)
            .frame(minHeight: 50)
            .accessibilityIdentifier(identifier)
    }

    private var kindCard: some View {
        AuraCard(padding: 20) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Kind")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.black)
                Picker("Kind", selection: $draft.kind) {
                    ForEach(EventDraft.kinds, id: \.self) { kind in
                        Text(Self.kindNames[kind] ?? kind).tag(kind)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("event-kind")
            }
        }
        .disabled(saving)
    }

    private var buttons: some View {
        HStack(spacing: 10) {
            PillButton(title: "Cancel", style: .secondary, fills: true) {
                dismiss()
            }
            .disabled(saving)
            .accessibilityIdentifier("event-cancel")

            PillButton(title: saving ? "Saving..." : (isNew ? "Add" : "Save"), fills: true) {
                save()
            }
            .disabled(saving || problem != nil)
            .accessibilityIdentifier("event-save")
        }
    }

    private var deleteButton: some View {
        Button(role: .destructive) {
            confirmingDelete = true
        } label: {
            Text(event?.isOccurrence == true ? "Delete this day" : "Delete event")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(AuraColor.red)
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.plain)
        .disabled(saving)
        .accessibilityIdentifier("event-delete")
    }

    /// Saves, then makes every screen load again through `dataVersion`, so Calendar and Home show the change.
    private func save() {
        let current = draft
        let original = event
        run {
            if let original {
                try await model.api.updateEvent(original, to: current)
            } else {
                try await model.api.createEvent(current)
            }
        }
    }

    private func delete() {
        guard let id = event?.id else { return }
        run {
            try await model.api.deleteEvent(id: id)
        }
    }

    private func run(_ write: @escaping () async throws -> Void) {
        saving = true
        errorText = nil
        Task {
            do {
                try await write()
                saving = false
                model.dataVersion += 1
                dismiss()
            } catch {
                saving = false
                errorText = Self.words(for: error)
                model.note(error)
            }
        }
    }

    private static func words(for error: Error) -> String {
        guard let error = error as? AuraError else { return "Couldn't save. Try again." }
        switch error {
        case .offline:
            return "You're offline. Try again when you're connected."
        case .server(let code, _, let status):
            if code == "work_limit_reached" { return "That would pass today's work limit. Pick a shorter time or another day." }
            if code == "apple_event_read_only" { return "This event belongs to Apple Calendar. Change it on your Mac." }
            if status == 412 { return "This event changed somewhere else. Close it and open it again." }
            if status == 404 { return "This event is gone. It may have been deleted somewhere else." }
            return "Couldn't save. Try again."
        default:
            return "Couldn't save. Try again."
        }
    }
}
