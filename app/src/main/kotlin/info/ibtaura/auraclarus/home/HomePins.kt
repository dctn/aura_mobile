package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.FieldAvailability
import info.ibtaura.aura.core.MetricMaths
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.ListRowCard
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.PinnedMetric
import info.ibtaura.auraclarus.shell.Route
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** Pins: the person's own pinned metrics with their latest values (iOS HomePinsSection). */
@Composable
internal fun HomePinsSection(app: AppState, nav: AuraNav) {
    var latest by remember { mutableStateOf<Map<String, FieldAvailability>>(emptyMap()) }
    // Each field's catalogue format, so a value in metres shows as km.
    var formats by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val pins = app.pins
    // Also loads again when a pin is added from a metric's page.
    val fields = pins.joinToString(",") { it.field }

    LaunchedEffect(app.dataVersion, retry, fields) {
        if (pins.isEmpty()) {
            failed = false
            return@LaunchedEffect
        }
        val today = LocalDate.now(DayKey.zone)
        try {
            // The backend allows at most 400 days.
            val available = app.api.availability(DayKey.string(today.minusDays(365)), DayKey.string(today))
            latest = available.associateBy { it.field }
            failed = false
            // Values still show without formats, so a failure here isn't an error on the page.
            if (formats.isEmpty()) {
                val sections = try {
                    app.loadCatalogue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                if (sections != null) {
                    formats = sections.flatMap { it.parameters }.mapNotNull { p -> p.field?.let { it to p.format } }.toMap()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = latest.isEmpty()
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pins", style = AuraType.title, color = Color.Black, modifier = Modifier.semantics { heading() })
            if (pins.isNotEmpty()) {
                // The small blue circle, with a larger area to tap.
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = if (editing) "Done editing pins" else "Edit pins") {
                            editing = !editing
                        }
                        .testTag("home-pins-edit")
                        .padding(9.dp),
                ) {
                    Box(Modifier.size(26.dp).background(AuraColors.blue, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(
                            auraIcon(if (editing) "checkmark" else "square.and.pencil"),
                            contentDescription = if (editing) "Done editing pins" else "Edit pins",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
        if (pins.isEmpty()) {
            Text("You can pin a metric from its page.", style = AuraType.callout, color = AuraColors.footnote)
        } else {
            pins.forEach { pin ->
                val availability = latest[pin.field]
                val format = formats[pin.field] ?: ""
                // Null hides the value: no number is better than a made-up one.
                val value = availability?.let { MetricMaths.latestText(it, format, pin.unit) }
                val unit = if (availability?.latestNumber == null) null else MetricMaths.shownUnit(format, pin.unit)
                if (editing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            auraIcon("minus.circle.fill"),
                            contentDescription = "Remove ${pin.name}",
                            tint = AuraColors.red,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) {
                                    app.togglePin(pin)
                                    if (app.pins.isEmpty()) editing = false
                                },
                        )
                        PinCard(pin, value, unit, Modifier.weight(1f))
                    }
                } else {
                    PinCard(
                        pin, value, unit,
                        Modifier.testTag("home-pin-${pin.field}"),
                        onClick = { nav.push(Route.Metric(name = pin.name, field = pin.field, unit = pin.unit)) },
                    )
                }
            }
        }
        if (failed) {
            HomeRetryLine("Couldn't load the latest values.", retry = { retry += 1 })
        }
        Footnote(HomeCopy.footnote, linkTitle = HomeCopy.privacyLink)
    }
}

@Composable
private fun PinCard(pin: PinnedMetric, value: String?, unit: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    ListRowCard(
        pin.name,
        modifier,
        symbol = "arrow.clockwise.heart.fill",
        value = value,
        unit = unit,
        showsChevron = false,
        onClick = onClick,
    )
}
