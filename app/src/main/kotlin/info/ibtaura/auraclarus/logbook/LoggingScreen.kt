package info.ibtaura.auraclarus.logbook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.CheckinKind
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.LogType
import info.ibtaura.aura.core.LogbookMaths
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Chevron
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.SearchCategoryStyle
import info.ibtaura.auraclarus.design.SearchGroupCard
import info.ibtaura.auraclarus.design.SearchRowDivider
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.home.HomeRetryLine
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Search > Logging: today's water, mood and connection in one card, the categories to log more, and this week's
// entries. A category (and the Calorie Tracker) opens in place, so the page needs no routes of its own beyond Logging.

private const val CALORIES_PAGE = "calories"

@Composable
fun LoggingScreen(app: AppState, nav: AuraNav) {
    val model = remember { LogbookModel(app) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(app.dataVersion) { model.load() }
    LaunchedEffect(Unit) { model.loadSettings() }

    val category = LogCategory.entries.firstOrNull { it.name == page }
    when {
        page == CALORIES_PAGE -> {
            BackHandler { page = null }
            CalorieTrackerPage(model, onBack = { page = null })
        }
        category != null -> {
            BackHandler { page = null }
            LogCategoryPage(model, category, onBack = { page = null }, onCalories = { page = CALORIES_PAGE })
        }
        else -> LoggingHome(model, onBack = { nav.pop() }, open = { page = it })
    }
}

@Composable
private fun LoggingHome(model: LogbookModel, onBack: () -> Unit, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    AuraPage(title = "Logging", onBack = onBack) {
        TodayCard(model)
        Footnote("Entries go to your Aura account. They can't be changed or removed yet, so log again to correct one.")
        CategoriesCard(model) { category ->
            open(if (category == LogCategory.nutrition) CALORIES_PAGE else category.name)
        }
        HistoryCard(model, title = "This week", category = null, retry = { scope.launch { model.load() } })
    }
}

// MARK: Today

@Composable
private fun TodayCard(model: LogbookModel) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val todayKey = model.todayKey
    val water = LogbookMaths.waterMl(model.logs, todayKey)
    val mood = LogbookMaths.latest(model.checkins, CheckinKind.mood, todayKey)?.mood
    val connection = LogbookMaths.latest(model.checkins, CheckinKind.connection, todayKey)?.connection

    fun send(write: suspend () -> String?) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = write()
            busy = false
        }
    }

    AuraCard(padding = 22.dp, spacing = 16.dp) {
        CardHeader("drop.fill", "Water", SearchCategoryStyle.blue) {
            Text("Goal ${LogTime.grouped(model.waterGoal)} ml", style = AuraType.caption, color = AuraColors.gray)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(LogTime.grouped(water), style = AuraType.system(34, androidx.compose.ui.text.font.FontWeight.Bold),
                color = Color.Black, modifier = Modifier.testTag("logging-water-total"))
            Text("ml today", style = AuraType.callout, color = AuraColors.gray, modifier = Modifier.padding(bottom = 6.dp))
        }
        ProgressBar(water.toFloat() / model.waterGoal.coerceAtLeast(1), SearchCategoryStyle.blue)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(250, 500).forEach { ml ->
                Chip("+ $ml ml", false, "logging-water-$ml", Modifier.weight(1f)) {
                    send { model.addLog(LogType.water, json("ml" to ml)) }
                }
            }
        }
        info.ibtaura.auraclarus.design.CardDivider()
        CardHeader(LogCategory.mood.symbol, "Mood", LogCategory.mood.color) {
            mood?.let { Text(ScaleWords.moodFaces[it - 1], style = AuraType.caption, color = AuraColors.gray) }
        }
        Text("How are you feeling right now?", style = AuraType.bodyMedium, color = Color.Black)
        FaceRow(mood, ScaleWords.moodFaces, enabled = !busy, tag = "logging-mood") { value ->
            send { model.addCheckin(CheckinKind.mood, json("mood" to value)) }
        }
        info.ibtaura.auraclarus.design.CardDivider()
        CardHeader(LogCategory.people.symbol, "Connection", LogCategory.people.color) {
            connection?.let { Text(ScaleWords.connectionFaces[it - 1], style = AuraType.caption, color = AuraColors.gray) }
        }
        Text("How connected to other people do you feel?", style = AuraType.bodyMedium, color = Color.Black)
        FaceRow(connection, ScaleWords.connectionFaces, enabled = !busy, tag = "logging-connection") { value ->
            send { model.addCheckin(CheckinKind.connection, json("connection" to value)) }
        }
        error?.let { Text(it, style = AuraType.callout, color = AuraColors.red, modifier = Modifier.testTag("logging-today-error")) }
    }
}

