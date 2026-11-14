package info.ibtaura.auraclarus.metric

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.ibtaura.aura.core.AuraApi
import info.ibtaura.aura.core.FieldAvailability
import info.ibtaura.aura.core.MetricMaths
import info.ibtaura.aura.core.MetricPoint
import info.ibtaura.aura.core.MetricRange
import info.ibtaura.aura.core.MetricTrendDirection
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.BackBar
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.MetricEmptyState
import info.ibtaura.auraclarus.design.MetricRetryState
import info.ibtaura.auraclarus.design.SearchGroupCard
import info.ibtaura.auraclarus.design.SearchGroupRow
import info.ibtaura.auraclarus.design.SearchRowDivider
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.bottomPadding
import info.ibtaura.auraclarus.goals.UnitFormat
import info.ibtaura.auraclarus.goals.UnitsPage
import info.ibtaura.auraclarus.goals.rememberUnitSystem
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.PinnedMetric
import info.ibtaura.auraclarus.shell.Route
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** Section titles and footnotes start 20 dp inside the cards' edge (iOS `metricTitleInset`). */
internal val MetricTitleInset = Modifier.padding(horizontal = 20.dp)

/**
 * Frame Movement _ Walk Distance (iOS MetricView). Home's Pins open this too, knowing only the name, field and unit;
 * the format is then looked up in the catalogue.
 */
@Composable
fun MetricScreen(app: AppState, nav: AuraNav, route: Route.Metric) {
    var range by rememberSaveable { mutableStateOf(MetricRange.twoWeeks) }
    var points by remember { mutableStateOf<List<MetricPoint>?>(null) }
    var format by remember { mutableStateOf<String?>(null) }
    /** The newest value when the chosen range has none. */
    var newest by remember { mutableStateOf<FieldAvailability?>(null) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    val resolvedFormat = format ?: route.format
    val shownFormat = resolvedFormat ?: ""
    val units = rememberUnitSystem(app)
    val shownUnit = UnitFormat.shownUnit(shownFormat, route.unit, units, route.field)
    fun shown(value: Double): String = UnitFormat.display(value, shownFormat, route.unit, units, route.field)
    /** Units opens over this page (the shell has no Units route yet); back closes it. */
    var unitsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(app.dataVersion, retry, range) {
        failed = false
        try {
            val span = range.span(Instant.now())
            val found = resolvedFormat ?: metricFormat(route.field, app)
            val series = app.api.series(route.field, span.from, span.to)
            val days = MetricMaths.dailyPoints(series.points, span)
            var fallback = newest
            if (MetricMaths.latest(days) == null && fallback == null) {
                fallback = newestValue(route.field, app.api)
            }
            format = found
            points = days
            newest = fallback
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            points = null
            failed = true
        }
    }

    val loaded = points
    val bigValue: String? = when {
        loaded != null && MetricMaths.latest(loaded)?.value != null ->
            shown(MetricMaths.latest(loaded)!!.value!!)
        newest != null -> newest!!.latestNumber?.let { shown(it) } ?: newest!!.latestText
        else -> null
    }
    val heroLine: HeroLine? = run {
        if (loaded == null) return@run null
        val index = selectedIndex
        val selectedValue = index?.let { loaded.getOrNull(it)?.value }
        if (index != null && selectedValue != null) {
            return@run HeroLine(MetricMaths.shortDay(loaded[index].date),
                shown(selectedValue), shownUnit)
        }
        val average = MetricMaths.average(loaded)
        if (MetricMaths.canAverage(shownFormat) && average != null) {
            return@run HeroLine("Avg", shown(average), shownUnit)
        }
        val last = newest
        if (MetricMaths.latest(loaded) == null && last != null) {
            return@run HeroLine("Latest", MetricMaths.shortDay(last.latestDate), "")
        }
        null
    }
    val trend: Double? = if (loaded != null && MetricMaths.canAverage(shownFormat)) MetricMaths.weekTrend(loaded) else null

    if (unitsOpen) {
        BackHandler { unitsOpen = false }
        UnitsPage(app, route.name, route.field, route.unit, resolvedFormat, onBack = { unitsOpen = false })
        return
    }

    Column(Modifier.fillMaxSize().background(AuraColors.background)) {
        // White right up behind the bar, as the iOS page.
        Column(Modifier.fillMaxWidth().background(AuraColors.card).windowInsetsPadding(WindowInsets.statusBars)) {
            BackBar(route.backTitle, onBack = { nav.pop() })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Top: hero, range switch and chart on white.
            Column(Modifier.fillMaxWidth().background(AuraColors.card).padding(top = 8.dp)) {
                MetricHero(route.name, bigValue, shownUnit, heroLine, Modifier.padding(horizontal = 42.dp))
                MetricRangeSwitch(
                    range,
                    onSelect = {
                        if (it != range) {
                            range = it
                            selectedIndex = null
                        }
                    },
                    Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp),
                )
                Box(Modifier.padding(start = 36.dp, end = 36.dp, top = 22.dp, bottom = 28.dp)) {
                    when {
                        loaded != null -> Box(contentAlignment = Alignment.Center) {
                            MetricBarChart(route.name, loaded, selectedIndex, unit = shownUnit, onSelect = { index ->
                                selectedIndex = if (selectedIndex == index) null else index
                            })
                            if (MetricMaths.latest(loaded) == null) MetricEmptyState()
                        }
                        failed -> Box(Modifier.fillMaxWidth().height(MetricChartHeight), contentAlignment = Alignment.Center) {
                            MetricRetryState(onRetry = { retry += 1 })
                        }
                        else -> Box(Modifier.fillMaxWidth().height(MetricChartHeight), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
            // Lower: highlights on grey.
            Column(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 24.dp + bottomPadding()),
                verticalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                SectionTitle("Highlights", MetricTitleInset.padding(bottom = 8.dp))
                if (trend != null) MetricTrendCard(route.name, trend)
                PinRow(
                    pinned = app.isPinned(route.field),
                    onToggle = { app.togglePin(PinnedMetric(route.field, route.name, route.unit)) },
                )
                MetricLinksCard(
                    onAllData = { nav.push(Route.Recorded(route.name, route.field, route.unit, resolvedFormat)) },
                    onSources = { nav.push(Route.Sources(route.name)) },
                    onUnits = { unitsOpen = true },
                )
                MetricAboutCard(route.name)
                Footnote("Your ${route.name} data is stored in your Aura Clarus account in the cloud.", MetricTitleInset)
            }
        }
    }
}

/** A page opened from a pin knows no format, so it comes from the catalogue. Empty when the field isn't listed. */
internal suspend fun metricFormat(field: String, app: AppState): String {
    for (section in app.loadCatalogue()) {
        section.parameters.firstOrNull { it.field == field }?.let { return it.format }
    }
    return ""
}

/** Only for the hero line, so a failure here leaves the page as it is. */
private suspend fun newestValue(field: String, api: AuraApi): FieldAvailability? = try {
    val span = MetricMaths.daySpan(MetricMaths.availabilityDays, Instant.now())
    api.availability(span.from, span.to).firstOrNull { it.field == field }
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    null
}

private data class HeroLine(val label: String, val value: String, val unit: String)

/** The name, the big newest value and the line under it. */
@Composable
private fun MetricHero(name: String, value: String?, unit: String, line: HeroLine?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = AuraType.system(27, FontWeight.Bold).copy(letterSpacing = (-0.3).sp), color = Color.Black)
        if (value != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    value,
                    style = AuraType.system(48, FontWeight.Bold),
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                )
                if (unit.isNotEmpty()) {
                    Text(unit, style = AuraType.callout, color = AuraColors.metricGrey, modifier = Modifier.alignByBaseline())
                }
            }
        }
        if (line != null) {
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).background(AuraColors.burnout, CircleShape))
                Text(line.label, style = AuraType.caption, color = AuraColors.metricGrey)
                Text(line.value, style = AuraType.calloutSemibold, color = AuraColors.burnout)
                if (line.unit.isNotEmpty()) Text(line.unit, style = AuraType.caption, color = AuraColors.metricGrey)
            }
        }
    }
}

