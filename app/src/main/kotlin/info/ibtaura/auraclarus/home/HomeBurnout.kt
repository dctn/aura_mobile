package info.ibtaura.auraclarus.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import info.ibtaura.aura.core.DailyMetrics
import info.ibtaura.aura.core.DayKey
import info.ibtaura.aura.core.MetricMaths
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.Footnote
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.hex
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.Locale

/** Burnout: the newest risk of the last 14 days and its 12 dots (iOS HomeBurnoutSection). */
@Composable
internal fun HomeBurnoutSection(app: AppState) {
    var latest by remember { mutableStateOf<DailyMetrics?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(app.dataVersion, retry) {
        val today = LocalDate.now(DayKey.zone)
        try {
            val days = app.api.dailyMetrics(DayKey.string(today.minusDays(13)), DayKey.string(today))
            latest = days.sortedByDescending { it.date }.firstOrNull { it.burnoutIndexScore != null || it.burnoutIndexRisk != null }
            loaded = true
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            app.note(e)
            failed = !loaded
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionTitle("Burnout")
        AuraCard {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(auraIcon("flame.fill"), contentDescription = null, tint = AuraColors.burnout, modifier = Modifier.size(20.dp))
                Text("Burnout Risk", style = AuraType.calloutMedium, color = AuraColors.burnout)
            }
            if (failed) {
                HomeRetryLine("Couldn't load this.", retry = { retry += 1 })
            } else {
                // The blank keeps the card's height while it loads.
                Text(
                    if (loaded) burnoutLine(latest) else " ",
                    style = AuraType.calloutMedium,
                    color = Color.Black,
                    modifier = Modifier.testTag("home-burnout-line"),
                )
            }
            HomeBurnoutDots(MetricMaths.burnoutLevel(latest?.burnoutIndexScore))
        }
        Footnote(HomeCopy.footnote, linkTitle = HomeCopy.privacyLink)
    }
}

/** The backend's risk word when it sends one, otherwise the score against the backend's own bands. */
private fun burnoutLine(day: DailyMetrics?): String {
    if (day == null) return "Not enough data yet"
    val word = day.burnoutIndexRisk?.lowercase(Locale.ROOT) ?: riskWord(day.burnoutIndexScore)
    return when (word) {
        "critical" -> "Highest possibility estimated"
        "high" -> "Higher possibility estimated"
        "moderate" -> "Moderate possibility estimated"
        "low" -> "Lower possibility estimated"
        else -> "Not enough data yet"
    }
}

private fun riskWord(score: Double?): String = when {
    score == null -> ""
    score >= 72 -> "critical"
    score >= 55 -> "high"
    score >= 32 -> "moderate"
    else -> "low"
}

/** The grey pill of 12 dots. The filled part is pale orange and its last dot is darker. */
@Composable
private fun HomeBurnoutDots(level: Int) {
    val dotCount = MetricMaths.burnoutDots
    val dotSize = 11.dp
    val pillHeight = 38.dp
    val fillHeight = 32.dp
    val fillInset = 3.dp
    // From the pill's edge to the first dot's centre, and how far the fill runs past its last dot's centre.
    val edgeToDot = 22.dp
    val fillOverrun = 12.5.dp
    BoxWithConstraints(
        Modifier
            .widthIn(max = 296.dp)
            .fillMaxWidth()
            .height(pillHeight)
            .background(AuraColors.background, CircleShape)
            .clearAndSetSemantics { contentDescription = "Burnout level: $level of $dotCount" },
    ) {
        val step = max(dotSize, (maxWidth - edgeToDot * 2) / (dotCount - 1).toFloat())
        if (level > 0) {
            val lastCentre = edgeToDot + step * (level - 1).toFloat()
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = fillInset)
                    .width(lastCentre + fillOverrun - fillInset)
                    .height(fillHeight)
                    .background(AuraColors.burnoutFill, CircleShape),
            )
        }
        for (index in 0 until dotCount) {
            val color = when {
                index >= level -> hex(0xD9D9D9)
                index == level - 1 -> AuraColors.burnout
                else -> hex(0xFBA280)
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = edgeToDot - dotSize / 2 + step * index.toFloat())
                    .size(dotSize)
                    .background(color, CircleShape),
            )
        }
    }
}
