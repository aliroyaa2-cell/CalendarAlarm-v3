package com.alaimtiaz.calendaralarm.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alaimtiaz.calendaralarm.CalendarAlarmApplication
import com.alaimtiaz.calendaralarm.MainActivity
import com.alaimtiaz.calendaralarm.R
import com.alaimtiaz.calendaralarm.alarm.AlarmScheduler
import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.repository.SourceCalendarsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that:
 *  1. Registers a ContentObserver on CalendarContract to detect changes immediately.
 *  2. Periodically performs a full sync (every 60 minutes) as a safety net.
 *  3. Reschedules alarms after every sync.
 */
class CalendarSyncService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val syncMutex = Mutex()
    private var periodicJob: Job? = null
    private var observer: ContentObserver? = null

    private lateinit var sourceRepo: SourceCalendarsRepository
    private lateinit var scheduler: AlarmScheduler

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        val db = AppDatabase.getInstance(applicationContext)
        sourceRepo = SourceCalendarsRepository(applicationContext, db)
        scheduler = AlarmScheduler(applicationContext)

        startInForeground()
        registerContentObserver()
        startPeriodicSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SYNC_NOW) {
            Log.d(TAG, "Manual sync requested")
            triggerSync()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        observer?.let { contentResolver.unregisterContentObserver(it) }
        periodicJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun startInForeground() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(
            this, CalendarAlarmApplication.CHANNEL_SYNC
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(getString(R.string.sync_notification_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerContentObserver() {
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "Calendar provider changed (selfChange=$selfChange)")
                triggerSync()
            }
        }
        contentResolver.registerContentObserver(
            CalendarContract.Events.CONTENT_URI,
            true,
            observer!!
        )
        contentResolver.registerContentObserver(
            CalendarContract.Calendars.CONTENT_URI,
            true,
            observer!!
        )
    }

    private fun startPeriodicSync() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            // Initial sync 5s after start
            delay(5_000L)
            performSync()
            // Then every 60 minutes
            while (true) {
                delay(60L * 60L * 1000L)
                performSync()
            }
        }
    }

    private fun triggerSync() {
        scope.launch { performSync() }
    }

    private suspend fun performSync() {
        // Coalesce concurrent triggers
        if (!syncMutex.tryLock()) {
            Log.d(TAG, "Sync already in progress, skipping")
            return
        }
        try {
            Log.d(TAG, "Starting sync…")
            sourceRepo.refreshSourceCalendars()
            val result = sourceRepo.syncEnabledCalendars()
            scheduler.rescheduleAll()
            Log.d(TAG, "Sync done. inserted=${result.inserted} updated=${result.updated}")
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
        } finally {
            syncMutex.unlock()
        }
    }

    companion object {
        private const val TAG = "CalendarSyncService"
        private const val NOTIFICATION_ID = 42

        const val ACTION_SYNC_NOW = "com.alaimtiaz.calendaralarm.SYNC_NOW"

        fun start(context: Context) {
            val intent = Intent(context, CalendarSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun syncNow(context: Context) {
            val intent = Intent(context, CalendarSyncService::class.java).apply {
                action = ACTION_SYNC_NOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
