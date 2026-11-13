import Foundation
import XCTest
@testable import AuraKit

/// The event editor's draft: what it refuses, where a new event starts, and what a save sends.
final class EventDraftTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_900_000_000)
    private var end: Date { start.addingTimeInterval(3600) }

    private func event(source: String = "app", seriesId: String? = nil) -> CalendarEvent {
        CalendarEvent(id: "e1", title: "Plan", start: start, end: end, kind: "focus", notes: "Old notes",
                      location: "Room 2", source: source, seriesId: seriesId, version: 4)
    }

    func testProblems() {
        XCTAssertEqual(EventDraft(title: "  ", start: start, end: end).problem, "Give it a title.")
        XCTAssertEqual(EventDraft(title: "Plan", start: end, end: start).problem, "It has to end after it starts.")
        XCTAssertEqual(EventDraft(title: "Plan", start: start, end: start).problem, "It has to end after it starts.")
        XCTAssertEqual(EventDraft(title: String(repeating: "a", count: 501), start: start, end: end).problem,
                       "The title is too long.")
        XCTAssertEqual(EventDraft(title: "Plan", start: start, end: end, location: String(repeating: "a", count: 501)).problem,
                       "The location is too long.")
        XCTAssertNil(EventDraft(title: "Plan", start: start, end: end).problem)
    }

    func testNewStartsAtTheNextHalfHourForAnHour() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let at = { (hour: Int, minute: Int, second: Int) -> Date in
            calendar.date(from: DateComponents(year: 2030, month: 3, day: 17, hour: hour, minute: minute, second: second))!
        }
        XCTAssertEqual(EventDraft.new(after: at(9, 10, 42), calendar: calendar).start, at(9, 30, 0))
        XCTAssertEqual(EventDraft.new(after: at(9, 30, 0), calendar: calendar).start, at(10, 0, 0))
        XCTAssertEqual(EventDraft.new(after: at(23, 45, 5), calendar: calendar).start,
                       calendar.date(from: DateComponents(year: 2030, month: 3, day: 18, hour: 0, minute: 0)))
        let draft = EventDraft.new(after: at(9, 10, 42), calendar: calendar)
        XCTAssertEqual(draft.end.timeIntervalSince(draft.start), 3600)
        XCTAssertEqual(draft.title, "")
        XCTAssertEqual(draft.kind, "focus")
    }

    func testDraftOfAnEventTakesItsFields() {
        let draft = EventDraft(event())
        XCTAssertEqual(draft, EventDraft(title: "Plan", start: start, end: end, kind: "focus", location: "Room 2",
                                         notes: "Old notes"))
    }

    func testUnchangedSendsNothing() {
        XCTAssertTrue(EventDraft(event()).changes(from: event()).isEmpty)
    }

    func testOnlyChangedFieldsAreSentAndClearedOnesAsNull() {
        var draft = EventDraft(event())
        draft.title = " Plan the week "
        draft.end = end.addingTimeInterval(1800)
        draft.notes = "  "
        let body = draft.changes(from: event())
        XCTAssertEqual(Set(body.keys), ["title", "end", "notes"])
        XCTAssertEqual(body["title"] as? String, "Plan the week")
        XCTAssertEqual(WireDate.parse(body["end"] as? String), end.addingTimeInterval(1800))
        XCTAssertTrue(body["notes"] is NSNull)
    }

    func testAKindIsSentOnlyWhenChosen() {
        var draft = EventDraft(event())
        draft.kind = "meeting"
        XCTAssertEqual(draft.changes(from: event()).keys.sorted(), ["kind"])
    }

    func testAppleEventSendsOnlyItsKind() {
        var draft = EventDraft(event(source: "apple"))
        draft.title = "Changed"
        draft.location = ""
        XCTAssertTrue(draft.changes(from: event(source: "apple")).isEmpty)
        draft.kind = "recovery"
        let body = draft.changes(from: event(source: "apple"))
        XCTAssertEqual(body.keys.sorted(), ["kind"])
        XCTAssertEqual(body["kind"] as? String, "recovery")
    }

    func testCreateBodyLeavesOutEmptyText() {
        let body = EventDraft(title: " Walk ", start: start, end: end, kind: "recovery").createBody()
        XCTAssertEqual(Set(body.keys), ["title", "start", "end", "kind"])
        XCTAssertEqual(body["title"] as? String, "Walk")
        let full = EventDraft(title: "Walk", start: start, end: end, location: "Park", notes: "Bring water").createBody()
        XCTAssertEqual(full["location"] as? String, "Park")
        XCTAssertEqual(full["notes"] as? String, "Bring water")
    }

    func testFlags() {
        XCTAssertTrue(event(source: "apple").isFromApple)
        XCTAssertFalse(event().isFromApple)
        XCTAssertTrue(event(seriesId: "s1").isOccurrence)
        XCTAssertFalse(event().isOccurrence)
    }
}

