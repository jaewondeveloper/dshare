package com.dshare.sender

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(private val onClick: (DiscoveredDevice) -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private val items = mutableListOf<DiscoveredDevice>()

    fun submitList(devices: List<DiscoveredDevice>) {
        items.clear()
        items.addAll(devices)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val nameText: android.widget.TextView = itemView.findViewById(R.id.deviceName)
        val card: android.view.View = itemView.findViewById(R.id.deviceCard)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = items[position]
        holder.nameText.text = device.name
        holder.card.setOnClickListener { onClick(device) }
    }

    override fun getItemCount(): Int = items.size
}
