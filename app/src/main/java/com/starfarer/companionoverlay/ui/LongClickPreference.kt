package com.starfarer.companionoverlay.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Preference that supports long-click via [onLongClick].
 * The listener is reattached on every bind, surviving RecyclerView rebinds.
 */
class LongClickPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    var onLongClick: (() -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val callback = onLongClick
        if (callback != null) {
            holder.itemView.setOnLongClickListener {
                callback()
                true
            }
        }
    }
}
