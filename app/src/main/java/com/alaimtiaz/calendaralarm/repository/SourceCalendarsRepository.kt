package com.alaimtiaz.calendaralarm.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.alaimtiaz.calendaralarm.alarm.AlarmScheduler
import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.data.EventEntity
import com.alaimtiaz.calendaralarm.data.SourceCalendarEntity
import com.alaimtiaz.calendaralarm.util.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads available calendars from the device CalendarContract,
 * persists which ones the user enabled, and syncs their events
 * into our Room database.
 *
 * Window: 365 days backward + 365 days forward (for past/future tabs and search).
 */
class SourceCalendarsRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val sourceDao = db.sourceCalendarDao()
    private val eventDao = db.eventDao()
    private val prefs = PreferencesHelper(context)

    suspend fun loadAvailableCalendars(): List<SourceCalendarEntity> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val list = mutableListOf<SourceCalendarEntity>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.OWNER_ACCOUNT
        )
        try {
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val displayName = cur.getString(1) ?: "(بلا اسم)"
                    val accountName = cur.getString(2) ?: ""
                    val accountType = cur.getString(3) ?: ""
                    val color = cur.getInt(4)
                    val owner = cur.getString(5) ?: accountName

                    val source = when {
                        accountType.contains("google", ignoreCase = true) -> EventEntity.SOURCE_GOOGLE
                        accountType.contains("samsung", ignoreCase = true) -> EventEntity.SOURCE_SAMSUNG
                        accountType.contains("outlook", ignoreCase = true) ||
                            accountType.contains("eas", ignoreCase = true) -> EventEntity.SOURCE_OUTLOOK
                        else -> "local"
                    }

                    val existing = sourceDao.getById(id)
                    val enabled = existing?.isEnabled ?: false

                    list.add(
                        SourceCalendarEntity(
                            id = id,
                            displayName = displayName,
                            accountName = accountName,
                            accountType = accountType,
                            ownerAccount = owner,
                            source = source,
                            color = color,
                            isEnabled = enabled
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load calendars from CalendarContract", e)
        }

        // Persist (preserve user's enable/disable choices)
        sourceDao.upsertAll(list)
        list
    }

    suspend fun setEnabled(calendarId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        sourceDao.setEnabled(calendarId, enabled)
        if (!enabled) {
            // Remove this calendar's events from local DB and cancel their alarms
            val events = eventDao.getActiveUpcoming(0)
                .filter { it.externalCalendarId == calendarId }
            val scheduler = AlarmScheduler(context)
            events.forEach { scheduler.cancelEvent(it.id, it.externalId) }
            eventDao.deleteByCalendarIds(listOf(calendarId))
        }
    }

    suspend fun getEnabledCalendars(): List<SourceCalendarEntity> = withContext(Dispatchers.IO) {
        sourceDao.getEnabled()
    }

    /**
     * Sync all enabled calendars: read events from Calendar Provider into local DB,
     * then schedule alarms for any new/updated upcoming events.
     */
    suspend fun syncEnabledCalendars(
        daysBackward: Int = 365,
        daysForward: Int = 365
    ): Int = withContext(Dispatchers.IO) {
        val enabled = sourceDao.getEnabled()
        if (enabled.isEmpty()) return@withContext 0

        val now = System.currentTimeMillis()
        val rangeStart = now - daysBackward * 24L * 60L * 60L * 1000L
        val rangeEnd = now + daysForward * 24L * 60L * 60L * 1000L

        var totalSynced = 0
        val scheduler = AlarmScheduler(context)
        val defaultMinutesBefore = prefs.defaultNotifyMinutesBefore

        for (cal in enabled) {
            val fetched = readEventsForCalendar(
                context.contentResolver,
                cal,
                rangeStart,
                rangeEnd,
                defaultMinutesBefore
            )

            // Replace this calendar's local events
            val externalIds = fetched.map { it.externalId }
            eventDao.deleteRemovedFromCalendar(cal.id, externalIds)

            for (e in fetched) {
                val existing = eventDao.getByExternalId(e.externalId, cal.id)
                val toInsert = if (existing != null) {
                    e.copy(
                        id = existing.id,
                        isAlarmEnabled = existing.isAlarmEnabled
                    )
                } else e

                eventDao.insert(toInsert)

                // Schedule alarm only for upcoming events
                if (toInsert.startTime > now) {
                    scheduler.scheduleEvent(toInsert)
                }
            }
            totalSynced += fetched.size
        }

        Log.d(TAG, "Synced $totalSynced events across ${enabled.size} calendars (range: -$daysBackward / +$daysForward days)")
        totalSynced
    }

    private fun readEventsForCalendar(
        resolver: ContentResolver,
        cal: SourceCalendarEntity,
        rangeStart: Long,
        rangeEnd: Long,
        defaultNotifyMinutesBefore: Int
    ): List<EventEntity> {
        val list = mutableListOf<EventEntity>()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, rangeStart)
        ContentUris.appendId(builder, rangeEnd)
        val uri = builder.build()

        val selection = "${CalendarContract.Instances.CALENDAR_ID} = ?"
        val args = arrayOf(cal.id.toString())

        try {
            resolver.query(
                uri,
                projection,
                selection,
                args,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val eventId = cur.getLong(0)
                    val title = cur.getString(1) ?: "(بلا عنوان)"
                    val desc = cur.getString(2)
                    val loc = cur.getString(3)
                    val begin = cur.getLong(4)
                    val end = cur.getLong(5)
                    val allDay = cur.getInt(6) == 1

                    list.add(
                        EventEntity(
                            id = 0L,
                            externalId = "${eventId}_$begin",
                            externalCalendarId = cal.id,
                            calendarColor = cal.color,
                            source = cal.source,
                            accountName = cal.accountName,
                            title = title,
                            description = desc,
                            location = loc,
                            startTime = begin,
                            endTime = end,
                            isAllDay = allDay,
                            notifyMinutesBefore = defaultNotifyMinutesBefore,
                            isAlarmEnabled = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read events for calendar ${cal.id}", e)
        }
        return list
    }

    companion object {
        private const val TAG = "SourceCalendarsRepo"
    }
}
