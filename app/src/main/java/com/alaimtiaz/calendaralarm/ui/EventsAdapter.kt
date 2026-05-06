package com.alaimtiaz.calendaralarm.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.alaimtiaz.calendaralarm.R
import com.alaimtiaz.calendaralarm.data.EventEntity
import com.alaimtiaz.calendaralarm.databinding.ItemEventBinding
import com.alaimtiaz.calendaralarm.util.DateUtils

class EventsAdapter(
    private val context: Context,
    private val onItemClick: ((EventEntity) -> Unit)? = null
) : ListAdapter<EventEntity, EventsAdapter.VH>(DIFF) {

    fun submit(list: List<EventEntity>) = submitList(list)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val b: ItemEventBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: EventEntity) {
            b.tvTitle.text = e.title
            b.tvTime.text = DateUtils.formatFull(context, e.startTime)
            b.tvSource.text = when (e.source) {
                EventEntity.SOURCE_GOOGLE -> "Google • ${e.accountName}"
                EventEntity.SOURCE_SAMSUNG -> "Samsung • ${e.accountName}"
                EventEntity.SOURCE_OUTLOOK -> "Outlook • ${e.accountName}"
                else -> e.accountName
            }
            // Color stripe
            val color = if (e.calendarColor != 0) e.calendarColor
                else androidx.core.content.ContextCompat.getColor(context, R.color.primary)
            b.colorStripe.setBackgroundColor(color)

            if (!e.location.isNullOrBlank()) {
                b.tvLocation.visibility = android.view.View.VISIBLE
                b.tvLocation.text = "📍 ${e.location}"
            } else b.tvLocation.visibility = android.view.View.GONE

            // Forward click to MainActivity
            b.root.setOnClickListener { onItemClick?.invoke(e) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<EventEntity>() {
            override fun areItemsTheSame(a: EventEntity, b: EventEntity) = a.id == b.id
            override fun areContentsTheSame(a: EventEntity, b: EventEntity) = a == b
        }
    }
}
