package com.alaimtiaz.calendaralarm.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.data.EventEntity
import com.alaimtiaz.calendaralarm.repository.EventsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = EventsRepository(AppDatabase.getInstance(app))

    enum class Tab { UPCOMING, PAST }

    // Original flow — kept for backward compatibility
    val upcoming: StateFlow<List<EventEntity>> = repo.observeUpcoming()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _currentTab = MutableStateFlow(Tab.UPCOMING)
    val currentTab: StateFlow<Tab> = _currentTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Dynamic events flow that switches based on the current tab and search query.
     * Behavior:
     *   - Tab UPCOMING + empty query  → all upcoming events
     *   - Tab UPCOMING + query        → search within upcoming
     *   - Tab PAST + empty query      → all past events (newest first)
     *   - Tab PAST + query            → search within past
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: StateFlow<List<EventEntity>> = combine(_currentTab, _searchQuery) { tab, query ->
        Pair(tab, query.trim())
    }.flatMapLatest { (tab, query) ->
        when {
            tab == Tab.UPCOMING && query.isEmpty() -> repo.observeUpcoming()
            tab == Tab.UPCOMING && query.isNotEmpty() -> repo.observeUpcomingSearch(query)
            tab == Tab.PAST && query.isEmpty() -> repo.observePast()
            else -> repo.observePastSearch(query)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun setTab(tab: Tab) {
        _currentTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
