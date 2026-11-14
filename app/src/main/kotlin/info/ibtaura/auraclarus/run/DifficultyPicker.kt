package info.ibtaura.auraclarus.run

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.Difficulty
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType

/**
 * How hard a focus session was, Easy to Very hard: five chips in two rows (Easy, Light, Normal / Hard, Very hard), so
 * all of them show on a phone. The question above it is the caller's, and so is the side padding. Each chip is tagged
 * `difficulty-<raw>` (iOS DifficultyPicker).
 */
@Composable
fun DifficultyPicker(selected: Difficulty?, onPick: (Difficulty) -> Unit) {
    val rows = Difficulty.entries.chunked(3)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { difficulty ->
                    DifficultyChip(difficulty, difficulty == selected, Modifier.weight(1f)) { onPick(difficulty) }
                }
                // The grid keeps three columns, so the second row's chips stay the first row's width.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DifficultyChip(difficulty: Difficulty, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(CircleShape)
            .background(if (isSelected) AuraColors.blue else AuraColors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = isSelected }
            .testTag("difficulty-${difficulty.raw}")
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            difficulty.label,
            style = AuraType.system(15, if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (isSelected) Color.White else Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
