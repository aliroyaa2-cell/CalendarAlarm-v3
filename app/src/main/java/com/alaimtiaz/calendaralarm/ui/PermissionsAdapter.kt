package com.alaimtiaz.calendaralarm.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.alaimtiaz.calendaralarm.R
import com.alaimtiaz.calendaralarm.databinding.ItemPermissionBinding
import com.alaimtiaz.calendaralarm.permissions.PermissionsManager

class PermissionsAdapter(
    private val context: Context,
    private val onAction: (PermissionsManager.PermInfo) -> Unit
) : RecyclerView.Adapter<PermissionsAdapter.VH>() {

    private val items = mutableListOf<PermissionsManager.PermInfo>()

    fun submit(list: List<PermissionsManager.PermInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPermissionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemPermissionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(info: PermissionsManager.PermInfo) {
            b.tvTitle.text = context.getString(info.titleResId)
            b.tvDesc.text = context.getString(info.descResId)
            if (info.granted) {
                b.tvStatus.text = context.getString(R.string.perm_status_granted)
                b.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.success))
                b.btnAction.visibility = android.view.View.GONE
            } else {
                b.tvStatus.text = context.getString(R.string.perm_status_denied)
                b.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.error))
                b.btnAction.visibility = android.view.View.VISIBLE
                b.btnAction.text = context.getString(
                    if (info.isRuntime) R.string.perm_action_grant
                    else R.string.perm_action_open
                )
                b.btnAction.setOnClickListener { onAction(info) }
            }
        }
    }
}
