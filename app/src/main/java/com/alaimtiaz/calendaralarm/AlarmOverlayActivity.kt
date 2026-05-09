package com.alaimtiaz.calendaralarm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
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
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.alaimtiaz.calendaralarm.alarm.AlarmScheduler
import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.data.EventEntity
import com.alaimtiaz.calendaralarm.databinding.ActivityAlarmOverlayBinding
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
    private var externalIdHint: String? = null
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
        externalIdHint = intent.getStringExtra(EXTRA_EXTERNAL_ID)

        Log.d(TAG, "onCreate: eventId=$eventId externalIdHint=$externalIdHint")

        if (eventId == -1L && externalIdHint.isNullOrBlank()) {
            Log.w(TAG, "Started without event id or externalId — finishing")
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
                val dao = AppDatabase.getInstance(applicationContext).eventDao()
                var found: EventEntity? = null

                if (eventId > 0L) {
                    found = dao.getById(eventId)
                    if (found != null) {
                        Log.d(TAG, "Event loaded by id=$eventId")
                    } else {
                        Log.w(TAG, "Event id=$eventId not in DB (likely re-inserted by sync)")
                    }
                }

                if (found == null && !externalIdHint.isNullOrBlank()) {
                    found = dao.getByExternalIdAny(externalIdHint!!)
                    if (found != null) {
                        Log.d(TAG, "Event recovered via externalId=$externalIdHint id=${found.id}")
                        eventId = found.id
                    } else {
                        Log.w(TAG, "Event also not found by externalId=$externalIdHint")
                    }
                }
                found
            }
            event = e
            renderEvent(e)
            // Show the persistent notification after the event has loaded so we
            // can use the real event title (or fallback if null).
            showPersistentNotification(e)
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
        binding.btnEdit.setOnClickListener { openInCalendar() }
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
        val e = event ?: run {
            Toast.makeText(this, "تعذّر التأجيل — بيانات الحدث غير متوفرة", Toast.LENGTH_SHORT).show()
            return
        }
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AlarmScheduler(applicationContext).scheduleEvent(e, triggerOverride = triggerAt)
            }
            dismissPersistentNotification()
            stopAlarmEffects()
            finishAndRemoveTask()
        }
    }

    private fun dismissAlarm() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (eventId > 0L) {
                    AlarmScheduler(applicationContext).cancelEvent(eventId, externalIdHint)
                }
            }
            dismissPersistentNotification()
            stopAlarmEffects()
            finishAndRemoveTask()
        }
    }

    /**
     * Open the event in its native calendar app.
     * Simple, trust-the-system approach (matches what worked in previous versions).
     *
     * Note: We do NOT dismiss the persistent notification here — the user may
     * want to view in Google Calendar then come back and snooze/dismiss.
     */
    private fun openInCalendar() {
        val e = event
        // Extract the native calendar event ID (strip recurrence suffix if any)
        val externalEventIdLong = e?.externalId?.substringBefore("_")?.toLongOrNull()

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
                Log.d(TAG, "openInCalendar: launched ACTION_VIEW for event $externalEventIdLong")
                stopAlarmEffects()
                finishAndRemoveTask()
                return
            }
        } catch (ex: Exception) {
            Log.w(TAG, "openInCalendar: ACTION_VIEW failed", ex)
        }

        // Fallback: open Google Calendar app's launcher
        try {
            val launcher = packageManager.getLaunchIntentForPackage("com.google.android.calendar")
            if (launcher != null) {
                launcher.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(launcher)
                Log.d(TAG, "openInCalendar: launched Google Calendar via package launcher")
                stopAlarmEffects()
                finishAndRemoveTask()
                return
            }
        } catch (ex: Exception) {
            Log.w(TAG, "openInCalendar: launcher fallback failed", ex)
        }

        Toast.makeText(this, "تعذّر فتح تطبيق التقويم", Toast.LENGTH_SHORT).show()
    }

    /**
     * Show a persistent (ongoing) notification in the notification shade.
     * This notification:
     *  - cannot be swiped away by the user
     *  - shows a badge on the app icon
     *  - re-launches AlarmOverlayActivity when tapped
     *  - is removed only when user interacts with the alarm (Snooze or Dismiss)
     *
     * Purpose: ensure the user never misses an alarm even if AlarmOverlayActivity
     * gets killed by Samsung's task killer or overridden by Samsung Alarm clock.
     */
    private fun showPersistentNotification(e: EventEntity?) {
        val notificationId = if (eventId > 0L) eventId.toInt() else NOTIFICATION_ID_FALLBACK
        val title = e?.title?.takeIf { it.isNotBlank() } ?: "منبه نشط"

        // Tapping the notification re-opens this same alarm screen
        val openIntent = Intent(this, AlarmOverlayActivity::class.java).apply {
            putExtra(EXTRA_EVENT_ID, eventId)
            externalIdHint?.let { putExtra(EXTRA_EXTERNAL_ID, it) }
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val openPi = PendingIntent.getActivity(
            this,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            this,
            CalendarAlarmApplication.CHANNEL_MISSED_ALARMS
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(getString(R.string.active_alarm_text))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi)
            .setNumber(1)
            .build()

        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, notification)
            Log.d(TAG, "Persistent notification posted for eventId=$eventId")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to post persistent notification", ex)
        }
    }

    /**
     * Remove the persistent notification associated with this alarm.
     * Called only when the user has truly interacted (Snooze or Dismiss).
     */
    private fun dismissPersistentNotification() {
        val notificationId = if (eventId > 0L) eventId.toInt() else NOTIFICATION_ID_FALLBACK
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationId)
            Log.d(TAG, "Persistent notification dismissed for eventId=$eventId")
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to dismiss persistent notification", ex)
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
        const val EXTRA_EXTERNAL_ID = "extra_external_id"
        // Used only when eventId is negative (which shouldn't happen, but be safe)
        private const val NOTIFICATION_ID_FALLBACK = 99999
    }
}
