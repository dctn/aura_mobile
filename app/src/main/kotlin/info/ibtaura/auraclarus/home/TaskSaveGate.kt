package info.ibtaura.auraclarus.home

import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.Deferred
import java.util.WeakHashMap

/**
 * Task saves still on their way to the server, by task id, so Find a time can open at once after "Schedule" and wait
 * for the save only where it has to: before it asks the Planner to place that task. Each entry answers true when the
 * save landed and false when it was refused (the Tasks page then puts the task back and says why).
 */
internal object TaskSaveGate {
    private val pending = WeakHashMap<AppState, MutableMap<String, Deferred<Boolean>>>()

    @Synchronized
    fun track(app: AppState, taskId: String, landed: Deferred<Boolean>) {
        pending.getOrPut(app) { HashMap() }[taskId] = landed
    }

    @Synchronized
    private fun waiting(app: AppState, ids: Collection<String>?): List<Pair<String, Deferred<Boolean>>> {
        val map = pending[app] ?: return emptyList()
        return map.entries.filter { ids == null || it.key in ids }.map { it.key to it.value }
    }

    @Synchronized
    private fun forget(app: AppState, taskId: String, landed: Deferred<Boolean>) {
        val map = pending[app] ?: return
        if (map[taskId] === landed) map.remove(taskId)
    }

    /**
     * Waits for the saves of `ids` (null: every task) still under way. True when all of them landed (or none was
     * pending), false when one was refused.
     */
    suspend fun await(app: AppState, ids: Collection<String>?): Boolean {
        var allLanded = true
        for ((id, landed) in waiting(app, ids)) {
            if (!landed.await()) allLanded = false
            forget(app, id, landed)
        }
        return allLanded
    }
}
