package com.alaimtiaz.calendaralarm

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.alaimtiaz.calendaralarm.databinding.ActivitySourceCalendarsBinding
import com.alaimtiaz.calendaralarm.permissions.PermissionsManager
import com.alaimtiaz.calendaralarm.ui.SourceCalendarsAdapter
import com.alaimtiaz.calendaralarm.viewmodel.SourceCalendarsViewModel
import kotlinx.coroutines.launch

class SourceCalendarsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceCalendarsBinding
    private val viewModel: SourceCalendarsViewModel by viewModels()
    private lateinit var adapter: SourceCalendarsAdapter
    private lateinit var permsMgr: PermissionsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceCalendarsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        permsMgr = PermissionsManager(applicationContext)

        adapter = SourceCalendarsAdapter { calendar, enabled ->
            viewModel.toggleEnabled(calendar, enabled)
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnRefresh.setOnClickListener {
            if (!permsMgr.isCalendarGranted()) {
                Toast.makeText(this, R.string.perm_calendar, Toast.LENGTH_SHORT).show()
            } else viewModel.refreshCalendarsList()
        }
        binding.btnSync.setOnClickListener {
            if (!permsMgr.isCalendarGranted()) {
                Toast.makeText(this, R.string.perm_calendar, Toast.LENGTH_SHORT).show()
            } else viewModel.syncNow()
        }
        binding.btnSave.setOnClickListener {
            Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        // Auto-refresh from CalendarContract on first entry
        if (permsMgr.isCalendarGranted()) {
            viewModel.refreshCalendarsList()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.calendars.collect { list ->
                    adapter.submit(list)
                    binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.busy.collect { busy ->
                    binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is SourceCalendarsViewModel.UiEvent.SyncDone -> {
                            Toast.makeText(
                                this@SourceCalendarsActivity,
                                getString(R.string.toast_sync_complete, event.total),
                                Toast.LENGTH_SHORT
                            ).show()
                            viewModel.consumeEvent()
                        }
                        null -> Unit
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
