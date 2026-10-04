package com.mewsic.app.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mewsic.app.R
import com.mewsic.app.databinding.ItemSongRowBinding
import com.mewsic.app.model.Song
import com.mewsic.app.scanner.ThumbnailLoader

class SongAdapter(
    private val onSongClicked: (song: Song, position: Int) -> Unit,
    private val onSongLongClicked: ((song: Song, position: Int) -> Unit)? = null
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    var activeSongId: Long? = null
        set(value) {
            val oldId = field
            field = value
            if (oldId != value) {
                val oldPos = currentList.indexOfFirst { it.id == oldId }
                val newPos = currentList.indexOfFirst { it.id == value }
                if (oldPos != -1) notifyItemChanged(oldPos)
                if (newPos != -1) notifyItemChanged(newPos)
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class SongViewHolder(
        private val binding: ItemSongRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, position: Int) {
            val isActive = song.id == activeSongId

            binding.tvSongArtist.text = song.artist
            binding.tvSongDuration.text = song.durationFormatted

            // Desktop Mewsic card style
            if (isActive) {
                binding.ivTrackStatus.visibility = View.VISIBLE
                binding.ivTrackStatus.setImageResource(R.drawable.ic_equalizer)
                binding.tvTrackIndex.visibility = View.GONE

                binding.tvSongTitle.text = "• ${song.title}"
                binding.tvSongTitle.setTextColor(Color.parseColor("#1ADA6B"))

                if (song.album.isNotBlank() && song.album != "Unknown Album") {
                    binding.tvSongAlbum.text = "• ${song.album}"
                    binding.tvSongAlbum.visibility = View.VISIBLE
                } else {
                    binding.tvSongAlbum.visibility = View.GONE
                }

                binding.songRowContainer.setBackgroundResource(R.drawable.bg_song_card_active)
            } else {
                binding.ivTrackStatus.visibility = View.GONE
                binding.tvTrackIndex.visibility = View.VISIBLE
                binding.tvTrackIndex.text = (position + 1).toString()
                binding.tvTrackIndex.setTextColor(Color.parseColor("#898D8E"))

                binding.tvSongTitle.text = song.title
                binding.tvSongTitle.setTextColor(Color.parseColor("#FFFFFF"))

                if (song.album.isNotBlank() && song.album != "Unknown Album") {
                    binding.tvSongAlbum.text = song.album
                    binding.tvSongAlbum.visibility = View.VISIBLE
                } else {
                    binding.tvSongAlbum.visibility = View.GONE
                }

                binding.songRowContainer.setBackgroundResource(R.drawable.bg_song_card_idle)
            }

            // Load album art thumbnail asynchronously with LRU cache & PC app fallback
            ThumbnailLoader.loadThumbnail(binding.ivAlbumArt, song)

            binding.root.setOnClickListener {
                onSongClicked(song, bindingAdapterPosition)
            }

            binding.root.setOnLongClickListener {
                onSongLongClicked?.invoke(song, bindingAdapterPosition)
                true
            }
        }
    }

    private class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean =
            oldItem == newItem
    }
}
