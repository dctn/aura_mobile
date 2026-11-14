package info.ibtaura.auraclarus.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.GoalsProfile
import info.ibtaura.aura.core.UnitSystem
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.SearchGroupCard
import info.ibtaura.auraclarus.design.SearchRowDivider
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

private val TitleInset = Modifier.padding(horizontal = 20.dp)

/**
 * Units for one metric, as a pushed page. The shell can route here with
 * `@Serializable data class Units(val name: String, val field: String, val unit: String, val format: String? = null)`
 * and `composable<Route.Units> { val r = it.toRoute<Route.Units>(); UnitsScreen(app, nav, r.name, r.field, r.unit, r.format) }`.
 * Until then the metric page opens [UnitsPage] over itself.
 */
@Composable
fun UnitsScreen(app: AppState, nav: AuraNav, name: String, field: String, unit: String, format: String? = null) {
    UnitsPage(app, name, field, unit, format, onBack = { nav.pop() })
}

/**
 * Metric or Imperial for every metric page, saved on tap (`PUT /v1/me/settings {"units": ...}`); the stored unit is
 * read-only. A metric with one unit (bpm, ms, %) says so instead of offering a choice that changes nothing.
 */
@Composable
fun UnitsPage(app: AppState, name: String, field: String, unit: String, format: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val system = UnitPreference.system
    val shownFormat = format ?: ""
    val convertible = UnitFormat.isConvertible(unit, shownFormat, field)

    LaunchedEffect(retry) {
        failed = false
        try {
            UnitPreference.refresh(app, force = true)
            // refresh keeps the old value on failure, so read once more to know whether it worked.
            app.api.meSettings()
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = true
            loaded = true
        }
    }

    fun choose(next: UnitSystem) {
        if (next == UnitPreference.system || saving) return
        val before = UnitPreference.system
        UnitPreference.remember(app, next)
        saving = true
        message = null
        scope.launch {
            try {
                app.api.putMeSettings(mapOf(GoalsProfile.UNITS_KEY to JsonPrimitive(next.raw)))
                app.dataVersion += 1
                UnitPreference.remember(app, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.note(e)
                UnitPreference.remember(app, before)
                message = "Couldn't save your units. They're back to ${label(before)}."
            } finally {
                saving = false
            }
        }
    }

    AuraPage(title = name, onBack = onBack) {
        Text("Units", style = AuraType.title, color = Color.Black, modifier = Modifier.padding(horizontal = 4.dp))

        SectionTitle("Show measurements in", TitleInset)
        SearchGroupCard(Modifier.testTag("units-choice")) {
            UnitChoiceRow(
                title = "Metric",
                detail = "km, kg, cm, km/h",
                selected = system == UnitSystem.metric,
                enabled = !saving,
                tag = "units-metric",
                onClick = { choose(UnitSystem.metric) },
            )
            SearchRowDivider(leading = 20.dp)
            UnitChoiceRow(
                title = "Imperial",
                detail = "mi, lb, ft and in, mph",
                selected = system == UnitSystem.imperial,
                enabled = !saving,
                tag = "units-imperial",
                onClick = { choose(UnitSystem.imperial) },
            )
        }
        when {
            !loaded -> Row(TitleInset, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }
            failed -> UnitsRetryLine("Couldn't read your units, so this shows Metric.", retry = { retry += 1 })
            message != null -> Text(message!!, style = AuraType.callout, color = AuraColors.red, modifier = TitleInset)
            else -> Footnote("Every metric page uses this. Aura Clarus saves it to your account.", TitleInset)
        }

        SectionTitle(name, TitleInset)
        SearchGroupCard(Modifier.testTag("units-metric-card")) {
            InfoRow("Shown in", UnitFormat.shownName(unit, shownFormat, system, field))
            SearchRowDivider(leading = 20.dp)
            InfoRow("Stored in", UnitFormat.storedName(unit, shownFormat, field))
        }
        if (convertible) {
            Footnote(
                "Aura Clarus keeps $name in ${UnitFormat.storedName(unit, shownFormat, field)}. Changing units changes " +
                    "only how it's shown, never the data.",
                TitleInset,
            )
        } else {
            Footnote("This measurement has one unit.", TitleInset.testTag("units-one-unit"))
        }
    }
}

private fun label(system: UnitSystem): String = if (system == UnitSystem.imperial) "Imperial" else "Metric"

@Composable
private fun UnitChoiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag(tag)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AuraType.row, color = Color.Black)
            Text(detail, style = AuraType.caption, color = AuraColors.metricGrey)
        }
        if (selected) {
            Icon(auraIcon("checkmark"), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AuraType.body, color = Color.Black)
        Spacer(Modifier.weight(1f).padding(start = 12.dp))
        Text(value, style = AuraType.callout, color = AuraColors.metricGrey)
    }
}

/** HomeRetryLine's look, kept here so Goals doesn't reach into home. */
@Composable
internal fun UnitsRetryLine(text: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.then(TitleInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = AuraType.callout, color = AuraColors.footnote, modifier = Modifier.weight(1f, fill = false))
        Text(
            "Try again",
            style = AuraType.calloutMedium,
            color = AuraColors.blue,
            modifier = Modifier.clickable(role = Role.Button, onClick = retry),
        )
    }
}
