package com.alaimtiaz.calendaralarm

import android.app.KeyguardManager
import android.content.ContentUris
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        playAlarmSoundOnce()
        startShortVibration()
        wireButtons()
        scheduleEnterPhase2()
    }

    private fun applyPhase1WindowFlags() {
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
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
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
            acquire((prefs.phase1DurationSeconds * 1000L) + 2_000L)
        }
    }

    private fun scheduleEnterPhase2() {
        phase2Runnable = Runnable { enterPhase2() }
        handler.postDelayed(phase2Runnable!!, prefs.phase1DurationSeconds * 1000L)
    }

    private fun enterPhase2() {
        Log.d(TAG, "Entering Phase 2 — releasing screen control to system")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

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

    /**
     * Open the event in its native calendar app.
     * Tries 3 strategies in order:
     *   1. Direct event URI to Google Calendar (preferred)
     *   2. Generic ACTION_VIEW with event URI (any calendar app picks it)
     *   3. Fallback: open Google Calendar app at today's date
     *   4. Final fallback: open our own MainActivity
     */
    private fun openSourceCalendar() {
        val e = event ?: run {
            Toast.makeText(this, "تعذّر تحميل بيانات الحدث", Toast.LENGTH_SHORT).show()
            return
        }

        val externalEventId = e.externalId.substringBefore("_").toLongOrNull()
        Log.d(TAG, "openSourceCalendar: eventId=$eventId externalEventId=$externalEventId source=${e.source}")

        // Strategy 1: Try Google Calendar with explicit package name
        if (externalEventId != null) {
            try {
                val uri = ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI,
                    externalEventId
                )
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.calendar")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    Log.d(TAG, "Opened event via Google Calendar (Strategy 1)")
                    stopAlarmEffects()
                    finishAndRemoveTask()
                    return
                } else {
                    Log.w(TAG, "Strategy 1 failed: Google Calendar not installed or won't handle URI")
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Strategy 1 threw exception", ex)
            }
        }

        // Strategy 2: Generic event URI — any calendar app
        if (externalEventId != null) {
            try {
                val uri = ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI,
                    externalEventId
                )
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    Log.d(TAG, "Opened event via generic ACTION_VIEW (Strategy 2)")
                    stopAlarmEffects()
                    finishAndRemoveTask()
                    return
                } else {
                    Log.w(TAG, "Strategy 2 failed: no app handles event URI")
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Strategy 2 threw exception", ex)
            }
        }

        // Strategy 3: Open Google Calendar app at today's date
        try {
            val todayBeginMillis = System.currentTimeMillis()
            val timeUri = Uri.parse("content://com.android.calendar/time/$todayBeginMillis")
            val intent = Intent(Intent.ACTION_VIEW, timeUri).apply {
                setPackage("com.google.android.calendar")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Toast.makeText(this, "افتح الحدث من Google Calendar", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Opened Google Calendar at today (Strategy 3)")
                stopAlarmEffects()
                finishAndRemoveTask()
                return
            } else {
                Log.w(TAG, "Strategy 3 failed: Google Calendar not installed")
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Strategy 3 threw exception", ex)
        }

        // Strategy 4: Try launching Google Calendar via package launcher
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.calendar")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Toast.makeText(this, "افتح الحدث من Google Calendar", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Opened Google Calendar launcher (Strategy 4)")
                stopAlarmEffects()
                finishAndRemoveTask()
                return
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Strategy 4 threw exception", ex)
        }

        // Final fallback: our own app
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            Toast.makeText(this, "تعذّر فتح Google Calendar", Toast.LENGTH_LONG).show()
            stopAlarmEffects()
            finishAndRemoveTask()
        } catch (ex: Exception) {
            Log.e(TAG, "All open strategies failed", ex)
            Toast.makeText(this, "تعذّر فتح أي تطبيق تقويم", Toast.LENGTH_LONG).show()
        }
    }

    private fun playAlarmSoundOnce() {
        try {
            val uri: Uri = prefs.defaultRingtoneUri
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmOverlayActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = false
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    Log.d(TAG, "Alarm sound finished playing")
                    try { it.release() } catch (_: Exception) {}
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm sound", e)
        }
    }

    private fun startShortVibration() {
        if (!prefs.vibrationEnabled) return
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(700L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(700L)
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

    override fun onDestroy() {
        stopAlarmEffects()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Disabled
    }

    companion object {
        private const val TAG = "AlarmOverlay"
        const val EXTRA_EVENT_ID = "extra_event_id"
    }
}
