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
 * We pass BOTH the row id AND the externalId so AlarmOverlayActivity can
 * recover the event even if Room re-inserts it with a new auto-generated id
 * during a sync.
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

    suspend fun rescheduleAll(): Int {
        alarmsRepo.deleteExpired()
        val events = eventsRepo.getActiveUpcoming()
        var count = 0
        for (e in events) {
            if (scheduleEvent(e)) count++
        }
        return count
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
