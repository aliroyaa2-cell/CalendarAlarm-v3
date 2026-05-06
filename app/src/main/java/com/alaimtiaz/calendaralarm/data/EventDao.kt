package com.alaimtiaz.calendaralarm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Query("SELECT * FROM events ORDER BY startTime ASC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("""
        SELECT * FROM events
        WHERE startTime >= :now
        ORDER BY startTime ASC
        LIMIT :limit
    """)
    fun observeUpcoming(now: Long, limit: Int = 200): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): EventEntity?

    @Query("SELECT * FROM events WHERE externalId = :externalId AND externalCalendarId = :calendarId LIMIT 1")
    suspend fun getByExternalId(externalId: String, calendarId: Long): EventEntity?

    /**
     * Lookup by externalId only — used when AlarmManager fires but the event
     * was re-inserted with a new auto-generated id during sync.
     */
    @Query("SELECT * FROM events WHERE externalId = :externalId LIMIT 1")
    suspend fun getByExternalIdAny(externalId: String): EventEntity?

    @Query("SELECT * FROM events WHERE startTime >= :now AND isAlarmEnabled = 1")
    suspend fun getActiveUpcoming(now: Long): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<EventEntity>)

    @Update
    suspend fun update(event: EventEntity)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM events WHERE externalCalendarId IN (:calendarIds)")
    suspend fun deleteByCalendarIds(calendarIds: List<Long>)

    @Query("DELETE FROM events WHERE externalCalendarId = :calendarId AND externalId NOT IN (:keepExternalIds)")
    suspend fun deleteRemovedFromCalendar(calendarId: Long, keepExternalIds: List<String>)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}
