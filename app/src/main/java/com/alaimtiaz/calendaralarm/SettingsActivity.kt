package com.alaimtiaz.calendaralarm

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.alaimtiaz.calendaralarm.databinding.ActivitySettingsBinding
import com.alaimtiaz.calendaralarm.permissions.PermissionsManager
import com.alaimtiaz.calendaralarm.ui.PermissionsAdapter
import com.alaimtiaz.calendaralarm.util.PreferencesHelper
import com.alaimtiaz.calendaralarm.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var prefs: PreferencesHelper
    private lateinit var permsMgr: PermissionsManager
    private lateinit var permsAdapter: PermissionsAdapter

    private val runtimePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.refreshPermissions() }

    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = IntentCompat.getParcelableExtra(
                result.data ?: return@registerForActivityResult,
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                Uri::class.java
            )
            if (uri != null) {
                prefs.defaultRingtoneUri = uri
                Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()
                renderCurrentRingtone()
            }
        }
    }

    private val genericSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> viewModel.refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = PreferencesHelper(applicationContext)
        permsMgr = PermissionsManager(applicationContext)

        permsAdapter = PermissionsAdapter(this) { perm -> handlePermissionRequest(perm) }
        binding.recyclerPermissions.layoutManager = LinearLayoutManager(this)
        binding.recyclerPermissions.adapter = permsAdapter

        // General section: ringtone picker
        binding.rowDefaultRingtone.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.settings_default_ringtone))
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, prefs.defaultRingtoneUri)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            }
            ringtonePickerLauncher.launch(intent)
        }

        // Phase 1 duration
        binding.sliderPhase1.value = prefs.phase1DurationSeconds.toFloat().coerceIn(3f, 60f)
        renderPhase1Label(prefs.phase1DurationSeconds)
        binding.sliderPhase1.addOnChangeListener { _, value, _ ->
            val sec = value.toInt()
            renderPhase1Label(sec)
        }
        binding.sliderPhase1.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                prefs.phase1DurationSeconds = slider.value.toInt()
            }
        })

        // Vibration
        binding.switchVibration.isChecked = prefs.vibrationEnabled
        binding.switchVibration.setOnCheckedChangeListener { _, checked ->
            prefs.vibrationEnabled = checked
        }

        // OEM battery
        binding.btnOpenBattery.setOnClickListener {
            try {
                genericSettingsLauncher.launch(permsMgr.openOemBatterySettings())
            } catch (_: Exception) {
                genericSettingsLauncher.launch(permsMgr.openAppDetailsIntent())
            }
        }
        binding.btnOpenAppInfo.setOnClickListener {
            genericSettingsLauncher.launch(permsMgr.openAppDetailsIntent())
        }

        renderCurrentRingtone()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.perms.collect { permsAdapter.submit(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.eventsCount.collect { count ->
                    binding.tvEventsCount.text = getString(R.string.settings_events_count, count)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAll()
    }

    private fun renderPhase1Label(seconds: Int) {
        binding.tvPhase1Value.text = "$seconds ث"
    }

    private fun renderCurrentRingtone() {
        val uri = prefs.defaultRingtoneUri
        val name = try {
            if (uri != null) {
                RingtoneManager.getRingtone(this, uri)?.getTitle(this)
            } else null
        } catch (_: Exception) { null } ?: "—"
        binding.tvCurrentRingtone.text = name
    }

    private fun handlePermissionRequest(perm: PermissionsManager.PermInfo) {
        // Runtime permissions: use ActivityResultContracts
        val runtimeName = permsMgr.runtimePermissionFor(perm.key)
        if (runtimeName != null && !perm.granted) {
            runtimePermLauncher.launch(runtimeName)
            return
        }
        // Special permissions: use Settings intent
        val intent = permsMgr.settingsIntentFor(perm.key)
        if (intent != null) {
            try { genericSettingsLauncher.launch(intent) }
            catch (_: Exception) {
                genericSettingsLauncher.launch(permsMgr.openAppDetailsIntent())
            }
            return
        }
        // Fallback
        genericSettingsLauncher.launch(permsMgr.openAppDetailsIntent())
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
