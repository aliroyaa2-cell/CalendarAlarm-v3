package com.alaimtiaz.calendaralarm.repository

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.data.EventEntity
import com.alaimtiaz.calendaralarm.data.SourceCalendarEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads calendar sources and events from the system Calendar Provider
 * and bridges them into our local Room database.
 *
 * Default sync window: 25 years backward + 2 years forward.
 * This effectively covers all archived events from a long-term Google Calendar user.
 */
class SourceCalendarsRepository(
    private val context: Context,
    db: AppDatabase
) {
    private val sourceDao = db.sourceCalendarDao()
    private val eventDao = db.eventDao()

    fun observeAll(): Flow<List<SourceCalendarEntity>> = sourceDao.observeAll()

    fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Read all calendars currently registered with CalendarContract,
     * preserve user's enabled selections, and persist into Room.
     */
    suspend fun refreshSourceCalendars(): List<SourceCalendarEntity> {
        if (!hasCalendarPermission()) return emptyList()

        val previous = sourceDao.getAll().associateBy { it.id }
        val fresh = readCalendarsFromProvider()

        // Carry over user's isEnabled and lastSyncedAt
        val merged = fresh.map { incoming ->
            val prev = previous[incoming.id]
            if (prev != null) {
                incoming.copy(
                    isEnabled = prev.isEnabled,
                    lastSyncedAt = prev.lastSyncedAt
                )
            } else incoming
        }

        sourceDao.insertOrReplaceAll(merged)
        // Remove rows that are no longer present (account/calendar removed from device)
        sourceDao.deleteRemoved(merged.map { it.id })
        return merged
    }

    private fun readCalendarsFromProvider(): List<SourceCalendarEntity> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        val result = mutableListOf<SourceCalendarEntity>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.ACCOUNT_TYPE} ASC"
        )?.use { c ->
            val idIdx = c.getColumnIndex(CalendarContract.Calendars._ID)
            val accNameIdx = c.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val accTypeIdx = c.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
            val displayIdx = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val colorIdx = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_COLOR)
            while (c.moveToNext()) {
                result.add(
                    SourceCalendarEntity(
                        id = c.getLong(idIdx),
                        accountName = c.getString(accNameIdx) ?: "",
                        accountType = c.getString(accTypeIdx) ?: "",
                        displayName = c.getString(displayIdx) ?: "Calendar",
                        color = if (colorIdx >= 0) c.getInt(colorIdx) else 0,
                        isEnabled = false
                    )
                )
            }
        }
        return result
    }

    /**
     * Sync events from all enabled source calendars.
     *
     * @param daysBackward — number of days BEFORE today to sync (default 9125 = 25 years).
     * @param daysForward  — number of days AFTER today to sync (default 730 = 2 years).
     */
    suspend fun syncEnabledCalendars(
        daysBackward: Int = 9125,
        daysForward: Int = 730
    ): SyncResult {
        if (!hasCalendarPermission()) return SyncResult(0, 0)

        val enabled = sourceDao.getEnabled()
        if (enabled.isEmpty()) {
            return SyncResult(0, 0)
        }

        val now = System.currentTimeMillis()
        val msPerDay = 24L * 60L * 60L * 1000L
        val windowStart = now - daysBackward * msPerDay
        val windowEnd = now + daysForward * msPerDay

        var inserted = 0
        var updated = 0

        for (calendar in enabled) {
            val (writes, externalIds) = readAndStoreEventsFor(calendar, windowStart, windowEnd)
            inserted += writes.first
            updated += writes.second
            eventDao.deleteRemovedFromCalendar(calendar.id, externalIds)
        }

        sourceDao.updateLastSync(enabled.map { it.id }, System.currentTimeMillis())
        return SyncResult(inserted, updated)
    }

    /**
     * Delete all events imported from the given calendar IDs.
     * Used when user disables a calendar in the source picker.
     */
    suspend fun deleteEventsFromCalendars(calendarIds: List<Long>) {
        if (calendarIds.isEmpty()) return
        eventDao.deleteByCalendarIds(calendarIds)
    }

    private suspend fun readAndStoreEventsFor(
        calendar: SourceCalendarEntity,
        windowStart: Long,
        windowEnd: Long
    ): Pair<Pair<Int, Int>, List<String>> {
        val source = EventEntity.sourceFromAccountType(calendar.accountType)
        val externalIds = mutableListOf<String>()
        val toInsert = mutableListOf<EventEntity>()
        val toUpdate = mutableListOf<EventEntity>()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, windowStart)
        ContentUris.appendId(builder, windowEnd)
        val uri = builder.build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.CALENDAR_ID
        )

        context.contentResolver.query(
            uri,
            projection,
            "${CalendarContract.Instances.CALENDAR_ID} = ?",
            arrayOf(calendar.id.toString()),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            val idIdx = c.getColumnIndex(CalendarContract.Instances.EVENT_ID)
            val titleIdx = c.getColumnIndex(CalendarContract.Instances.TITLE)
            val descIdx = c.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
            val locIdx = c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            val beginIdx = c.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endIdx = c.getColumnIndex(CalendarContract.Instances.END)
            val allDayIdx = c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val rruleIdx = c.getColumnIndex(CalendarContract.Instances.RRULE)

            while (c.moveToNext()) {
                val eventId = c.getLong(idIdx).toString()
                val begin = c.getLong(beginIdx)

                val externalKey = if (c.isNull(rruleIdx)) eventId else "${eventId}_$begin"
                externalIds.add(externalKey)

                val existing = eventDao.getByExternalId(externalKey, calendar.id)
                val entity = EventEntity(
                    id = existing?.id ?: 0L,
                    externalId = externalKey,
                    externalCalendarId = calendar.id,
                    source = source,
                    accountName = calendar.accountName,
                    title = c.getString(titleIdx) ?: "(بدون عنوان)",
                    description = c.getString(descIdx),
                    location = c.getString(locIdx),
                    startTime = begin,
                    endTime = if (!c.isNull(endIdx)) c.getLong(endIdx) else null,
                    isAllDay = c.getInt(allDayIdx) == 1,
                    recurrenceRule = c.getString(rruleIdx),
                    calendarColor = calendar.color,
                    isAlarmEnabled = existing?.isAlarmEnabled ?: true,
                    notifyMinutesBefore = existing?.notifyMinutesBefore ?: 0,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                if (existing == null) toInsert.add(entity) else toUpdate.add(entity)
            }
        }

        if (toInsert.isNotEmpty()) eventDao.insertAll(toInsert)
        toUpdate.forEach { eventDao.update(it) }

        return Pair(Pair(toInsert.size, toUpdate.size), externalIds)
    }

    data class SyncResult(val inserted: Int, val updated: Int) {
        val total: Int get() = inserted + updated
    }
}
