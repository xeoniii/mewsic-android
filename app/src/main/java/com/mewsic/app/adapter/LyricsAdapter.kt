package com.mewsic.app.adapter

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mewsic.app.databinding.ItemLyricLineBinding
import com.mewsic.app.scanner.LyricLine
import com.mewsic.app.scanner.LyricsData

class LyricsAdapter(
    private val onLineClicked: (LyricLine, Int) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    private var lyricsData: LyricsData = LyricsData(false, emptyList())
    private var activeIndex: Int = -1

    fun submitLyrics(data: LyricsData) {
        lyricsData = data
        activeIndex = -1
        notifyDataSetChanged()
    }

    fun setActiveIndex(index: Int): Boolean {
        if (activeIndex == index) return false
        val prev = activeIndex
        activeIndex = index
        if (prev in lyricsData.lines.indices) {
            notifyItemChanged(prev)
        }
        if (activeIndex in lyricsData.lines.indices) {
            notifyItemChanged(activeIndex)
        }
        return true
    }

    fun getActiveIndex(): Int = activeIndex

    fun getLines(): List<LyricLine> = lyricsData.lines

    fun isSynced(): Boolean = lyricsData.isSynced

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val binding = ItemLyricLineBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LyricViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        val line = lyricsData.lines[position]
        val isActive = lyricsData.isSynced && position == activeIndex

        holder.binding.tvLyricText.text = line.text

        if (lyricsData.isSynced) {
            if (isActive) {
                holder.binding.tvLyricText.setTextColor(Color.WHITE)
                holder.binding.tvLyricText.alpha = 1.0f
                holder.binding.tvLyricText.textSize = 21f
                holder.binding.tvLyricText.setTypeface(null, Typeface.BOLD)
            } else {
                holder.binding.tvLyricText.setTextColor(Color.parseColor("#94A3B8"))
                holder.binding.tvLyricText.alpha = 0.40f
                holder.binding.tvLyricText.textSize = 17f
                holder.binding.tvLyricText.setTypeface(null, Typeface.NORMAL)
            }
        } else {
            // Unsynced plain text
            holder.binding.tvLyricText.setTextColor(Color.parseColor("#E2E8F0"))
            holder.binding.tvLyricText.alpha = 0.85f
            holder.binding.tvLyricText.textSize = 17f
            holder.binding.tvLyricText.setTypeface(null, Typeface.NORMAL)
        }

        holder.itemView.setOnClickListener {
            if (line.timeMs >= 0) {
                onLineClicked(line, position)
            }
        }
    }

    override fun getItemCount(): Int = lyricsData.lines.size

    class LyricViewHolder(val binding: ItemLyricLineBinding) :
        RecyclerView.ViewHolder(binding.root)
}
