package com.mewsic.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mewsic.app.R
import com.mewsic.app.databinding.ItemPlaylistCardBinding
import com.mewsic.app.scanner.PlaylistInfo

class PlaylistAdapter(
    private val onPlaylistClicked: (playlist: PlaylistInfo) -> Unit,
    private val onPlaylistMoreClicked: (playlist: PlaylistInfo, anchorView: View) -> Unit
) : ListAdapter<PlaylistInfo, PlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaylistViewHolder(
        private val binding: ItemPlaylistCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: PlaylistInfo) {
            binding.tvPlaylistName.text = playlist.name
            val countStr = "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"}"
            binding.tvPlaylistTrackCount.text = countStr

            if (playlist.isDefault) {
                binding.ivPlaylistIcon.setImageResource(R.drawable.ic_heart)
                binding.btnPlaylistMore.visibility = View.GONE
            } else {
                binding.ivPlaylistIcon.setImageResource(R.drawable.ic_tab_playlist)
                binding.btnPlaylistMore.visibility = View.VISIBLE
            }

            binding.root.setOnClickListener {
                onPlaylistClicked(playlist)
            }

            binding.btnPlaylistMore.setOnClickListener {
                onPlaylistMoreClicked(playlist, it)
            }
        }
    }

    private class PlaylistDiffCallback : DiffUtil.ItemCallback<PlaylistInfo>() {
        override fun areItemsTheSame(oldItem: PlaylistInfo, newItem: PlaylistInfo): Boolean =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: PlaylistInfo, newItem: PlaylistInfo): Boolean =
            oldItem == newItem
    }
}
