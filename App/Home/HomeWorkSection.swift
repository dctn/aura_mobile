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

/// Events for the next 7 days, by day.
private struct HomeCalendarList: View {
    private struct Day: Identifiable {
        let id: Date
        let events: [CalendarEvent]
    }

    @Environment(AppModel.self) private var model
    @State private var events: [CalendarEvent]?
    @State private var failed = false
    @State private var retry = 0

    private var loadKey: String { "\(model.dataVersion)-\(retry)" }

    private var days: [Day] {
        let calendar = Calendar.current
        let sorted = (events ?? []).sorted { $0.start < $1.start }
        let grouped = Dictionary(grouping: sorted) { calendar.startOfDay(for: $0.start) }
        let starts = grouped.keys.sorted()
        return starts.map { start in Day(id: start, events: grouped[start] ?? []) }
    }

    var body: some View {
        AuraPage {
            content
        }
        .navigationTitle("Calendar")
        .navigationBarTitleDisplayMode(.large)
        .task(id: loadKey) { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if failed {
            HomeRetryLine(text: "Couldn't load your calendar.") { retry += 1 }
        } else if events == nil {
            ProgressView()
                .frame(maxWidth: .infinity)
        } else if days.isEmpty {
            Text("Nothing in the next 7 days")
                .font(.system(size: 15))
                .foregroundStyle(AuraColor.footnote)
        } else {
            ForEach(days) { day in
                dayGroup(day)
            }
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

    private func load() async {
        let now = Date()
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
        let end = calendar.date(byAdding: .day, value: 7, to: today) ?? now
        do {
            let loaded = try await model.api.events(from: now, to: end)
            events = loaded.filter { $0.end > now && $0.start < end }
            failed = false
        } catch {
            if Task.isCancelled { return }
            model.note(error)
            failed = events == nil
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
        .navigationTitle("Tasks")
        .navigationBarTitleDisplayMode(.large)
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
