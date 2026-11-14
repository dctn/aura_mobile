package info.ibtaura.auraclarus.logbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.CheckinKind
import info.ibtaura.aura.core.InsomniaScale
import info.ibtaura.aura.core.LogType
import info.ibtaura.aura.core.LogbookMaths
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Footnote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

// One page per Logging category: its input cards, then its entries of the week. Every card saves on its own.

@Composable
internal fun LogCategoryPage(model: LogbookModel, category: LogCategory, onBack: () -> Unit, onCalories: () -> Unit) {
    val scope = rememberCoroutineScope()
    AuraPage(title = category.title, onBack = onBack) {
        Text(category.blurb, style = AuraType.callout, color = AuraColors.gray)
        when (category) {
            LogCategory.mood -> MoodCards(model)
            LogCategory.stress -> StressCards(model)
            LogCategory.people -> PeopleCards(model)
            LogCategory.body -> BodyCards(model)
            LogCategory.strength -> StrengthCards(model)
            LogCategory.sleep -> SleepCards(model)
            LogCategory.nutrition -> AuraCard(padding = 22.dp) {
                LinkRow("fork.knife", category.color, "Open the Calorie Tracker", "logging-open-calories", onCalories)
            }
        }
        HistoryCard(model, title = "Logged this week", category = category, retry = { scope.launch { model.load() } })
        Footnote("Entries can't be changed or removed yet. Log again to correct one.")
    }
}

/** The state a card's Save needs: saving, an error, and a "Saved" line until the next edit. */
private class SaveState {
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var saved by mutableStateOf<String?>(null)

    fun edited() {
        error = null
        saved = null
    }

    fun run(scope: CoroutineScope, done: String = "Saved", onSaved: () -> Unit = {}, write: suspend () -> String?) {
        if (saving) return
        saving = true
        error = null
        saved = null
        scope.launch {
            val refusal = write()
            saving = false
            if (refusal == null) {
                saved = done
                onSaved()
            } else {
                error = refusal
            }
        }
    }
}

@Composable
private fun rememberSave() = remember { SaveState() }

private fun lastText(model: LogbookModel, kind: CheckinKind): String? =
    LogbookMaths.latest(model.checkins, kind)?.takenAt?.let { LogTime.whenText(it, model.today) }

/** The picked answers only, so an unanswered question isn't sent. */
private fun picked(vararg pairs: Pair<String, Int?>): Map<String, JsonElement> =
    json(*pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toTypedArray())

// MARK: Mood & energy

@Composable
private fun MoodCards(model: LogbookModel) {
    val scope = rememberCoroutineScope()
    val todayMood = LogbookMaths.latest(model.checkins, CheckinKind.mood, model.todayKey)?.mood
    val moodSave = rememberSave()
    LogInputCard(LogCategory.mood.symbol, "Mood", LogCategory.mood.color, "How are you feeling right now?",
        lastText(model, CheckinKind.mood)) {
        FaceRow(todayMood, ScaleWords.moodFaces, enabled = !moodSave.saving, tag = "logging-mood-page") { value ->
            moodSave.run(scope) { model.addCheckin(CheckinKind.mood, json("mood" to value)) }
        }
        moodSave.error?.let { Text(it, style = AuraType.callout, color = AuraColors.red) }
    }

    var energy by remember { mutableStateOf<Int?>(null) }
    var onEdge by remember { mutableStateOf<Int?>(null) }
    var worry by remember { mutableStateOf<Int?>(null) }
    val save = rememberSave()
    LogInputCard("bolt.circle.fill", "Energy and worries", LogCategory.mood.color, null, null) {
        Question("Your energy")
        ScaleChips(1..5, energy, { energy = it; save.edited() }, "logging-energy", words = ScaleWords.lowHigh)
        Question("Feeling nervous or on edge")
        ScaleChips(1..5, onEdge, { onEdge = it; save.edited() }, "logging-on-edge", words = ScaleWords.agree)
        Question("Worrying about work")
        ScaleChips(1..5, worry, { worry = it; save.edited() }, "logging-worry", words = ScaleWords.agree)
        SaveLine(save.saving, listOf(energy, onEdge, worry).any { it != null }, save.error, save.saved, "logging-mood-save") {
            save.run(scope, onSaved = { energy = null; onEdge = null; worry = null }) {
                model.addLog(LogType.mood, picked("energy" to energy, "onEdge" to onEdge, "worry" to worry))
            }
        }
    }
}

