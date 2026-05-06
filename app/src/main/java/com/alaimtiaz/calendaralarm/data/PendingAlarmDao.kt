package com.alaimtiaz.calendaralarm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingAlarmDao {

    @Query("SELECT * FROM pending_alarms WHERE event_id = :eventId LIMIT 1")
    suspend fun getByEventId(eventId: Long): PendingAlarmEntity?

    @Query("SELECT * FROM pending_alarms WHERE trigger_at >= :now")
    suspend fun getActive(now: Long): List<PendingAlarmEntity>

    @Query("SELECT * FROM pending_alarms")
    suspend fun getAll(): List<PendingAlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(alarm: PendingAlarmEntity): Long

    @Query("DELETE FROM pending_alarms WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: Long)

    @Query("DELETE FROM pending_alarms WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_alarms WHERE trigger_at < :before")
    suspend fun deleteExpired(before: Long)

    @Query("DELETE FROM pending_alarms")
    suspend fun deleteAll()
}
