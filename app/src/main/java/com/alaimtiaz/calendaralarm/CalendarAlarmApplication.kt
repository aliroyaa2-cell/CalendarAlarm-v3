package com.alaimtiaz.calendaralarm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.alaimtiaz.calendaralarm.alarm.MissedAlarmChecker
import java.util.concurrent.TimeUnit

class CalendarAlarmApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        scheduleHeartbeat()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Alarm channel: HIGH so heads-up appears
        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            getString(R.string.channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_alarm_desc)
            enableVibration(true)
            enableLights(true)
            setBypassDnd(true)
            setShowBadge(true)
            // The actual sound is played via MediaPlayer in AlarmOverlayActivity
            // (notification channel sound is set once and can't change per event).
            setSound(null, null)
        }
        nm.createNotificationChannel(alarmChannel)

        // Sync service channel: LOW (silent persistent notification)
        val syncChannel = NotificationChannel(
            CHANNEL_SYNC,
            getString(R.string.channel_sync_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_sync_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(syncChannel)

        // Missed alarms channel: DEFAULT priority, silent, but shows badge on icon.
        // Used for the persistent ongoing notification that survives until user
        // interacts with the alarm (Snooze or Dismiss).
        val missedChannel = NotificationChannel(
            CHANNEL_MISSED_ALARMS,
            getString(R.string.channel_missed_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_missed_desc)
            enableVibration(false)
            enableLights(false)
            setBypassDnd(false)
            setShowBadge(true)
            setSound(null, null)
        }
        nm.createNotificationChannel(missedChannel)
    }

    /**
     * Schedule the MissedAlarmChecker as a periodic 15-minute heartbeat.
     * This is Layer 3 of our alarm reliability strategy:
     *  1. AlarmManager.setAlarmClock — primary mechanism
     *  2. Foreground sync service — keeps app alive
     *  3. WorkManager heartbeat — catches missed alarms when 1 & 2 fail
     *
     * 15 minutes is Android's minimum periodic interval. WorkManager survives
     * Samsung's aggressive task killer better than AlarmManager because it
     * uses JobScheduler under the hood.
     */
    private fun scheduleHeartbeat() {
        val request = PeriodicWorkRequestBuilder<MissedAlarmChecker>(
            15L, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MissedAlarmChecker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val CHANNEL_ALARM = "alarm_channel"
        const val CHANNEL_SYNC = "sync_channel"
        const val CHANNEL_MISSED_ALARMS = "missed_alarms_channel"
    }
}
