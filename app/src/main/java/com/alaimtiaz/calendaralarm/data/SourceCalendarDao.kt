package com.alaimtiaz.calendaralarm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceCalendarDao {

    @Query("SELECT * FROM source_calendars ORDER BY accountType ASC, accountName ASC, displayName ASC")
    fun observeAll(): Flow<List<SourceCalendarEntity>>

    @Query("SELECT * FROM source_calendars")
    suspend fun getAll(): List<SourceCalendarEntity>

    @Query("SELECT * FROM source_calendars WHERE isEnabled = 1")
    suspend fun getEnabled(): List<SourceCalendarEntity>

    @Query("SELECT id FROM source_calendars WHERE isEnabled = 1")
    suspend fun getEnabledIds(): List<Long>

    @Query("SELECT * FROM source_calendars WHERE id = :id")
    suspend fun getById(id: Long): SourceCalendarEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(calendars: List<SourceCalendarEntity>)

    @Update
    suspend fun update(calendar: SourceCalendarEntity)

    @Query("UPDATE source_calendars SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE source_calendars SET lastSyncedAt = :timestamp WHERE id IN (:ids)")
    suspend fun updateLastSync(ids: List<Long>, timestamp: Long)

    @Query("DELETE FROM source_calendars WHERE id NOT IN (:keepIds)")
    suspend fun deleteRemoved(keepIds: List<Long>)
}
