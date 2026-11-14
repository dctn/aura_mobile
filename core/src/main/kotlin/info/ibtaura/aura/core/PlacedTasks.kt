package info.ibtaura.aura.core

/**
 * Ids of the tasks Find a time placed in this run of the app, from the plans LiveApi reads and writes. Task mirrors a
 * block onto its task a moment after Planner writes it, so a task read just before can still show no block: one listed
 * here is always looked for on the calendar. Keeps the newest [maxIds].
 */
internal class PlacedTasks(private val maxIds: Int = 500) {
    private val lock = Any()
    private val ids = LinkedHashSet<String>()

    fun note(plan: DayPlan) {
        synchronized(lock) {
            for (block in plan.placed) {
                val id = block.taskId ?: continue
                ids.remove(id)
                ids.add(id)
            }
            while (ids.size > maxIds) ids.remove(ids.first())
        }
    }

    fun contains(id: String): Boolean = synchronized(lock) { id in ids }

    fun clear() {
        synchronized(lock) { ids.clear() }
    }
}
