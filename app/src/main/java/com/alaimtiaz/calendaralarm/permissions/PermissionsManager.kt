package com.alaimtiaz.calendaralarm.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.alaimtiaz.calendaralarm.R

/**
 * Centralized permission state and "open settings" intents.
 * Used by the warning banner and SettingsActivity.
 */
class PermissionsManager(private val context: Context) {

    enum class PermKey {
        CALENDAR,
        NOTIFICATIONS,
        FULL_SCREEN_INTENT,
        EXACT_ALARM,
        BATTERY_OPTIMIZATION,
        AUDIO_MEDIA
    }

    data class PermInfo(
        val key: PermKey,
        val titleResId: Int,
        val descResId: Int,
        val granted: Boolean,
        val isRuntime: Boolean
    )

    fun all(): List<PermInfo> = listOf(
        PermInfo(PermKey.CALENDAR, R.string.perm_calendar, R.string.perm_calendar_desc,
            isCalendarGranted(), true),
        PermInfo(PermKey.NOTIFICATIONS, R.string.perm_notifications, R.string.perm_notifications_desc,
            isNotificationsGranted(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
        PermInfo(PermKey.FULL_SCREEN_INTENT, R.string.perm_full_screen, R.string.perm_full_screen_desc,
            isFullScreenIntentGranted(), false),
        PermInfo(PermKey.EXACT_ALARM, R.string.perm_exact_alarm, R.string.perm_exact_alarm_desc,
            isExactAlarmGranted(), false),
        PermInfo(PermKey.BATTERY_OPTIMIZATION, R.string.perm_battery, R.string.perm_battery_desc,
            isBatteryOptimizationDisabled(), false),
        PermInfo(PermKey.AUDIO_MEDIA, R.string.perm_audio, R.string.perm_audio_desc,
            isAudioGranted(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    )

    fun missingCount(): Int = all().count { !it.granted }

    fun allGranted(): Boolean = missingCount() == 0

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun isCalendarGranted(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    fun isNotificationsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun isAudioGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun isFullScreenIntentGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.canUseFullScreenIntent()
        } else true
    }

    fun isExactAlarmGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }

    fun isBatteryOptimizationDisabled(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Returns the runtime permission string for [key] if applicable,
     * or null if it's a Settings-based permission.
     */
    fun runtimePermissionFor(key: PermKey): String? = when (key) {
        PermKey.CALENDAR -> Manifest.permission.READ_CALENDAR
        PermKey.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.POST_NOTIFICATIONS else null
        PermKey.AUDIO_MEDIA ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO else null
        else -> null
    }

    /**
     * Returns an intent to open the relevant Settings screen for a Special permission,
     * or null for runtime permissions (caller should request via ActivityResultContracts).
     */
    fun settingsIntentFor(key: PermKey): Intent? = when (key) {
        PermKey.FULL_SCREEN_INTENT -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else null
        }
        PermKey.EXACT_ALARM -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else null
        }
        PermKey.BATTERY_OPTIMIZATION -> {
            @Suppress("BatteryLife")
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        PermKey.NOTIFICATIONS -> {
            // For Android 13 we use runtime; on older versions point to app notification settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            } else null
        }
        else -> null
    }

    fun openAppDetailsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Opens an OEM-specific Battery / Background screen if available — falls back to app details.
     */
    fun openOemBatterySettings(): Intent {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("samsung") -> Intent().apply {
                component = android.content.ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            }
            manufacturer.contains("xiaomi") -> Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            manufacturer.contains("huawei") -> Intent().apply {
                component = android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
            else -> openAppDetailsIntent()
        }
    }
}
