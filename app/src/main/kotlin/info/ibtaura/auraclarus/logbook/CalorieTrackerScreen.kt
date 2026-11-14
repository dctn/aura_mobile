package info.ibtaura.auraclarus.logbook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.FoodEntry
import info.ibtaura.aura.core.Meal
import info.ibtaura.aura.core.NutritionDay
import info.ibtaura.aura.core.NutritionMaths
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.Chevron
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.SearchCategoryStyle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.home.HomeRetryLine
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import kotlinx.coroutines.launch
import java.time.LocalDate

// Search > Calorie Tracker (also Logging > Nutrition): a day's calories against the goal, its macros, the four meals,
// what the meal times say, the diet pattern and the week. Meals are food logs in the Health Data service.

private val proteinColor = SearchCategoryStyle.blue
private val carbsColor = AuraColors.orange
private val fatColor = SearchCategoryStyle.pink
private val kcalColor = AuraColors.green

@Composable
fun CalorieTrackerScreen(app: AppState, nav: AuraNav) {
    val model = remember { LogbookModel(app) }
    LaunchedEffect(app.dataVersion) { model.load(withCheckins = false, withWaterGoal = false) }
    LaunchedEffect(Unit) { model.loadSettings() }
    CalorieTrackerPage(model, onBack = { nav.pop() })
}

/** The page itself, shared by the Search entry and Logging's Nutrition row. */
@Composable
internal fun CalorieTrackerPage(model: LogbookModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dayKey by rememberSaveable { mutableStateOf(model.todayKey) }
    var addMeal by rememberSaveable { mutableStateOf<String?>(null) }
    var openEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var logAgain by remember { mutableStateOf<FoodEntry?>(null) }
    var editGoal by rememberSaveable { mutableStateOf(false) }
    var editPattern by rememberSaveable { mutableStateOf(false) }

    val date = DayKey.localDate(dayKey) ?: model.today
    val day = NutritionDay.of(dayKey, model.logs)
    val dayBefore = NutritionDay.of(DayKey.string(date.minusDays(1)), model.logs)
    val retry: () -> Unit = { scope.launch { model.load(withCheckins = false, withWaterGoal = false) } }

    AuraPage(title = "Calorie Tracker", onBack = onBack) {
        SummaryCard(model, day, date, retry, onGoal = { editGoal = true })
        Meal.entries.forEach { meal ->
            MealCard(meal, day, onAdd = { addMeal = meal.raw }, onOpen = { openEntryId = it.log.id })
        }
        if (model.loaded) InsightsCard(day, dayBefore)
        PatternRow(NutritionMaths.latestPattern(model.logs)) { editPattern = true }
        WeekCard(model, dayKey) { dayKey = it }
        Footnote("Calories are what you enter. Protein and carbs count 4 kcal a gram, fat 9. Logged meals can't be changed or removed yet.")
    }

    addMeal?.let { raw ->
        MealSheet(model, date, Meal.of(raw), onDismiss = { addMeal = null })
    }
    logAgain?.let { entry ->
        MealSheet(model, date, entry.meal, onDismiss = { logAgain = null }, prefill = entry)
    }
    val openEntry = openEntryId?.let { id -> day.meals.firstOrNull { it.log.id == id } }
    if (openEntry != null) {
        MealEntrySheet(openEntry, onDismiss = { openEntryId = null }, onLogAgain = {
            openEntryId = null
            logAgain = openEntry
        })
    }
    if (editGoal) CalorieGoalSheet(model) { editGoal = false }
    if (editPattern) DietPatternSheet(model, NutritionMaths.latestPattern(model.logs)) { editPattern = false }
}

// MARK: Summary

@Composable
private fun SummaryCard(model: LogbookModel, day: NutritionDay, date: LocalDate, retry: () -> Unit, onGoal: () -> Unit) {
    val goal = model.calorieGoal
    val eaten = NutritionMaths.whole(day.kcal)
    AuraCard(padding = 22.dp, spacing = 16.dp) {
        CardHeader("flame.fill", LogTime.day(date, model.today), kcalColor) {
            Row(
                Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onGoal).testTag("calorie-goal")
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Goal ${LogTime.grouped(goal)} kcal", style = AuraType.calloutMedium, color = AuraColors.blue)
                Icon(auraIcon("pencil"), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(14.dp))
            }
        }
        when {
            !model.loaded && model.failed -> HomeRetryLine("Your meals didn't load.", retry, Modifier.testTag("calorie-retry"))
            !model.loaded -> SmallSpinner()
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    KcalRing(eaten, goal)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val left = goal - eaten
                        SummaryNumber(if (left >= 0) "Left" else "Over", "${LogTime.grouped(kotlin.math.abs(left))} kcal",
                            if (left >= 0) Color.Black else AuraColors.burnout)
                        SummaryNumber("Meals", "${day.meals.size}", Color.Black)
                        day.largestMeal?.let { SummaryNumber("Largest", it.meal.label, Color.Black) }
                    }
                }
                MacroBar(day)
            }
        }
    }
}

