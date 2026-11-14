package info.ibtaura.auraclarus.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.GoalProgress
import info.ibtaura.aura.core.GoalsProfile
import info.ibtaura.aura.core.Habit
import info.ibtaura.aura.core.HabitDraft
import info.ibtaura.aura.core.HabitLog
import info.ibtaura.aura.core.HabitTargetType
import info.ibtaura.aura.core.HealthGoals
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.IconTile
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.SearchGroupCard
import info.ibtaura.auraclarus.design.SearchRowDivider
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.home.BetaTimeField
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.OwnSaves
import info.ibtaura.auraclarus.shell.rememberSaveMessenger
import info.ibtaura.auraclarus.shell.saveInBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.Locale

private val Inset = Modifier.padding(horizontal = 20.dp)

private val stepsSpec = GoalSpec(
    HealthGoals.STEPS, "Steps", "figure.walk", AuraColors.green, 1000, 30000, 500,
    "Health Data counts today's steps against this.", { "${grouped(it)} steps" },
)
private val kcalSpec = GoalSpec(
    HealthGoals.ACTIVE_KCAL, "Active calories", "flame.fill", AuraColors.red, 100, 2000, 50,
    "Calories burned by moving, not at rest.", { "${grouped(it)} kcal" },
)
private val standSpec = GoalSpec(
    HealthGoals.STAND_HOURS, "Stand hours", "figure.arms.open", AuraColors.blue, 1, 24, 1,
    "Hours with at least a minute of standing.", { if (it == 1) "1 hr" else "$it hr" },
)
private val waterSpec = GoalSpec(
    HealthGoals.WATER_ML, "Water", "drop.fill", AuraColors.startBlue, 500, 5000, 250,
    "What you log in Water adds up toward this.", { "${grouped(it)} ml" },
)
private val focusSpec = GoalSpec(
    GoalsProfile.FOCUS_GOAL_KEY, "Focus minutes", "timer", AuraColors.indigo,
    GoalsProfile.minFocusGoalMinutes, GoalsProfile.maxFocusGoalMinutes, 15,
    "Minutes of focus sessions a day.", { hoursAndMinutes(it) },
)
private val sleepSpec = GoalSpec(
    HealthGoals.SLEEP_NEED, "Sleep need", "bed.double.fill", AuraColors.indigo,
    HealthGoals.minSleepNeedMinutes, HealthGoals.maxSleepNeedMinutes, 15,
    "How much sleep you need a night. Recovery and your bedtime use it.", { hoursAndMinutes(it) },
)

/**
 * Search > Goals: daily goals with today's progress, the sleep schedule, and habits with today's check-offs.
 *
 * Instant saves: a goal, a habit, a check-off, an undo or a delete shows at once (a new habit as a pending row) and the
 * write runs in the app's scope, so leaving the page doesn't cancel it. The server's answer is merged into the page
 * instead of loading it all again; a failure puts the page back and says so under its section. The profile, today's
 * progress and the habits load side by side.
 */
