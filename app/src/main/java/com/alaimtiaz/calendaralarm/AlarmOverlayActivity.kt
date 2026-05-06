package com.alaimtiaz.calendaralarm

import android.app.KeyguardManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CalendarContract
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.alaimtiaz.calendaralarm.alarm.AlarmScheduler
import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.data.EventEntity
import com.alaimtiaz.calendaralarm.databinding.ActivityAlarmOverlayBinding
import com.alaimtiaz.calendaralarm.repository.EventsRepository
import com.alaimtiaz.calendaralarm.util.DateUtils
import com.alaimtiaz.calendaralarm.util.PreferencesHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen alarm activity shown over the lock screen.
 *
 * SCREEN BEHAVIOR (Phase 1 / Phase 2):
 *
 *  Phase 1 (first ~8 seconds): we FORCE the screen on with multiple flags +
 *  a SCREEN_BRIGHT_WAKE_LOCK + setShowWhenLocked / setTurnScreenOn.
 *  This guarantees Samsung One UI 8 wakes the device and shows our UI on top
 *  of the lock screen.
 *
 *  Phase 2 (after ~8 seconds): we RELEASE the wake-lock and CLEAR
 *  FLAG_KEEP_SCREEN_ON. The system can now turn the screen off naturally
 *  with the device's normal timeout (e.g. 30 seconds). The Activity itself
 *  remains in memory — no finish() — so when the user wakes the phone again
 *  they see the alarm still pending.
 *
 *  We never call finish() automatically. Only Dismiss / Snooze / Edit
 *  end the Activity.
 *
 *  We never use PowerManager.ON_AFTER_RELEASE (that flag adds an extra delay
 *  after release which is the bug the previous attempts hit).
 */
class AlarmOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmOverlayBinding
    private lateinit var prefs: PreferencesHelper
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var event: EventEntity? = null
    private var eventId: Long = -1L
    private var clockTickRunnable: Runnable? = null
    private var phase2Runnable: Runnable? = null
    private val clockFormat = SimpleDateFormat("h:mm:ss", Locale("ar"))
    private val ampmFormat = SimpleDateFormat("a", Locale("ar"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ───── Phase 1: FORCE screen on + show over lock ─────
        applyPhase1WindowFlags()

        binding = ActivityAlarmOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesHelper(applicationContext)
        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)

        if (eventId == -1L) {
            Log.w(TAG, "Started without event id — finishing")
            finish()
            return
        }

        acquireWakeLockPhase1()
        scheduleClockTicks()
        loadEvent()
        playAlarmSound()
        startVibration()
        wireButtons()
        scheduleEnterPhase2()
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Phase 1: forced wakeup
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun applyPhase1WindowFlags() {
        // New API (Android 8.1+) for showing over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            try {
                val km = getSystemService(KeyguardManager::class.java)
                km?.requestDismissKeyguard(this, null)
            } catch (e: Exception) {
                Log.w(TAG, "requestDismissKeyguard failed", e)
            }
        }

        // Legacy flags — still required on Samsung One UI for reliable behavior
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Hide system UI
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun acquireWakeLockPhase1() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CalendarAlarm:OverlayWakeLock"
        ).apply {
            setReferenceCounted(false)
            // Hold for slightly longer than Phase 1 duration (safety margin)
            acquire((prefs.phase1DurationSeconds * 1000L) + 2_000L)
        }
    }

    private fun scheduleEnterPhase2() {
        phase2Runnable = Runnable { enterPhase2() }
        handler.postDelayed(phase2Runnable!!, prefs.phase1DurationSeconds * 1000L)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Phase 2: release the screen — let system manage it
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun enterPhase2() {
        Log.d(TAG, "Entering Phase 2 — releasing screen control to system")
        // Drop the keep-on flag — system's display timeout will now apply normally
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Release SCREEN_BRIGHT wakelock if still held
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        // The Activity stays alive. No finish(). User can come back to it later.
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // UI wiring
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun loadEvent() {
        lifecycleScope.launch {
            val e = withContext(Dispatchers.IO) {
                EventsRepository(AppDatabase.getInstance(applicationContext)).getById(eventId)
            }
            event = e
            renderEvent(e)
        }
    }

    private fun renderEvent(e: EventEntity?) {
        if (e == null) {
            binding.tvTitle.text = "(تعذّر تحميل الحدث)"
            binding.tvSourceName.text = ""
            binding.tvDescription.visibility = View.GONE
            binding.tvLocation.visibility = View.GONE
            return
        }
        binding.tvTitle.text = e.title

        val sourceLabel = when (e.source) {
            EventEntity.SOURCE_GOOGLE -> "📅 Google"
            EventEntity.SOURCE_SAMSUNG -> "📱 Samsung"
            EventEntity.SOURCE_OUTLOOK -> "📧 Outlook"
            else -> "📋 ${e.source}"
        }
        binding.tvSourceName.text = "$sourceLabel • ${e.accountName}"
        binding.tvEventTime.text = DateUtils.formatFull(this, e.startTime)

        if (!e.description.isNullOrBlank()) {
            binding.tvDescription.visibility = View.VISIBLE
            binding.tvDescription.text = e.description
        } else binding.tvDescription.visibility = View.GONE

        if (!e.location.isNullOrBlank()) {
            binding.tvLocation.visibility = View.VISIBLE
            binding.tvLocation.text = "📍 ${e.location}"
        } else binding.tvLocation.visibility = View.GONE
    }

    private fun wireButtons() {
        binding.btnDismiss.setOnClickListener { dismissAlarm() }
        binding.btnEdit.setOnClickListener { openSourceCalendar() }

        binding.btnSnooze5.setOnClickListener { snooze(5L) }
        binding.btnSnooze10.setOnClickListener { snooze(10L) }
        binding.btnSnooze30.setOnClickListener { snooze(30L) }

        binding.btnSnoozeMore.setOnClickListener { showSnoozeMoreDialog() }
    }

    private fun showSnoozeMoreDialog() {
        val labels = arrayOf(
            getString(R.string.alarm_snooze_2h),
            getString(R.string.alarm_snooze_4h),
            getString(R.string.alarm_snooze_8h),
            getString(R.string.alarm_snooze_1d),
            getString(R.string.alarm_snooze_1w)
        )
        val minutes = longArrayOf(120L, 240L, 480L, 1440L, 10080L)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.alarm_snooze_more)
            .setItems(labels) { _, which -> snooze(minutes[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun snooze(minutes: Long) {
        val e = event ?: return
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AlarmScheduler(applicationContext).scheduleEvent(e, triggerOverride = triggerAt)
            }
            stopAlarmEffects()
            finishAndRemoveTask()
        }
    }

    private fun dismissAlarm() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AlarmScheduler(applicationContext).cancelEvent(eventId)
            }
            stopAlarmEffects()
            finishAndRemoveTask()
        }
    }

    private fun openSourceCalendar() {
        val e = event ?: return
        // Try to open the event in its native calendar app first
        val externalEventId = e.externalId.substringBefore("_").toLongOrNull()
        if (externalEventId != null) {
            try {
                val uri = android.content.ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI,
                    externalEventId
                )
                val intent = Intent(Intent.ACTION_VIEW).setData(uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                stopAlarmEffects()
                finishAndRemoveTask()
                return
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to open native calendar event", ex)
            }
        }
        // Fallback: open our app
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        stopAlarmEffects()
        finishAndRemoveTask()
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Sound & vibration
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun playAlarmSound() {
        try {
            val uri: Uri = prefs.defaultRingtoneUri
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmOverlayActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm sound", e)
        }
    }

    private fun startVibration() {
        if (!prefs.vibrationEnabled) return
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 700, 500, 700, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun stopAlarmEffects() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        clockTickRunnable?.let { handler.removeCallbacks(it) }
        phase2Runnable?.let { handler.removeCallbacks(it) }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Live clock
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun scheduleClockTicks() {
        clockTickRunnable = object : Runnable {
            override fun run() {
                val now = Date()
                binding.tvClock.text = clockFormat.format(now)
                binding.tvClockAmpm.text = ampmFormat.format(now)
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(clockTickRunnable!!)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    override fun onDestroy() {
        stopAlarmEffects()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Disable back button — user must explicitly dismiss
        // (no super.onBackPressed())
    }

    companion object {
        private const val TAG = "AlarmOverlay"
        const val EXTRA_EVENT_ID = "extra_event_id"
    }
}
