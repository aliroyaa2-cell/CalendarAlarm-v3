package com.alaimtiaz.calendaralarm.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A scheduled alarm (one row per active AlarmManager registration).
 * We persist these so BootReceiver can re-schedule everything after restart.
 */
@Entity(
    tableName = "pending_alarms",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("event_id"),
        Index("trigger_at")
    ]
)
data class PendingAlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @androidx.room.ColumnInfo(name = "event_id")
    val eventId: Long,

    /** Scheduled trigger time in epoch millis */
    @androidx.room.ColumnInfo(name = "trigger_at")
    val triggerAt: Long,

    val isSnoozed: Boolean = false,
    val snoozeCount: Int = 0,

    val createdAt: Long = System.currentTimeMillis()
)
