import AuraKit
import SwiftUI

/// Work: two rows that open read-only lists. The Figma has no frame for those lists.
struct HomeWorkSection: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SectionTitle("Work")
            NavigationLink {
                HomeCalendarList()
            } label: {
                ListRowCard(symbol: "arrow.clockwise.heart.fill", title: "Calendar")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("home-work-calendar")

            NavigationLink {
                HomeTaskList()
            } label: {
                ListRowCard(symbol: "arrow.clockwise.heart.fill", title: "Task")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("home-work-task")
        }
    }
}

/// Today's zones, Find a time and today's events, the work hours, then the events of the next 6 days by day.
private struct HomeCalendarList: View {
    private struct Day: Identifiable {
        let id: Date
        let events: [CalendarEvent]
    }

    @Environment(AppModel.self) private var model
    /// Events still to come in the next 7 days, for the lists.
    @State private var events: [CalendarEvent]?
    /// All of today's events, past ones too, for the hour grid.
    @State private var todayEvents: [CalendarEvent] = []
    @State private var failed = false
    @State private var zoneDay: ZoneDay?
    @State private var zonesFailed = false
    @State private var schedule: WorkSchedule?
    @State private var scheduleLoaded = false
    @State private var editingHours = false
    @State private var findingTime = false
    @State private var retry = 0

    private var loadKey: String { "\(model.dataVersion)-\(retry)" }

    private var readyDay: ZoneDay? {
        guard let zoneDay, zoneDay.status == .ready else { return nil }
        return zoneDay
    }

    private var days: [Day] {
        let calendar = Calendar.current
        let sorted = (events ?? []).sorted { $0.start < $1.start }
        let grouped = Dictionary(grouping: sorted) { calendar.startOfDay(for: $0.start) }
        let starts = grouped.keys.sorted()
        return starts.map { start in Day(id: start, events: grouped[start] ?? []) }
    }

    private var todayGroup: Day? {
        days.first { Calendar.current.isDateInToday($0.id) }
    }

    private var laterDays: [Day] {
        days.filter { !Calendar.current.isDateInToday($0.id) }
    }

