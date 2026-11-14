package info.ibtaura.auraclarus.home

import androidx.compose.runtime.Composable
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav

/** Blocks to review on a page of their own (the iOS ReviewInbox that Home shows inline). */
@Composable
fun ReviewInboxScreen(app: AppState, nav: AuraNav) {
    AuraPage(title = "Did these happen?", onBack = { nav.pop() }) {
        Footnote("Planned blocks from the last 7 days that nothing recorded. Saving a card answers it.")
        ReviewInboxContent(app, showsHeader = false, showsEmpty = true)
    }
}