@Composable
private fun Question(text: String) {
    Text(text, style = AuraType.calloutMedium, color = Color.Black)
}

// MARK: Stress

@Composable
private fun StressCards(model: LogbookModel) {
    val scope = rememberCoroutineScope()

    var effort by remember { mutableStateOf<Int?>(null) }
    var stress by remember { mutableStateOf<Int?>(null) }
    val blockSave = rememberSave()
    LogInputCard("brain", "After a work block", LogCategory.stress.color, null, lastText(model, CheckinKind.block)) {
        Question("How much effort did it take?")
        ScaleChips(1..5, effort, { effort = it; blockSave.edited() }, "logging-effort", words = "Very little" to "A great deal")
        Question("How stressed do you feel?")
        ScaleChips(0..10, stress, { stress = it; blockSave.edited() }, "logging-block-stress", words = ScaleWords.stress)
        SaveLine(blockSave.saving, effort != null || stress != null, blockSave.error, blockSave.saved, "logging-block-save") {
            blockSave.run(scope, onSaved = { effort = null; stress = null }) {
                model.addCheckin(CheckinKind.block, picked("effort" to effort, "stress" to stress))
            }
        }
    }

    var exhaustion by remember { mutableStateOf<Int?>(null) }
    val weeklySave = rememberSave()
    LogInputCard("hourglass", "This week", LogCategory.stress.color, "How exhausted have you felt this week?",
        lastText(model, CheckinKind.weekly)) {
        ScaleChips(0..10, exhaustion, { exhaustion = it; weeklySave.edited() }, "logging-exhaustion",
            words = "Not at all" to "Completely")
        SaveLine(weeklySave.saving, exhaustion != null, weeklySave.error, weeklySave.saved, "logging-weekly-save") {
            weeklySave.run(scope, onSaved = { exhaustion = null }) {
                model.addCheckin(CheckinKind.weekly, picked("exhaustion" to exhaustion))
            }
        }
    }

    var months by remember { mutableIntStateOf(0) }
    var challenge by remember { mutableStateOf<String?>(null) }
    val yearSave = rememberSave()
    LogInputCard("calendar", "This year", LogCategory.stress.color, null, null) {
        StepperRow("Stressful months in the last 12", months, "", 0..12, 1, "logging-stressful-months") {
            months = it
            yearSave.edited()
        }
        Question("Your work's demands feel like")
        ChoiceChips(challengeChoices, challenge, { challenge = it; yearSave.edited() }, "logging-challenge")
        SaveLine(yearSave.saving, true, yearSave.error, yearSave.saved, "logging-year-save") {
            val code = challenge?.let { challengeChoices.indexOf(it) + 1 }
            yearSave.run(scope, onSaved = { challenge = null }) {
                model.addLog(LogType.custom, picked("stressfulMonths" to months, "challengeOrHindrance" to code))
            }
        }
    }
}

// MARK: People & purpose

@Composable
private fun PeopleCards(model: LogbookModel) {
    val scope = rememberCoroutineScope()
    val todayConnection = LogbookMaths.latest(model.checkins, CheckinKind.connection, model.todayKey)?.connection
    val connectionSave = rememberSave()
    LogInputCard(LogCategory.people.symbol, "Connection", LogCategory.people.color,
        "How connected to other people do you feel?", lastText(model, CheckinKind.connection)) {
        FaceRow(todayConnection, ScaleWords.connectionFaces, enabled = !connectionSave.saving, tag = "logging-connection-page") { value ->
            connectionSave.run(scope) { model.addCheckin(CheckinKind.connection, json("connection" to value)) }
        }
        connectionSave.error?.let { Text(it, style = AuraType.callout, color = AuraColors.red) }
    }

    var talks by remember { mutableIntStateOf(1) }
    val talksSave = rememberSave()
    LogInputCard("person.fill", "Conversations", LogCategory.people.color, null, null) {
        StepperRow("Meaningful conversations today", talks, "", 0..20, 1, "logging-conversations") {
            talks = it
            talksSave.edited()
        }
        SaveLine(talksSave.saving, true, talksSave.error, talksSave.saved, "logging-conversations-save") {
            talksSave.run(scope) { model.addLog(LogType.custom, json("meaningfulConversations" to talks)) }
        }
    }

    var support by remember { mutableStateOf<Int?>(null) }
    var challenge by remember { mutableStateOf<Int?>(null) }
    val workSave = rememberSave()
    LogInputCard("target", "At work", LogCategory.people.color, null, null) {
        Question("I feel supported at work")
        ScaleChips(1..5, support, { support = it; workSave.edited() }, "logging-support", words = ScaleWords.agree)
        Question("My work stretches me in a good way")
        ScaleChips(1..5, challenge, { challenge = it; workSave.edited() }, "logging-work-challenge", words = ScaleWords.agree)
        SaveLine(workSave.saving, support != null || challenge != null, workSave.error, workSave.saved, "logging-work-save") {
            workSave.run(scope, onSaved = { support = null; challenge = null }) {
                model.addLog(LogType.custom, picked("supportAtWork" to support, "workChallenge" to challenge))
            }
        }
    }
}

