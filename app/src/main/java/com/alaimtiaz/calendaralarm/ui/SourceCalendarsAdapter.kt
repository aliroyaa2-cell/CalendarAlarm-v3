package com.alaimtiaz.calendaralarm.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.alaimtiaz.calendaralarm.data.SourceCalendarEntity
import com.alaimtiaz.calendaralarm.databinding.ItemAccountHeaderBinding
import com.alaimtiaz.calendaralarm.databinding.ItemSourceCalendarBinding

class SourceCalendarsAdapter(
    private val onToggle: (SourceCalendarEntity, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val accountKey: String, val displayLabel: String) : Row()
        data class Item(val cal: SourceCalendarEntity) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(list: List<SourceCalendarEntity>) {
        rows.clear()
        // Group by accountName + accountType
        val grouped = list.groupBy { "${it.accountType}|${it.accountName}" }
        for ((key, calendars) in grouped) {
            val parts = key.split("|", limit = 2)
            val accountType = parts.getOrNull(0).orEmpty()
            val accountName = parts.getOrNull(1).orEmpty()
            val typeLabel = when {
                accountType.contains("google", true) -> "Google"
                accountType.contains("samsung", true) -> "Samsung"
                accountType.contains("outlook", true) ||
                    accountType.contains("eas", true) -> "Outlook"
                accountType.contains("local", true) -> "محلي"
                else -> accountType
            }
            rows.add(Row.Header(key, "$typeLabel — $accountName"))
            calendars.forEach { rows.add(Row.Item(it)) }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(
                ItemAccountHeaderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
            else -> ItemVH(
                ItemSourceCalendarBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (row) {
            is Row.Header -> (holder as HeaderVH).bind(row)
            is Row.Item -> (holder as ItemVH).bind(row.cal)
        }
    }

    private inner class HeaderVH(private val b: ItemAccountHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(h: Row.Header) {
            b.tvHeader.text = h.displayLabel
        }
    }

    private inner class ItemVH(private val b: ItemSourceCalendarBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(cal: SourceCalendarEntity) {
            b.tvName.text = cal.displayName
            b.colorDot.setBackgroundColor(
                if (cal.color != 0) cal.color else 0xFF2196F3.toInt()
            )
            // Avoid firing callback while we set the initial state
            b.switchEnabled.setOnCheckedChangeListener(null)
            b.switchEnabled.isChecked = cal.isEnabled
            b.switchEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(cal, checked)
            }
            b.root.setOnClickListener { b.switchEnabled.isChecked = !b.switchEnabled.isChecked }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
