package info.ibtaura.auraclarus.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import info.ibtaura.aura.core.QuickCheckResult
import kotlinx.serialization.Serializable

/**
 * Every page pushed inside a tab. Each tab keeps its own back stack; `nav.push(route)` adds to the current tab's.
 * iOS sheets (Work hours, Find a time, Event editor) are pushed pages here; the system back gesture pops them.
 * Small sheets inside one screen (break pickers, rating, Done) stay local with `AuraSheet`.
 */
@Serializable
sealed interface Route {
    // Tab roots
    @Serializable data object Home : Route
    @Serializable data object Recovery : Route
    @Serializable data object Search : Route

    // Home
    /** Home > Work > Calendar (HomeCalendarList: zones, hour grid, Find a time, editor). */
    @Serializable data object Calendar : Route
    /** Home > Work > Task (HomeTaskList). */
    @Serializable data object Tasks : Route
    /** Today's sessions (inline on iOS Home; a page here when a screen wants one). */
    @Serializable data object Sessions : Route
    /** Blocks to review (inline on iOS Home; a page here when a screen wants one). */
    @Serializable data object ReviewInbox : Route
    /** New event when `eventId` is null; else the event `app.event(eventId)` (remember it with `app.rememberEvents`). */
    @Serializable data class EventEditor(val eventId: String? = null) : Route
    /** Find a time for `date` (a DayKey). On confirm: `app.dataVersion += 1`, then `nav.pop()`. */
    @Serializable data class FindATime(val date: String) : Route

    // Settings
    @Serializable data object Settings : Route
    /** The work hours form; opens with `app.workSchedule` (null shows "Later" instead of "Cancel"). */
    @Serializable data object WorkHours : Route

    // Metrics
    @Serializable data class Metric(
        val name: String,
        val field: String,
        val unit: String,
        val format: String? = null,
        val backTitle: String = "Home",
    ) : Route
    @Serializable data class Recorded(val name: String, val field: String, val unit: String, val format: String? = null) : Route
    @Serializable data class Sources(val name: String) : Route

    // Recovery
    @Serializable data class ModeDetail(val modeId: String, val headline: String? = null) : Route
    /** The Recovery page reached from Search > Modes (pushed, with a back button). */
    @Serializable data object Modes : Route

    // Search
    /** A catalogue section; the screen finds it in `app.catalogue` (or `app.loadCatalogue()`). */
    @Serializable data class SearchCategory(val sectionId: String, val title: String) : Route
    /** Search > Clinical Documents: an honest empty page until the backend stores documents. */
    @Serializable data object ClinicalDocuments : Route
    /** Search > Goals, Settings > Goals: daily goals, sleep need and habits. */
    @Serializable data object Goals : Route
    /** Search > Sessions: 30 days of sessions, rewards and CSV export. */
    @Serializable data object SessionHistory : Route
    @Serializable data class SessionDetail(val sessionId: String) : Route
    /** Search > Logging: water, mood, check-ins and the log categories. */
    @Serializable data object Logging : Route
    @Serializable data object CalorieTracker : Route
    /** Settings > Units: metric or imperial for every metric page. */
    @Serializable data class Units(val name: String, val field: String, val unit: String, val format: String? = null) : Route

    /** A page the Figma doesn't have yet ("Units", "Logging", "Goals", ...). */
    @Serializable data class NotDesigned(val title: String) : Route
}

/** The three tabs, as RootView.swift. `root` is the tab's first page. */
enum class Tab(val label: String, val symbol: String, val root: Route, val tag: String) {
    Home("Home", "heart.fill", Route.Home, "tab-home"),
    Recovery("Recovery", "leaf.fill", Route.Recovery, "tab-recovery"),
    Search("Search", "magnifyingglass", Route.Search, "tab-search"),
}

/**
 * The navigation screens use. RootScreen makes one and passes it to every screen as `nav`.
 * Full-screen covers (the run, the Quick check) sit over the tabs, outside any back stack.
 */
@Stable
class AuraNav(private val app: AppState) {
    /** The selected tab. */
    var tab by mutableStateOf(Tab.Home)
        private set

    /** Each composed tab's controller. Only the selected tab is composed. */
    internal val controllers = mutableMapOf<Tab, NavHostController>()

    private val current: NavHostController? get() = controllers[tab]

    /** Pushes a page on the current tab. */
    fun push(route: Route) {
        current?.navigate(route)
    }

    /** Pops the current tab's top page. False (and nothing happens) at the tab's root. */
    fun pop(): Boolean {
        val controller = current ?: return false
        if (controller.previousBackStackEntry == null) return false
        return controller.popBackStack()
    }

    /** Back to the current tab's root page. */
    fun popToRoot() {
        current?.popBackStack(tab.root, inclusive = false)
    }

    /** Shows a tab. Tapping the tab already shown pops it to its root, as iOS does. */
    fun selectTab(target: Tab) {
        if (target == tab) popToRoot() else tab = target
    }

    // MARK: The run cover

    /** Shows the run page over the tabs (the mini bar's tap). `app.startRun` opens it itself. */
    fun openRun() {
        app.runExpanded = true
    }

    /** Hides the run page; a run still going shows in the mini bar. */
    fun closeRun() {
        app.runExpanded = false
    }

    // MARK: The Quick check cover

    /** True while the Quick check covers the tabs. */
    var quickCheckOpen by mutableStateOf(false)
        private set

    private var onQuickCheckFinished: ((QuickCheckResult?) -> Unit)? = null

    /** Opens the Quick check over everything. `onFinished` gets the backend's answer, or null when left without one. */
    fun openQuickCheck(onFinished: (QuickCheckResult?) -> Unit = {}) {
        onQuickCheckFinished = onFinished
        quickCheckOpen = true
    }

    /** Closes the Quick check and hands `result` to whoever opened it. QuickCheckScreen calls this. */
    fun finishQuickCheck(result: QuickCheckResult?) {
        quickCheckOpen = false
        val callback = onQuickCheckFinished
        onQuickCheckFinished = null
        callback?.invoke(result)
    }
}
