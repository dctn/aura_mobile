package info.ibtaura.auraclarus.metric

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.MetricMaths
import info.ibtaura.aura.core.MetricPoint
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraLazyPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.MetricEmptyState
import info.ibtaura.auraclarus.design.MetricRetryState
import info.ibtaura.auraclarus.design.SearchCapsule
import info.ibtaura.auraclarus.design.SearchRowDivider
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import kotlinx.coroutines.CancellationException
import java.time.Instant

private val Gap = 18.dp

/**
 * Frame Walk Distance _ All Recorded Data (iOS MetricRecordedView). The backend has daily points only, so rows show
 * dates, not times. Up to a year of rows, so the card is drawn row by row in a lazy list.
 */
@Composable
fun RecordedScreen(app: AppState, nav: AuraNav, route: Route.Recorded) {
    var query by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<MetricPoint>?>(null) }
    var format by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(app.dataVersion, retry) {
        failed = false
        try {
            val span = MetricMaths.daySpan(MetricMaths.recordedDays, Instant.now())
            val found = route.format ?: metricFormat(route.field, app)
            val series = app.api.series(route.field, span.from, span.to)
            format = found
            rows = MetricMaths.recordedRows(series.points)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = true
        }
    }

    fun text(point: MetricPoint): String {
        val number = MetricMaths.display(point.value ?: 0.0, format, route.unit)
        val unit = MetricMaths.shownUnit(format, route.unit)
        return if (unit.isEmpty()) number else "$number $unit"
    }

    val loaded = rows
    val shown = loaded?.filter { MetricMaths.matches(query, MetricMaths.shortDay(it.date) + " " + text(it)) }

    AuraLazyPage(title = route.name, onBack = { nav.pop() }, spacing = 0.dp) {
        item(key = "field") {
            SearchCapsule(query, { query = it }, fieldModifier = Modifier.testTag("recorded-search-field"))
        }
        item(key = "title") {
            SectionTitle("All Recorded Data", MetricTitleInset.padding(top = Gap, bottom = Gap))
        }
        when {
            shown != null && shown.isEmpty() -> item(key = "empty") {
                MetricEmptyState(if (loaded.isEmpty()) "No data yet" else "No results")
            }
            shown != null -> {
                val last = shown.lastIndex
                itemsIndexed(shown, key = { _, point -> point.date }) { index, point ->
                    RecordRowInCard(
                        day = MetricMaths.shortDay(point.date),
                        value = text(point),
                        first = index == 0,
                        last = index == last,
                    )
                }
            }
            failed -> item(key = "retry") { MetricRetryState(onRetry = { retry += 1 }) }
            else -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                }
            }
        }
        item(key = "footnote") {
            Footnote(
                "One ${route.name} value a day from the last 12 months, newest first, stored in your Aura Clarus account in the cloud.",
                MetricTitleInset.padding(top = Gap),
            )
        }
    }
}

/** One row of the white card, with the card's rounded top on the first row and rounded bottom on the last. */
@Composable
private fun RecordRowInCard(day: String, value: String, first: Boolean, last: Boolean) {
    val shape = RoundedCornerShape(
        topStart = if (first) 30.dp else 0.dp,
        topEnd = if (first) 30.dp else 0.dp,
        bottomStart = if (last) 30.dp else 0.dp,
        bottomEnd = if (last) 30.dp else 0.dp,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(AuraColors.card, shape)
            .padding(top = if (first) 7.dp else 0.dp, bottom = if (last) 7.dp else 0.dp),
    ) {
        if (!first) SearchRowDivider(leading = 20.dp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(day, style = AuraType.system(16, FontWeight.Medium), color = Color.Black)
            Spacer(Modifier.weight(1f).width(8.dp))
            Text(value, style = AuraType.system(16, FontWeight.SemiBold), color = AuraColors.burnout, maxLines = 1)
        }
    }
}