// MARK: Body measurements

private data class Measure(val key: String, val label: String, val unit: String, val range: ClosedFloatingPointRange<Double>)

private val composition = listOf(
    Measure("weightKg", "Weight", "kg", 20.0..400.0),
    Measure("bodyFatPct", "Body fat", "%", 2.0..75.0),
    Measure("muscleMassKg", "Muscle mass", "kg", 5.0..200.0),
    Measure("leanBodyMassKg", "Lean mass", "kg", 10.0..250.0),
    Measure("bodyWaterPct", "Body water", "%", 20.0..80.0),
    Measure("boneMassKg", "Bone mass", "kg", 0.5..10.0),
    Measure("visceralFatScore", "Visceral fat", "", 1.0..60.0),
)

private val pressure = listOf(
    Measure("bloodPressureSystolic", "Systolic", "mmHg", 60.0..260.0),
    Measure("bloodPressureDiastolic", "Diastolic", "mmHg", 30.0..160.0),
)

/** Numbers from the filled fields, or the label of the first one out of range. */
private fun measured(fields: List<Measure>, values: Map<String, String>): Pair<Map<String, JsonElement>, String?> {
    val numbers = LinkedHashMap<String, Any>()
    for (field in fields) {
        val text = values[field.key].orEmpty()
        if (text.isEmpty()) continue
        val number = text.toDoubleOrNull() ?: return emptyMap<String, JsonElement>() to field.label
        if (number !in field.range) return emptyMap<String, JsonElement>() to field.label
        numbers[field.key] = number
    }
    return json(*numbers.map { it.key to it.value }.toTypedArray()) to null
}

@Composable
private fun MeasureForm(title: String, symbol: String, fields: List<Measure>, tag: String, model: LogbookModel, decimals: Boolean = true) {
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateOf(mapOf<String, String>()) }
    val save = rememberSave()
    LogInputCard(symbol, title, LogCategory.body.color, null, null) {
        fields.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { field ->
                    LabeledNumber(
                        label = field.label,
                        value = values.value[field.key].orEmpty(),
                        onValueChange = { text ->
                            values.value = values.value + (field.key to text)
                            save.edited()
                        },
                        unit = field.unit,
                        tag = "$tag-${field.key}",
                        modifier = Modifier.weight(1f),
                        decimals = decimals,
                    )
                }
                if (row.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
        val filled = values.value.values.any { it.isNotEmpty() }
        SaveLine(save.saving, filled, save.error, save.saved, "$tag-save") {
            val (data, wrong) = measured(fields, values.value)
            if (wrong != null) {
                save.error = "Check $wrong: that number looks out of range."
            } else if (data.isNotEmpty()) {
                save.run(scope, onSaved = { values.value = emptyMap() }) { model.addLog(LogType.measurement, data) }
            }
        }
    }
}

@Composable
private fun BodyCards(model: LogbookModel) {
    MeasureForm("Body composition", LogCategory.body.symbol, composition, "logging-body", model)
    MeasureForm("Blood pressure", "heart.fill", pressure, "logging-bp", model, decimals = false)
    Footnote("Fill in what your scale or cuff shows; empty fields aren't sent.")
}

// MARK: Movement & strength

private val muscleGroups = listOf("Full body", "Upper body", "Legs", "Core")

