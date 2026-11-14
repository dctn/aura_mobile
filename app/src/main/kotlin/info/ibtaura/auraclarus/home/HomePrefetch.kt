package info.ibtaura.auraclarus.home

import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.DayPlan
import info.ibtaura.aura.core.Habit
import info.ibtaura.aura.core.HabitLog
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.CacheKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * After Home's own loads settle, fills the cache for the pages most often opened from it, and wakes their backends
 * (the task and habit functions answer slowly the first time after a rest). Not in iOS. Each load fails on its own and
 * quietly: the page asks again when it opens. Keys (see CacheKeys):
 *
 * - `CacheKeys.TASKS`: `api.tasks()`, the Tasks page's list.
 * - `CacheKeys.plan(today)` and `CacheKeys.plan(tomorrow)`: `api.plan(day)` (null when there's no plan yet).
 * - `CacheKeys.HABITS`: `api.habits(includeArchived = false)`; then `CacheKeys.habitLogs(today - 399 days, today)`,
 *   Goals' check-offs, when there are habits.
 * - Calendar's events, through `app.loadCalendarEvents()` (Home's Upcoming already loads it, so usually nothing).
 * - `CacheKeys.zones(today)` through `loadDayZones`, and `CacheKeys.WORK_SCHEDULE` through `app.loadWorkSchedule()`:
 *   Calendar's zone card, hour grid and work hours.
 *
 * Run it through `app.prefetchIfDue`, which keeps it to once per sign-in plus once a minute after a change, and never
 * in UI tests.
 */
internal suspend fun prefetchFromHome(app: AppState) = coroutineScope {
    val api = app.api
    val cache = app.cache
    val today = LocalDate.now(DayKey.zone)
    launch { quietly { cache.load<List<TaskItem>>(CacheKeys.TASKS) { api.tasks() } } }
    listOf(today, today.plusDays(1)).map(DayKey::string).forEach { day ->
        launch { quietly { cache.load<DayPlan?>(CacheKeys.plan(day)) { api.plan(day) } } }
    }
    launch {
        quietly {
            val habits = cache.load<List<Habit>>(CacheKeys.HABITS) { api.habits(includeArchived = false) }
            if (habits.isNotEmpty()) {
                val from = DayKey.string(today.minusDays(399))
                val to = DayKey.string(today)
                cache.load<List<HabitLog>>(CacheKeys.habitLogs(from, to)) { api.habitLogs(from, to) }
            }
        }
    }
    launch { quietly { app.loadCalendarEvents() } }
    // Calendar's top card and hour grid (today's zones) and its work hours, so the page opens whole.
    launch { quietly { loadDayZones(app, DayKey.string(today)) } }
    launch { quietly { app.loadWorkSchedule() } }
}

// `Any?`, not Unit: a Unit block would make each `cache.load` above infer T = Unit and store Unit in place of the
// answer (it did: Tasks then crashed casting the cached entry).
private suspend fun quietly(block: suspend () -> Any?) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
    }
}
