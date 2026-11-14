package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The days Calendar can show: yesterday (-1), today (0) and the next 13 days. */
internal object EventDays {
    const val FIRST = -1
    const val LAST = 13

    val offsets: IntRange = FIRST..LAST

    private val shortDay: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
    private val longDay: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())

    /** "Yesterday", "Today", "Tomorrow", then "Wed 19". */
    fun chipTitle(offset: Int, today: LocalDate): String = when (offset) {
        -1 -> "Yesterday"
        0 -> "Today"
        1 -> "Tomorrow"
        else -> shortDay.format(today.plusDays(offset.toLong()))
    }

    /** The day's group title: "Yesterday", "Today", "Tomorrow", else "Wednesday, November 19". */
    fun title(date: LocalDate, today: LocalDate): String = when (date) {
        today.minusDays(1) -> "Yesterday"
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> longDay.format(date)
    }

    /** What TalkBack reads for a chip: the whole date from the day after tomorrow on. */
    fun spoken(offset: Int, today: LocalDate): String =
        if (offset in -1..1) chipTitle(offset, today) else longDay.format(today.plusDays(offset.toLong()))
}

/**
 * Calendar's day chips: Yesterday, Today, Tomorrow, then "Wed 19" up to 13 days ahead. They scroll sideways; the
 * picked one is blue. Each chip is tagged "calendar-day-<offset>" (-1 for yesterday).
 */
@Composable
internal fun EventDayPicker(selected: Int, today: LocalDate, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    // Coming back to a later day, it is scrolled roughly into view.
    val chipStep = with(LocalDensity.current) { 96.dp.roundToPx() }
    LaunchedEffect(Unit) {
        if (selected > 2) scroll.scrollTo(chipStep * (selected - 1))
    }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .selectableGroup()
            .testTag("calendar-days"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (offset in EventDays.offsets) {
            val isSelected = offset == selected
            val spoken = EventDays.spoken(offset, today)
            Box(
                Modifier
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) AuraColors.blue else AuraColors.card)
                    .clickable(role = Role.Tab) { onSelect(offset) }
                    .semantics {
                        contentDescription = spoken
                        this.selected = isSelected
                    }
                    .testTag("calendar-day-$offset")
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    EventDays.chipTitle(offset, today),
                    style = AuraType.system(15, if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (isSelected) Color.White else Color.Black,
                    maxLines = 1,
                )
            }
        }
    }
}