@Composable
private fun StrengthCards(model: LogbookModel) {
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableIntStateOf(30) }
    var group by remember { mutableStateOf<String?>(null) }
    val strengthSave = rememberSave()
    LogInputCard(LogCategory.strength.symbol, "Strength session", LogCategory.strength.color, null, null) {
        StepperRow("How long", minutes, "min", 5..240, 5, "logging-strength-minutes") {
            minutes = it
            strengthSave.edited()
        }
        Question("What you trained (optional)")
        ChoiceChips(muscleGroups, group, { group = if (group == it) null else it; strengthSave.edited() }, "logging-strength-group")
        SaveLine(strengthSave.saving, true, strengthSave.error, strengthSave.saved, "logging-strength-save") {
            val data = if (group != null) json("minutes" to minutes, "group" to group!!) else json("minutes" to minutes)
            strengthSave.run(scope, onSaved = { group = null }) { model.addLog(LogType.strength, data) }
        }
    }

    var tv by remember { mutableIntStateOf(60) }
    val tvSave = rememberSave()
    LogInputCard("moon.fill", "Screen time at rest", LogCategory.strength.color, "Time in front of the TV today", null) {
        StepperRow("TV time", tv, "min", 0..720, 15, "logging-tv-minutes") {
            tv = it
            tvSave.edited()
        }
        SaveLine(tvSave.saving, true, tvSave.error, tvSave.saved, "logging-tv-save") {
            tvSave.run(scope) { model.addLog(LogType.custom, json("tvTimeMin" to tv)) }
        }
    }
}

// MARK: Sleep check

/** The words under each ISI item's 0 and 4. */
private val insomniaWords: List<Pair<String, String>> = listOf(
    "None" to "Very severe",
    "None" to "Very severe",
    "None" to "Very severe",
    "Very satisfied" to "Very dissatisfied",
    "Not at all" to "Very much",
    "Not at all" to "Very much",
    "Not at all" to "Very much",
)

@Composable
private fun SleepCards(model: LogbookModel) {
    val scope = rememberCoroutineScope()
    val last = LogbookMaths.latest(model.checkins, CheckinKind.insomnia)
    val score = last?.insomniaScore
    if (last != null && score != null) {
        AuraCard(padding = 22.dp, spacing = 8.dp) {
            CardHeader(LogCategory.sleep.symbol, "Last sleep check", LogCategory.sleep.color) {
                last.takenAt?.let { Text(LogTime.whenText(it, model.today), style = AuraType.caption, color = AuraColors.gray) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$score", style = AuraType.system(34, androidx.compose.ui.text.font.FontWeight.Bold), color = Color.Black,
                    modifier = Modifier.testTag("logging-isi-last"))
                Text("of 28", style = AuraType.callout, color = AuraColors.gray, modifier = Modifier.align(androidx.compose.ui.Alignment.Bottom))
            }
            Text(InsomniaScale.band(score), style = AuraType.calloutSemibold, color = LogCategory.sleep.color)
            ProgressBar(score / 28f, LogCategory.sleep.color)
        }
    }

    val answers = remember { mutableStateListOf<Int?>(*arrayOfNulls(InsomniaScale.items.size)) }
    val save = rememberSave()
    LogInputCard(LogCategory.sleep.symbol, "Sleep check", LogCategory.sleep.color,
        "Over the last two weeks:", null) {
        InsomniaScale.items.forEachIndexed { index, item ->
            Question(item)
            ScaleChips(0..4, answers[index], { answers[index] = it; save.edited() }, "logging-isi-$index",
                words = insomniaWords[index], color = LogCategory.sleep.color)
        }
        val complete = answers.all { it != null }
        if (complete) {
            val total = answers.sumOf { it ?: 0 }
            Text("Total $total of 28 · ${InsomniaScale.band(total)}", style = AuraType.calloutSemibold, color = Color.Black,
                modifier = Modifier.testTag("logging-isi-total"))
        } else {
            Text("Answer all seven to save.", style = AuraType.callout, color = AuraColors.gray)
        }
        SaveLine(save.saving, complete, save.error, save.saved, "logging-isi-save") {
            val items = answers.map { it ?: 0 }
            save.run(scope, onSaved = { for (i in answers.indices) answers[i] = null }) {
                model.addCheckin(CheckinKind.insomnia, json("insomnia" to items))
            }
        }
    }
    Footnote("The Insomnia Severity Index (Bastien, Vallières and Morin, 2001). It is a check, not a diagnosis.")
}
