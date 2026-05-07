package com.alaimtiaz.calendaralarm.alarm

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alaimtiaz.calendaralarm.AlarmOverlayActivity
import com.alaimtiaz.calendaralarm.data.AppDatabase

/**
 * Heartbeat worker that runs every 15 minutes (Android's minimum interval).
 *
 * Purpose: Catch missed alarms when AlarmManager fails (e.g., Samsung One UI
 * killing the app in background). For each alarm whose trigger time has
 * passed within the last 15 minutes but was never fired, this worker
 * launches the full-screen alarm activity manually.
 *
 * This is a fallback safety net — AlarmManager remains the primary mechanism.
 * Worst-case delay for a fallback alarm: 0-15 minutes.
 */
class MissedAlarmChecker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val context = applicationContext
            val db = AppDatabase.getInstance(context)
            val now = System.currentTimeMillis()
            val windowStart = now - WINDOW_MS

            // Find pending alarms whose trigger time has passed within the window
            val allPending = db.pendingAlarmDao().getAll()
            val missed = allPending.filter { alarm ->
                alarm.triggerAt in (windowStart + 1)..now
            }

            if (missed.isEmpty()) {
                Log.d(TAG, "No missed alarms found at $now")
                return Result.success()
            }

            Log.w(TAG, "Found ${missed.size} missed alarm(s), firing fallback")

            for (alarm in missed) {
                fireMissedAlarm(context, db, alarm.eventId)
                // Remove pending entry so it doesn't fire again
                db.pendingAlarmDao().deleteByEventId(alarm.eventId)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "MissedAlarmChecker failed", e)
            Result.retry()
        }
    }

    /**
     * Launch the full-screen alarm activity for a missed alarm.
     * Mirrors the launch logic in EventAlarmReceiver.
     */
    private suspend fun fireMissedAlarm(context: Context, db: AppDatabase, eventId: Long) {
        // Look up event to get externalId (in case row id was rewritten by sync)
        val externalId = try {
            db.eventDao().getById(eventId)?.externalId
        } catch (e: Exception) {
            null
        }

        // Acquire a short wakelock to ensure the Activity launches
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CalendarAlarm:MissedAlarmWakeLock"
        )
        wl.acquire(15_000L)

        try {
            val overlayIntent = Intent(context, AlarmOverlayActivity::class.java).apply {
                putExtra(AlarmOverlayActivity.EXTRA_EVENT_ID, eventId)
                if (!externalId.isNullOrBlank()) {
                    putExtra(AlarmOverlayActivity.EXTRA_EXTERNAL_ID, externalId)
                }
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
                )
            }
            context.startActivity(overlayIntent)
            Log.d(TAG, "Fallback alarm launched for event $eventId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch fallback alarm for event $eventId", e)
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    companion object {
        private const val TAG = "MissedAlarmChecker"

        /** Window of time in the past to look for missed alarms (15 minutes). */
        private const val WINDOW_MS = 15L * 60L * 1000L

        const val WORK_NAME = "missed_alarm_checker"
    }
}
