package com.alaimtiaz.calendaralarm.util

import android.content.Context
import com.alaimtiaz.calendaralarm.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateUtils {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale("ar"))
    private val dateFormat = SimpleDateFormat("EEEE d MMMM yyyy", Locale("ar"))
    private val fullFormatWithYear = SimpleDateFormat("EEEE d MMMM yyyy، h:mm a", Locale("ar"))
    private val syncFormat = SimpleDateFormat("d MMM، h:mm a", Locale("ar"))

    fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

    fun formatDate(millis: Long): String = dateFormat.format(Date(millis))

    fun formatFull(context: Context, millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DATE, 1) }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DATE, -1) }

        val sameDay: (Calendar, Calendar) -> Boolean = { a, b ->
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }

        return when {
            sameDay(cal, today) -> "${context.getString(R.string.time_today)} • ${formatTime(millis)}"
            sameDay(cal, tomorrow) -> "${context.getString(R.string.time_tomorrow)} • ${formatTime(millis)}"
            sameDay(cal, yesterday) -> "${context.getString(R.string.time_yesterday)} • ${formatTime(millis)}"
            // All other events — always include the year
            else -> fullFormatWithYear.format(Date(millis))
        }
    }

    fun formatSync(millis: Long): String = syncFormat.format(Date(millis))
}