@Composable
fun GoalsScreen(app: AppState, nav: AuraNav) {
    var profile by remember { mutableStateOf<GoalsProfile?>(null) }
    var profileFailed by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<GoalProgress?>(null) }
    var progressFailed by remember { mutableStateOf(false) }
    var habits by remember { mutableStateOf<List<Habit>?>(null) }
    var habitsFailed by remember { mutableStateOf(false) }
    /** Every check-off in the last 400 days, for today's state and the goal counts. */
    var logs by remember { mutableStateOf<List<HabitLog>>(emptyList()) }
    var retry by remember { mutableIntStateOf(0) }
    var goalsMessage by remember { mutableStateOf<String?>(null) }
    var habitsMessage by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<GoalSpec?>(null) }
    var habitSheet by remember { mutableStateOf<HabitSheet?>(null) }
    /** Habits with a write on their way (a check-off, an undo, or a new one not yet on the server). */
    var busyHabits by remember { mutableStateOf(emptySet<String>()) }
    val today = DayKey.string(Instant.now())
    val own = remember { OwnSaves() }
    val goalsMessenger = rememberSaveMessenger { goalsMessage = it }
    val habitsMessenger = rememberSaveMessenger { habitsMessage = it }

    suspend fun loadProfile() {
        try {
            profile = app.api.goalsProfile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            profileFailed = true
        }
    }

    suspend fun loadProgress() {
        try {
            progress = app.api.goalProgressToday()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            progressFailed = true
        }
    }

    /** The habits and their check-offs, side by side (the check-offs are dropped when there are no habits). */
    suspend fun loadHabits() {
        try {
            val from = DayKey.string(java.time.LocalDate.parse(today).minusDays(399))
            coroutineScope {
                val list = async { app.api.habits(includeArchived = false) }
                val days = async { app.api.habitLogs(from, today) }
                val loaded = list.await()
                logs = if (loaded.isEmpty()) emptyList() else days.await()
                habits = loaded
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            habitsFailed = true
        }
    }

    // Each group loads on its own. A data bump from this page's own save is skipped: the page already shows it.
    LaunchedEffect(app.dataVersion, retry) {
        if (!own.shouldLoad(app.dataVersion, retry)) return@LaunchedEffect
        profileFailed = false
        progressFailed = false
        habitsFailed = false
        coroutineScope {
            launch { loadProfile() }
            launch { loadProgress() }
            launch { loadHabits() }
        }
    }

    val goals = profile?.healthGoals
    val goalsRefused = profile != null && goals == null

    fun goalValue(spec: GoalSpec): Int = when (spec.key) {
        HealthGoals.STEPS -> goals?.stepsPerDay ?: HealthGoals.defaultSteps
        HealthGoals.ACTIVE_KCAL -> goals?.activeKcalPerDay ?: HealthGoals.defaultActiveKcal
        HealthGoals.STAND_HOURS -> goals?.standHoursPerDay ?: HealthGoals.defaultStandHours
        HealthGoals.WATER_ML -> goals?.waterGoalMl ?: HealthGoals.defaultWaterMl
        HealthGoals.SLEEP_NEED -> goals?.sleepNeedMinutes ?: HealthGoals.defaultSleepNeedMinutes
        GoalsProfile.FOCUS_GOAL_KEY -> profile?.focusGoalMinutes ?: GoalsProfile.defaultFocusGoalMinutes
        else -> 0
    }

    fun withGoal(p: GoalsProfile, key: String, value: Any?): GoalsProfile {
        val g = p.healthGoals ?: HealthGoals()
        val next = when (key) {
            HealthGoals.STEPS -> g.copy(stepsPerDay = value as Int?)
            HealthGoals.ACTIVE_KCAL -> g.copy(activeKcalPerDay = value as Int?)
            HealthGoals.STAND_HOURS -> g.copy(standHoursPerDay = value as Int?)
            HealthGoals.WATER_ML -> g.copy(waterGoalMl = value as Int?)
            HealthGoals.SLEEP_NEED -> g.copy(sleepNeedMinutes = value as Int?)
            HealthGoals.BEDTIME -> g.copy(bedtime = value as String?)
            HealthGoals.WAKE_TIME -> g.copy(wakeTime = value as String?)
            else -> g
        }
        return p.copy(healthGoals = next)
    }

    /** Optimistic: the page shows the new goal at once and goes back if the write fails. */
    fun saveGoal(key: String, value: Any) {
        val before = profile ?: return
        goalsMessage = null
        if (key == GoalsProfile.FOCUS_GOAL_KEY) {
            val minutes = value as Int
            profile = before.copy(settings = before.settings + (key to JsonPrimitive(minutes)))
            app.saveInBackground(
                write = { app.api.putMeSettings(mapOf(key to JsonPrimitive(minutes))) },
                onFailed = {
                    profile = before
                    goalsMessenger.tell("Couldn't save your focus goal. Nothing was changed.")
                },
                onSaved = { own.bump(app) },
            )
            return
        }
        profile = withGoal(before, key, value)
        val body = if (value is Int) JsonPrimitive(value) else JsonPrimitive(value as String)
        app.saveInBackground(
            write = { app.api.putHealthGoals(mapOf(key to body), before.version) },
            onFailed = { e ->
                profile = before
                goalsMessenger.tell(
                    if (e is AuraError.Server && e.code == "health_goals_refused") e.message
                    else "Couldn't save that goal. Nothing was changed.",
                )
            },
            onSaved = { saved ->
                profile = saved
                own.bump(app)
            },
        )
    }

    fun todayOf(habit: Habit): HabitToday {
        val mine = logs.filter { it.habitId == habit.id && it.date == today }
        val count = mine.sumOf { it.count }
        val done = when (habit.targetType) {
            HabitTargetType.count -> count >= habit.targetCount
            else -> mine.isNotEmpty()
        }
        return HabitToday(count, done)
    }

    fun check(habit: Habit) {
        if (habit.id in busyHabits) return
        val state = todayOf(habit)
        habitsMessage = null
        busyHabits = busyHabits + habit.id
        if (state.done) {
            // Undo today: remove the day's logs (the newest one for a count habit).
            val removing = HabitEdits.undoLogs(habit, logs, today)
            logs = logs - removing.toSet()
            app.saveInBackground(
                write = {
                    removing.forEach { app.api.deleteHabitLog(habit.id, it.id) }
                    // Only the habits again, for the streak the server works out; the check-offs are known.
                    runCatching { app.api.habits(includeArchived = false) }.getOrNull()
                },
                onFailed = {
                    busyHabits = busyHabits - habit.id
                    logs = logs + removing.filterNot { log -> logs.any { it.id == log.id } }
                    habitsMessenger.tell("Couldn't undo ${habit.name}. It's still checked off.")
                },
                onSaved = { fresh ->
                    busyHabits = busyHabits - habit.id
                    val streak = fresh?.firstOrNull { it.id == habit.id }?.streak
                    if (streak != null) habits = habits?.map { if (it.id == habit.id) it.copy(streak = streak) else it }
                    own.bump(app)
                },
            )
            return
        }
        val pending = HabitEdits.pendingLog(habit, today)
        logs = logs + pending
        app.saveInBackground(
            write = { app.api.logHabit(habit.id, today, 1, pending.minutes) },
            onFailed = { e ->
                busyHabits = busyHabits - habit.id
                logs = logs.filterNot { it.id == pending.id }
                habitsMessenger.tell(
                    when ((e as? AuraError)?.serverCode) {
                        "habit_paused" -> "${habit.name} is paused. Turn it back on to check it off."
                        else -> "Couldn't check off ${habit.name}. Try again."
                    },
                )
            },
            onSaved = { result ->
                busyHabits = busyHabits - habit.id
                logs = HabitEdits.confirmLog(logs, pending.id, result)
                habits = habits?.map { if (it.id == habit.id) it.copy(streak = result.streak) else it }
                own.bump(app)
            },
        )
    }

    // The sheet closes at once; the habit shows as it will be saved, and goes back if the write fails.
    fun saveHabit(sheet: HabitSheet, draft: HabitDraft) {
        val existing = sheet.habit
        habitSheet = null
        habitsMessage = null
        val list = habits.orEmpty()
        val id = existing?.id ?: HabitEdits.pendingId()
        val shown = existing?.let { HabitEdits.applied(draft, it) } ?: HabitEdits.preview(draft, id, list)
        habits = HabitEdits.replacing(list, id, shown)
        if (existing == null) busyHabits = busyHabits + id
        app.saveInBackground(
            write = { if (existing == null) app.api.createHabit(draft) else app.api.updateHabit(existing, draft) },
            onFailed = { e ->
                busyHabits = busyHabits - id
                habits = habits?.let { HabitEdits.undoing(it, id, existing) }
                val fallback = if (existing == null) "Couldn't add ${shown.name}. Try again." else "Couldn't save ${existing.name}. Try again."
                habitsMessenger.tell((e as? AuraError.Server)?.message?.takeIf { it.isNotBlank() } ?: fallback)
            },
            onSaved = { saved ->
                busyHabits = busyHabits - id
                habits = habits?.let { HabitEdits.replacing(it, id, saved) }
                own.bump(app)
            },
        )
    }

    fun deleteHabit(habit: Habit) {
        val beforeLogs = logs.filter { it.habitId == habit.id }
        habits = habits?.filterNot { it.id == habit.id }
        logs = logs.filterNot { it.habitId == habit.id }
        habitSheet = null
        habitsMessage = null
        app.saveInBackground(
            write = { app.api.deleteHabit(habit.id) },
            onFailed = {
                habits = habits?.let { HabitEdits.replacing(it, habit.id, habit) }?.sortedBy { it.sortOrder }
                logs = logs + beforeLogs
                habitsMessenger.tell("Couldn't delete ${habit.name}. It's back in the list.")
            },
            onSaved = { own.bump(app) },
        )
    }

    AuraPage(Modifier.testTag("goals-page"), title = "Goals", onBack = { nav.pop() }) {

        // Daily goals
        SectionTitle("Daily goals", Inset)
        when {
            profile == null && !profileFailed -> Spinner()
            profile == null -> UnitsRetryLine("Couldn't load your goals.", retry = { retry += 1 })
            else -> {
                val p = progress
                SearchGroupCard(Modifier.testTag("goals-daily")) {
                    GoalRow(stepsSpec, goalValue(stepsSpec), p?.steps, p?.stepsPct, "steps") { editing = stepsSpec }
                    SearchRowDivider(leading = 64.dp)
                    GoalRow(kcalSpec, goalValue(kcalSpec), p?.activeKcal, p?.activeKcalPct, "kcal") { editing = kcalSpec }
                    SearchRowDivider(leading = 64.dp)
                    GoalRow(standSpec, goalValue(standSpec), p?.standHours, p?.standHoursPct, "hr") { editing = standSpec }
                    SearchRowDivider(leading = 64.dp)
                    GoalRow(waterSpec, goalValue(waterSpec), p?.waterMl, null, "ml") { editing = waterSpec }
                    SearchRowDivider(leading = 64.dp)
                    GoalRow(focusSpec, goalValue(focusSpec), p?.focusMinutes?.toDouble(), null, "min") { editing = focusSpec }
                }
                when {
                    goalsMessage != null -> Text(goalsMessage!!, style = AuraType.callout, color = AuraColors.red, modifier = Inset)
                    goalsRefused -> Footnote(
                        "Your profile doesn't keep health goals yet, so these are Aura Clarus's suggestions. You can " +
                            "still set a focus goal.",
                        Inset.testTag("goals-refused"),
                    )
                    progressFailed -> UnitsRetryLine("Couldn't load today's progress.", retry = { retry += 1 })
                    else -> Footnote("Tap a goal to change it. Progress is today's so far.", Inset)
                }
            }
        }

        // Sleep
        if (profile != null) {
            SectionTitle("Sleep", Inset)
            AuraCard(padding = 0.dp, spacing = 0.dp, modifier = Modifier.testTag("goals-sleep")) {
                SleepNeedRow(goalValue(sleepSpec)) { editing = sleepSpec }
                CardDivider(Modifier.padding(start = 20.dp))
                TimeRow("Bedtime", "moon.fill", parseTime(goals?.bedtime, LocalTime.of(23, 0)), "goals-bedtime") {
                    saveGoal(HealthGoals.BEDTIME, timeText(it))
                }
                CardDivider(Modifier.padding(start = 20.dp))
                TimeRow("Wake up", "sun.max.fill", parseTime(goals?.wakeTime, LocalTime.of(7, 0)), "goals-wake") {
                    saveGoal(HealthGoals.WAKE_TIME, timeText(it))
                }
            }
        }

        // Habits
        Row(Inset, verticalAlignment = Alignment.CenterVertically) {
            // SectionTitle fills the width on its own; weighted, it leaves room for Add.
            SectionTitle("Habits", Modifier.weight(1f))
            if (habits?.isNotEmpty() == true) {
                Text(
                    "Add",
                    style = AuraType.calloutMedium,
                    color = AuraColors.blue,
                    modifier = Modifier
                        .clickable(role = Role.Button) { habitSheet = HabitSheet(null) }
                        .padding(6.dp)
                        .testTag("habit-add"),
                )
            }
        }
        val list = habits
        when {
            list == null && !habitsFailed -> Spinner()
            list == null -> UnitsRetryLine("Couldn't load your habits.", retry = { retry += 1 })
            list.isEmpty() -> HabitsEmpty { habitSheet = HabitSheet(null) }
            else -> {
                SearchGroupCard(Modifier.testTag("goals-habits")) {
                    list.forEachIndexed { index, habit ->
                        if (index > 0) SearchRowDivider(leading = 70.dp)
                        HabitRow(
                            habit = habit,
                            today = todayOf(habit),
                            streak = habit.streak.currentOn(today),
                            totalLogs = if (habit.goal.enabled) logs.count { it.habitId == habit.id } else null,
                            busy = habit.id in busyHabits,
                            onCheck = { check(habit) },
                            // A new habit opens once the server has it.
                            onOpen = { if (!HabitEdits.isPending(habit.id)) habitSheet = HabitSheet(habit) },
                        )
                    }
                }
                if (habitsMessage != null) {
                    Text(habitsMessage!!, style = AuraType.callout, color = AuraColors.red, modifier = Inset)
                } else if (habitsFailed) {
                    UnitsRetryLine("Couldn't refresh your habits.", retry = { retry += 1 })
                } else {
                    Footnote("Tap the circle to check a habit off for today; tap it again to undo.", Inset)
                }
            }
        }
    }

    editing?.let { spec ->
        GoalStepperSheet(
            spec = spec,
            value = goalValue(spec),
            onSave = {
                editing = null
                if (it != goalValue(spec)) saveGoal(spec.key, it)
            },
            onDismiss = { editing = null },
        )
    }
    habitSheet?.let { sheet ->
        HabitEditorSheet(
            habit = sheet.habit,
            saving = false,
            error = null,
            onSave = { saveHabit(sheet, it) },
            onDelete = sheet.habit?.let { habit -> { deleteHabit(habit) } },
            onDismiss = { habitSheet = null },
        )
    }
}

