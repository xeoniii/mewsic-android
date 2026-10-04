package com.mewsic.app.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mewsic.app.databinding.ItemSongRowBinding
import com.mewsic.app.model.Song
import com.mewsic.app.scanner.ThumbnailLoader

class SongAdapter(
    private val onSongClicked: (song: Song, position: Int) -> Unit
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    var activeSongId: Long? = null
        set(value) {
            val oldId = field
            field = value
            if (oldId != value) {
                notifyDataSetChanged()
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

            binding.tvTrackIndex.text = (position + 1).toString()
            binding.tvSongTitle.text = song.title
            binding.tvSongArtist.text = song.artist
            binding.tvSongDuration.text = song.durationFormatted

            if (song.album.isNotBlank() && song.album != "Unknown Album") {
                binding.tvSongAlbum.text = song.album
                binding.tvSongAlbum.visibility = android.view.View.VISIBLE
            } else {
                binding.tvSongAlbum.visibility = android.view.View.GONE
            }

            // Active track styling (green title & index matching Mewsic PC app)
            if (isActive) {
                binding.tvSongTitle.setTextColor(Color.parseColor("#1ADA6B"))
                binding.tvTrackIndex.setTextColor(Color.parseColor("#1ADA6B"))
                binding.songRowContainer.setBackgroundColor(Color.parseColor("#151ADA6B"))
            } else {
                binding.tvSongTitle.setTextColor(Color.parseColor("#FFFFFF"))
                binding.tvTrackIndex.setTextColor(Color.parseColor("#898D8E"))
                binding.songRowContainer.setBackgroundColor(Color.TRANSPARENT)
            }

            // Load album art thumbnail asynchronously with LRU cache & PC app fallback
            ThumbnailLoader.loadThumbnail(binding.ivAlbumArt, song)

            binding.root.setOnClickListener {
                onSongClicked(song, bindingAdapterPosition)
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
