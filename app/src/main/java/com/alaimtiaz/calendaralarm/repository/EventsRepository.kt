package com.alaimtiaz.calendaralarm.repository

import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.data.EventEntity
import kotlinx.coroutines.flow.Flow

class EventsRepository(db: AppDatabase) {
    private val dao = db.eventDao()

    fun observeUpcoming(): Flow<List<EventEntity>> {
        return dao.observeUpcoming(System.currentTimeMillis())
    }

    fun observePast(): Flow<List<EventEntity>> {
        return dao.observePast(System.currentTimeMillis())
    }

    fun observeUpcomingSearch(query: String): Flow<List<EventEntity>> {
        return dao.observeUpcomingSearch(System.currentTimeMillis(), query)
    }

    fun observePastSearch(query: String): Flow<List<EventEntity>> {
        return dao.observePastSearch(System.currentTimeMillis(), query)
    }

    suspend fun getById(id: Long): EventEntity? = dao.getById(id)

    suspend fun getActiveUpcoming(): List<EventEntity> {
        return dao.getActiveUpcoming(System.currentTimeMillis())
    }

    suspend fun count(): Int = dao.count()

    suspend fun delete(eventId: Long) = dao.deleteById(eventId)
}
