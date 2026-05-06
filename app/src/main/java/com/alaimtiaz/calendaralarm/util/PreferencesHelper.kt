package com.alaimtiaz.calendaralarm.util

import android.content.Context
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.content.edit

class PreferencesHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var defaultRingtoneUri: Uri?
        get() {
            val str = prefs.getString(KEY_RINGTONE_URI, null)
                ?: return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            return Uri.parse(str)
        }
        set(value) = prefs.edit { putString(KEY_RINGTONE_URI, value?.toString()) }

    var phase1DurationSeconds: Int
        get() = prefs.getInt(KEY_PHASE1_DURATION, DEFAULT_PHASE1_SECONDS)
        set(value) = prefs.edit { putInt(KEY_PHASE1_DURATION, value.coerceIn(3, 60)) }

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION, true)
        set(value) = prefs.edit { putBoolean(KEY_VIBRATION, value) }

    /**
     * Has the user opened the app at least once?
     * Used by BootReceiver-related logic and welcome state.
     */
    var hasOpenedBefore: Boolean
        get() = prefs.getBoolean(KEY_OPENED_BEFORE, false)
        set(value) = prefs.edit { putBoolean(KEY_OPENED_BEFORE, value) }

    companion object {
        private const val NAME = "calendar_alarm_prefs"
        private const val KEY_RINGTONE_URI = "ringtone_uri"
        private const val KEY_PHASE1_DURATION = "phase1_duration_sec"
        private const val KEY_VIBRATION = "vibration_enabled"
        private const val KEY_OPENED_BEFORE = "opened_before"

        const val DEFAULT_PHASE1_SECONDS = 8
    }
}
