package info.ibtaura.auraclarus.run

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import info.ibtaura.auraclarus.MainActivity
import java.time.Instant

// The iOS run's local notification ("Timer done" / "Your time is up."): an alarm at the run's end, and a receiver that
// posts it. Exact when the phone allows exact alarms, else within about a minute.

/** Schedules and cancels the one end-of-run alarm. */
internal class RunEndAlarm(private val context: Context) {
    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun schedule(at: Instant, title: String, body: String) {
        val manager = alarmManager ?: return
        val intent = Intent(context, RunEndReceiver::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_BODY, body)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val millis = at.toEpochMilli()
        runCatching {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            } else {
                manager.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000L, pending)
            }
        }.onFailure {
            // SecurityException when exact alarms were revoked between the check and the call.
            runCatching { manager.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000L, pending) }
        }
    }

    fun cancel() {
        val manager = alarmManager ?: return
        val intent = Intent(context, RunEndReceiver::class.java).setAction(ACTION)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        manager.cancel(pending)
        pending.cancel()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val ACTION = "info.ibtaura.auraclarus.RUN_END"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val CHANNEL = "run-end"
        const val NOTIFICATION_ID = 4101
        private const val REQUEST = 4101
    }
}

/** Posts "<title> done" when a run's time is up while the app isn't on screen (the run screen beeps when it is). */
class RunEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RunEndAlarm.ACTION) return
        val onScreen = runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(false)
        if (onScreen) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(RunEndAlarm.CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(RunEndAlarm.CHANNEL, "Run finished", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a timed run's time is up."
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, RunEndAlarm.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(intent.getStringExtra(RunEndAlarm.EXTRA_TITLE) ?: "Run done")
            .setContentText(intent.getStringExtra(RunEndAlarm.EXTRA_BODY) ?: "Your time is up.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(RunEndAlarm.NOTIFICATION_ID, notification) }
    }
}
