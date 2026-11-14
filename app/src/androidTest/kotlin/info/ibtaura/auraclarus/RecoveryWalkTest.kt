package info.ibtaura.auraclarus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** The Recovery tab on its own (the Recovery part of UITests/ScreenWalkTests.swift), plus search with no match. */
@RunWith(AndroidJUnit4::class)
class RecoveryWalkTest : WalkTestCase() {
    @Test
    fun recoveryTab() {
        Walk.openTab("Recovery")
        assertNotNull("No Now cards", Walk.device.wait(Until.findObject(By.res(Pattern.compile("now-card-.*"))), 20_000))
        Walk.find("recovery-take-break", "No Take a recovery break")
        Walk.find("recovery-log-break", "No Add a break I took")
        save("02-recovery")
    }

    @Test
    fun searchWithNoMatch() {
        Walk.openTab("Recovery")
        Walk.tap("recovery-search", "the search field", timeoutMs = 20_000)
        Walk.type("recovery-search", "zzzz")
        assertTrue("No empty search state", Walk.device.wait(Until.hasObject(By.text("No Results")), 10_000) == true)
    }
}
