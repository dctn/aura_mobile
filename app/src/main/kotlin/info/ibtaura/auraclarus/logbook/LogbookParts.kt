package info.ibtaura.auraclarus.logbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SentimentVeryDissatisfied
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.hex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Pieces the Logging page, its category pages and the Calorie Tracker share. iOS look: white cards, a coloured icon and
// word over black text, blue pills, grey footnotes.

/** The coloured icon and word at the top of a card (the Figma's "Sleep", "Burnout Risk"). */
@Composable
internal fun CardHeader(symbol: String, title: String, color: Color, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(auraIcon(symbol), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(title, style = AuraType.calloutSemibold, color = color, modifier = Modifier.weight(1f), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** One input card on a category page: header, a question, the inputs, then [footer] (usually a [SaveLine]). */
@Composable
internal fun LogInputCard(
    symbol: String,
    title: String,
    color: Color,
    question: String?,
    last: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AuraCard(modifier, padding = 22.dp, spacing = 14.dp) {
        CardHeader(symbol, title, color) {
            if (last != null) Text(last, style = AuraType.caption, color = AuraColors.gray, maxLines = 1)
        }
        if (question != null) Text(question, style = AuraType.bodyMedium, color = Color.Black)
        content()
    }
}

/** Numbered chips from [range] in one row, the picked one blue. Words under the ends when given. */
@Composable
internal fun ScaleChips(
    range: IntRange,
    selected: Int?,
    onSelect: (Int) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    words: Pair<String, String>? = null,
    color: Color = AuraColors.blue,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val compact = range.count() > 7
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 8.dp)) {
            range.forEach { value ->
                val isOn = value == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (compact) 36.dp else 42.dp)
                        .clip(RoundedCornerShape(if (compact) 10.dp else 14.dp))
                        .background(if (isOn) color else AuraColors.track)
                        .clickable(role = Role.RadioButton) { onSelect(value) }
                        .semantics { this.selected = isOn }
                        .testTag("$tag-$value"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$value",
                        style = AuraType.system(if (compact) 14 else 17, if (isOn) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (isOn) Color.White else Color.Black,
                    )
                }
            }
        }
        if (words != null) {
            Row(Modifier.fillMaxWidth()) {
                Text(words.first, style = AuraType.caption, color = AuraColors.gray, modifier = Modifier.weight(1f))
                Text(words.second, style = AuraType.caption, color = AuraColors.gray, textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Capsule choices that wrap two to a row, the picked one blue. */
@Composable
internal fun ChoiceChips(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    tag: String,
    perRow: Int = 2,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    Chip(option, option == selected, "$tag-${option.lowercase(Locale.ROOT).replace(' ', '-')}",
                        Modifier.weight(1f)) { onSelect(option) }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A capsule chip that turns blue when picked. */
@Composable
internal fun Chip(title: String, isSelected: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (isSelected) AuraColors.blue else AuraColors.track)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = isSelected }
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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

private val faces: List<ImageVector> = listOf(
    Icons.Filled.SentimentVeryDissatisfied,
    Icons.Filled.SentimentDissatisfied,
    Icons.Filled.SentimentNeutral,
    Icons.Filled.SentimentSatisfied,
    Icons.Filled.SentimentVerySatisfied,
)

private val faceColors: List<Color> = listOf(hex(0xFF3B30), hex(0xFF9500), hex(0xFFCC00), hex(0x8BD346), hex(0x34C759))

/** Five faces, 1 (low) to 5. The one given today is filled in its colour. */
@Composable
internal fun FaceRow(selected: Int?, words: List<String>, enabled: Boolean, tag: String, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (1..5).forEach { value ->
            val isOn = value == selected
            val color = faceColors[value - 1]
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (isOn) color.copy(alpha = 0.18f) else AuraColors.track)
                    .then(if (isOn) Modifier.border(2.dp, color, CircleShape) else Modifier)
                    .clickable(enabled = enabled, role = Role.Button) { onPick(value) }
                    .semantics {
                        contentDescription = words.getOrElse(value - 1) { "$value" }
                        this.selected = isOn
                    }
                    .testTag("$tag-$value"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(faces[value - 1], contentDescription = null, tint = if (isOn) color else AuraColors.gray,
                    modifier = Modifier.size(30.dp))
            }
        }
    }
}

/** A label, the value with − and + beside it (SwiftUI's Stepper). */
@Composable
internal fun StepperRow(
    label: String,
    value: Int,
    unit: String,
    range: IntRange,
    step: Int,
    tag: String,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = AuraType.bodyMedium, color = Color.Black, modifier = Modifier.weight(1f), maxLines = 2)
        Text(if (unit.isEmpty()) "$value" else "$value $unit", style = AuraType.system(17, FontWeight.SemiBold), color = Color.Black)
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(AuraColors.track), verticalAlignment = Alignment.CenterVertically) {
            StepHalf("−", "Less", "$tag-minus", value - step >= range.first) { onChange((value - step).coerceAtLeast(range.first)) }
            Box(Modifier.width(1.dp).height(18.dp).background(AuraColors.separator))
            StepHalf("+", "More", "$tag-plus", value + step <= range.last) { onChange((value + step).coerceAtMost(range.last)) }
        }
    }
}

