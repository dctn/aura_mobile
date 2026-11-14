package info.ibtaura.auraclarus.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import info.ibtaura.aura.core.AuthState
import info.ibtaura.auraclarus.check.QuickCheckScreen
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.LoadingPage
import info.ibtaura.auraclarus.design.LocalBottomInset
import info.ibtaura.auraclarus.design.NotDesignedYet
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.home.CalendarScreen
import info.ibtaura.auraclarus.home.EventEditorScreen
import info.ibtaura.auraclarus.home.FindATimeSheet
import info.ibtaura.auraclarus.home.HomeScreen
import info.ibtaura.auraclarus.home.ReviewInboxScreen
import info.ibtaura.auraclarus.home.SessionsScreen
import info.ibtaura.auraclarus.home.TasksScreen
import info.ibtaura.auraclarus.metric.MetricScreen
import info.ibtaura.auraclarus.metric.RecordedScreen
import info.ibtaura.auraclarus.metric.SourcesScreen
import info.ibtaura.auraclarus.recovery.ModeDetailScreen
import info.ibtaura.auraclarus.recovery.RecoveryScreen
import info.ibtaura.auraclarus.run.MiniRunBar
import info.ibtaura.auraclarus.run.OtherSessionAlert
import info.ibtaura.auraclarus.run.RunScreen
import info.ibtaura.auraclarus.search.SearchCategoryScreen
import info.ibtaura.auraclarus.search.SearchScreen
import info.ibtaura.auraclarus.settings.SettingsScreen
import info.ibtaura.auraclarus.settings.WorkHoursScreen
import info.ibtaura.auraclarus.signin.SignInScreen

/** Sign-in first, then the three tabs (RootView.swift). */
@Composable
fun RootScreen(app: AppState) {
    LaunchedEffect(Unit) { app.restore() }
    val auth = app.authState
    LaunchedEffect(auth) {
        app.sendTimeZoneIfNeeded()
        app.syncSleepIfNeeded()
        app.syncHealthIfNeeded()
    }
    // Last night is sent the first time the app is opened after waking.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        app.reloadIfNewDay()
        app.launch {
            app.syncSleepIfNeeded()
            app.syncHealthIfNeeded()
        }
    }
    when (auth) {
        null -> LoadingPage()
        AuthState.SignedOut -> SignInScreen(app)
        is AuthState.SignedIn -> MainTabs(app)
    }
}

@Composable
private fun MainTabs(app: AppState) {
    val nav = remember(app) { AuraNav(app) }
    val holder = rememberSaveableStateHolder()
    val density = LocalDensity.current
    // The floating bars' own height (tab bar, plus the mini run bar while it shows), measured without the navigation
    // bar under them; starts at the tab bar's size so the first frame already clears it.
    var barsHeight by remember { mutableStateOf(TabBarHeight) }
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val covered = app.runExpanded || nav.quickCheckOpen

    Box(Modifier.fillMaxSize().background(AuraColors.background)) {
        // Every tab page pads its bottom by this (bottomPadding() adds the gap), so its last item scrolls clear.
        CompositionLocalProvider(LocalBottomInset provides barsHeight + navBarHeight) {
            // One back stack per tab: each tab's controller is saved with its pages while another tab shows.
            val tab = nav.tab
            holder.SaveableStateProvider(tab.name) {
                TabHost(app, nav, tab)
            }
        }

        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            // Measured inside the navigation-bar padding, so this is the bars alone; the inset is added above.
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { barsHeight = with(density) { it.height.toDp() } },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (app.run?.showsMiniBar == true && !app.runExpanded) MiniRunBar(app, nav)
                AuraTabBar(selected = nav.tab, onSelect = nav::selectTab)
            }
        }

        OtherSessionAlert(app, showsHere = !app.runExpanded)

        if (app.runExpanded) {
            Cover("run-cover") {
                BackHandler { nav.closeRun() }
                RunScreen(app, nav)
            }
        }
        if (nav.quickCheckOpen) {
            Cover("quick-check-cover") {
                BackHandler { nav.finishQuickCheck(null) }
                QuickCheckScreen(app, nav)
            }
        }
        if (app.needsConsent && !covered) ConsentSheet(app)
    }
}

