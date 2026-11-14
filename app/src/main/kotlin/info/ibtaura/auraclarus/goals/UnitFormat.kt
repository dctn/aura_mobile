package info.ibtaura.auraclarus.goals

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import info.ibtaura.aura.core.GoalsProfile
import info.ibtaura.aura.core.MetricMaths
import info.ibtaura.aura.core.UnitSystem
import info.ibtaura.aura.core.settingText
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.floor

/**
 * Metric values shown in the person's units. The backend always sends metric (m, km, kg, cm, km/h); `imperial` turns
 * distance, speed, pace, weight and length into mi, mph, /mi, lb, ft and in. Everything else (bpm, ms, %, kcal, minutes)
 * has one unit and passes through as MetricMaths prints it.
 */
object UnitFormat {
    /** How one catalogue unit converts. */
    enum class Kind(val metricName: String, val imperialName: String, val imperialUnit: String) {
        /** `format` metresAsKm: the value is metres, shown as km. Without that format the value is already km. */
        distance("kilometres (km)", "miles (mi)", "mi"),
        speed("kilometres per hour (km/h)", "miles per hour (mph)", "mph"),
        pace("minutes per kilometre (/km)", "minutes per mile (/mi)", "/mi"),
        swimPace("minutes per 100 metres", "minutes per 100 yards", "/100 yd"),
        weight("kilograms (kg)", "pounds (lb)", "lb"),
        /** A body height in cm: feet and inches, written 5′ 9″. */
        height("centimetres (cm)", "feet and inches", ""),
        shortLength("centimetres (cm)", "inches (in)", "in"),
        length("metres (m)", "feet (ft)", "ft"),
        metresPerSecond("metres per second (m/s)", "feet per second (ft/s)", "ft/s"),
    }

    private const val MILE_M = 1609.344
    private const val KM_PER_MILE = 1.609344
    private const val LB_PER_KG = 2.2046226218
    private const val FT_PER_M = 3.280839895
    private const val CM_PER_IN = 2.54

    /** Null when the unit has only one form (bpm, ms, %, kcal, steps, a score...). */
    fun kind(unit: String, format: String, field: String? = null): Kind? = when (unit.trim().lowercase(Locale.ROOT)) {
        "km" -> Kind.distance
        "km/h" -> Kind.speed
        "/km" -> Kind.pace
        "/100 m" -> Kind.swimPace
        "kg" -> Kind.weight
        "cm" -> if (field?.lowercase(Locale.ROOT)?.contains("height") == true) Kind.height else Kind.shortLength
        "m" -> Kind.length
        "m/s" -> Kind.metresPerSecond
        else -> null
    }

    fun isConvertible(unit: String, format: String, field: String? = null): Boolean = kind(unit, format, field) != null

    /** A value as the page prints it, in `system`. Metric is exactly MetricMaths.display. */
    fun display(value: Double, format: String, unit: String, system: UnitSystem, field: String? = null): String {
        val kind = kind(unit, format, field)
        if (system == UnitSystem.metric || kind == null) return MetricMaths.display(value, format, unit)
        return when (kind) {
            Kind.distance -> {
                val miles = if (format == "metresAsKm") value / MILE_M else value / KM_PER_MILE
                MetricMaths.display(miles, "decimal1", "mi")
            }
            Kind.speed -> MetricMaths.display(value / KM_PER_MILE, format, "mph")
            Kind.pace -> MetricMaths.display(value * KM_PER_MILE, format, "/mi")
            Kind.swimPace -> MetricMaths.display(value * 0.9144, format, "/100 yd")
            Kind.weight -> MetricMaths.display(value * LB_PER_KG, format, "lb")
            Kind.height -> feetAndInches(value)
            Kind.shortLength -> MetricMaths.display(value / CM_PER_IN, if (format.isEmpty()) "decimal1" else format, "in")
            Kind.length -> MetricMaths.display(value * FT_PER_M, if (format == "decimal2") "decimal1" else format, "ft")
            Kind.metresPerSecond -> MetricMaths.display(value * FT_PER_M, format, "ft/s")
        }
    }

    /** The unit printed beside a value in `system`: words and yes/no values have none, nor does 5′ 9″. */
    fun shownUnit(format: String, unit: String, system: UnitSystem, field: String? = null): String {
        val metric = MetricMaths.shownUnit(format, unit)
        if (metric.isEmpty() || system == UnitSystem.metric) return metric
        return kind(unit, format, field)?.imperialUnit ?: metric
    }

    /** "3.4 km", "2.1 mi", "5′ 9″": the number and its unit together. */
    fun text(value: Double, format: String, unit: String, system: UnitSystem, field: String? = null): String {
        val number = display(value, format, unit, system, field)
        val shown = shownUnit(format, unit, system, field)
        return if (shown.isEmpty()) number else "$number $shown"
    }

    /** What the backend keeps the metric in, for the Units page's read-only row. */
    fun storedName(unit: String, format: String, field: String? = null): String {
        kind(unit, format, field)?.let { return it.metricName }
        return when (unit.trim()) {
            "" -> "No unit"
            "bpm" -> "beats per minute (bpm)"
            "ms" -> "milliseconds (ms)"
            "%" -> "percent (%)"
            "kcal" -> "kilocalories (kcal)"
            "min" -> "minutes (min)"
            "hr", "h", "hrs" -> "hours (hr)"
            else -> unit.trim()
        }
    }

    /** What the metric shows in `system`. */
    fun shownName(unit: String, format: String, system: UnitSystem, field: String? = null): String {
        val kind = kind(unit, format, field) ?: return storedName(unit, format, field)
        return if (system == UnitSystem.imperial) kind.imperialName else kind.metricName
    }

    /** 175.3 cm → 5′ 9″. Inches round to whole; 12 rolls over to the next foot. */
    fun feetAndInches(cm: Double): String {
        if (!cm.isFinite()) return ""
        val inches = floor(cm / CM_PER_IN + 0.5).toLong()
        return "${inches / 12}′ ${inches % 12}″"
    }
}

/**
 * The `units` setting, shared by the metric pages and Units. It lives here because the app's shared state is the
 * shell's; it is read again whenever `dataVersion` or the signed-in account changes.
 */
object UnitPreference {
    var system by mutableStateOf(UnitSystem.metric)

    private var loadedKey: Any? = null

    /** Reads the setting unless it was read for this account and data version. A failure keeps what it had. */
    suspend fun refresh(app: AppState, force: Boolean = false) {
        val key = listOf(System.identityHashCode(app.api), app.dataVersion, app.authState)
        if (!force && key == loadedKey) return
        try {
            system = UnitSystem.fromRaw(app.api.meSettings()[GoalsProfile.UNITS_KEY].settingText())
            loadedKey = key
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The pages fall back to metric, the backend's own units.
        }
    }

    /** After Units saves, so the pages show the new units at once. */
    fun remember(app: AppState, value: UnitSystem) {
        system = value
        loadedKey = listOf(System.identityHashCode(app.api), app.dataVersion, app.authState)
    }
}

/** The person's units for a metric page; metric until the setting is read. */
@Composable
fun rememberUnitSystem(app: AppState): UnitSystem {
    LaunchedEffect(app.dataVersion, app.authState) { UnitPreference.refresh(app) }
    return UnitPreference.system
}