/** The habit sheet's subject; `habit` null adds one. */
private data class HabitSheet(val habit: Habit?)

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
    }
}

/** Icon, name, "6,240 of 8,000 steps" and the bar. Health Data's own percentage wins when it sends one. */
@Composable
private fun GoalRow(spec: GoalSpec, goal: Int, value: Double?, pct: Double?, unit: String, onClick: () -> Unit) {
    val fraction = pct?.let { it / 100 } ?: value?.let { if (goal > 0) it / goal else null }
    val done = value?.let { "${grouped(Math.round(it).toInt())} of " } ?: ""
    val line = "$done${spec.text(goal)}"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("goal-row-${spec.key}")
            .padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(auraIcon(spec.symbol), contentDescription = null, tint = spec.color, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.title, style = AuraType.bodyMedium, color = Color.Black)
                Spacer(Modifier.weight(1f))
                if (fraction != null) {
                    Text("${Math.round(fraction * 100)}%", style = AuraType.calloutSemibold, color = spec.color)
                }
            }
            GoalBar(fraction, spec.color)
            Text(
                if (unit == "min" && value != null) "${spec.text(Math.round(value).toInt())} of ${spec.text(goal)}" else line,
                style = AuraType.caption,
                color = AuraColors.metricGrey,
            )
        }
    }
}

