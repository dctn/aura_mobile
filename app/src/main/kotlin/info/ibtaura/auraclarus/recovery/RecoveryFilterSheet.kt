package info.ibtaura.auraclarus.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.Mode
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraSheet
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.color
import info.ibtaura.auraclarus.design.symbol

/**
 * Recovery's Filter: length chips and shelf chips. The tab behind updates as chips are tapped; the button at the
 * bottom says how many modes match and closes the sheet. Tags: "recovery-filter-sheet", "recovery-filter-done",
 * "recovery-filter-reset", "filter-length-<short|medium|long|open>", "filter-shelf-<key>", "recovery-filter-show".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecoveryFilterSheet(
    filter: RecoveryFilter,
    modes: List<Mode>,
    onChange: (RecoveryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val matching = modes.count { filter.matches(it) }
    AuraSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetHeader(
                title = "Filter",
                leadingTitle = if (filter.isActive) "Reset" else null,
                onLeading = { onChange(RecoveryFilter()) },
                trailingTitle = "Done",
                onTrailing = onDismiss,
                titleTag = "recovery-filter-sheet",
                leadingTag = "recovery-filter-reset",
                trailingTag = "recovery-filter-done",
            )
            FilterGroupTitle("Length", "A mode's usual length; you can change it before you start.")
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LengthBand.entries.forEach { band ->
                    val count = modes.count { band.matches(it.minutes) && (filter.shelves.isEmpty() || it.shelf in filter.shelves) }
                    RecoveryChip(
                        title = band.label,
                        isSelected = band in filter.lengths,
                        tag = "filter-length-${band.tag}",
                        onClick = { onChange(filter.toggled(band)) },
                        enabled = count > 0,
                    )
                }
            }
            FilterGroupTitle("Shelves", null, Modifier.padding(top = 8.dp))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recoveryShelves.forEach { shelf ->
                    val count = modes.count { it.shelf == shelf && (filter.lengths.isEmpty() || filter.lengths.any { b -> b.matches(it.minutes) }) }
                    RecoveryChip(
                        title = shelf.raw,
                        isSelected = shelf in filter.shelves,
                        tag = "filter-shelf-${shelf.name}",
                        onClick = { onChange(filter.toggled(shelf)) },
                        symbol = shelf.symbol,
                        symbolColor = shelf.color,
                        enabled = count > 0,
                    )
                }
            }
            PillButton(
                when {
                    !filter.isActive -> "Show All ${modes.size} Modes"
                    matching == 0 -> "No Modes Match"
                    matching == 1 -> "Show 1 Mode"
                    else -> "Show $matching Modes"
                },
                onClick = onDismiss,
                fills = true,
                enabled = matching > 0 || !filter.isActive,
                modifier = Modifier.padding(top = 12.dp).testTag("recovery-filter-show"),
            )
        }
    }
}

@Composable
private fun FilterGroupTitle(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = AuraType.system(20, FontWeight.Bold), color = Color.Black)
        if (detail != null) Text(detail, style = AuraType.system(13), color = AuraColors.gray)
    }
}
