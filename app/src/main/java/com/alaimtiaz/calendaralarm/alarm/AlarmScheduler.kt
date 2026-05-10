package com.alaimtiaz.calendaralarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.alaimtiaz.calendaralarm.MainActivity
import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.data.EventEntity
import com.alaimtiaz.calendaralarm.data.PendingAlarmEntity
import com.alaimtiaz.calendaralarm.repository.AlarmsRepository
import com.alaimtiaz.calendaralarm.repository.EventsRepository

/**
 * Schedules and cancels alarms via AlarmManager.
 * Uses setAlarmClock() for max precision (bypasses Doze on Samsung One UI).
 *
 * Build #44 changes:
 * - rescheduleAll() now respects snoozed alarms (doesn't override their triggerAt with original startTime)
 * - rescheduleAll() returns count of missed alarms so BootReceiver can show a notification
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val db = AppDatabase.getInstance(context)
    private val alarmsRepo = AlarmsRepository(db)
    private val eventsRepo = EventsRepository(db)

    fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true
    }

    suspend fun scheduleEvent(event: EventEntity, triggerOverride: Long? = null): Boolean {
        if (!event.isAlarmEnabled) {
            cancelEvent(event.id, event.externalId)
            return false
        }
        val triggerAt = triggerOverride
            ?: (event.startTime - event.notifyMinutesBefore * 60_000L)

        if (triggerAt <= System.currentTimeMillis()) {
            return false
        }

        if (!canScheduleExact()) {
            Log.w(TAG, "Cannot schedule exact alarms — permission missing.")
            return false
        }

        val pi = pendingIntentFor(event.id, event.externalId)
        val showIntent = showIntentFor()

        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                pi
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling alarm for event ${event.id}", e)
            return false
        }

        alarmsRepo.upsert(
            PendingAlarmEntity(
                eventId = event.id,
                triggerAt = triggerAt,
                isSnoozed = triggerOverride != null,
                snoozeCount = (alarmsRepo.getByEventId(event.id)?.snoozeCount ?: 0) +
                    if (triggerOverride != null) 1 else 0
            )
        )

        Log.d(TAG, "Scheduled alarm event=${event.id} ext=${event.externalId} ('${event.title}') at $triggerAt")
        return true
    }

    suspend fun cancelEvent(eventId: Long, externalId: String? = null) {
        val pi = pendingIntentFor(eventId, externalId ?: "")
        alarmManager.cancel(pi)
        alarmsRepo.deleteByEventId(eventId)
        Log.d(TAG, "Canceled alarm for event $eventId")
    }

    /**
     * Result of rescheduleAll() — used by BootReceiver to decide if it needs to show
     * a "missed alarms" notification.
     */
    data class RescheduleResult(
        val rescheduledCount: Int,
        val missedCount: Int
    )

    /**
     * Re-schedules all alarms after device boot.
     *
     * Build #44 fixes:
     * 1. Snoozed alarms keep their snoozed triggerAt (don't fall back to original startTime).
     * 2. Missed alarms (triggerAt < now) are counted before deletion so BootReceiver can notify.
     * 3. Events without an existing pending alarm get freshly scheduled.
     */
    suspend fun rescheduleAll(): RescheduleResult {
        val now = System.currentTimeMillis()
        val allPending = alarmsRepo.getAll()
        var rescheduledCount = 0
        var missedCount = 0

        // Pass 1: handle existing pending alarms — preserve snoozed times
        for (p in allPending) {
            if (p.triggerAt > now) {
                // Future alarm (snoozed or not) — reschedule with the saved triggerAt
                val event = eventsRepo.getById(p.eventId)
                if (event != null && event.isAlarmEnabled) {
                    val ok = scheduleEvent(event, triggerOverride = p.triggerAt)
                    if (ok) rescheduledCount++
                    Log.d(TAG, "Reschedule preserved alarm event=${p.eventId} at ${p.triggerAt} snoozed=${p.isSnoozed}")
                }
            } else {
                // Past alarm — count as missed
                missedCount++
                Log.d(TAG, "Missed alarm event=${p.eventId} fired at ${p.triggerAt}")
            }
        }

        // Clean up the missed entries from DB
        alarmsRepo.deleteExpired()

        // Pass 2: schedule fresh alarms for upcoming events that don't have a pending row yet
        val events = eventsRepo.getActiveUpcoming()
        for (e in events) {
            val existing = alarmsRepo.getByEventId(e.id)
            if (existing == null) {
                if (scheduleEvent(e)) rescheduledCount++
            }
        }

        Log.d(TAG, "rescheduleAll done — rescheduled=$rescheduledCount missed=$missedCount")
        return RescheduleResult(rescheduledCount, missedCount)
    }

    suspend fun cancelAll() {
        val all = alarmsRepo.getAll()
        for (a in all) {
            val pi = pendingIntentFor(a.eventId, "")
            alarmManager.cancel(pi)
        }
        alarmsRepo.clearAll()
    }

    private fun pendingIntentFor(eventId: Long, externalId: String): PendingIntent {
        val intent = Intent(context, EventAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_EXTERNAL_ID, externalId)
        }
        return PendingIntent.getBroadcast(
            context,
            eventId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showIntentFor(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        const val ACTION_FIRE = "com.alaimtiaz.calendaralarm.ACTION_FIRE_ALARM"
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val EXTRA_EXTERNAL_ID = "extra_external_id"
    }
}