/// The editor's three routes against the stub network.
final class LiveAPIEventEditorTests: XCTestCase {
    private let start = TestRig.now
    private var end: Date { start.addingTimeInterval(3600) }

    func testCreateSendsAClientIdAndTheFields() async throws {
        let rig = TestRig()
        rig.server.on("/v1/calendar/events", [.json(201, "{\"event\":{\"id\":\"x\"}}")])
        try await rig.api.createEvent(EventDraft(title: "Walk with Ana", start: start, end: end, kind: "recovery",
                                                 location: "Park"))
        let call = try XCTUnwrap(rig.server.calls("/v1/calendar/events").first)
        XCTAssertEqual(call.request.httpMethod, "POST")
        XCTAssertNotNil(UUID(uuidString: call.body["id"] as? String ?? ""))
        XCTAssertEqual(call.body["title"] as? String, "Walk with Ana")
        XCTAssertEqual(call.body["kind"] as? String, "recovery")
        XCTAssertEqual(call.body["location"] as? String, "Park")
        XCTAssertNil(call.body["notes"])
        XCTAssertEqual(WireDate.parse(call.body["start"] as? String), start)
    }

    func testUpdatePatchesTheChangesWithTheVersionRead() async throws {
        let rig = TestRig()
        let path = "/v1/calendar/events/e1"
        rig.server.on(path, [.json(200, "{\"event\":{\"id\":\"e1\"}}")])
        let event = CalendarEvent(id: "e1", title: "Plan", start: start, end: end, version: 4)
        var draft = EventDraft(event)
        draft.title = "Plan the week"
        try await rig.api.updateEvent(event, to: draft)
        let calls = rig.server.calls(path)
        XCTAssertEqual(calls.count, 1)
        XCTAssertEqual(calls[0].request.httpMethod, "PATCH")
        XCTAssertEqual(calls[0].header("If-Match"), "\"v4\"")
        XCTAssertEqual(calls[0].body.keys.sorted(), ["title"])
    }

    func testUpdateWithoutAVersionReadsOneFirst() async throws {
        let rig = TestRig()
        let path = "/v1/calendar/events/e1"
        let read = "{\"event\":{\"id\":\"e1\",\"title\":\"Plan\",\"start\":\"\(WireDate.string(start))\",\"end\":\"\(WireDate.string(end))\",\"version\":9}}"
        rig.server.on(path, [.json(200, read), .json(200, read)])
        let event = CalendarEvent(id: "e1", title: "Plan", start: start, end: end)
        var draft = EventDraft(event)
        draft.kind = "meeting"
        try await rig.api.updateEvent(event, to: draft)
        let calls = rig.server.calls(path)
        XCTAssertEqual(calls.map { $0.request.httpMethod }, ["GET", "PATCH"])
        XCTAssertEqual(calls[1].header("If-Match"), "\"v9\"")
    }

