package com.alaimtiaz.calendaralarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.alaimtiaz.calendaralarm.AlarmOverlayActivity
import com.alaimtiaz.calendaralarm.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the AlarmManager broadcast and launches AlarmOverlayActivity.
 *
 * We use a partial WakeLock to give AlarmOverlayActivity time to start
 * before the system goes back to sleep — onCreate of the Activity will
 * then take over with its own SCREEN_BRIGHT_WAKE_LOCK.
 */
class EventAlarmReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(AlarmScheduler.EXTRA_EVENT_ID, -1L)
        if (eventId == -1L) {
            Log.w(TAG, "Received alarm with no event id, ignoring")
            return
        }

        Log.d(TAG, "Alarm fired for event $eventId")

        // Acquire a short partial wakelock to ensure the Activity launches
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CalendarAlarm:ReceiverWakeLock"
        )
        wl.acquire(15_000L)

        // Launch the full-screen alarm activity
        val overlayIntent = Intent(context, AlarmOverlayActivity::class.java).apply {
            putExtra(AlarmOverlayActivity.EXTRA_EVENT_ID, eventId)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_HISTORY
            )
        }
        try {
            context.startActivity(overlayIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AlarmOverlayActivity for event $eventId", e)
        }

        // After alarm fires, remove its pending entry asynchronously
        val pendingResult = goAsync()
        scope.launch {
            try {
                AppDatabase.getInstance(context.applicationContext)
                    .pendingAlarmDao()
                    .deleteByEventId(eventId)
            } finally {
                if (wl.isHeld) wl.release()
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "EventAlarmReceiver"
    }
}
