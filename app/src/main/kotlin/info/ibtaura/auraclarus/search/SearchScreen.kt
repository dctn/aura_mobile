package info.ibtaura.auraclarus.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.ibtaura.aura.core.CatalogueSection
import info.ibtaura.aura.core.MetricMaths
import info.ibtaura.aura.core.MetricSearchHit
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Chevron
import info.ibtaura.auraclarus.design.MetricEmptyState
import info.ibtaura.auraclarus.design.MetricRetryState
import info.ibtaura.auraclarus.design.SearchCapsule
import info.ibtaura.auraclarus.design.SearchCategoryStyle
import info.ibtaura.auraclarus.design.SearchGroupCard
import info.ibtaura.auraclarus.design.SearchRowDivider
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import kotlinx.coroutines.CancellationException

/** Frame Search _ All categories (iOS SearchView): the categories, then the other places Search reaches. */
@Composable
fun SearchScreen(app: AppState, nav: AuraNav) {
    var query by rememberSaveable { mutableStateOf("") }
    var sections by remember { mutableStateOf(app.catalogue) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val go: (Route) -> Unit = { route ->
        focus.clearFocus()
        nav.push(route)
    }

    LaunchedEffect(app.dataVersion, retry) {
        failed = false
        try {
            sections = app.loadCatalogue(force = retry > 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = true
        }
    }

    val isSearching = query.isNotBlank()
    AuraPage(spacing = 23.dp) {
        Text(
            "Search",
            style = AuraType.system(34, FontWeight.Bold).copy(letterSpacing = 0.4.sp),
            color = Color.Black,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        SearchCapsule(query, { query = it }, fieldModifier = Modifier.testTag("search-field"))
        if (isSearching) {
            SearchHitsList(query, sections.orEmpty(), go)
        } else {
            val loaded = sections
            when {
                loaded != null && loaded.isEmpty() -> MetricEmptyState("No categories yet")
                loaded != null -> SearchCategoriesCard(loaded, go)
                failed -> MetricRetryState(onRetry = { retry += 1 })
                else -> SearchSpinner()
            }
            SearchPlacesList(go)
        }
    }
}

/** The iOS `ProgressView()` with 40 pt above and below. */
@Composable
internal fun SearchSpinner() {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
    }
}

/** The 25 categories. */
@Composable
private fun SearchCategoriesCard(sections: List<CatalogueSection>, go: (Route) -> Unit) {
    SearchGroupCard {
        sections.forEachIndexed { index, section ->
            if (index > 0) SearchRowDivider()
            val style = SearchCategoryStyle.of(section.id)
            SearchListRow(
                section.title,
                Modifier.testTag("category-${section.id}"),
                symbol = style.symbol,
                symbolColor = style.color,
                onClick = { go(Route.SearchCategory(section.id, section.title)) },
            )
        }
    }
}

/** The other places Search reaches, under the categories. */
private data class SearchPlace(val title: String, val symbol: String, val color: Color, val route: Route) {
    companion object {
        val group = listOf(
            SearchPlace("Logging", "square.and.pencil", SearchCategoryStyle.orange, Route.Logging),
            SearchPlace("Goals", "target", AuraColors.green, Route.Goals),
            SearchPlace("Sessions", "clock.arrow.circlepath", AuraColors.indigo, Route.SessionHistory),
            SearchPlace("Modes", "bolt.circle.fill", AuraColors.red, Route.Modes),
            SearchPlace("Calorie Tracker", "fork.knife", SearchCategoryStyle.orange, Route.CalorieTracker),
        )
        val settings = SearchPlace("Settings", "gearshape.fill", SearchCategoryStyle.grey, Route.Settings)
        val documents = SearchPlace("Clinical Documents", "doc.text.fill", SearchCategoryStyle.blue, Route.ClinicalDocuments)
        val all = group + listOf(settings, documents)
    }
}

@Composable
private fun SearchPlaceRows(places: List<SearchPlace>, go: (Route) -> Unit) {
    SearchGroupCard {
        places.forEachIndexed { index, place ->
            if (index > 0) SearchRowDivider()
            SearchListRow(
                place.title,
                Modifier.testTag("search-place-${place.title}"),
                symbol = place.symbol,
                symbolColor = place.color,
                onClick = { go(place.route) },
            )
        }
    }
}

/** A place on its own white capsule: Settings and Clinical Documents. */
@Composable
private fun SearchPlaceCapsule(place: SearchPlace, go: (Route) -> Unit) {
    SearchCapsuleRow(
        place.title,
        Modifier.testTag("search-place-${place.title}"),
        symbol = place.symbol,
        symbolColor = place.color,
        onClick = { go(place.route) },
    )
}

@Composable
private fun SearchPlacesList(go: (Route) -> Unit) {
    SearchPlaceRows(SearchPlace.group, go)
    SearchPlaceCapsule(SearchPlace.settings, go)
    SearchPlaceCapsule(SearchPlace.documents, go)
}

/** What typing shows: matching categories, metrics and places. */
@Composable
private fun SearchHitsList(query: String, sections: List<CatalogueSection>, go: (Route) -> Unit) {
    val found = MetricMaths.search(query, sections)
    val places = SearchPlace.all.filter { MetricMaths.matches(query, it.title) }
    if (found.categories.isEmpty() && found.metrics.isEmpty() && places.isEmpty()) {
        MetricEmptyState("No results")
    }
    if (found.categories.isNotEmpty()) SearchCategoriesCard(found.categories, go)
    if (found.metrics.isNotEmpty()) {
        SearchGroupCard {
            found.metrics.forEachIndexed { index, hit ->
                if (index > 0) SearchRowDivider()
                SearchMetricHitRow(hit, go)
            }
        }
    }
    if (places.isNotEmpty()) SearchPlaceRows(places, go)
}

@Composable
private fun SearchMetricHitRow(hit: MetricSearchHit, go: (Route) -> Unit) {
    val style = SearchCategoryStyle.of(hit.sectionId)
    val parameter = hit.parameter
    val field = parameter.field
    if (parameter.hasSeries && field != null) {
        SearchListRow(
            parameter.name,
            Modifier.testTag("metric-$field"),
            symbol = style.symbol,
            symbolColor = style.color,
            onClick = {
                go(Route.Metric(parameter.name, field, parameter.unit, parameter.format, backTitle = "Search"))
            },
        )
    } else {
        SearchListRow(parameter.name, symbol = style.symbol, symbolColor = style.color, showsChevron = false)
    }
}

// Search rows. These follow design/SearchGroupRow and ListRowCard, but a long title never ellipsizes on one line:
// like iOS `.minimumScaleFactor`, it first shrinks a little to stay on one line, then wraps to a second line.

/** The smallest a row title shrinks to before it wraps instead. */
private const val TitleMinScale = 0.85f
private const val TitleScaleStep = 0.05f

/** A row title at 20 medium: full size if it fits, else up to 15 % smaller on one line, else two lines at full size. */
@Composable
internal fun SearchRowTitle(title: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val base = AuraType.row
        val style = remember(title, width, measurer) {
            if (width == Constraints.Infinity) return@remember base
            var scale = 1f
            while (scale >= TitleMinScale - 0.001f) {
                val candidate = base.copy(fontSize = base.fontSize * scale)
                val fits = !measurer.measure(
                    title,
                    candidate,
                    softWrap = false,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = width),
                ).hasVisualOverflow
                if (fits) return@remember candidate
                scale -= TitleScaleStep
            }
            base
        }
        Text(title, style = style, color = Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One row of a SearchGroupCard: an optional icon, the title and a chevron (iOS SearchGroupRow). */
@Composable
internal fun SearchListRow(
    title: String,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    symbolColor: Color = AuraColors.green,
    showsChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = if (symbol == null) 20.dp else 26.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (symbol != null) {
            Icon(auraIcon(symbol), contentDescription = null, tint = symbolColor, modifier = Modifier.size(26.dp))
        }
        SearchRowTitle(title, Modifier.weight(1f))
        if (showsChevron) Chevron()
    }
}

/** The white capsule row: icon, title and chevron (iOS ListRowCard), with the title given all the free width. */
@Composable
private fun SearchCapsuleRow(
    title: String,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    symbolColor: Color = AuraColors.green,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clip(CircleShape)
            .background(AuraColors.card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 26.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (symbol != null) {
            Icon(auraIcon(symbol), contentDescription = null, tint = symbolColor, modifier = Modifier.size(26.dp))
        }
        SearchRowTitle(title, Modifier.weight(1f))
        Chevron()
    }
}