/** "Trending Up", the change, and what the change compares. */
@Composable
private fun MetricTrendCard(name: String, percent: Double) {
    val direction = MetricMaths.trendDirection(percent)
    val symbol = when (direction) {
        MetricTrendDirection.up -> "arrow.up.right"
        MetricTrendDirection.steady -> "arrow.right"
        MetricTrendDirection.down -> "arrow.down.right"
    }
    val sentence = when (direction) {
        MetricTrendDirection.up -> "$name averaged higher over the last 7 days than the 7 days before."
        MetricTrendDirection.steady -> "$name stayed about level over the last 7 days compared with the 7 days before."
        MetricTrendDirection.down -> "$name averaged lower over the last 7 days than the 7 days before."
    }
    AuraCard(padding = 22.dp, spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(auraIcon(symbol), contentDescription = null, tint = AuraColors.burnout, modifier = Modifier.size(24.dp))
            Text(MetricMaths.trendTitle(percent), style = AuraType.system(22, FontWeight.SemiBold), color = Color.Black)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(MetricMaths.trendPercentText(percent), style = AuraType.system(26, FontWeight.SemiBold),
                color = Color.Black, modifier = Modifier.alignByBaseline())
            Text("from last week", style = AuraType.caption, color = AuraColors.metricGrey, modifier = Modifier.alignByBaseline())
        }
        Text(sentence, style = AuraType.callout, color = AuraColors.metricGrey)
    }
}

/** "Pin to Home" on a white capsule; the pin turns blue when pinned. */
@Composable
private fun PinRow(pinned: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(CircleShape)
            .background(AuraColors.card)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (pinned) "Pinned" else "Not pinned" }
            .testTag("metric-pin")
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Pin to Home", style = AuraType.row, color = Color.Black)
        Spacer(Modifier.weight(1f))
        Icon(
            auraIcon(if (pinned) "pin.fill" else "pin"),
            contentDescription = null,
            tint = if (pinned) AuraColors.blue else AuraColors.metricGrey,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Show All Data, Data Sources & Access and Units. */
@Composable
private fun MetricLinksCard(onAllData: () -> Unit, onSources: () -> Unit, onUnits: () -> Unit) {
    SearchGroupCard {
        SearchGroupRow("Show All Data", Modifier.testTag("metric-row-all-data"), onClick = onAllData)
        SearchRowDivider(leading = 20.dp)
        SearchGroupRow("Data Sources & Access", Modifier.testTag("metric-row-sources"), onClick = onSources)
        SearchRowDivider(leading = 20.dp)
        SearchGroupRow("Units", Modifier.testTag("metric-row-units"), onClick = onUnits)
    }
}

@Composable
private fun MetricAboutCard(name: String) {
    AuraCard(padding = 22.dp, spacing = 8.dp) {
        Text("About $name", style = AuraType.system(18, FontWeight.SemiBold), color = Color.Black)
        Text(
            "$name is one value a day from your Aura Clarus account. The big number is the newest value, and Avg is " +
                "the average of the days with data in the range you pick.",
            style = AuraType.callout,
            color = AuraColors.metricGrey,
        )
    }
}
