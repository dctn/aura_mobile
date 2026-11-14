package info.ibtaura.auraclarus.logbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.FoodEntry
import info.ibtaura.aura.core.LogType
import info.ibtaura.aura.core.Meal
import info.ibtaura.aura.core.NutritionMaths
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraSheet
import info.ibtaura.auraclarus.design.AuraTextField
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.home.BetaTimeField
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

// The sheet that logs a meal, and the one that shows a logged meal. The Health Data service keeps a food log's data
// as given (meal, name, kcal, proteinG, carbsG, fatG, time "HH:mm"); there is no edit or delete, so a wrong entry is
// corrected by logging again.

/**
 * Logs a meal on [date]. The meal chips start at [meal] (or the one the clock suggests), the time at now for today
 * and at the meal's usual hour for another day.
 */
@Composable
internal fun MealSheet(model: LogbookModel, date: LocalDate, meal: Meal?, onDismiss: () -> Unit, prefill: FoodEntry? = null) {
    val scope = rememberCoroutineScope()
    val isToday = date == model.today
    val startTime = prefill?.time ?: if (isToday) LocalTime.now(DayKey.zone).withSecond(0).withNano(0) else usualTime(meal ?: Meal.lunch)
    var picked by remember { mutableStateOf(meal ?: prefill?.meal ?: Meal.at(startTime)) }
    var time by remember { mutableStateOf(startTime) }
    var name by remember { mutableStateOf(prefill?.name.orEmpty()) }
    var kcal by remember { mutableStateOf(prefill?.kcal?.let { NutritionMaths.whole(it).toString() }.orEmpty()) }
    var protein by remember { mutableStateOf(prefill?.proteinG?.let { gramsText(it) }.orEmpty()) }
    var carbs by remember { mutableStateOf(prefill?.carbsG?.let { gramsText(it) }.orEmpty()) }
    var fat by remember { mutableStateOf(prefill?.fatG?.let { gramsText(it) }.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val p = protein.toDoubleOrNull() ?: 0.0
    val c = carbs.toDoubleOrNull() ?: 0.0
    val f = fat.toDoubleOrNull() ?: 0.0
    val fromMacros = NutritionMaths.kcalFromMacros(p, c, f)
    val calories = kcal.toDoubleOrNull() ?: fromMacros.takeIf { it > 0.0 }
    val canSave = name.isNotBlank() && calories != null && calories in 1.0..10000.0 && !saving

    fun save() {
        val total = calories ?: return
        if (p > 1000 || c > 1000 || f > 1000) {
            error = "Check the grams: one of them looks too large."
            return
        }
        saving = true
        error = null
        val takenAt = date.atTime(time).atZone(DayKey.zone).toInstant()
        val data = json(
            "meal" to picked.raw,
            "name" to name.trim(),
            "kcal" to NutritionMaths.whole(total),
            "proteinG" to roundTenth(p),
            "carbsG" to roundTenth(c),
            "fatG" to roundTenth(f),
            "time" to String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute),
        )
        scope.launch {
            val refusal = model.addLog(LogType.food, data, takenAt, DayKey.string(date))
            saving = false
            if (refusal == null) onDismiss() else error = refusal
        }
    }

    AuraSheet(onDismiss = onDismiss, dismissible = !saving) {
        SheetColumn {
            Text(
                if (prefill != null) "Log again" else "Add food",
                style = AuraType.system(24, FontWeight.Bold),
                color = Color.Black,
                modifier = Modifier.semantics { heading() }.testTag("meal-sheet"),
            )
            Text(LogTime.day(date, model.today), style = AuraType.callout, color = AuraColors.gray)
            AuraCard(padding = 20.dp, spacing = 14.dp) {
                Text("Meal", style = AuraType.caption, color = AuraColors.gray)
                ChoiceChips(Meal.entries.map { it.label }, picked.label, { label ->
                    Meal.entries.firstOrNull { it.label == label }?.let { picked = it }
                }, "meal-sheet-meal")
                Text("What you ate", style = AuraType.caption, color = AuraColors.gray)
                AuraTextField(
                    value = name,
                    onValueChange = { if (it.length <= 120) name = it },
                    placeholder = "Chicken rice bowl",
                    modifier = Modifier.testTag("meal-sheet-name"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    background = AuraColors.track,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Time", style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.weight(1f))
                    BetaTimeField("Time", time, "meal-sheet-time") { time = it }
                }
            }
            AuraCard(padding = 20.dp, spacing = 14.dp) {
                LabeledNumber("Calories", kcal, { kcal = it }, "kcal", "meal-sheet-kcal",
                    placeholder = if (fromMacros > 0) "${NutritionMaths.whole(fromMacros)}" else "", decimals = false)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledNumber("Protein", protein, { protein = it }, "g", "meal-sheet-protein", Modifier.weight(1f))
                    LabeledNumber("Carbs", carbs, { carbs = it }, "g", "meal-sheet-carbs", Modifier.weight(1f))
                    LabeledNumber("Fat", fat, { fat = it }, "g", "meal-sheet-fat", Modifier.weight(1f))
                }
                val hint = when {
                    fromMacros <= 0.0 -> "Macros are optional. Leave calories empty to count them from the macros."
                    kcal.isEmpty() -> "${NutritionMaths.whole(fromMacros)} kcal from the macros will be saved."
                    else -> "The macros come to about ${NutritionMaths.whole(fromMacros)} kcal."
                }
                Text(hint, style = AuraType.caption, color = AuraColors.gray)
            }
            error?.let { Text(it, style = AuraType.callout, color = AuraColors.red, modifier = Modifier.testTag("meal-sheet-error")) }
            PillButton(if (saving) "Saving..." else "Save", ::save, Modifier.testTag("meal-sheet-save"), fills = true, enabled = canSave)
            Text("Logged meals can't be changed or removed yet.", style = AuraType.caption, color = AuraColors.gray)
        }
    }
}

/** A logged meal: what it was, its numbers, and Log again (there's no edit or delete). */
@Composable
internal fun MealEntrySheet(entry: FoodEntry, onDismiss: () -> Unit, onLogAgain: () -> Unit) {
    AuraSheet(onDismiss = onDismiss) {
        SheetColumn {
            Text(entry.name, style = AuraType.system(24, FontWeight.Bold), color = Color.Black,
                modifier = Modifier.semantics { heading() }.testTag("meal-entry-sheet"))
            val clock = entry.time?.let { String.format(Locale.ROOT, "%02d:%02d", it.hour, it.minute) }
            Text(listOfNotNull(entry.meal.label, clock).joinToString(" · "), style = AuraType.callout, color = AuraColors.gray)
            AuraCard(padding = 20.dp, spacing = 12.dp) {
                EntryLine("Calories", "${NutritionMaths.whole(entry.kcal)} kcal")
                EntryLine("Protein", "${gramsText(entry.proteinG)} g")
                EntryLine("Carbs", "${gramsText(entry.carbsG)} g")
                EntryLine("Fat", "${gramsText(entry.fatG)} g")
            }
            Text(
                "Aura can't change or remove a logged meal yet. If this one is wrong, log it again with the right numbers.",
                style = AuraType.callout,
                color = AuraColors.gray,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Close", onDismiss, Modifier.weight(1f).testTag("meal-entry-close"), style = PillStyle.Secondary, fills = true)
                PillButton("Log again", onLogAgain, Modifier.weight(1f).testTag("meal-entry-again"), fills = true)
            }
            Spacer(Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun EntryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = AuraType.body, color = Color.Black, modifier = Modifier.weight(1f))
        Text(value, style = AuraType.bodyMedium, color = Color.Black)
    }
}

/** A sheet of diet patterns; the one picked is logged as a food log `{pattern}`. */
@Composable
internal fun DietPatternSheet(model: LogbookModel, current: String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(current) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AuraSheet(onDismiss = onDismiss, dismissible = !saving) {
        SheetColumn {
            Text("Diet pattern", style = AuraType.system(24, FontWeight.Bold), color = Color.Black,
                modifier = Modifier.semantics { heading() }.testTag("diet-pattern-sheet"))
            Text("How you usually eat at the moment. Change it whenever it changes.", style = AuraType.callout, color = AuraColors.gray)
            AuraCard(padding = 20.dp) {
                ChoiceChips(info.ibtaura.aura.core.dietPatterns, picked, { picked = it; error = null }, "diet-pattern")
            }
            error?.let { Text(it, style = AuraType.callout, color = AuraColors.red) }
            Column {
                PillButton(
                    if (saving) "Saving..." else "Save",
                    onClick = {
                        val pattern = picked ?: return@PillButton
                        saving = true
                        scope.launch {
                            val refusal = model.addLog(LogType.food, json("pattern" to pattern))
                            saving = false
                            if (refusal == null) onDismiss() else error = refusal
                        }
                    },
                    modifier = Modifier.testTag("diet-pattern-save"),
                    fills = true,
                    enabled = picked != null && picked != current && !saving,
                )
            }
        }
    }
}

/** A sheet to set the daily calorie goal, saved as the setting `nutrition.calorieGoal`. */
@Composable
internal fun CalorieGoalSheet(model: LogbookModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var goal by remember { mutableStateOf(model.calorieGoal) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AuraSheet(onDismiss = onDismiss, dismissible = !saving) {
        SheetColumn {
            Text("Daily calorie goal", style = AuraType.system(24, FontWeight.Bold), color = Color.Black,
                modifier = Modifier.semantics { heading() }.testTag("calorie-goal-sheet"))
            Text("The ring and the week's goal line use it. It's saved to your Aura account.", style = AuraType.callout,
                color = AuraColors.gray)
            AuraCard(padding = 20.dp) {
                StepperRow("Goal", goal, "kcal", LogbookModel.calorieGoalRange, 50, "calorie-goal") {
                    goal = it
                    error = null
                }
            }
            error?.let { Text(it, style = AuraType.callout, color = AuraColors.red) }
            PillButton(
                if (saving) "Saving..." else "Save",
                onClick = {
                    saving = true
                    scope.launch {
                        val refusal = model.saveCalorieGoal(goal)
                        saving = false
                        if (refusal == null) onDismiss() else error = refusal
                    }
                },
                modifier = Modifier.testTag("calorie-goal-save"),
                fills = true,
                enabled = goal != model.calorieGoal && !saving,
            )
        }
    }
}

private fun usualTime(meal: Meal): LocalTime = when (meal) {
    Meal.breakfast -> LocalTime.of(8, 0)
    Meal.lunch -> LocalTime.of(13, 0)
    Meal.snack -> LocalTime.of(16, 0)
    Meal.dinner -> LocalTime.of(19, 30)
}

private fun roundTenth(value: Double): Double = Math.round(value * 10.0) / 10.0

internal fun gramsText(value: Double): String {
    val tenth = roundTenth(value)
    return if (tenth == Math.floor(tenth)) "${tenth.toLong()}" else String.format(Locale.ROOT, "%.1f", tenth)
}
