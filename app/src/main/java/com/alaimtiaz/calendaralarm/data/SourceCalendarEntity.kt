package com.alaimtiaz.calendaralarm.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A calendar source as returned by CalendarContract.Calendars.
 * The user toggles `isEnabled` to decide whether to import events from it.
 */
@Entity(tableName = "source_calendars")
data class SourceCalendarEntity(
    /** CalendarContract.Calendars._ID — used as primary key directly */
    @PrimaryKey
    val id: Long,

    val accountName: String,
    val accountType: String,
    val displayName: String,
    val color: Int = 0,

    /** Whether the user enabled this calendar for import */
    val isEnabled: Boolean = false,

    val lastSyncedAt: Long? = null
)