@Composable
private fun StepHalf(sign: String, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, style = AuraType.system(22, FontWeight.Medium), color = if (enabled) Color.Black else AuraColors.footnote)
    }
}

/** A grey capsule number field with its unit at the end. Decimal keyboard; a comma counts as a point. */
@Composable
internal fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    unit: String,
    tag: String,
    modifier: Modifier = Modifier,
    decimals: Boolean = true,
) {
    Row(
        modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(14.dp)).background(AuraColors.track).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = AuraType.field, color = AuraColors.footnote, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = { text ->
                    val cleaned = text.replace(',', '.').filter { it.isDigit() || (decimals && it == '.') }
                    if (cleaned.count { it == '.' } <= 1 && cleaned.length <= 7) onValueChange(cleaned)
                },
                singleLine = true,
                textStyle = AuraType.field.copy(color = Color.Black),
                keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag(tag),
            )
        }
        if (unit.isNotEmpty()) Text(unit, style = AuraType.callout, color = AuraColors.gray, maxLines = 1)
    }
}

/** A label over a [NumberField], for forms of several numbers. */
@Composable
internal fun LabeledNumber(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    tag: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    decimals: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = AuraType.caption, color = AuraColors.gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        NumberField(value, onValueChange, placeholder, unit, tag, decimals = decimals)
    }
}

/** What a save says: an error in red, a confirmation in green, else nothing. Then the Save pill. */
@Composable
internal fun SaveLine(
    saving: Boolean,
    enabled: Boolean,
    error: String?,
    saved: String?,
    tag: String,
    title: String = "Save",
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { Text(it, style = AuraType.callout, color = AuraColors.red, modifier = Modifier.testTag("$tag-error")) }
        saved?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(auraIcon("checkmark.circle.fill"), null, tint = AuraColors.green, modifier = Modifier.size(18.dp))
                Text(it, style = AuraType.calloutMedium, color = AuraColors.green, modifier = Modifier.testTag("$tag-saved"))
            }
        }
        PillButton(if (saving) "Saving..." else title, onClick = onSave, fills = true, enabled = enabled && !saving,
            modifier = Modifier.testTag(tag))
    }
}

/** A calm empty state: an icon in a soft circle, one line, and an optional action. */
@Composable
internal fun LogEmptyState(symbol: String, color: Color, text: String, action: String? = null, tag: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(auraIcon(symbol), null, tint = color, modifier = Modifier.size(28.dp))
        }
        Text(text, style = AuraType.callout, color = AuraColors.gray, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Text(
                action,
                style = AuraType.calloutSemibold,
                color = AuraColors.blue,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onAction)
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** The small grey spinner a loading section shows. */
@Composable
internal fun SmallSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    }
}

/** A thin capsule progress bar. */
@Composable
internal fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(AuraColors.track)) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

/** A small rounded tile with an icon, for history rows. */
@Composable
internal fun SmallIconTile(symbol: String, color: Color, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).aspectRatio(1f).clip(RoundedCornerShape(size * 0.3f)).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(auraIcon(symbol), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

/** The scrolling column inside a sheet, padded as AuraPage. */
@Composable
internal fun SheetColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/** Words for when things happened. */
internal object LogTime {
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val dayMonth = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun clock(at: Instant): String = clock.format(at.atZone(zone))

    /** "Today", "Yesterday", "Tue 12 Nov". */
    fun day(date: LocalDate, today: LocalDate = LocalDate.now(zone)): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dayMonth.format(date)
    }

    /** "Today 14:05", "Tue 09:12" within a week. */
    fun whenText(at: Instant, today: LocalDate = LocalDate.now(zone)): String {
        val date = at.atZone(zone).toLocalDate()
        val day = when {
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            date.isAfter(today.minusDays(7)) -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            else -> dayMonth.format(date)
        }
        return "$day ${clock(at)}"
    }

    /** "M", "T": a day's initial, for chart labels. */
    fun initial(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())

    /** "1,240". */
    fun grouped(value: Int): String = String.format(Locale.getDefault(), "%,d", value)
}
