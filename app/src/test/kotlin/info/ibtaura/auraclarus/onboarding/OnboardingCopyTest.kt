package info.ibtaura.auraclarus.onboarding

import info.ibtaura.aura.core.HealthSources
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The onboarding screen's lines for what the check found.

class OnboardingCopyTest {
    @Test
    fun readyNamesTheAppAndCountsNights() {
        assertEquals("Found 12 nights from Samsung Health", OnboardingCopy.ready(12, HealthSources.SAMSUNG))
        assertEquals("Found 1 night from Oura", OnboardingCopy.ready(1, HealthSources.OURA))
        assertEquals("Found 3 nights in Health Connect", OnboardingCopy.ready(3, null))
    }

    @Test
    fun collectingNamesTheApp() {
        assertEquals("Fitbit is connected. Your sleep is collected from tonight.", OnboardingCopy.collecting(HealthSources.FITBIT))
        assertTrue(OnboardingCopy.collecting(null).startsWith("Your sleep app"))
    }

    @Test
    fun exampleAppsAreSleepAppsBandsFirst() {
        val apps = OnboardingCopy.exampleApps()
        assertTrue(apps.startsWith("Oura, Whoop, Garmin Connect"))
        assertTrue("Samsung Health" in apps)
        assertFalse("Strava" in apps)
        assertFalse("Google Fit" in apps)
    }
}
