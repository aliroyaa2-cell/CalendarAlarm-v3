package com.alaimtiaz.calendaralarm.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alaimtiaz.calendaralarm.MainActivity
import com.alaimtiaz.calendaralarm.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-schedules every active alarm after the device boots.
 *
 * Build #44 changes:
 * - Reads RescheduleResult from AlarmScheduler to detect missed alarms
 * - If any alarms fired before the device shut down (and were not handled),
 *   shows a single "missed alarms" notification so the user can review them.
 *
 * Note: per Android 3.1+ policy, the user must have launched the app at least
 * once after install for this receiver to fire.
 */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) {
            Log.d(TAG, "Ignoring unknown action: $action")
            return
        }

        Log.d(TAG, "Boot action received: $action — rescheduling alarms")

        val pendingResult = goAsync()
        scope.launch {
            try {
                ensureMissedAlarmsChannel(context)

                val scheduler = AlarmScheduler(context.applicationContext)
                val result = scheduler.rescheduleAll()
                Log.d(TAG, "Rescheduled ${result.rescheduledCount} alarms; missed=${result.missedCount}")

                if (result.missedCount > 0) {
                    showMissedAlarmsNotification(context, result.missedCount)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule alarms on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Creates the notification channel for missed alarms (Android 8+).
     * Uses HIGH importance so the notification appears in the status bar
     * and stays there until the user taps it.
     */
    private fun ensureMissedAlarmsChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "تنبيهات بعد إعادة التشغيل",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "تنبيهات للمنبهات اللي فاتت أثناء إغلاق الجهاز"
                    enableLights(true)
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    /**
     * Shows a single notification listing how many alarms fired but were not handled
     * (e.g. because the device shut down before the user could dismiss/snooze them).
     */
    private fun showMissedAlarmsNotification(context: Context, count: Int) {
        // Check POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                "android.permission.POST_NOTIFICATIONS"
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted — skipping missed-alarm notification")
                return
            }
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            MISSED_NOTIF_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "⚠️ منبهات فاتتك"
        val body = if (count == 1) {
            "كان لديك منبه نشط قبل إغلاق الجهاز. اضغط للمراجعة."
        } else {
            "كان لديك $count منبهات نشطة قبل إغلاق الجهاز. اضغط للمراجعة."
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(MISSED_NOTIF_ID, notif)
            Log.d(TAG, "Showed missed alarms notification (count=$count)")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException showing missed alarms notification", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val CHANNEL_ID = "missed_alarms_channel"
        private const val MISSED_NOTIF_ID = 9999

        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
