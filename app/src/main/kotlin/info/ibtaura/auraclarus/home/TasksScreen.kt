package info.ibtaura.auraclarus.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.TaskItem
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Home > Work > Task (iOS HomeTaskList): open tasks, those with a time first, soonest on top. Read only. */
@Composable
fun TasksScreen(app: AppState, nav: AuraNav) {
    var tasks by remember { mutableStateOf<List<TaskItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(app.dataVersion, retry) {
        try {
            tasks = openTasks(app.api.tasks())
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = tasks == null
        }
    }

    AuraPage(Modifier.testTag("tasks-page"), title = "Tasks", onBack = { nav.pop() }) {
        val loaded = tasks
        when {
            failed -> HomeRetryLine("Couldn't load your tasks.", retry = { retry += 1 })
            loaded == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }
            loaded.isEmpty() -> Text("No open tasks", style = AuraType.callout, color = AuraColors.footnote)
            else -> AuraCard(padding = 20.dp, spacing = 14.dp) {
                loaded.forEach { TaskRow(it) }
            }
        }
    }
}

private val taskDay: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val taskTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@Composable
private fun TaskRow(task: TaskItem) {
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("task-${task.id}"),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        task.at?.let { at ->
            val local = at.atZone(ZoneId.systemDefault())
            Text("${taskDay.format(local)}, ${taskTime.format(local)}", style = AuraType.system(11), color = AuraColors.gray)
        }
        Text(task.title, style = AuraType.calloutMedium, color = Color.Black)
    }
}

private fun openTasks(all: List<TaskItem>): List<TaskItem> {
    val open = all.filter { !it.done }
    val timed = open.filter { it.at != null }.sortedBy { it.at }
    return timed + open.filter { it.at == null }
}
