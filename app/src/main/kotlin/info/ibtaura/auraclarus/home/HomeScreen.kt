package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.ListRowCard
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import kotlinx.coroutines.delay

/**
 * The Home tab (iOS HomeView, Figma "iPhone 18 Pro - 4"): Insights, Today, Burnout, Work, Pins. Each section loads on
 * its own, so one failure doesn't blank the page. The Figma has no bar here; Settings opens from Search.
 */
@Composable
fun HomeScreen(app: AppState, nav: AuraNav) {
    Box(Modifier.fillMaxSize()) {
        AuraPage(Modifier.testTag("home-page")) {
            HomeInsightsSection(app, onEventClick = { nav.push(Route.EventEditor(it.id)) })
            HomeTodaySection(app, onStart = { modeId, headline -> nav.push(Route.ModeDetail(modeId, headline)) })
            HomeBurnoutSection(app)
            HomeWorkSection(app, nav)
            HomePinsSection(app, nav)
        }
        // With no bar there's no fade, so this keeps scrolled text from cutting hard under the status bar.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .fillMaxWidth()
                .height(16.dp)
                .background(Brush.verticalGradient(listOf(AuraColors.background, AuraColors.background.copy(alpha = 0f))))
                .clearAndSetSemantics {},
        )
    }
    // Once Home's own loads settle, the pages most often opened from it are filled in the background (Tasks, Goals'
    // habits, Calendar), which also wakes their slow backends. AppState keeps it rare, and off in UI tests.
    LaunchedEffect(app.dataVersion) {
        // Long enough for every section's load to have started.
        delay(300)
        app.cache.awaitIdle()
        app.prefetchIfDue { prefetchFromHome(app) }
    }
}

/**
 * Work: two rows that open the Calendar and the read-only task list, then today's sessions and the blocks to review
 * (both owned by the beta-score area).
 */
@Composable
private fun HomeWorkSection(app: AppState, nav: AuraNav) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionTitle("Work")
        ListRowCard(
            "Calendar",
            Modifier.testTag("home-work-calendar"),
            symbol = "arrow.clockwise.heart.fill",
            onClick = { nav.push(Route.Calendar) },
        )
        ListRowCard(
            "Task",
            Modifier.testTag("home-work-task"),
            symbol = "arrow.clockwise.heart.fill",
            onClick = { nav.push(Route.Tasks) },
        )
        SessionsList(app, nav)
        ReviewInbox(app, nav)
    }
}
