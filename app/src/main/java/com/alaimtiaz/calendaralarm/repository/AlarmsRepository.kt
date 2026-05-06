package com.alaimtiaz.calendaralarm.repository

import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.data.PendingAlarmEntity

class AlarmsRepository(db: AppDatabase) {
    private val dao = db.pendingAlarmDao()

    suspend fun upsert(alarm: PendingAlarmEntity): Long = dao.insertOrReplace(alarm)

    suspend fun getByEventId(eventId: Long): PendingAlarmEntity? = dao.getByEventId(eventId)

    suspend fun getActive(): List<PendingAlarmEntity> = dao.getActive(System.currentTimeMillis())

    suspend fun getAll(): List<PendingAlarmEntity> = dao.getAll()

    suspend fun deleteByEventId(eventId: Long) = dao.deleteByEventId(eventId)

    suspend fun deleteExpired() = dao.deleteExpired(System.currentTimeMillis())

    suspend fun clearAll() = dao.deleteAll()
}
