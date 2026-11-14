package info.ibtaura.auraclarus

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Screenshots for comparing with the iOS app and the Figma. CI pulls the folder off the emulator. */
object Shots {
    private const val DIR = "/sdcard/Download/aura-shots"

    fun save(name: String) {
        shell("mkdir -p $DIR")
        shell("screencap -p $DIR/$name.png")
    }

    private fun shell(command: String) {
        val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}
