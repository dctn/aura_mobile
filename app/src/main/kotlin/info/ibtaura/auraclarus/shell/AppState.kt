package info.ibtaura.auraclarus.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import info.ibtaura.aura.core.AuraApi
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.AuthService
import info.ibtaura.aura.core.AuthState
import info.ibtaura.aura.core.CalendarEvent
import info.ibtaura.aura.core.CatalogueSection
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.RecoveryType
import info.ibtaura.aura.core.WorkSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.TimeZone

/** A metric pinned from its page, shown under Pins on Home. Saved on the phone only. */
@Serializable
data class PinnedMetric(val field: String, val name: String, val unit: String) {
    val id: String get() = this.field
}

/**
 * What every screen shares (the port of AppModel.swift). Screens get it as the `app` parameter. Every field is
 * Compose state, so reading it in a composable recomposes on change. Mutate it on the main thread only (screens'
 * coroutines are on main). Long work goes through `app.launch { }`, which outlives the screen.
 */
@Stable
class AppState(
    val api: AuraApi,
    val auth: AuthService,
    val flags: LaunchFlags,
    private val scope: CoroutineScope,
    private val prefs: KeyValues = InMemoryKeyValues(),
    /** The 65 modes from core's modes.json. */
    val modes: List<Mode> = Mode.loadCatalogue(),
) {
    /** UI tests run a few-second Quick check; the app the full one. */
    val quickCheckShort: Boolean get() = flags.shortCheck

    // MARK: Ports (filled at launch by installPorts; null until then or when the area isn't built yet)

    private var runSlot by mutableStateOf<RunPort?>(null)

    /** The run controller. Setting it routes its errors to `note`. */
    var run: RunPort?
        get() = runSlot
        set(value) {
            value?.onError = { note(it) }
            runSlot = value
        }

    /** Last night's sleep from Health Connect. UI tests use a stub. */
    var sleep by mutableStateOf<SleepPort?>(null)

    /** Health Connect day sync. */
    var health by mutableStateOf<HealthPort?>(null)

    // MARK: Shared state

    /** Null while the token store is being read. */
    var authState by mutableStateOf<AuthState?>(null)

    /** True while the consent sheet shows. */
    var needsConsent by mutableStateOf(false)

    /**
     * Loading screens key their loads on it (`LaunchedEffect(app.dataVersion) { load() }`), so they load again after
     * consent is granted, after a save that changes what other screens show, and on a new day.
     */
    var dataVersion by mutableIntStateOf(0)

    /** True while the run page covers the tabs (iOS `run.isExpanded`). `startRun` sets it; the mini bar reopens it. */
    var runExpanded by mutableStateOf(false)

    private var pinsState by mutableStateOf<List<PinnedMetric>>(emptyList())

    /** Metrics pinned to Home. Saved on the phone. */
    var pins: List<PinnedMetric>
        get() = pinsState
        private set(value) {
            pinsState = value
            prefs.putString(PINS_KEY, json.encodeToString(ListSerializer(PinnedMetric.serializer()), value))
        }

    private var healthConnectedState by mutableStateOf(false)

    /** Turned on from the morning check-in's "Use Health Connect for sleep" (iOS `appleHealthConnected`). Saved. */
    var healthConnected: Boolean
        get() = healthConnectedState
        set(value) {
            healthConnectedState = value
            prefs.putBoolean(HEALTH_KEY, value)
        }

    /** Last night went to the backend from Health Connect today, so the check-in doesn't ask for sleep times. */
    var sleepSentToday by mutableStateOf(false)
        private set

    /** The morning check-in went in today, so Home stops showing it. */
    var morningCheckinSent by mutableStateOf(false)
        private set

    // MARK: Caches screens share (not in iOS AppModel; they save a second load between screens)

    /** The metric catalogue once loaded (Search, SearchCategory). `loadCatalogue()` fills it. */
    var catalogue by mutableStateOf<List<CatalogueSection>?>(null)
        private set

    /** The profile's work hours once loaded; null when none are set. Check `workScheduleLoaded` first. */
    var workSchedule by mutableStateOf<WorkSchedule?>(null)
        private set
    var workScheduleLoaded by mutableStateOf(false)
        private set

    private val events = mutableMapOf<String, CalendarEvent>()

    private var loadedDay = DayKey.string(Instant.now())
    private var timeZoneSent = false
    private var syncingSleep = false

    /** UI tests send the stub night at any hour, so the walk doesn't depend on when CI runs. */
    private val sleepAnyHour: Boolean = flags.healthNight

    init {
        if (flags.isUiTesting) {
            // Every launch starts the morning afresh, so one walk's check-in doesn't hide the card from the next.
            clearDayFlags()
            prefs.putBoolean(HEALTH_KEY, flags.healthNight)
            if (!flags.checkin && !flags.healthNight) prefs.putBoolean(checkinKey(today()), true)
        }
        prefs.getString(PINS_KEY)?.let { saved ->
            runCatching { json.decodeFromString(ListSerializer(PinnedMetric.serializer()), saved) }
                .onSuccess { pinsState = it }
        }
        healthConnectedState = prefs.getBoolean(HEALTH_KEY)
        refreshDayFlags()
    }

    /** Runs `block` in the app's scope: it carries on when the screen that started it goes away. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    // MARK: Session

    suspend fun restore() {
        authState = auth.restore()
    }

    suspend fun signOut() {
        // First, while the token still works.
        runCatching { run?.endForSignOut() }
        runCatching { auth.signOut() }
        clearForSignedOut()
    }

    /** Pins, the run and today's flags are per phone, so the next account mustn't inherit them. Safe to repeat. */
    private fun clearForSignedOut() {
        run?.close()
        runExpanded = false
        pins = emptyList()
        needsConsent = false
        timeZoneSent = false
        catalogue = null
        workSchedule = null
        workScheduleLoaded = false
        events.clear()
        // The next account mustn't get this account's sleep, nor skip its own check-in.
        clearDayFlags()
        healthConnected = false
        refreshDayFlags()
        authState = AuthState.SignedOut
    }

    /** Screens send every caught error here. Missing consent opens the consent sheet, a lost session the sign-in. */
    fun note(error: Throwable) {
        when (error) {
            AuraError.ConsentRequired -> needsConsent = true
            AuraError.SignedOut -> clearForSignedOut()
            else -> Unit
        }
    }

    /** Once per sign-in, and it doesn't matter if it fails. */
    fun sendTimeZoneIfNeeded() {
        if (authState !is AuthState.SignedIn || timeZoneSent) return
        timeZoneSent = true
        val identifier = TimeZone.getDefault().id
        launch { runCatching { api.setTimeZoneIfMissing(identifier) } }
    }

    /** Screens only load when `dataVersion` changes, so without this Home would still show yesterday after midnight. */
    fun reloadIfNewDay() {
        refreshDayFlags()
        val today = today()
        if (today == loadedDay) return
        loadedDay = today
        dataVersion += 1
    }

    /** Grants consent, then makes every screen load again through `dataVersion`. */
    suspend fun grantConsent() {
        try {
            api.grantHealthConsent()
            needsConsent = false
            dataVersion += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            note(e)
            return
        }
        // A night refused for missing consent goes now.
        syncSleepIfNeeded()
    }

    // MARK: Sleep and the morning check-in

    /** Reads today's flags again, so a new day asks for the check-in and last night once more. */
    private fun refreshDayFlags() {
        val today = today()
        val sleep = prefs.getBoolean(sleepSentKey(today))
        if (sleep != sleepSentToday) sleepSentToday = sleep
        val checkin = prefs.getBoolean(checkinKey(today))
        if (checkin != morningCheckinSent) morningCheckinSent = checkin
    }

    private fun clearDayFlags() {
        val today = today()
        prefs.remove(sleepSentKey(today))
        prefs.remove(checkinKey(today))
        prefs.remove(HEALTH_KEY)
    }

    fun markMorningCheckinSent() {
        prefs.putBoolean(checkinKey(today()), true)
        morningCheckinSent = true
    }

    /** Asks Health Connect for sleep, then sends last night. Throws when it can't ask (iOS `connectAppleHealth`). */
    suspend fun connectHealth() {
        val source = sleep ?: throw IllegalStateException("Health Connect isn't available")
        source.requestAccess()
        healthConnected = true
        syncSleepIfNeeded()
    }

    /**
     * Sends last night from Health Connect once a day, from 04:00, when it ended this morning. Runs after sign-in and
     * each time the app comes to the front. Missing consent opens the consent sheet; other failures try next time.
     */
    suspend fun syncSleepIfNeeded() {
        if (authState !is AuthState.SignedIn || !healthConnected || syncingSleep) return
        val source = sleep ?: return
        refreshDayFlags()
        if (sleepSentToday) return
        val now = Instant.now()
        val local = now.atZone(DayKey.zone)
        if (!sleepAnyHour && local.hour < 4) return
        val from = local.toLocalDate().minusDays(1).atTime(18, 0).atZone(DayKey.zone).toInstant()
        val todayKey = DayKey.string(now)

        syncingSleep = true
        try {
            val night = source.lastNight(from, now) ?: return
            if (night.date != todayKey) return
            api.putSleepNight(night)
            prefs.putBoolean(sleepSentKey(todayKey), true)
            sleepSentToday = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            note(e)
        } finally {
            syncingSleep = false
        }
    }

    /** Sends Health Connect days when connected (Android only). Runs with `syncSleepIfNeeded`. */
    suspend fun syncHealthIfNeeded() {
        if (authState !is AuthState.SignedIn) return
        val port = health ?: return
        try {
            if (port.isConnected()) port.syncRecentDays()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            note(e)
        }
    }

    // MARK: Modes and runs

    fun mode(id: String): Mode? = modes.firstOrNull { it.id == id }

    /** The mode an alert's `action.ref` starts. Only actions that are a timed run have one. */
    fun modeForAlert(ref: String): Mode? {
        val ids = mapOf("nap" to "nap-10", "recovery" to "recovery-break", "breathing" to "coherent", "move" to "stand-reset")
        return mode(ids[ref] ?: ref)
    }

    /** Starts a run and shows the run page over the tabs. Does nothing while the run controller isn't installed. */
    fun startRun(mode: Mode, minutes: Int, title: String? = null, recoveryType: RecoveryType? = null) {
        val controller = run ?: return
        controller.start(mode, minutes, title, recoveryType)
        runExpanded = true
    }

    // MARK: Pins

    fun isPinned(field: String): Boolean = pins.any { it.field == field }

    fun togglePin(metric: PinnedMetric) {
        pins = if (isPinned(metric.field)) pins.filterNot { it.field == metric.field } else pins + metric
    }

    // MARK: Shared loads

    /** The catalogue, loaded once per sign-in. Throws AuraError (pass it to `note`). */
    suspend fun loadCatalogue(force: Boolean = false): List<CatalogueSection> {
        catalogue?.let { if (!force) return it }
        val loaded = api.catalogue()
        catalogue = loaded
        return loaded
    }

    /** Loads the work hours into `workSchedule` (null when none are set). Throws AuraError. */
    suspend fun loadWorkSchedule(): WorkSchedule? {
        val loaded = api.workSchedule()
        workSchedule = loaded
        workScheduleLoaded = true
        return loaded
    }

    /** Saves the work hours, then makes every screen load again (Calendar and Settings show the new hours). */
    suspend fun saveWorkSchedule(schedule: WorkSchedule) {
        api.setWorkSchedule(schedule)
        workSchedule = schedule
        workScheduleLoaded = true
        dataVersion += 1
    }

    /** Keeps loaded events so the event editor can find one by id (`Route.EventEditor(eventId)`). */
    fun rememberEvents(list: List<CalendarEvent>) {
        list.forEach { events[it.id] = it }
    }

    fun event(id: String): CalendarEvent? = events[id]

    companion object {
        private const val PINS_KEY = "pinnedMetrics"
        private const val HEALTH_KEY = "healthConnected"
        private val json = Json { ignoreUnknownKeys = true }

        private fun sleepSentKey(day: String) = "sleepSent-$day"
        private fun checkinKey(day: String) = "morningCheckin-$day"
        private fun today() = DayKey.string(Instant.now())
    }
}