    var body: some View {
        AuraPage {
            content
        }
        .metricBackBar("Calendar")
        .task(id: loadKey) { await load() }
        .sheet(isPresented: $editingHours) {
            WorkHoursForm(saved: schedule)
        }
        // A confirmed plan reloads every screen through `dataVersion`, so its blocks show on the hour grid.
        .sheet(isPresented: $findingTime) {
            FindATimeSheet(date: DayKey.string(Date())) {
                model.dataVersion += 1
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        zoneLine
        if events != nil || failed {
            PillButton(title: "Find a time", fills: true) {
                findingTime = true
            }
            .accessibilityIdentifier("calendar-find-time")
        }
        if let readyDay {
            ZoneCardSection(title: "Today", padding: 14) {
                TimelineView(.everyMinute) { context in
                    ZoneDayGrid(zones: readyDay.zones, events: todayEvents, schedule: schedule, now: context.date)
                }
            }
            ZoneDayList(zones: readyDay.zones)
            ZoneLegend()
        } else {
            eventsState
            if let todayGroup {
                dayGroup(todayGroup)
            }
        }
        if scheduleLoaded {
            WorkHoursRow(title: "Work hours", value: ZoneText.schedule(schedule), identifier: "calendar-work-hours") {
                editingHours = true
            }
        }
        if readyDay != nil {
            eventsState
        }
        ForEach(laterDays) { day in
            dayGroup(day)
        }
    }

    /// A failed zones load only shows here, so the events still show.
    @ViewBuilder
    private var zoneLine: some View {
        if zonesFailed {
            AuraCard(padding: 20) {
                HomeRetryLine(text: "Couldn't load your zones.") { retry += 1 }
            }
        } else if let zoneDay, zoneDay.status != .unknown {
            ZoneLineCard(day: zoneDay) { editingHours = true }
        }
    }

    @ViewBuilder
    private var eventsState: some View {
        if failed {
            HomeRetryLine(text: "Couldn't load your calendar.") { retry += 1 }
        } else if events == nil {
            ProgressView()
                .frame(maxWidth: .infinity)
        } else if days.isEmpty && readyDay == nil {
            Text("Nothing in the next 7 days")
                .font(.system(size: 15))
                .foregroundStyle(AuraColor.footnote)
        }
    }

    private func dayGroup(_ day: Day) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(Self.dayTitle(day.id))
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.black)
                .accessibilityAddTraits(.isHeader)
            AuraCard(padding: 20) {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(day.events) { event in
                        HomeEventRow(event: event)
                    }
                }
            }
        }
    }

    private static func dayTitle(_ date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return "Today" }
        if calendar.isDateInTomorrow(date) { return "Tomorrow" }
        return date.formatted(.dateTime.weekday(.wide).day().month(.wide))
    }

    /// Events, today's zones and the work hours load together; each can fail on its own.
    private func load() async {
        let now = Date()
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: today) ?? now
        let end = calendar.date(byAdding: .day, value: 7, to: today) ?? now
        let todayKey = DayKey.string(now)
        let api = model.api

        async let eventsCall = api.events(from: today, to: end)
        async let zonesCall = api.dayZones(from: todayKey, to: todayKey)
        async let scheduleCall = api.workSchedule()

        let eventsResult: Result<[CalendarEvent], Error>
        do { eventsResult = .success(try await eventsCall) } catch { eventsResult = .failure(error) }
        let zonesResult: Result<[ZoneDay], Error>
        do { zonesResult = .success(try await zonesCall) } catch { zonesResult = .failure(error) }
        let scheduleResult: Result<WorkSchedule?, Error>
        do { scheduleResult = .success(try await scheduleCall) } catch { scheduleResult = .failure(error) }

        if Task.isCancelled { return }

        switch eventsResult {
        case .success(let loaded):
            events = loaded.filter { $0.end > now && $0.start < end }
            todayEvents = loaded.filter { $0.end > today && $0.start < tomorrow }
            failed = false
        case .failure(let error):
            model.note(error)
            failed = events == nil
        }

        switch zonesResult {
        case .success(let loaded):
            zoneDay = loaded.first(where: { $0.date == todayKey }) ?? loaded.first ?? ZoneDay(date: todayKey, status: .unknown)
            zonesFailed = false
        case .failure(let error):
            model.note(error)
            zonesFailed = zoneDay == nil
        }

        switch scheduleResult {
        case .success(let loaded):
            schedule = loaded
            scheduleLoaded = true
        case .failure(let error):
            model.note(error)
        }
    }
}

/// Open tasks: those with a time first, soonest on top.
private struct HomeTaskList: View {
    @Environment(AppModel.self) private var model
    @State private var tasks: [TaskItem]?
    @State private var failed = false
    @State private var retry = 0

    private var loadKey: String { "\(model.dataVersion)-\(retry)" }

    var body: some View {
        AuraPage {
            content
        }
        .metricBackBar("Tasks")
        .task(id: loadKey) { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if failed {
            HomeRetryLine(text: "Couldn't load your tasks.") { retry += 1 }
        } else if let tasks {
            if tasks.isEmpty {
                Text("No open tasks")
                    .font(.system(size: 15))
                    .foregroundStyle(AuraColor.footnote)
            } else {
                taskCard(tasks)
            }
        } else {
            ProgressView()
                .frame(maxWidth: .infinity)
        }
    }

    private func taskCard(_ items: [TaskItem]) -> some View {
        AuraCard(padding: 20) {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(items) { task in
                    taskRow(task)
                }
            }
        }
    }

    private func taskRow(_ task: TaskItem) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            if let at = task.at {
                Text(at.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated).hour().minute()))
                    .font(.system(size: 11))
                    .foregroundStyle(Color.gray)
            }
            Text(task.title)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.black)
        }
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        do {
            let loaded = try await model.api.tasks()
            tasks = Self.openTasks(loaded)
            failed = false
        } catch {
            if Task.isCancelled { return }
            model.note(error)
            failed = tasks == nil
        }
    }

    private static func openTasks(_ all: [TaskItem]) -> [TaskItem] {
        let open = all.filter { !$0.done }
        let timed = open.filter { $0.at != nil }
        let sortedTimed = timed.sorted { ($0.at ?? .distantFuture) < ($1.at ?? .distantFuture) }
        let untimed = open.filter { $0.at == nil }
        return sortedTimed + untimed
    }
}
