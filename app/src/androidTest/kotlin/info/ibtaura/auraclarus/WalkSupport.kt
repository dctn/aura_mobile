package info.ibtaura.auraclarus

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import info.ibtaura.auraclarus.shell.LaunchFlags
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before

/** Screenshots for comparing with the iOS app and the Figma. CI pulls the folder off the emulator. */
object Shots {
    private const val DIR = "/sdcard/Download/aura-shots"

    /** Saves `<name>.png`, named as the iOS walk names it ("14-settings"). */
    fun save(name: String) {
        Walk.device.waitForIdle()
        shell("mkdir -p $DIR")
        shell("screencap -p $DIR/$name.png")
    }

    private fun shell(command: String) {
        val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}

/**
 * The walks' helpers, as iOS UITests/WalkSupport.swift. Elements are found by test tag (the iOS accessibility
 * identifier), which the app exposes as the resource id: `Walk.find("settings-sign-out")`.
 */
object Walk {
    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Launches the app with UI-test flags, passed as the `aura.flags` extra: `Walk.launch("--uitesting")`. */
    fun launch(vararg flags: String): ActivityScenario<MainActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        if (flags.isNotEmpty()) intent.putExtra(LaunchFlags.EXTRA, flags.joinToString(" "))
        return ActivityScenario.launch(intent)
    }

    fun byTag(tag: String): BySelector = By.res(tag)

    /** The element tagged `tag`, waiting up to `timeoutMs`; fails with `name` when it never turns up. */
    fun find(tag: String, name: String = tag, timeoutMs: Long = 10_000): UiObject2 {
        val found = device.wait(Until.findObject(byTag(tag)), timeoutMs)
        assertNotNull("Not found: $name", found)
        return found!!
    }

    /** The first element showing exactly `text`. */
    fun findText(text: String, timeoutMs: Long = 10_000): UiObject2 {
        val found = device.wait(Until.findObject(By.text(text)), timeoutMs)
        assertNotNull("No text: $text", found)
        return found!!
    }

    fun exists(tag: String, timeoutMs: Long = 0): Boolean =
        if (timeoutMs == 0L) device.hasObject(byTag(tag)) else device.wait(Until.hasObject(byTag(tag)), timeoutMs) == true

    /** Fails with the name when the element never turns up, then taps it. */
    fun tap(tag: String, name: String = tag, timeoutMs: Long = 10_000) {
        find(tag, name, timeoutMs).click()
        device.waitForIdle()
    }

    fun tapText(text: String, timeoutMs: Long = 10_000) {
        findText(text, timeoutMs).click()
        device.waitForIdle()
    }

    /** Types into a field (replacing what's there). */
    fun type(tag: String, text: String) {
        find(tag).text = text
        device.waitForIdle()
    }

    /** Fails with the message when the element is still there after the timeout. */
    fun waitUntilGone(tag: String, message: String, timeoutMs: Long = 10_000) {
        assertTrue(message, device.wait(Until.gone(byTag(tag)), timeoutMs) == true)
    }

    /** Opens a tab by its label: "Home", "Recovery" or "Search" (tags "tab-home", ...). */
    fun openTab(label: String) {
        tap("tab-${label.lowercase()}", "tab $label", timeoutMs = 20_000)
    }

    /** The system back (gesture or button). */
    fun back() {
        device.pressBack()
        device.waitForIdle()
    }

    /** Drags in the page's right margin, as iOS `dragPage`: fractions of the screen height. */
    fun dragPage(fromY: Double, toY: Double, steps: Int = 40) {
        val x = (device.displayWidth * 0.97).toInt()
        device.drag(x, (device.displayHeight * fromY).toInt(), x, (device.displayHeight * toY).toInt(), steps)
        device.waitForIdle()
    }

    /** Scrolls until the element sits between 15% and 70% of the screen height, clear of the bars. */
    fun bringIntoView(tag: String) {
        val lowest = device.displayHeight * 0.7
        val highest = device.displayHeight * 0.15
        repeat(8) {
            val element = device.findObject(byTag(tag))
            if (element == null) {
                dragPage(0.6, 0.35)
                return@repeat
            }
            val middle = element.visibleBounds.centerY()
            when {
                middle > lowest -> dragPage(0.6, 0.35)
                middle < highest -> dragPage(0.35, 0.6)
                else -> return
            }
        }
    }

    /** Brings the row into view and taps it until `target` shows, up to 3 tries. */
    fun open(tag: String, until: String, message: String) {
        repeat(3) {
            bringIntoView(tag)
            if (exists(until)) return
            device.findObject(byTag(tag))?.click()
            if (exists(until, timeoutMs = 4_000)) return
        }
        fail(message)
    }
}

/**
 * The base of the screen walks (iOS `WalkTestCase`): launches on stub data before each test. Override `flags` for
 * more switches: `override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.SHORT_RUN)`.
 */
abstract class WalkTestCase {
    open val flags: List<String> = listOf(LaunchFlags.UITESTING)
    protected lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchApp() {
        scenario = Walk.launch(*flags.toTypedArray())
    }

    @After
    fun closeApp() {
        scenario.close()
    }

    fun save(name: String) = Shots.save(name)
}