@Composable
private fun SleepNeedRow(minutes: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("goal-row-${HealthGoals.SLEEP_NEED}")
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(auraIcon("bed.double.fill"), contentDescription = null, tint = AuraColors.indigo, modifier = Modifier.size(24.dp))
        Text("Sleep need", style = AuraType.body, color = Color.Black)
        Spacer(Modifier.weight(1f))
        Text(hoursAndMinutes(minutes), style = AuraType.body, color = AuraColors.blue)
    }
}

@Composable
private fun TimeRow(title: String, symbol: String, time: LocalTime, tag: String, onChange: (LocalTime) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(auraIcon(symbol), contentDescription = null, tint = AuraColors.indigo, modifier = Modifier.size(24.dp))
        Text(title, style = AuraType.body, color = Color.Black)
        Spacer(Modifier.weight(1f))
        BetaTimeField(title, time, tag) { if (it != time) onChange(it) }
    }
}

@Composable
private fun HabitsEmpty(onAdd: () -> Unit) {
    AuraCard(padding = 24.dp, spacing = 12.dp, modifier = Modifier.testTag("habits-empty")) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile("checklist", AuraColors.green, size = 56.dp)
            Text("No habits yet", style = AuraType.bodyMedium, color = Color.Black)
            Text(
                "Pick one small thing to do each day, like a glass of water after you wake up.",
                style = AuraType.callout,
                color = AuraColors.metricGrey,
            )
            PillButton("Add a habit", onClick = onAdd, modifier = Modifier.testTag("habit-add-first"), style = PillStyle.Primary)
        }
    }
}

private fun parseTime(text: String?, fallback: LocalTime): LocalTime = try {
    if (text == null) fallback else LocalTime.parse(text)
} catch (_: DateTimeParseException) {
    fallback
}

private fun timeText(time: LocalTime): String = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
