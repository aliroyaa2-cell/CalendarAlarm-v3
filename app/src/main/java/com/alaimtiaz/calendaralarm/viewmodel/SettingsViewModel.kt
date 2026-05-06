package com.alaimtiaz.calendaralarm.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.permissions.PermissionsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val permsMgr = PermissionsManager(app)

    private val _perms = MutableStateFlow<List<PermissionsManager.PermInfo>>(permsMgr.all())
    val perms: StateFlow<List<PermissionsManager.PermInfo>> = _perms.asStateFlow()

    private val _eventsCount = MutableStateFlow(0)
    val eventsCount: StateFlow<Int> = _eventsCount.asStateFlow()

    init { refreshAll() }

    fun refreshPermissions() { _perms.value = permsMgr.all() }

    fun refreshEventsCount() {
        viewModelScope.launch {
            _eventsCount.value = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(getApplication()).eventDao().count()
            }
        }
    }

    fun refreshAll() {
        refreshPermissions()
        refreshEventsCount()
    }
}