@Composable
private fun SummaryNumber(label: String, value: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = AuraType.caption, color = AuraColors.gray)
        Text(value, style = AuraType.system(19, FontWeight.SemiBold), color = color, maxLines = 1)
    }
}

/** The day's calories as a ring against the goal; past the goal the overflow laps in orange. */
@Composable
private fun KcalRing(eaten: Int, goal: Int) {
    val fraction = eaten.toFloat() / goal.coerceAtLeast(1)
    Box(
        Modifier.size(132.dp).clearAndSetSemantics { contentDescription = "$eaten of $goal kcal" }.testTag("calorie-ring"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(AuraColors.track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(kcalColor, -90f, 360f * fraction.coerceAtMost(1f), false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
            if (fraction > 1f) {
                drawArc(AuraColors.burnout, -90f, 360f * (fraction - 1f).coerceAtMost(1f), false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(LogTime.grouped(eaten), style = AuraType.system(28, FontWeight.Bold), color = Color.Black,
                modifier = Modifier.testTag("calorie-total"))
            Text("of ${LogTime.grouped(goal)} kcal", style = AuraType.caption, color = AuraColors.gray)
        }
    }
}

/** Protein, carbs and fat as one bar of their calorie shares, with grams under it. */
@Composable
private fun MacroBar(day: NutritionDay) {
    val shares = day.macroShares
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(AuraColors.track)) {
            if (shares != null) {
                listOf(shares.first to proteinColor, shares.second to carbsColor, shares.third to fatColor).forEach { (share, color) ->
                    if (share > 0.0) Box(Modifier.weight(share.toFloat()).fillMaxHeight().background(color))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MacroLegend("Protein", day.proteinG, shares?.first, proteinColor)
            MacroLegend("Carbs", day.carbsG, shares?.second, carbsColor)
            MacroLegend("Fat", day.fatG, shares?.third, fatColor)
        }
    }
}

@Composable
private fun MacroLegend(label: String, grams: Double, share: Double?, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, style = AuraType.caption, color = AuraColors.gray)
        }
        val percent = share?.let { " · ${NutritionMaths.whole(it * 100)}%" } ?: ""
        Text("${gramsText(grams)} g$percent", style = AuraType.calloutSemibold, color = Color.Black)
    }
}

// MARK: Meals

private val mealSymbols = mapOf(
    Meal.breakfast to "sun.max.fill",
    Meal.lunch to "fork.knife",
    Meal.snack to "leaf.fill",
    Meal.dinner to "moon.fill",
)

@Composable
private fun MealCard(meal: Meal, day: NutritionDay, onAdd: () -> Unit, onOpen: (FoodEntry) -> Unit) {
    val entries = day.meals(meal)
    AuraCard(padding = 22.dp, spacing = 10.dp, modifier = Modifier.testTag("calorie-meal-${meal.raw}")) {
        CardHeader(mealSymbols[meal] ?: "fork.knife", meal.label, AuraColors.green) {
            if (entries.isNotEmpty()) {
                Text("${NutritionMaths.whole(day.kcal(meal))} kcal", style = AuraType.calloutSemibold, color = Color.Black)
            }
        }
        if (entries.isEmpty()) {
            Text("Nothing logged.", style = AuraType.callout, color = AuraColors.gray)
        } else {
            entries.forEachIndexed { index, entry ->
                if (index > 0) CardDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(entry) }
                        .testTag("calorie-entry-${entry.log.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = AuraType.bodyMedium, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val macros = "P ${gramsText(entry.proteinG)} · C ${gramsText(entry.carbsG)} · F ${gramsText(entry.fatG)} g"
                        val clock = entry.time?.let { String.format(java.util.Locale.ROOT, "%02d:%02d", it.hour, it.minute) }
                        Text(listOfNotNull(clock, macros).joinToString(" · "), style = AuraType.caption, color = AuraColors.gray, maxLines = 1)
                    }
                    Text("${NutritionMaths.whole(entry.kcal)}", style = AuraType.calloutSemibold, color = Color.Black)
                    Chevron()
                }
            }
        }
        Text(
            "+ Add ${meal.label.lowercase()}",
            style = AuraType.calloutSemibold,
            color = AuraColors.blue,
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onAdd)
                .testTag("calorie-add-${meal.raw}").padding(vertical = 6.dp),
        )
    }
}

// MARK: Insights

