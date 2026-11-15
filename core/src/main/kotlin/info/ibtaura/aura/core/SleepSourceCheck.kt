package info.ibtaura.aura.core

import java.time.ZoneId

// Android onboarding: the capacity score is about 40% sleep (debt, last night, regularity, awake), so the app can only
// be used with a source that writes nightly sleep into Health Connect. A source with past nights is ready at once; a
// sleep-capable app with no nights yet lets the user in while data is collected; no source at all keeps them out.

/** Health Connect on this phone, as the onboarding check sees it. */
enum class HealthStoreStatus { available, needsInstall, unavailable }

/** Why the user can't go past onboarding. */
enum class SleepSourceBlock {
    /** No Health Connect on this phone (no Play Store, or too old). */
    noHealthConnect,
    /** Health Connect can be installed from Play. */
    installHealthConnect,
    /** Aura may not read sleep yet. */
    notConnected,
    /** Connected, but no nights and no app that writes sleep. */
    noSleepApp,
}

sealed class SleepSourceState {
    /** The check is running. */
    object Checking : SleepSourceState()

    data class Blocked(val reason: SleepSourceBlock) : SleepSourceState()

    /** Past nights found: `nights` of them, mostly from `source` (a package name). */
    data class Ready(val nights: Int, val source: String?) : SleepSourceState()

    /** No nights yet, but `app` (a package name) can write sleep, so data is collected from tonight. */
    data class Collecting(val app: String?) : SleepSourceState()

    val canUseApp: Boolean get() = this is Ready || this is Collecting
}

object SleepSourceCheck {
    /** How far back onboarding looks for nights: the 14 the sleep debt reads, plus last night. */
    const val HISTORY_NIGHTS = 15

    /** The decision, in order: Health Connect, permission, past nights, a sleep-capable app. */
    fun decide(
        store: HealthStoreStatus,
        sleepGranted: Boolean,
        nightsFound: Int,
        nightSource: String?,
        installed: Collection<String>,
    ): SleepSourceState = when {
        store == HealthStoreStatus.unavailable -> SleepSourceState.Blocked(SleepSourceBlock.noHealthConnect)
        store == HealthStoreStatus.needsInstall -> SleepSourceState.Blocked(SleepSourceBlock.installHealthConnect)
        !sleepGranted -> SleepSourceState.Blocked(SleepSourceBlock.notConnected)
        nightsFound > 0 -> SleepSourceState.Ready(nightsFound, nightSource)
        else -> sleepApps(installed).firstOrNull()?.let { SleepSourceState.Collecting(it) }
            ?: SleepSourceState.Blocked(SleepSourceBlock.noSleepApp)
    }

    /** The installed apps that can write sleep, best first. */
    fun sleepApps(installed: Collection<String>): List<String> =
        installed.filter { it in HealthSources.sleepCapable }.sortedBy { HealthSources.rank(HealthMetric.sleep, it) }
}

/** Past nights for onboarding, oldest first, and the app most of them came from. */
data class SleepHistory(val nights: List<SleepNight>, val source: String?)

object SleepHistoryBuilder {
    /**
     * Every night in the samples, one per wake date (the longest, so a nap never replaces the night), oldest first.
     * Each night keeps one app's samples (`HealthSourcePicker.nightSamples`), as last night does.
     */
    fun build(samples: List<SleepSample>, timeZone: ZoneId, main: HealthSourceChoice?, override: String?): SleepHistory {
        val runs = SleepNightBuilder.clusters(SleepNightBuilder.merge(SleepNightBuilder.spans(samples) { it.isAsleep }))
        val byDate = LinkedHashMap<String, Pair<SleepNight, String>>()
        for (run in runs) {
            val start = run.first().start
            val end = run.last().end
            val inside = samples.filter { it.end > start && it.start < end }
            val picked = HealthSourcePicker.nightSamples(inside, main, override)
            val night = SleepNightBuilder.build(picked, timeZone) ?: continue
            val kept = byDate[night.date]
            if (kept == null || night.totalSleepMin > kept.first.totalSleepMin) {
                byDate[night.date] = night to picked.first().source
            }
        }
        val sorted = byDate.values.sortedBy { it.first.date }
        val source = sorted.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key?.ifBlank { null }
        return SleepHistory(sorted.map { it.first }, source)
    }
}

/** The Home banner while the morning score is an estimate. */
object CollectingCopy {
    /** Nights (last night included) before the score stops being an estimate, until the Quick check normal exists. */
    const val NIGHTS_FOR_SCORE = 8
    /** Nights with no sleep before the banner turns into a warning. */
    const val MISSED_FOR_WARNING = 3

    data class Banner(val title: String, val detail: String, val warning: Boolean)

    /**
     * Null when there is no opening or it is not an estimate. `appName` is the sleep app's name, `missedNights` the
     * nights since onboarding with no sleep sent.
     */
    fun banner(opening: MorningOpening?, appName: String?, missedNights: Int): Banner? {
        if (opening == null || !opening.estimated) return null
        val where = appName?.let { "in $it" } ?: "in your watch or band's app"
        if (!opening.lastNight) {
            val warning = missedNights >= MISSED_FOR_WARNING
            val title = if (warning) "No sleep for $missedNights nights" else "No sleep from last night"
            return Banner(title, "Your capacity score is an estimate. Check that sleep sharing is on $where.", warning)
        }
        val nights = opening.baselineDays + 1
        if (nights < NIGHTS_FOR_SCORE) {
            return Banner(
                "Collecting sleep data: $nights of $NIGHTS_FOR_SCORE nights",
                "Your capacity score is an estimate until then.",
                warning = false,
            )
        }
        return Banner("Your capacity score is an estimate", opening.reason ?: "Part of this morning is still missing.", false)
    }
}
