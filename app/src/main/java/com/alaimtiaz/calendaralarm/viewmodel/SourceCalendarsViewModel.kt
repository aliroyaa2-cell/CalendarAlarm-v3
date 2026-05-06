package com.alaimtiaz.calendaralarm.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alaimtiaz.calendaralarm.alarm.AlarmScheduler
import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.data.SourceCalendarEntity
import com.alaimtiaz.calendaralarm.repository.SourceCalendarsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceCalendarsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SourceCalendarsRepository(app, AppDatabase.getInstance(app))
    private val scheduler = AlarmScheduler(app)

    val calendars: StateFlow<List<SourceCalendarEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableStateFlow<UiEvent?>(null)
    val events: StateFlow<UiEvent?> = _events.asStateFlow()

    fun refreshCalendarsList() {
        viewModelScope.launch {
            _busy.value = true
            try { withContext(Dispatchers.IO) { repo.refreshSourceCalendars() } }
            finally { _busy.value = false }
        }
    }

    fun toggleEnabled(calendar: SourceCalendarEntity, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(getApplication())
            db.sourceCalendarDao().setEnabled(calendar.id, enabled)
            // If disabling, cancel alarms for all events from that calendar then drop the events
            if (!enabled) {
                val allEvents = db.eventDao().getActiveUpcoming(0L)
                for (ev in allEvents.filter { it.externalCalendarId == calendar.id }) {
                    scheduler.cancelEvent(ev.id)
                }
                repo.deleteEventsFromCalendars(listOf(calendar.id))
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val r = repo.syncEnabledCalendars()
                    scheduler.rescheduleAll()
                    r
                }
                _events.value = UiEvent.SyncDone(result.total)
            } finally {
                _busy.value = false
            }
        }
    }

    fun consumeEvent() { _events.value = null }

    sealed class UiEvent {
        data class SyncDone(val total: Int) : UiEvent()
    }
}
