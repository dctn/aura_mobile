package info.ibtaura.auraclarus

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.platform.ViewRootForTest
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import info.ibtaura.auraclarus.shell.LaunchFlags
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream

/**
 * Screenshots for comparing with the iOS app and the Figma, named as the iOS walk names them ("14-settings"). The
 * shell user writes them with `screencap`, so the app needs no storage permission; CI pulls the folder off the
 * emulator. A failed walk also leaves `fail-<Class>-<test>.png` there.
 */
object Shots {
    const val DIR = "/sdcard/Download/aura-shots"

    fun save(name: String) {
        Walk.device.waitForIdle()
        shell("mkdir -p $DIR")
        val out = shell("screencap -p $DIR/$name.png").trim()
        val check = shell("ls $DIR/$name.png").trim()
        if (!check.endsWith("$name.png")) Log.w(Walk.TAG, "Screenshot $name not written: $out $check")
    }

    /** Runs a command as the shell user and returns what it printed. */
    fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }
}

/**
 * The walks' helpers, as iOS UITests/WalkSupport.swift. Elements are found by test tag (the iOS accessibility
 * identifier), which the app exposes as the resource id: `Walk.find("settings-sign-out")`.
 *
 * Why [prepare] exists: under UiAutomator, Compose believes accessibility is off (UiAutomation suppresses the
 * enabled-services list Compose checks), so it sends no accessibility events; and from API 34 UiAutomation caches
 * nodes, which only those events invalidate. UiAutomator then keeps reading the first frame it saw (the loading
 * page before sign-in or the tabs), and no tag is ever found. So every Compose root is forced to report as if a
 * screen reader were on, and the node cache is dropped before each query.
 */
object Walk {
    const val TAG = "AuraWalk"
    private const val POLL_MS = 200L

    @Volatile
    private var prepared = false

    /** The device, with UiAutomation's node cache dropped first, so even a direct `Walk.device.findObject` is fresh. */
    val device: UiDevice
        get() {
            prepare()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            clearCache()
            return device
        }

    /** Once per process, before the first activity: Compose roots report to accessibility, idle waits stay short. */
    fun prepare() {
        if (prepared) return
        prepared = true
        // Called in every AndroidComposeView's constructor (the activity's, and each dialog's and popup's).
        ViewRootForTest.onViewCreatedCallback = { root -> root.forceAccessibilityForTesting(true) }
        // Compose now sends events while things move; an idle wait is capped, not 10 s per query.
        Configurator.getInstance().waitForIdleTimeout = 3_000
    }

    private fun clearCache() {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache() }
        }
    }

    /**
     * Launches the app with UI-test flags, passed as the `aura.flags` extra: `Walk.launch("--uitesting")`. A stub
     * launch starts from empty UI-test preferences (pins and all), as a fresh iOS simulator install does.
     */
    fun launch(vararg flags: String): ActivityScenario<MainActivity> {
        prepare()
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (LaunchFlags.UITESTING in flags) {
            context.getSharedPreferences("uitesting", Context.MODE_PRIVATE).edit().clear().commit()
        }
        val intent = Intent(context, MainActivity::class.java)
        if (flags.isNotEmpty()) intent.putExtra(LaunchFlags.EXTRA, flags.joinToString(" "))
        return ActivityScenario.launch(intent)
    }

    fun byTag(tag: String): BySelector = By.res(tag)

    /** Checks `probe` until it gives something (dropping the node cache each time), or null after `timeoutMs`. */
    fun <T : Any> poll(timeoutMs: Long, probe: () -> T?): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val found = try {
                probe()
            } catch (_: StaleObjectException) {
                null
            }
            if (found != null) return found
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(POLL_MS)
        }
    }

    /** The element tagged `tag`, waiting up to `timeoutMs`; fails with `name` when it never turns up. */
    fun find(tag: String, name: String = tag, timeoutMs: Long = 10_000): UiObject2 =
        poll(timeoutMs) { device.findObject(byTag(tag)) } ?: failHere("Not found: $name")

    /** The first element showing exactly `text`. */
    fun findText(text: String, timeoutMs: Long = 10_000): UiObject2 =
        poll(timeoutMs) { device.findObject(By.text(text)) } ?: failHere("No text: $text")

    fun exists(tag: String, timeoutMs: Long = 0): Boolean =
        poll(timeoutMs) { if (device.hasObject(byTag(tag))) true else null } == true

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
        poll(timeoutMs) { if (device.hasObject(byTag(tag))) null else true } ?: failHere(message)
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
        val device = device
        val x = (device.displayWidth * 0.97).toInt()
        device.drag(x, (device.displayHeight * fromY).toInt(), x, (device.displayHeight * toY).toInt(), steps)
        device.waitForIdle()
    }

    /** Scrolls until the element sits between 15% and 70% of the screen height, clear of the bars. */
    fun bringIntoView(tag: String) {
        val lowest = device.displayHeight * 0.7
        val highest = device.displayHeight * 0.15
        repeat(8) {
            val middle = try {
                device.findObject(byTag(tag))?.visibleBounds?.centerY()
            } catch (_: StaleObjectException) {
                null
            }
            when {
                middle == null -> dragPage(0.6, 0.35)
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
            try {
                device.findObject(byTag(tag))?.click()
            } catch (_: StaleObjectException) {
            }
            if (exists(until, timeoutMs = 4_000)) return
        }
        failHere(message)
    }

    /** Logs what's on screen and saves `fail-<name>.png`, so a CI failure can be read from the artifact. */
    fun recordScreen(name: String) {
        logHierarchy()
        runCatching { Shots.save("fail-$name") }.onFailure { Log.w(TAG, "No failure screenshot", it) }
    }

    /** Fails with `message`; the walk rule then logs the window hierarchy and saves the screenshot. */
    fun failHere(message: String): Nothing {
        Log.e(TAG, message)
        fail(message)
        throw AssertionError(message)
    }

    /** The UiAutomator window dump, to logcat in chunks (logcat cuts long lines). */
    private fun logHierarchy() {
        runCatching {
            val out = ByteArrayOutputStream()
            device.dumpWindowHierarchy(out)
            out.toString().chunked(3_000).forEach { Log.i(TAG, it) }
        }.onFailure { Log.w(TAG, "No window dump", it) }
    }
}

/**
 * Launches the app for one test and closes it after; on a failure it first saves `fail-<Class>-<test>.png` while
 * the failing screen is still up.
 */
class WalkRule(private val launch: () -> ActivityScenario<MainActivity>?) : TestRule {
    var scenario: ActivityScenario<MainActivity>? = null

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            Walk.prepare()
            scenario = launch()
            try {
                base.evaluate()
            } catch (t: Throwable) {
                Walk.recordScreen("${description.testClass?.simpleName}-${description.methodName}")
                throw t
            } finally {
                runCatching { scenario?.close() }
            }
        }
    }
}

/**
 * The base of the screen walks (iOS `WalkTestCase`): launches on stub data before each test. Override `flags` for
 * more switches: `override val flags = listOf(LaunchFlags.UITESTING, LaunchFlags.SHORT_RUN)`. A walk that relaunches
 * assigns `scenario`; the rule closes whichever is current.
 */
abstract class WalkTestCase {
    open val flags: List<String> = listOf(LaunchFlags.UITESTING)

    @get:Rule
    val walk = WalkRule { Walk.launch(*flags.toTypedArray()) }

    protected var scenario: ActivityScenario<MainActivity>
        get() = walk.scenario!!
        set(value) {
            walk.scenario = value
        }

    fun save(name: String) = Shots.save(name)
}
