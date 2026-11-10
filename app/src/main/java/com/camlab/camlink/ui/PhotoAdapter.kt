package com.camlab.camlink.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.camlab.camlink.R
import com.camlab.camlink.databinding.ItemPhotoBinding
import com.camlab.camlink.net.MediaItem

class PhotoAdapter(
    context: Context,
    private val onToggle: (MediaItem) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.Holder>() {

    private val appContext: Context = context.applicationContext
    private var items: List<MediaItem> = emptyList()
    private var selected: Set<String> = emptySet()
    private val brand = ContextCompat.getColor(appContext, R.color.brand)

    fun submitItems(newItems: List<MediaItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition].id == newItems[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition] == newItems[newItemPosition]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    fun submitSelection(ids: Set<String>) {
        val previous = selected
        selected = ids
        for (index in items.indices) {
            val was = items[index].id in previous
            val isNow = items[index].id in ids
            if (was != isNow) notifyItemChanged(index, PAYLOAD_SELECTION)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], items[position].id in selected)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(items[position], items[position].id in selected)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class Holder(private val binding: ItemPhotoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // The card is clickable (it draws the press ripple), so the click
            // listener must live on the card itself. A listener on the root
            // never fires because the card consumes the touch.
            binding.photoCard.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onToggle(items[position])
            }
        }

        fun bind(item: MediaItem, isSelected: Boolean) {
            binding.videoBadge.visibility = if (item.isVideo) ViewGroup.VISIBLE else ViewGroup.GONE

            if (item.isVideo && item.thumbUrl == null) {
                binding.thumb.setImageResource(R.drawable.ph_video)
            } else {
                val night = (binding.thumb.context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val placeholder = ContextCompat.getColor(
                    binding.thumb.context,
                    if (night) R.color.placeholder_dark else R.color.placeholder
                )
                Glide.with(binding.thumb)
                    .load(item.thumbUrl ?: item.url)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(android.graphics.drawable.ColorDrawable(placeholder))
                    .error(R.drawable.ph_photo)
                    .into(binding.thumb)
            }
            bindSelection(item, isSelected)
        }

        fun bindSelection(item: MediaItem, isSelected: Boolean) {
            binding.check.setImageResource(
                if (isSelected) R.drawable.ic_check_circle_24 else R.drawable.ic_circle_outline_24
            )
            binding.photoCard.strokeColor = if (isSelected) brand else Color.TRANSPARENT
            binding.photoCard.strokeWidth = dp(2)
            binding.photoCard.isSelected = isSelected
            binding.thumb.alpha = if (isSelected) 1f else 0.8f
            binding.photoCard.contentDescription = binding.root.context.getString(
                if (isSelected) R.string.photo_selected else R.string.photo_unselected,
                item.title.ifBlank { item.id }
            )
        }

        private fun dp(value: Int): Int = (value * binding.root.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"
    }
}