@Composable
private fun InsightsCard(day: NutritionDay, dayBefore: NutritionDay) {
    val clock = { t: java.time.LocalTime -> String.format(java.util.Locale.ROOT, "%02d:%02d", t.hour, t.minute) }
    val lines = listOfNotNull(
        day.eatingWindow?.let { window ->
            val span = if (day.firstMeal != null && day.lastMeal != null) " (${clock(day.firstMeal!!)}–${clock(day.lastMeal!!)})" else ""
            Triple("clock.fill", "Eating window", NutritionMaths.durationText(window) + span)
        },
        NutritionMaths.overnightFast(day, dayBefore)?.let { Triple("moon.fill", "Overnight fast", NutritionMaths.durationText(it)) },
        day.proteinPer100Kcal?.let {
            Triple("bolt.circle.fill", "Protein per 100 kcal", "${String.format(java.util.Locale.getDefault(), "%.1f", it)} g")
        },
        day.largestMeal?.let { Triple("flame.fill", "Largest meal", "${it.meal.label}, ${NutritionMaths.whole(it.kcal)} kcal") },
    )
    AuraCard(padding = 22.dp, spacing = 12.dp, modifier = Modifier.testTag("calorie-insights")) {
        CardHeader("chart.line.uptrend.xyaxis", "Meal timing", AuraColors.indigo)
        if (lines.isEmpty()) {
            Text("Log two meals with times to see your eating window and overnight fast.", style = AuraType.callout,
                color = AuraColors.gray)
        } else {
            lines.forEachIndexed { index, (symbol, label, value) ->
                if (index > 0) CardDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(auraIcon(symbol), null, tint = AuraColors.indigo, modifier = Modifier.size(18.dp))
                    Text(label, style = AuraType.body, color = Color.Black, modifier = Modifier.weight(1f))
                    Text(value, style = AuraType.calloutSemibold, color = Color.Black)
                }
            }
        }
    }
}

// MARK: Diet pattern

@Composable
private fun PatternRow(pattern: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(30.dp)).background(AuraColors.card)
            .clickable(role = Role.Button, onClick = onClick).testTag("calorie-pattern").padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(auraIcon("leaf.fill"), null, tint = AuraColors.green, modifier = Modifier.size(22.dp))
        Text("Diet pattern", style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.weight(1f))
        Text(pattern ?: "Not set", style = AuraType.callout, color = AuraColors.gray)
        Chevron()
    }
}

// MARK: Week

/** Seven bars of daily calories with the goal as a dashed line. A tap picks that day for the page. */
@Composable
private fun WeekCard(model: LogbookModel, selectedKey: String, onSelect: (String) -> Unit) {
    val days = NutritionMaths.dailyKcal(model.logs, model.today, 7)
    val goal = model.calorieGoal.toDouble()
    val top = maxOf(goal * 1.15, days.maxOfOrNull { it.second } ?: 0.0, 1.0)
    val chartHeight = 140.dp
    val logged = days.filter { it.second > 0 }
    AuraCard(padding = 22.dp, spacing = 14.dp, modifier = Modifier.testTag("calorie-week")) {
        CardHeader("chart.line.uptrend.xyaxis", "Last 7 days", kcalColor) {
            if (logged.isNotEmpty()) {
                val average = NutritionMaths.whole(logged.sumOf { it.second } / logged.size)
                Text("Avg ${LogTime.grouped(average)} kcal", style = AuraType.caption, color = AuraColors.gray)
            }
        }
        Box(Modifier.fillMaxWidth().height(chartHeight)) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height * (1f - (goal / top).toFloat())
                drawLine(AuraColors.gray.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                days.forEach { (key, kcal) ->
                    val isOn = key == selectedKey
                    val over = kcal > goal
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Tab) { onSelect(key) }
                            .semantics {
                                selected = isOn
                                contentDescription = "${DayKey.localDate(key)?.let { LogTime.day(it, model.today) } ?: key}, ${NutritionMaths.whole(kcal)} kcal"
                            }
                            .testTag("calorie-day-$key"),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val fraction = (kcal / top).toFloat().coerceIn(0f, 1f)
                        if (fraction > 0f) {
                            val color = if (over) AuraColors.burnout else kcalColor
                            Box(Modifier.fillMaxWidth(0.7f).fillMaxHeight(fraction.coerceAtLeast(0.03f))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(if (isOn) color else color.copy(alpha = 0.45f)))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { (key, _) ->
                val isOn = key == selectedKey
                val label = DayKey.localDate(key)?.let { LogTime.initial(it) } ?: ""
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(label, style = AuraType.system(13, if (isOn) FontWeight.Bold else FontWeight.Normal),
                        color = if (isOn) Color.Black else AuraColors.gray)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(14.dp).height(1.dp).background(AuraColors.gray))
            Text("Goal", style = AuraType.caption, color = AuraColors.gray)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(AuraColors.burnout))
            Text("Over goal", style = AuraType.caption, color = AuraColors.gray)
        }
        Text("Tap a day to see its meals.", style = AuraType.caption, color = AuraColors.gray)
    }
}
