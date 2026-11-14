package info.ibtaura.auraclarus.search

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.CatalogueParameter
import info.ibtaura.aura.core.CatalogueSection
import info.ibtaura.aura.core.FieldAvailability
import info.ibtaura.aura.core.MetricAvailableRow
import info.ibtaura.aura.core.MetricCategoryRows
import info.ibtaura.aura.core.MetricMaths
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.MetricEmptyState
import info.ibtaura.auraclarus.design.MetricRetryState
import info.ibtaura.auraclarus.design.SearchCapsule
import info.ibtaura.auraclarus.design.SearchCategoryStyle
import info.ibtaura.auraclarus.design.SearchGroupCard
import info.ibtaura.auraclarus.design.SearchRowDivider
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** Section titles and footnotes start 20 dp inside the cards' edge (iOS `metricTitleInset`). */
internal val TitleInset = Modifier.padding(horizontal = 20.dp)

/** Frame Search _ Movement (iOS SearchCategoryView): rows with data, the About text, then the rows with none. */
@Composable
fun SearchCategoryScreen(app: AppState, nav: AuraNav, route: Route.SearchCategory) {
    var query by rememberSaveable { mutableStateOf("") }
    var section by remember {
        mutableStateOf(app.catalogue?.firstOrNull { it.id == route.sectionId })
    }
    var availability by remember { mutableStateOf<List<FieldAvailability>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val go: (Route) -> Unit = { target ->
        focus.clearFocus()
        nav.push(target)
    }

    LaunchedEffect(app.dataVersion, retry) {
        failed = false
        try {
            if (section == null) {
                section = app.loadCatalogue().firstOrNull { it.id == route.sectionId }
                    ?: CatalogueSection(route.sectionId, route.title, emptyList())
            }
            val span = MetricMaths.daySpan(MetricMaths.availabilityDays, Instant.now())
            availability = app.api.availability(span.from, span.to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = true
        }
    }

    AuraPage(title = "Search", onBack = { nav.pop() }) {
        SearchCapsule(query, { query = it }, fieldModifier = Modifier.testTag("category-search-field"))
        val shown = section
        val loaded = availability
        when {
            shown != null && loaded != null -> {
                val wanted = shown.parameters.filter { MetricMaths.matches(query, it.name) }
                SearchCategoryLists(shown, MetricMaths.categoryRows(wanted, loaded), query.isNotBlank(), go)
            }
            failed -> MetricRetryState(onRetry = { retry += 1 })
            else -> SearchSpinner()
        }
    }
}

@Composable
private fun SearchCategoryLists(
    section: CatalogueSection,
    rows: MetricCategoryRows,
    isFiltering: Boolean,
    go: (Route) -> Unit,
) {
    if (rows.available.isEmpty() && rows.empty.isEmpty()) {
        MetricEmptyState(if (isFiltering) "No results" else "No data yet")
    }
    if (rows.available.isNotEmpty()) {
        SectionTitle("Available Data", TitleInset)
        SearchGroupCard {
            rows.available.forEachIndexed { index, row ->
                if (index > 0) SearchRowDivider()
                SearchAvailableRow(section, row, go)
            }
        }
    }
    if (!isFiltering) SearchCategoryAbout(section)
    if (rows.empty.isNotEmpty()) {
        SectionTitle("No Data Available", TitleInset)
        SearchGroupCard {
            rows.empty.forEachIndexed { index, parameter ->
                if (index > 0) SearchRowDivider(leading = 20.dp)
                SearchNoDataRow(section.title, parameter, go)
            }
        }
        Footnote(
            "These parameters have no recorded data yet. They move up into the section above once data comes in.",
            TitleInset,
        )
    }
}

/** Only the Movement frame has About text, so other categories show none. */
@Composable
private fun SearchCategoryAbout(section: CatalogueSection) {
    if (section.id != "movement") return
    AuraCard(padding = 22.dp, spacing = 8.dp) {
        Text("About Movement", style = AuraType.system(22, FontWeight.SemiBold), color = Color.Black)
        Text(
            "Movement tracks how much you move through the day, including steps, distance, active energy, exercise " +
                "minutes, and time on your feet. These signals combine into your daily activity picture and feed your trends.",
            style = AuraType.callout,
            color = AuraColors.metricGrey,
        )
    }
    Footnote("Movement data is stored in your Aura Clarus account in the cloud, one value a day.", TitleInset)
}

private fun metricRoute(parameter: CatalogueParameter, field: String, backTitle: String) =
    Route.Metric(parameter.name, field, parameter.unit, parameter.format, backTitle)

@Composable
private fun SearchAvailableRow(section: CatalogueSection, row: MetricAvailableRow, go: (Route) -> Unit) {
    val style = SearchCategoryStyle.of(section.id)
    val parameter = row.parameter
    val field = parameter.field
    if (parameter.hasSeries && field != null) {
        SearchListRow(
            parameter.name,
            Modifier.testTag("metric-$field"),
            symbol = style.symbol,
            symbolColor = style.color,
            onClick = { go(metricRoute(parameter, field, section.title)) },
        )
    } else {
        SearchListRow(parameter.name, symbol = style.symbol, symbolColor = style.color, showsChevron = false)
    }
}

@Composable
private fun SearchNoDataRow(sectionTitle: String, parameter: CatalogueParameter, go: (Route) -> Unit) {
    val field = parameter.field
    if (parameter.hasSeries && field != null) {
        SearchListRow(
            parameter.name,
            Modifier.testTag("metric-$field"),
            onClick = { go(metricRoute(parameter, field, sectionTitle)) },
        )
    } else {
        SearchListRow(parameter.name, showsChevron = false)
    }
}
