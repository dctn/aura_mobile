package info.ibtaura.auraclarus.health

import android.content.Context
import android.content.SharedPreferences
import info.ibtaura.aura.core.HealthMetric
import info.ibtaura.aura.core.HealthSourceChoice
import info.ibtaura.aura.core.HealthSourceDays
import info.ibtaura.aura.core.HealthSourceGroup
import info.ibtaura.aura.core.HealthSourcePicker
import java.time.LocalDate

/**
 * Which app each Health Connect metric comes from, kept on the phone (prefs `aura_health`): the picker's main app per
 * metric with the last day it had data, the user's own main app per group from Settings, and every app seen per group.
 * Package names only; never a health value.
 */
internal class HealthSourceStore(
    private val prefs: SharedPreferences,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    constructor(context: Context) : this(context.getSharedPreferences("aura_health", Context.MODE_PRIVATE))

    /** Apps with no data since this day are forgotten: not listed, not named as the main app. */
    private fun cutoff(): LocalDate = today().minusDays(HealthSourcePicker.FORGET_AFTER_DAYS)

    @Synchronized
    fun choices(): Map<HealthMetric, HealthSourceChoice> = HealthMetric.entries.mapNotNull { metric ->
        val text = prefs.getString(CHOICE + metric.raw, null) ?: return@mapNotNull null
        val source = text.substringBefore('|')
        val day = runCatching { LocalDate.parse(text.substringAfter('|')) }.getOrNull() ?: return@mapNotNull null
        if (source.isBlank()) null else metric to HealthSourceChoice(source, day)
    }.toMap()

    /** The user's own main app per group (Settings); the picker's choice applies where there is none. */
    @Synchronized
    fun overrides(): Map<HealthSourceGroup, String> = HealthSourceGroup.entries.mapNotNull { group ->
        prefs.getString(OVERRIDE + group.raw, null)?.takeIf { it.isNotBlank() }?.let { group to it }
    }.toMap()

    /** The apps that wrote each group's data on the phone in the last 30 days (only apps that really had data). */
    @Synchronized
    fun seen(): Map<HealthSourceGroup, Set<String>> = HealthSourceGroup.entries.mapNotNull { group ->
        seenDays(group).filterValues { it >= cutoff() }.keys.takeIf { it.isNotEmpty() }?.let { group to it }
    }.toMap()

    /** Stored as "package|last day with data"; an entry without a day (older build) counts as not seen lately. */
    private fun seenDays(group: HealthSourceGroup): Map<String, LocalDate> = seenDays(SEEN + group.raw)

    private fun seenDays(key: String): Map<String, LocalDate> =
        prefs.getStringSet(key, null).orEmpty().mapNotNull { entry ->
            val app = entry.substringBefore('|').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val day = runCatching { LocalDate.parse(entry.substringAfter('|', "")) }.getOrNull() ?: return@mapNotNull null
            app to day
        }.toMap()

    /** After a sync: the picker's choices replace the old ones; each app keeps its latest day with data; old apps drop. */
    @Synchronized
    fun save(days: HealthSourceDays) {
        val edit = prefs.edit()
        days.choices.forEach { (metric, choice) -> edit.putString(CHOICE + metric.raw, "${choice.source}|${choice.lastSeen}") }
        days.lastSeen.forEach { (group, latest) ->
            val merged = seenDays(group).toMutableMap()
            latest.forEach { (app, day) -> merged[app] = maxOf(merged[app] ?: day, day) }
            edit.putStringSet(SEEN + group.raw, merged.filterValues { it >= cutoff() }.map { "${it.key}|${it.value}" }.toSet())
        }
        edit.apply()
    }

    /**
     * The apps behind the daily totals (steps, distance, calories) in the last 30 days. Health Connect settles these
     * itself with its App priority list, so they're shown, not picked.
     */
    @Synchronized
    fun totals(): Set<String> = seenDays(TOTALS).filterValues { it >= cutoff() }.keys

    /** After a sync: each totals app with its latest day. */
    @Synchronized
    fun saveTotals(latest: Map<String, LocalDate>) {
        if (latest.isEmpty()) return
        val merged = seenDays(TOTALS).toMutableMap()
        latest.forEach { (app, day) -> merged[app] = maxOf(merged[app] ?: day, day) }
        prefs.edit().putStringSet(TOTALS, merged.filterValues { it >= cutoff() }.map { "${it.key}|${it.value}" }.toSet()).apply()
    }

    /** Settings: the user's main app for a group, or null for automatic. */
    @Synchronized
    fun setOverride(group: HealthSourceGroup, source: String?) {
        prefs.edit().apply { if (source.isNullOrBlank()) remove(OVERRIDE + group.raw) else putString(OVERRIDE + group.raw, source) }.apply()
    }

    /**
     * The app a group's data comes from now: the user's choice, else the picker's main app of the group's metric seen
     * most recently. Null before any data.
     */
    fun mainSource(group: HealthSourceGroup): String? {
        // The user's app only while it has had data lately; otherwise Aura picks from the apps that do.
        overrides()[group]?.takeIf { it in seen()[group].orEmpty() }?.let { return it }
        return choices().filterKeys { it.group == group }.values.filter { it.lastSeen >= cutoff() }.maxByOrNull { it.lastSeen }?.source
    }

    /** Sign-out: forgets every app choice, the user's own main apps and the apps seen, so the next account starts fresh. */
    @Synchronized
    fun clear() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(SOURCE_PREFIX) }.forEach(edit::remove)
        edit.apply()
    }

    private companion object {
        /** Every key above starts with this, and no other `aura_health` key does. */
        const val SOURCE_PREFIX = "source-"
        const val CHOICE = "source-choice-"
        const val OVERRIDE = "source-override-"
        const val SEEN = "source-seen-"
        const val TOTALS = "source-seen-totals"
    }
}
