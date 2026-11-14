package info.ibtaura.auraclarus.home

import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.DayPlan
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.CacheKeys
import java.time.LocalDate
import java.util.WeakHashMap

/**
 * The task list and stored plans the Tasks page last showed, kept per AppState for a short while. Find a time opens
 * with this list instead of loading the tasks again, and Tasks shows it at once when it comes back (then loads the
 * fresh list behind it). An entry counts only for the same account and `dataVersion`, and for [MAX_AGE_MILLIS]: a save
 * on another screen bumps the version, so the next open loads.
 *
 * Falls back to the shared screen cache's task list (prefetched after Home settles) while that is fresh. Originally a
 * stand-in for that cache: `TasksScreen`'s `loadTasks()`/`loadPlans()` and Find a time's task load
 * read and write only through [tasks], [plans] and [remember], so a shared cache can take its place in one spot.
 */
internal object TasksMemo {
    const val MAX_AGE_MILLIS = 120_000L

    private class Entry(
        val tasks: List<TaskItem>?,
        val plans: List<DayPlan>?,
        val account: Any?,
        val version: Int,
        val at: Long,
    )

    private val entries = WeakHashMap<AppState, Entry>()

    /** Keeps what the page shows now. A null part keeps what was there. */
    fun remember(app: AppState, tasks: List<TaskItem>?, plans: List<DayPlan>?, now: Long = System.currentTimeMillis()) {
        val old = fresh(app, now)
        entries[app] = Entry(tasks ?: old?.tasks, plans ?: old?.plans, app.authState, app.dataVersion, now)
    }

    /** The task list, when one was kept recently enough to show; else null (load it). */
    fun tasks(app: AppState, now: Long = System.currentTimeMillis()): List<TaskItem>? =
        fresh(app, now)?.tasks ?: app.cache.takeIf { it.isFresh(CacheKeys.TASKS) }?.peek<List<TaskItem>>(CacheKeys.TASKS)

    /** Today's and tomorrow's stored plans, as [tasks]. */
    fun plans(app: AppState, now: Long = System.currentTimeMillis()): List<DayPlan>? =
        fresh(app, now)?.plans ?: cachedPlans(app)

    /** Today's and tomorrow's plans from the shared cache, when both are there and fresh. */
    private fun cachedPlans(app: AppState): List<DayPlan>? {
        val today = LocalDate.now(DayKey.zone)
        val keys = listOf(today, today.plusDays(1)).map { CacheKeys.plan(DayKey.string(it)) }
        if (!keys.all { app.cache.has(it) && app.cache.isFresh(it) }) return null
        return keys.mapNotNull { app.cache.peek<DayPlan>(it) }
    }

    /** Forgets the list, so the next open loads it. */
    fun forget(app: AppState) {
        entries.remove(app)
    }

    private fun fresh(app: AppState, now: Long): Entry? {
        val entry = entries[app] ?: return null
        val usable = entry.account == app.authState && entry.version == app.dataVersion && now - entry.at in 0..MAX_AGE_MILLIS
        return entry.takeIf { usable }
    }
}
