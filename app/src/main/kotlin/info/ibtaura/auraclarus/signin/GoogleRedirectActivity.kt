package info.ibtaura.auraclarus.signin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import info.ibtaura.auraclarus.MainActivity

/**
 * Catches the hosted sign-in's redirect (`auraclarus://auth...`) and hands it to the MainActivity already under the
 * Custom Tab: CLEAR_TOP closes the tab, SINGLE_TOP delivers it to `onNewIntent` instead of making a second activity
 * (and a second AppState). NEW_TASK finds the app's own task when a plain browser, in its own task, sent it. After a
 * process death MainActivity starts fresh and reads the redirect in `onCreate`. Shows nothing.
 */
class GoogleRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(intent?.data)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(forward)
        finish()
    }
}
