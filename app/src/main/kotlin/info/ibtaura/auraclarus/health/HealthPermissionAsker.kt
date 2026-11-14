package info.ibtaura.auraclarus.health

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Shows Health Connect's permission screen from a port that only has the application context: it remembers the
 * activity in front and launches the request through that activity's result registry.
 */
internal class HealthPermissionAsker(application: Application) {
    private var front: WeakReference<ComponentActivity>? = null
    private val counter = AtomicInteger()

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is ComponentActivity) front = WeakReference(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (front?.get() === activity) front = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /** Asks for `permissions`; returns the ones granted by this request. Throws when no activity is in front. */
    suspend fun request(permissions: Set<String>): Set<String> = withContext(Dispatchers.Main) {
        val activity = front?.get() ?: throw IllegalStateException("No screen to ask from")
        suspendCancellableCoroutine { continuation ->
            var registered: ActivityResultLauncher<Set<String>>? = null
            val launcher = activity.activityResultRegistry.register(
                "aura-health-permissions-${counter.incrementAndGet()}",
                PermissionController.createRequestPermissionResultContract(),
            ) { granted ->
                registered?.unregister()
                if (continuation.isActive) continuation.resume(granted)
            }
            registered = launcher
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(permissions)
        }
    }
}