// MARK: Categories

@Composable
private fun CategoriesCard(model: LogbookModel, open: (LogCategory) -> Unit) {
    SearchGroupCard {
        LogCategory.entries.forEachIndexed { index, category ->
            if (index > 0) SearchRowDivider(leading = 70.dp)
            val count = LogText.todayCount(model.logs, model.checkins, model.todayKey, category)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 66.dp)
                    .clickable(role = Role.Button) { open(category) }
                    .testTag(category.tag)
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SmallIconTile(category.symbol, category.color)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(category.title, style = AuraType.bodyMedium, color = Color.Black, maxLines = 1)
                    Text(
                        if (category == LogCategory.nutrition) "Calorie Tracker" else category.blurb,
                        style = AuraType.caption, color = AuraColors.gray, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (count > 0) Text("$count today", style = AuraType.caption, color = AuraColors.gray)
                Chevron()
            }
        }
    }
}

// MARK: History

/** The last week's entries, newest first, of one category or all. Twelve at first, then all. */
@Composable
internal fun HistoryCard(model: LogbookModel, title: String, category: LogCategory?, retry: () -> Unit) {
    var showAll by rememberSaveable(category) { mutableStateOf(false) }
    val fromKey = DayKey.string(model.today.minusDays(6))
    val rows = LogText.history(model.logs, model.checkins, fromKey, category)
    AuraCard(padding = 22.dp, spacing = 12.dp) {
        CardHeader("clock.arrow.circlepath", title, AuraColors.gray)
        when {
            !model.loaded && model.failed -> HomeRetryLine("Your entries didn't load.", retry, Modifier.testTag("logging-history-retry"))
            !model.loaded -> SmallSpinner()
            rows.isEmpty() -> LogEmptyState(
                category?.symbol ?: "square.and.pencil",
                category?.color ?: SearchCategoryStyle.orange,
                if (category == null) "Nothing logged this week yet. Start with a glass of water or how you feel."
                else "Nothing logged here this week.",
            )
            else -> {
                val shown = if (showAll) rows else rows.take(HISTORY_FIRST)
                Column(Modifier.testTag(if (category == null) "logging-history" else "logging-history-${category.name}")) {
                    shown.forEachIndexed { index, row ->
                        if (index > 0) info.ibtaura.auraclarus.design.CardDivider(Modifier.padding(start = 50.dp))
                        HistoryLine(row, model)
                    }
                }
                if (rows.size > HISTORY_FIRST) {
                    Text(
                        if (showAll) "Show less" else "Show all ${rows.size}",
                        style = AuraType.calloutSemibold,
                        color = AuraColors.blue,
                        modifier = Modifier
                            .clickable(role = Role.Button) { showAll = !showAll }
                            .testTag("logging-history-more")
                            .padding(vertical = 4.dp),
                    )
                }
                if (model.failed) HomeRetryLine("Couldn't refresh.", retry)
            }
        }
    }
}

private const val HISTORY_FIRST = 12

@Composable
private fun HistoryLine(row: HistoryRow, model: LogbookModel) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SmallIconTile(row.symbol, row.color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = AuraType.calloutSemibold, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.detail.isNotBlank()) {
                Text(row.detail, style = AuraType.caption, color = AuraColors.gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val whenText = row.at?.let { LogTime.whenText(it, model.today) }
            ?: DayKey.localDate(row.date)?.let { LogTime.day(it, model.today) } ?: row.date
        Text(whenText, style = AuraType.caption, color = AuraColors.gray, maxLines = 1)
    }
}

/** A small map of answers: numbers, words, lists of numbers. */
internal fun json(vararg pairs: Pair<String, Any>): Map<String, JsonElement> = pairs.associate { (key, value) ->
    key to when (value) {
        is JsonElement -> value
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> kotlinx.serialization.json.JsonArray(value.map { JsonPrimitive(it as Number) })
        else -> JsonPrimitive(value.toString())
    }
}

/** The icon of a category page's "Calorie Tracker" link. */
@Composable
internal fun LinkRow(symbol: String, color: Color, title: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(auraIcon(symbol), null, tint = color, modifier = Modifier.size(22.dp))
        Text(title, style = AuraType.bodyMedium, color = AuraColors.blue, modifier = Modifier.weight(1f))
        Chevron()
    }
}
