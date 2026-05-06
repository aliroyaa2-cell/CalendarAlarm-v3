package com.alaimtiaz.calendaralarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-schedules every active alarm after the device boots.
 *
 * Note: per Android 3.1+ policy, the user must have launched the app at least
 * once after install for this receiver to fire.
 */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) {
            Log.d(TAG, "Ignoring unknown action: $action")
            return
        }

        Log.d(TAG, "Boot action received: $action — rescheduling alarms")

        val pendingResult = goAsync()
        scope.launch {
            try {
                val scheduler = AlarmScheduler(context.applicationContext)
                val count = scheduler.rescheduleAll()
                Log.d(TAG, "Rescheduled $count alarms after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule alarms on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
