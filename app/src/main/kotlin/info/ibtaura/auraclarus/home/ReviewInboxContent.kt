package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.RecoveryType
import info.ibtaura.aura.core.ReviewAnswer
import info.ibtaura.aura.core.ReviewIntensity
import info.ibtaura.aura.core.ReviewItem
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.ZoneText
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home → Work, under the sessions: planned blocks of the last 7 days that nothing recorded, one card each (iOS
 * ReviewInbox). Saving a card answers it and takes it away. The section hides when there's nothing to review, unless
 * [showsEmpty] (the Review page) asks for a line saying so.
 */
@Composable
internal fun ReviewInboxContent(app: AppState, showsHeader: Boolean = true, showsEmpty: Boolean = false) {
    var items by remember { mutableStateOf<List<ReviewItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // Answered here. A reload can't bring them back while the backend catches up.
    var answered by remember { mutableStateOf<Set<String>>(emptySet()) }
    val openItems = items.filter { it.blockId !in answered }

    LaunchedEffect(app.dataVersion) {
        try {
            items = app.api.reviewInbox(date = DayKey.string(LocalDate.now(DayKey.zone)), days = 7)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
        }
        loaded = true
    }

    Column(Modifier.fillMaxWidth().testTag("review-inbox"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (openItems.isNotEmpty()) {
            if (showsHeader) {
                Text("Did these happen?", style = AuraType.heading, color = Color.Black, modifier = Modifier.semantics { heading() })
            }
            openItems.forEach { item ->
                androidx.compose.runtime.key(item.blockId) {
                    ReviewCard(app, item) { answered = answered + item.blockId }
                }
            }
        } else if (showsEmpty && loaded) {
            AuraCard(padding = 20.dp) {
                Text("Nothing to review", style = AuraType.callout, color = AuraColors.footnote)
            }
        }
    }
}

/**
 * One block: did it happen, then how it went. A break asks what kind it was, since a break without a type gives the
 * day score no lift; other blocks ask intensity and drain, both optional.
 */
@Composable
private fun ReviewCard(app: AppState, item: ReviewItem, onSaved: () -> Unit) {
    var happened by remember { mutableStateOf<Boolean?>(null) }
    var intensity by remember { mutableStateOf<ReviewIntensity?>(null) }
    var drain by remember { mutableStateOf<Int?>(null) }
    var recoveryType by remember { mutableStateOf<RecoveryType?>(null) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val blockId = item.blockId

    fun save() {
        val did = happened ?: return
        if (saving) return
        val asksType = did && item.isRecovery
        val asksEffort = did && !item.isRecovery
        val answer = ReviewAnswer(
            item = item, happened = did,
            intensity = if (asksEffort) intensity else null,
            drain = if (asksEffort) drain else null,
            recoveryType = if (asksType) recoveryType else null,
        )
        saving = true
        errorText = null
        scope.launch {
            try {
                app.api.submitReview(answer)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A session already covers this block, so there's nothing left to answer.
                if ((e as? AuraError)?.serverCode == "block_recorded") {
                    onSaved()
                    return@launch
                }
                app.note(e)
                errorText = "Couldn't save that. Try again."
                saving = false
            }
        }
    }

    AuraCard(Modifier.testTag("review-card-$blockId"), padding = 20.dp, spacing = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(ReviewText.whenText(item), style = AuraType.system(11), color = AuraColors.gray)
            Text(item.title, style = AuraType.system(17, FontWeight.SemiBold), color = Color.Black)
        }
        ReviewQuestion("Did it happen?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewChip("Yes", happened == true, "review-happened-yes-$blockId", Modifier.weight(1f)) { happened = true }
            ReviewChip("No", happened == false, "review-happened-no-$blockId", Modifier.weight(1f)) { happened = false }
        }
        if (happened == true) {
            if (item.isRecovery) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReviewQuestion("What kind of break was it?")
                    // Two a row, so long labels like "Talk with someone" still fit the card.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecoveryType.entries.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { type ->
                                    ReviewChip(type.label, recoveryType == type, "review-type-${type.raw}-$blockId", Modifier.weight(1f)) {
                                        recoveryType = type
                                    }
                                }
                                if (row.size < 2) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (recoveryType == null) {
                        Text(
                            "A break counts toward your day score once you say what it was.",
                            style = AuraType.caption, color = AuraColors.gray,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReviewQuestion("How intense was it?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReviewIntensity.entries.forEach { value ->
                            ReviewChip(value.label, intensity == value, "review-intensity-${value.raw}-$blockId", Modifier.weight(1f)) {
                                intensity = value
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReviewQuestion("How draining was it? (1 is not at all)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..5).forEach { value ->
                            ReviewChip("$value", drain == value, "review-drain-$value-$blockId", Modifier.weight(1f)) { drain = value }
                        }
                    }
                }
            }
        }
        errorText?.let { Text(it, style = AuraType.callout, color = AuraColors.red) }
        PillButton(
            title = if (saving) "Saving..." else "Save",
            onClick = { save() },
            fills = true,
            enabled = happened != null && !saving,
            modifier = Modifier.testTag("review-save-$blockId"),
        )
    }
}

@Composable
private fun ReviewQuestion(text: String) {
    Text(text, style = AuraType.calloutMedium, color = Color.Black)
}

/** A grey chip that turns blue when picked, as on Find a time's day row. It takes its share of the row. */
@Composable
private fun ReviewChip(title: String, isSelected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(CircleShape)
            .background(if (isSelected) AuraColors.blue else AuraColors.background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = isSelected }
            .testTag(tag)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = AuraType.system(15, if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (isSelected) Color.White else Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The words a review card shows (iOS ReviewText). */
private object ReviewText {
    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** "Today 10:00–10:30", "Yesterday 15:54–16:04" or "Mon 9 Nov 09:00–10:00". */
    fun whenText(item: ReviewItem): String {
        val range = ZoneText.range(item.start, item.end)
        val today = LocalDate.now(DayKey.zone)
        if (item.date == DayKey.string(today)) return "Today $range"
        if (item.date == DayKey.string(today.minusDays(1))) return "Yesterday $range"
        val day = DayKey.localDate(item.date) ?: return range
        return "${dayFormat.format(day)} $range"
    }
}
