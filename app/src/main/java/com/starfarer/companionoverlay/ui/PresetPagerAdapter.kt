package com.starfarer.companionoverlay.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.starfarer.companionoverlay.CharacterPreset
import com.starfarer.companionoverlay.R

/**
 * Adapter for the preset carousel ViewPager2.
 *
 * Each page shows the idle and walk sprites for a preset.
 * Sprite loading and animation is handled by the host via callbacks.
 */
class PresetPagerAdapter(
    private val onIdleSpriteClick: (Int) -> Unit,
    private val onWalkSpriteClick: (Int) -> Unit
) : RecyclerView.Adapter<PresetPagerAdapter.PresetViewHolder>() {

    private var presets: List<CharacterPreset> = emptyList()
    private val spriteFrames = mutableMapOf<Int, SpriteFrames>()

    data class SpriteFrames(
        var idleFrame: Bitmap? = null,
        var walkFrame: Bitmap? = null
    )

    fun submitList(newPresets: List<CharacterPreset>) {
        presets = newPresets
        spriteFrames.clear()
        notifyDataSetChanged()
    }

    fun updateSpriteFrame(position: Int, idleFrame: Bitmap?, walkFrame: Bitmap?) {
        val frames = spriteFrames.getOrPut(position) { SpriteFrames() }
        frames.idleFrame = idleFrame
        frames.walkFrame = walkFrame
        notifyItemChanged(position, PAYLOAD_SPRITE_UPDATE)
    }

    override fun getItemCount(): Int = presets.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_preset_card, parent, false)
        return PresetViewHolder(view)
    }

    override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onBindViewHolder(holder: PresetViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_SPRITE_UPDATE)) {
            holder.updateSprites(position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class PresetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val idlePreview: ImageView = itemView.findViewById(R.id.idleSpritePreview)
        private val walkPreview: ImageView = itemView.findViewById(R.id.walkSpritePreview)
        private val idleCard: View = itemView.findViewById(R.id.idleSpriteCard)
        private val walkCard: View = itemView.findViewById(R.id.walkSpriteCard)

        fun bind(position: Int) {
            idleCard.setOnClickListener { onIdleSpriteClick(position) }
            walkCard.setOnClickListener { onWalkSpriteClick(position) }
            updateSprites(position)
        }

        fun updateSprites(position: Int) {
            val frames = spriteFrames[position]
            idlePreview.setImageBitmap(frames?.idleFrame)
            walkPreview.setImageBitmap(frames?.walkFrame)
        }
    }

    companion object {
        private const val PAYLOAD_SPRITE_UPDATE = "sprite_update"
    }
}