    func testNothingChangedSendsNothing() async throws {
        let rig = TestRig()
        let event = CalendarEvent(id: "e1", title: "Plan", start: start, end: end, version: 4)
        try await rig.api.updateEvent(event, to: EventDraft(event))
        XCTAssertTrue(rig.server.calls("/v1/calendar/events/e1").isEmpty)
    }

    func testChangedElsewhereIs412() async throws {
        let rig = TestRig()
        let path = "/v1/calendar/events/e1"
        rig.server.on(path, [.json(412, "{\"error\":{\"code\":\"version_mismatch\",\"message\":\"Changed.\"}}")])
        let event = CalendarEvent(id: "e1", title: "Plan", start: start, end: end, version: 4)
        var draft = EventDraft(event)
        draft.title = "New"
        do {
            try await rig.api.updateEvent(event, to: draft)
            XCTFail("A 412 should throw")
        } catch AuraError.server(_, _, let status) {
            XCTAssertEqual(status, 412)
        }
    }

    func testDeleteEscapesAnOccurrenceId() async throws {
        let rig = TestRig()
        let path = "/v1/calendar/events/s1@2030-03-17"
        rig.server.on(path, [.json(204, "")])
        try await rig.api.deleteEvent(id: "s1@2030-03-17")
        let call = try XCTUnwrap(rig.server.calls(path).first)
        XCTAssertEqual(call.request.httpMethod, "DELETE")
        XCTAssertTrue(call.address.hasSuffix("/v1/calendar/events/s1%402030-03-17"), call.address)
    }

    func testEventsCarryWhatTheEditorNeeds() async throws {
        let rig = TestRig()
        let page = """
        {"events":[{"id":"s1@2030-03-17","title":"Standup","start":"\(WireDate.string(start))","end":"\(WireDate.string(end))",
        "kind":"meeting","notes":"Agenda","location":"Room 2","source":"app","seriesId":"s1","version":3}],"cursor":null}
        """
        rig.server.on("/v1/calendar/events", [.json(200, page)])
        let events = try await rig.api.events(from: start, to: end)
        let event = try XCTUnwrap(events.first)
        XCTAssertEqual(event, CalendarEvent(id: "s1@2030-03-17", title: "Standup", start: start, end: end, kind: "meeting",
                                            notes: "Agenda", location: "Room 2", source: "app", seriesId: "s1", version: 3))
    }
}

/// The stub keeps what the editor does, so the UI walk sees it.
final class StubEventEditorTests: XCTestCase {
    func testCreateChangeAndDelete() async throws {
        let api = StubAPI()
        let now = Date()
        let from = Calendar.current.startOfDay(for: now)
        let to = from.addingTimeInterval(8 * 86_400)
        let start = now.addingTimeInterval(3 * 3600)
        try await api.createEvent(EventDraft(title: "Walk with Ana", start: start, end: start.addingTimeInterval(1800),
                                             kind: "recovery"))
        let listed = try await api.events(from: from, to: to)
        var made = try XCTUnwrap(listed.first { $0.title == "Walk with Ana" })
        XCTAssertEqual(made.kind, "recovery")

        var draft = EventDraft(made)
        draft.title = "Walk with Ana and Sam"
        try await api.updateEvent(made, to: draft)
        let relisted = try await api.events(from: from, to: to)
        let madeId = made.id
        made = try XCTUnwrap(relisted.first { $0.id == madeId })
        XCTAssertEqual(made.title, "Walk with Ana and Sam")

        try await api.deleteEvent(id: made.id)
        let after = try await api.events(from: from, to: to)
        XCTAssertFalse(after.contains { $0.id == made.id })
    }

    func testAFixedEventCanBeDeleted() async throws {
        let api = StubAPI()
        let now = Date()
        try await api.deleteEvent(id: "e1")
        let events = try await api.events(from: now, to: now.addingTimeInterval(86_400))
        XCTAssertFalse(events.contains { $0.id == "e1" })
        XCTAssertTrue(events.contains { $0.id == "e2" })
    }
}
