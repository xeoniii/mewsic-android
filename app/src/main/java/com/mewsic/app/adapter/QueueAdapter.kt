package com.mewsic.app.adapter

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mewsic.app.databinding.ItemQueueSongBinding
import com.mewsic.app.model.Song
import com.mewsic.app.scanner.ThumbnailLoader
import java.util.Collections

class QueueAdapter(
    private val queueList: MutableList<Song>,
    private val onSongClicked: (Int, Song) -> Unit,
    private val onRemoveClicked: (Int, Song) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onQueueChanged: () -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val binding = ItemQueueSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return QueueViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(queueList[position])
    }

    override fun getItemCount(): Int = queueList.size

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(queueList, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(queueList, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        onQueueChanged()
    }

    fun removeItem(position: Int): Song? {
        if (position in 0 until queueList.size) {
            val removed = queueList.removeAt(position)
            notifyItemRemoved(position)
            onQueueChanged()
            return removed
        }
        return null
    }

    inner class QueueViewHolder(
        val binding: ItemQueueSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song) {
            binding.tvQueueSongTitle.text = song.title
            binding.tvQueueSongArtist.text = song.artist
            binding.tvQueueSongDuration.text = song.durationFormatted
            ThumbnailLoader.loadThumbnail(binding.ivQueueAlbumArt, song)

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onSongClicked(pos, song)
                }
            }

            binding.btnQueueRemove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onRemoveClicked(pos, song)
                }
            }

            binding.btnQueueDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }
        }
    }
}
