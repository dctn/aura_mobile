package info.ibtaura.auraclarus.recovery

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import info.ibtaura.aura.core.Mode
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraSheet
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.CardDivider
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.color
import info.ibtaura.auraclarus.design.hex
import info.ibtaura.auraclarus.design.symbol

private val RowHeight = 64.dp

/**
 * Recovery's Edit: show or hide each section (Now and the ten shelves) and drag them into a new order. Nothing
 * changes until Done; Cancel and a swipe leave the tab as it was. Tags: "recovery-edit-sheet", "recovery-edit-done",
 * "recovery-edit-cancel", "recovery-edit-reset", and per section "recovery-edit-row-<key>" /
 * "recovery-edit-toggle-<key>" / "recovery-edit-handle-<key>".
 */
@Composable
internal fun RecoveryEditSheet(
    initial: RecoveryLayout,
    modes: List<Mode>,
    onSave: (RecoveryLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    var layout by remember { mutableStateOf(initial) }
    // The row being dragged, and how far it has moved from its slot.
    var dragging by remember { mutableStateOf<RecoverySection?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val shownCount = layout.visible.size

    AuraSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState(), enabled = dragging == null)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(
                title = "Edit Recovery",
                leadingTitle = "Cancel",
                onLeading = onDismiss,
                trailingTitle = "Done",
                onTrailing = {
                    onSave(layout)
                    onDismiss()
                },
                titleTag = "recovery-edit-sheet",
                leadingTag = "recovery-edit-cancel",
                trailingTag = "recovery-edit-done",
            )
            Text(
                "Choose what Recovery shows. Drag the handle to change the order.",
                style = AuraType.callout,
                color = AuraColors.gray,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(AuraColors.card).padding(vertical = 6.dp),
            ) {
                layout.order.forEachIndexed { index, section ->
                    key(section.key) {
                        val isDragging = dragging == section
                        val lift by animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "lift")
                        EditRow(
                            section = section,
                            detail = sectionDetail(section, modes),
                            shown = !layout.isHidden(section),
                            showsDivider = index > 0 && !isDragging && layout.order.getOrNull(index - 1) != dragging,
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                                .shadow(lift, RoundedCornerShape(18.dp), clip = false)
                                .background(AuraColors.card, RoundedCornerShape(18.dp)),
                            onToggle = { layout = layout.toggled(section) },
                            canMoveUp = index > 0,
                            canMoveDown = index < layout.order.lastIndex,
                            onMove = { delta ->
                                val from = layout.order.indexOf(section)
                                layout = layout.moved(from, from + delta)
                            },
                            handleModifier = Modifier.pointerInput(section) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = section
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        dragging = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        // Swap with a neighbour once the row is more than half way over it.
                                        var at = layout.order.indexOf(section)
                                        while (dragOffset > rowPx / 2 && at < layout.order.lastIndex) {
                                            layout = layout.moved(at, at + 1)
                                            at += 1
                                            dragOffset -= rowPx
                                        }
                                        while (dragOffset < -rowPx / 2 && at > 0) {
                                            layout = layout.moved(at, at - 1)
                                            at -= 1
                                            dragOffset += rowPx
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }
            Footnote(
                if (shownCount == 0) {
                    "Everything is hidden. Recovery will show only search and the break buttons."
                } else {
                    "Hidden sections still come up when you search."
                },
                Modifier.padding(horizontal = 6.dp),
            )
            PillButton(
                "Reset to Default",
                onClick = { layout = RecoveryLayout() },
                style = PillStyle.Secondary,
                fills = true,
                enabled = !layout.isDefault,
                modifier = Modifier.testTag("recovery-edit-reset"),
            )
        }
    }
}

/** "3 modes", or what Now holds. */
private fun sectionDetail(section: RecoverySection, modes: List<Mode>): String {
    val shelf = section.shelf ?: return "Picks for right now"
    val count = modes.count { it.shelf == shelf }
    return if (count == 1) "1 mode" else "$count modes"
}

@Composable
private fun EditRow(
    section: RecoverySection,
    detail: String,
    shown: Boolean,
    showsDivider: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    handleModifier: Modifier,
) {
    val title = section.title
    Column(
        modifier
            .fillMaxWidth()
            .height(RowHeight)
            .semantics {
                stateDescription = if (shown) "Shown" else "Hidden"
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction("Move up") { onMove(-1); true })
                    if (canMoveDown) add(CustomAccessibilityAction("Move down") { onMove(1); true })
                }
            }
            .testTag("recovery-edit-row-${section.key}"),
    ) {
        if (showsDivider) CardDivider(Modifier.padding(start = 70.dp))
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The show / hide circle (iOS edit-mode style).
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Checkbox, onClick = onToggle)
                    .semantics { contentDescription = if (shown) "Hide $title" else "Show $title" }
                    .testTag("recovery-edit-toggle-${section.key}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    auraIcon(if (shown) "checkmark.circle.fill" else "circle"),
                    contentDescription = null,
                    tint = if (shown) AuraColors.blue else AuraColors.footnote,
                    modifier = Modifier.size(26.dp),
                )
            }
            SectionBadge(section, dimmed = !shown)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    title,
                    style = AuraType.system(17, FontWeight.SemiBold),
                    color = if (shown) Color.Black else AuraColors.gray,
                    maxLines = 1,
                )
                Text(detail, style = AuraType.system(13), color = AuraColors.gray, maxLines = 1)
            }
            Box(
                handleModifier
                    .size(44.dp)
                    .semantics { contentDescription = "Reorder $title" }
                    .testTag("recovery-edit-handle-${section.key}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.DragHandle, contentDescription = null, tint = AuraColors.footnote, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** A small rounded square with the section's icon: the shelf's own, or a gradient sparkle for Now. */
@Composable
internal fun SectionBadge(section: RecoverySection, dimmed: Boolean = false, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val shape = RoundedCornerShape(size * 0.3f)
    val shelf = section.shelf
    val alpha = if (dimmed) 0.45f else 1f
    if (shelf == null) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { this.alpha = alpha }
                .clip(shape)
                .background(Brush.linearGradient(listOf(hex(0x3F92FE), hex(0x8A3EFE), hex(0xC23EF8)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(auraIcon("sparkles"), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    } else {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { this.alpha = alpha }
                .clip(shape)
                .background(shelf.color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(auraIcon(shelf.symbol), contentDescription = null, tint = shelf.color, modifier = Modifier.size(size * 0.58f))
        }
    }
}
