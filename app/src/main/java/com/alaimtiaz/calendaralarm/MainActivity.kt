package com.alaimtiaz.calendaralarm

import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.alaimtiaz.calendaralarm.data.EventEntity
import com.alaimtiaz.calendaralarm.databinding.ActivityMainBinding
import com.alaimtiaz.calendaralarm.permissions.PermissionsManager
import com.alaimtiaz.calendaralarm.service.CalendarSyncService
import com.alaimtiaz.calendaralarm.ui.EventsAdapter
import com.alaimtiaz.calendaralarm.util.PreferencesHelper
import com.alaimtiaz.calendaralarm.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: EventsAdapter
    private lateinit var permsMgr: PermissionsManager
    private lateinit var prefs: PreferencesHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        permsMgr = PermissionsManager(applicationContext)
        prefs = PreferencesHelper(applicationContext)

        adapter = EventsAdapter(this) { event ->
            openEventInCalendar(event)
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.bannerPermissions.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Subscribe to upcoming events
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.upcoming.collect { list ->
                    adapter.submit(list)
                    binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        // Mark first launch
        if (!prefs.hasOpenedBefore) prefs.hasOpenedBefore = true

        // Start (or ensure running) the sync service if calendar permission is present
        if (permsMgr.isCalendarGranted()) {
            CalendarSyncService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBanner()
    }

    private fun refreshBanner() {
        val missing = permsMgr.missingCount()
        if (missing == 0) {
            binding.bannerPermissions.visibility = View.GONE
        } else {
            binding.bannerPermissions.visibility = View.VISIBLE
            binding.tvBannerSubtitle.text = getString(R.string.permissions_banner_subtitle) + " ($missing)"
        }
    }

    /**
     * Open an event in the device's native calendar app.
     * Uses the simple, trust-the-system approach proven to work in legacy versions.
     */
    private fun openEventInCalendar(event: EventEntity) {
        val externalEventIdLong = event.externalId.substringBefore("_").toLongOrNull()

        try {
            if (externalEventIdLong != null) {
                val uri = ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI,
                    externalEventIdLong
                )
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Log.d(TAG, "openEventInCalendar: launched ACTION_VIEW for event $externalEventIdLong")
                return
            }
        } catch (ex: Exception) {
            Log.w(TAG, "openEventInCalendar: ACTION_VIEW failed", ex)
        }

        // Fallback: open Google Calendar app's launcher
        try {
            val launcher = packageManager.getLaunchIntentForPackage("com.google.android.calendar")
            if (launcher != null) {
                launcher.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(launcher)
                Log.d(TAG, "openEventInCalendar: launched Google Calendar via launcher")
                return
            }
        } catch (ex: Exception) {
            Log.w(TAG, "openEventInCalendar: launcher fallback failed", ex)
        }

        Toast.makeText(this, "تعذّر فتح تطبيق التقويم", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_source_calendars -> {
                startActivity(Intent(this, SourceCalendarsActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_sync_now -> {
                if (!permsMgr.isCalendarGranted()) {
                    Toast.makeText(this, R.string.perm_calendar, Toast.LENGTH_SHORT).show()
                } else {
                    CalendarSyncService.syncNow(this)
                    Toast.makeText(this, R.string.toast_sync_started, Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
