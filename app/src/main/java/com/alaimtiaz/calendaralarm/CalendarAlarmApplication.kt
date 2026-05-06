package com.alaimtiaz.calendaralarm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CalendarAlarmApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
    }

    companion object {
        const val CHANNEL_ALARM = "alarm_channel"
        const val CHANNEL_SYNC = "sync_channel"
    }
}
