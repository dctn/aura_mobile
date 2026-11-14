package info.ibtaura.auraclarus.goals

import info.ibtaura.aura.core.MetricMaths
import info.ibtaura.aura.core.UnitSystem
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitFormatTest {
    private val metric = UnitSystem.metric
    private val imperial = UnitSystem.imperial

    @Test fun metricIsExactlyMetricMaths() {
        assertEquals(MetricMaths.display(5230.0, "metresAsKm", "km"), UnitFormat.display(5230.0, "metresAsKm", "km", metric))
        assertEquals(MetricMaths.display(72.4, "decimal1", "kg"), UnitFormat.display(72.4, "decimal1", "kg", metric))
        assertEquals("km", UnitFormat.shownUnit("metresAsKm", "km", metric))
    }

    @Test fun metresAsKmBecomeMiles() {
        // 5,000 m = 3.107 mi
        assertEquals(MetricMaths.display(5000 / 1609.344, "decimal1", "mi"), UnitFormat.display(5000.0, "metresAsKm", "km", imperial))
        assertEquals("mi", UnitFormat.shownUnit("metresAsKm", "km", imperial))
    }

    @Test fun kilometresBecomeMiles() {
        assertEquals(MetricMaths.display(10 / 1.609344, "decimal1", "mi"), UnitFormat.display(10.0, "decimal1", "km", imperial))
    }

    @Test fun kilogramsBecomePounds() {
        assertEquals(MetricMaths.display(70 * 2.2046226218, "decimal1", "lb"), UnitFormat.display(70.0, "decimal1", "kg", imperial))
        assertEquals("lb", UnitFormat.shownUnit("decimal1", "kg", imperial))
    }

    @Test fun heightBecomesFeetAndInches() {
        assertEquals("5′ 9″", UnitFormat.display(175.3, "decimal1", "cm", imperial, field = "heightCm"))
        assertEquals("6′ 0″", UnitFormat.display(182.88, "decimal1", "cm", imperial, field = "heightCm"))
        assertEquals("", UnitFormat.shownUnit("decimal1", "cm", imperial, field = "heightCm"))
        assertEquals("5′ 9″", UnitFormat.text(175.3, "decimal1", "cm", imperial, field = "heightCm"))
    }

    @Test fun otherCentimetresBecomeInches() {
        assertEquals(UnitFormat.Kind.shortLength, UnitFormat.kind("cm", "decimal1", "verticalOscillationCm"))
        assertEquals(MetricMaths.display(10 / 2.54, "decimal1", "in"), UnitFormat.display(10.0, "decimal1", "cm", imperial, "verticalOscillationCm"))
        assertEquals("in", UnitFormat.shownUnit("decimal1", "cm", imperial, "verticalOscillationCm"))
    }

    @Test fun metresBecomeFeet() {
        assertEquals(MetricMaths.display(100 * 3.280839895, "int", "ft"), UnitFormat.display(100.0, "int", "m", imperial))
        assertEquals("ft", UnitFormat.shownUnit("int", "m", imperial))
    }

    @Test fun paceIsPerMile() {
        // 5:00 /km = 8:03 /mi
        assertEquals("8:03", UnitFormat.display(300.0, "hhmm", "/km", imperial))
        assertEquals("/mi", UnitFormat.shownUnit("hhmm", "/km", imperial))
    }

    @Test fun speedIsMph() {
        assertEquals(MetricMaths.display(16.0934 / 1.609344, "decimal1", "mph"), UnitFormat.display(16.0934, "decimal1", "km/h", imperial))
        assertEquals("mph", UnitFormat.shownUnit("decimal1", "km/h", imperial))
    }

    @Test fun oneUnitMeasurementsPassThrough() {
        for (unit in listOf("bpm", "ms", "%", "kcal", "min", "steps")) {
            assertNull(UnitFormat.kind(unit, "int"))
            assertFalse(UnitFormat.isConvertible(unit, "int"))
            assertEquals(MetricMaths.display(61.0, "int", unit), UnitFormat.display(61.0, "int", unit, imperial))
            assertEquals(unit, UnitFormat.shownUnit("int", unit, imperial))
        }
        assertTrue(UnitFormat.isConvertible("km", "metresAsKm"))
    }

    @Test fun yesNoValuesHaveNoUnit() {
        assertEquals("", UnitFormat.shownUnit("bool", "kg", imperial))
    }

    @Test fun namesForTheUnitsPage() {
        assertEquals("kilometres (km)", UnitFormat.storedName("km", "metresAsKm"))
        assertEquals("miles (mi)", UnitFormat.shownName("km", "metresAsKm", imperial))
        assertEquals("kilometres (km)", UnitFormat.shownName("km", "metresAsKm", metric))
        assertEquals("beats per minute (bpm)", UnitFormat.storedName("bpm", "int"))
        assertEquals("beats per minute (bpm)", UnitFormat.shownName("bpm", "int", imperial))
    }

    @Test fun feetAndInchesRollOver() {
        // 152.4 cm is exactly 60 in.
        assertEquals("5′ 0″", UnitFormat.feetAndInches(152.4))
        assertEquals("", UnitFormat.feetAndInches(Double.NaN))
    }

    @Test fun goalHelpers() {
        assertEquals("8,000", grouped(8000))
        assertEquals("8 hr", hoursAndMinutes(480))
        assertEquals("7 hr 15 min", hoursAndMinutes(435))
        assertEquals("45 min", hoursAndMinutes(45))
        val spec = GoalSpec("k", "t", "s", androidx.compose.ui.graphics.Color.Black, 420, 540, 15, "", { "$it" })
        assertEquals(420, snap(100, spec))
        assertEquals(540, snap(999, spec))
        assertEquals(435, snap(440, spec))
    }
}
