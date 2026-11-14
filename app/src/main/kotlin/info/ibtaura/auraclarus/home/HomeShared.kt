package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.CalendarEvent
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.ZoneText
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Pieces several Home files share (iOS HomeView.swift's HomeCopy and HomeRetryLine, HomeInsightsSection's
// HomeEventRow, CalendarZones' ZoneCardSection). Other home/ files may call them; don't redefine them.

/** The two lines of copy several sections share. */
internal object HomeCopy {
    const val footnote =
        "Each against your own normal (100) over the last 14 days. The band is your usual range. For more information, see the"

    /** There's no URL yet, so the link stays inactive. */
    const val privacyLink = "Adarna privacy policy"
}

/** A small line shown when a section couldn't load. */
@Composable
internal fun HomeRetryLine(text: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = AuraType.callout, color = AuraColors.footnote)
        Text(
            "Try again",
            style = AuraType.calloutMedium,
            color = AuraColors.blue,
            modifier = Modifier.clickable(role = Role.Button, onClick = retry),
        )
    }
}

/** The colour of an event's bar by kind: recovery green, meeting light blue, else the outer ring's blue. */
internal fun homeEventColor(kind: String): Color = when (kind) {
    "recovery" -> AuraColors.green
    "meeting" -> AuraColors.runMeeting
    else -> AuraColors.ringOuter
}

private val spokenTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** "Title, 10:00 AM to 11:00 AM": how an event row is read aloud (and found by the walks). */
internal fun homeEventSpoken(event: CalendarEvent): String {
    val zone = ZoneId.systemDefault()
    return "${event.title}, ${spokenTime.format(event.start.atZone(zone))} to ${spokenTime.format(event.end.atZone(zone))}"
}

/** A coloured bar, the time range and the title. Also used by the Calendar list. */
@Composable
internal fun HomeEventRow(event: CalendarEvent, modifier: Modifier = Modifier) {
    // Always 24-hour: a 12-hour locale with the AM/PM left off showed 15:51 as "03:51".
    val timeRange = "${ZoneText.clock(event.start)} - ${ZoneText.clock(event.end)}"
    val spoken = homeEventSpoken(event)
    Row(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 28.dp).background(homeEventColor(event.kind), RoundedCornerShape(1.5.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(timeRange, style = AuraType.system(11), color = AuraColors.gray, maxLines = 1)
            Text(event.title, style = AuraType.calloutMedium, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A bold 20pt title over a white card, like Calendar's day groups. */
@Composable
internal fun ZoneCardSection(
    title: String,
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = AuraType.system(20, FontWeight.Bold), color = Color.Black, modifier = Modifier.semantics { heading() })
        AuraCard(padding = padding, spacing = 12.dp, content = content)
    }
}