/** A full-screen cover over the tabs (SwiftUI `fullScreenCover`): takes every touch and hides the tabs from tests. */
@Composable
private fun Cover(tag: String, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AuraColors.background)
            .pointerInput(Unit) {}
            .testTag(tag),
    ) {
        content()
    }
}

@Composable
private fun TabHost(app: AppState, nav: AuraNav, tab: Tab) {
    val controller = rememberNavController()
    DisposableEffect(controller, tab) {
        nav.controllers[tab] = controller
        onDispose { if (nav.controllers[tab] === controller) nav.controllers.remove(tab) }
    }
    NavHost(
        navController = controller,
        startDestination = tab.root,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { slideInHorizontally { it } },
        exitTransition = { slideOutHorizontally { -it / 3 } },
        popEnterTransition = { slideInHorizontally { -it / 3 } },
        popExitTransition = { slideOutHorizontally { it } },
    ) {
        auraDestinations(app, nav)
    }
}

/** Every page, in every tab's graph (a metric can open from Home and from Search). */
private fun NavGraphBuilder.auraDestinations(app: AppState, nav: AuraNav) {
    composable<Route.Home> { HomeScreen(app, nav) }
    composable<Route.Recovery> { RecoveryScreen(app, nav) }
    composable<Route.Search> { SearchScreen(app, nav) }

    composable<Route.Calendar> { CalendarScreen(app, nav) }
    composable<Route.Tasks> { TasksScreen(app, nav) }
    composable<Route.Sessions> { SessionsScreen(app, nav) }
    composable<Route.ReviewInbox> { ReviewInboxScreen(app, nav) }
    composable<Route.EventEditor> { EventEditorScreen(app, nav, it.toRoute()) }
    composable<Route.FindATime> { FindATimeSheet(app, nav, it.toRoute()) }

    composable<Route.Settings> { SettingsScreen(app, nav) }
    composable<Route.WorkHours> { WorkHoursScreen(app, nav) }

    composable<Route.Metric> { MetricScreen(app, nav, it.toRoute()) }
    composable<Route.Recorded> { RecordedScreen(app, nav, it.toRoute()) }
    composable<Route.Sources> { SourcesScreen(app, nav, it.toRoute()) }

    composable<Route.ModeDetail> { ModeDetailScreen(app, nav, it.toRoute()) }
    composable<Route.Modes> { RecoveryScreen(app, nav, pushed = true) }

    composable<Route.SearchCategory> { SearchCategoryScreen(app, nav, it.toRoute()) }
    composable<Route.NotDesigned> { NotDesignedYet(it.toRoute<Route.NotDesigned>().title, onBack = { nav.pop() }) }
}

/** AuraTabBar's laid-out height: 4 top + 62 pill + 8 bottom. Only a first-frame estimate; the real height is measured. */
private val TabBarHeight = 74.dp

/** The floating capsule tab bar of iOS 26: icon over label, the selected tab blue on a grey pill. */
@Composable
private fun AuraTabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .padding(bottom = 8.dp, top = 4.dp)
            .width(276.dp)
            .height(62.dp)
            .shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f))
            .clip(CircleShape)
            .background(AuraColors.card.copy(alpha = 0.97f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            val tint = if (isSelected) AuraColors.blue else Color.Black
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(if (isSelected) AuraColors.tabSelected else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .semantics {
                        role = Role.Tab
                        this.selected = isSelected
                    }
                    .testTag(tab.tag),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(auraIcon(tab.symbol), contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                Text(tab.label, style = AuraType.tab, color = tint)
            }
        }
    }
}
