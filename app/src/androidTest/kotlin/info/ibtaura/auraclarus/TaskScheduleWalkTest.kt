package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Tasks > auto-allocation on stub data: a new task with Auto-schedule on, its Schedule button opening Find a time for
 * just that task, Add to calendar, the "Scheduled" line back on Tasks, then completing it. Plan my day opens Find a time
 * with the marked tasks ticked.
 */
@RunWith(AndroidJUnit4::class)
class TaskScheduleWalkTest : WalkTestCase() {
    @Test
    fun scheduleOneTask() {
        val title = "Draft the walk notes"
        openTasksFromHome()

        Walk.tap("tasks-add", "Add task")
        Walk.find("task-editor", "The task editor didn't open")
        Walk.type("task-title-field", title)
        Walk.bringIntoView("task-auto")
        assertTrue("Auto-schedule isn't on for a new task", Walk.find("task-auto").isChecked)
        save("73-task-auto-schedule")

        Walk.bringIntoView("task-schedule")
        Walk.tap("task-schedule", "Schedule")
        Walk.find("find-time-confirm", "Schedule didn't open Find a time")
        assertTrue("The task isn't in Tasks to place", Walk.exists("find-time-tasks", timeoutMs = 10_000))
        Walk.find("find-time-block", "No blocks in the preview")
        assertTrue(
            "$title isn't placed in the preview",
            FindTimeWalk.scrollUntil { FindTimeWalk.blockLabels().any { it.contains(title) } },
        )
        save("74-task-find-time")

        Walk.tap("find-time-confirm", "Add to calendar")
        Walk.waitUntilGone("find-time-confirm", "The Find a time page didn't close")
        Walk.find("tasks-page", "Didn't come back to Tasks")

        val scheduled = Walk.poll(10_000) {
            row(title)?.takeIf { it.hasObject(By.textStartsWith("Scheduled")) }
        }
        assertTrue("$title doesn't say Scheduled", scheduled != null)
        save("75-task-scheduled")

        Walk.device.findObject(By.desc("Complete $title"))?.click() ?: Walk.failHere("No tick for $title")
        Walk.device.waitForIdle()
        val gone = Walk.poll(10_000) { if (row(title) == null) true else null }
        assertTrue("$title is still open after completing it", gone == true)
        save("76-task-completed")
    }

    @Test
    fun planMyDayTicksTheMarkedTasks() {
        openTasksFromHome()
        Walk.tap("tasks-plan-day", "Plan my day")
        Walk.find("find-time-tasks", "Plan my day didn't list the tasks")
        assertTrue("Write the review isn't listed", FindTimeWalk.hasText("Write the review", timeoutMs = 5_000))
        // The seeded task without Auto-schedule isn't offered.
        assertTrue("Tidy the inbox is offered", !FindTimeWalk.hasText("Tidy the inbox"))
        Walk.find("find-time-block", "No blocks in the preview")
        save("77-plan-my-day")

        Walk.tap("find-time-cancel", "Cancel")
        Walk.find("tasks-page", "Cancel didn't go back to Tasks")
    }

    /** The open row (`task-<id>`) showing `title`, or null. */
    private fun row(title: String): UiObject2? =
        Walk.device.findObject(By.res(Pattern.compile("task-[^ ]+")).hasDescendant(By.text(title), 4))

    /** Home > Work > Task. */
    private fun openTasksFromHome() {
        Walk.openTab("Home")
        Walk.findText("Insights", timeoutMs = 20_000)
        Walk.find("home-work-task", "Task row")
        Walk.dragPage(0.7, 0.2)
        Walk.dragPage(0.7, 0.2)
        Walk.open("home-work-task", until = "tasks-page", message = "Tasks didn't open")
    }
}
