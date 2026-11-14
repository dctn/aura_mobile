package info.ibtaura.auraclarus.home

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav

// The beta day score's pieces of Home (iOS SessionsList, ReviewInbox, MorningCheckinCard). Home calls them; their
// bodies live in SessionsListContent.kt, ReviewInboxContent.kt and CheckinCard.kt.

/**
 * iOS SessionsList: yesterday's and today's sessions, newest first, with "Rate it" on an ended focus session. Shown
 * inline in Home's Work section, with its own "Sessions" heading. Tags: `sessions-list`, `session-row-<id>`,
 * `session-rate-<id>`, `session-rating-<id>`, `session-rating-sheet`, `difficulty-<raw>`.
 */
@Composable
fun SessionsList(app: AppState, nav: AuraNav) {
    SessionsListContent(app)
}

/**
 * iOS ReviewInbox: planned blocks of the last 7 days that nothing recorded, one card each, under "Did these happen?".
 * Inline in Home's Work section; shows nothing when there's nothing to review. Tags: `review-inbox`,
 * `review-card-<blockId>`, `review-happened-yes|no-<blockId>`, `review-type-<type>-<blockId>`, `review-save-<blockId>`.
 */
@Composable
fun ReviewInbox(app: AppState, nav: AuraNav) {
    ReviewInboxContent(app)
}

/**
 * iOS MorningCheckinCard, shown in Home's Today section while `!app.morningCheckinSent` (or while it shows its
 * thank-you). [onSent] tells Today it was sent here. Tags: `morning-checkin`, `checkin-alertness`,
 * `checkin-fell-asleep`, `checkin-woke`, `checkin-apple-health`, `checkin-send`, `checkin-done`.
 */
@Composable
fun MorningCheckinCard(app: AppState, onSent: () -> Unit) {
    CheckinCard(app, onSent)
}
