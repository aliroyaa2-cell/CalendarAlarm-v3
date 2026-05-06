package com.alaimtiaz.calendaralarm.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Event imported from a source calendar (Google / Samsung / etc.).
 * We store a local copy so we can schedule alarms reliably even when offline.
 */
@Entity(
    tableName = "events",
    indices = [
        Index("external_id", "external_calendar_id", unique = true),
        Index("start_time"),
        Index("external_calendar_id")
    ]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** Google Calendar event id (CalendarContract.Events._ID as String) */
    val externalId: String,

    /** Calendar id from CalendarContract.Calendars._ID */
    val externalCalendarId: Long,

    /** Source label: "GOOGLE" / "SAMSUNG" / "OUTLOOK" / "OTHER" */
    val source: String,

    /** Account name (e.g. user@gmail.com) for display */
    val accountName: String,

    val title: String,
    val description: String? = null,
    val location: String? = null,

    /** Event start time in epoch millis */
    val startTime: Long,

    /** Event end time in epoch millis (nullable for "all day" or no end) */
    val endTime: Long? = null,

    val isAllDay: Boolean = false,

    /** RRULE string from CalendarContract (for recurring events) */
    val recurrenceRule: String? = null,

    /** Minutes before event to fire alarm (default 0 = at event time) */
    val notifyMinutesBefore: Int = 0,

    /** Whether this event's alarm is enabled (default true). User can toggle per event later. */
    val isAlarmEnabled: Boolean = true,

    /** Cached calendar color for UI */
    val calendarColor: Int = 0,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_GOOGLE = "GOOGLE"
        const val SOURCE_SAMSUNG = "SAMSUNG"
        const val SOURCE_OUTLOOK = "OUTLOOK"
        const val SOURCE_OTHER = "OTHER"

        fun sourceFromAccountType(accountType: String?): String = when {
            accountType == null -> SOURCE_OTHER
            accountType.contains("google", ignoreCase = true) -> SOURCE_GOOGLE
            accountType.contains("samsung", ignoreCase = true) -> SOURCE_SAMSUNG
            accountType.contains("outlook", ignoreCase = true) ||
                accountType.contains("exchange", ignoreCase = true) ||
                accountType.contains("eas", ignoreCase = true) -> SOURCE_OUTLOOK
            else -> SOURCE_OTHER
        }
    }
}
