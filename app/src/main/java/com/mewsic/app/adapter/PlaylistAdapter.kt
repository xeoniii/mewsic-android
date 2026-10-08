package com.mewsic.app.adapter

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mewsic.app.R
import com.mewsic.app.databinding.ItemPlaylistCardBinding
import com.mewsic.app.scanner.PlaylistInfo
import com.mewsic.app.scanner.ThumbnailLoader

class PlaylistAdapter(
    private val onPlaylistClicked: (playlist: PlaylistInfo) -> Unit,
    private val onPlaylistMoreClicked: (playlist: PlaylistInfo, anchorView: View) -> Unit
) : ListAdapter<PlaylistInfo, PlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    companion object {
        private const val PAYLOAD_COVER_ART = "payload_cover_art"
    }

    var areCoverArtsRendered: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, PAYLOAD_COVER_ART)
            }
        }

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

    override fun onBindViewHolder(
        holder: PlaylistViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_COVER_ART)) {
            holder.updateCoverArt(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class PlaylistViewHolder(
        private val binding: ItemPlaylistCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: PlaylistInfo) {
            binding.tvPlaylistName.text = playlist.name
            val countStr = "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"}"
            binding.tvPlaylistTrackCount.text = countStr
            binding.btnPlaylistMore.visibility = View.VISIBLE

            updateCoverArt(playlist)

            binding.root.setOnClickListener {
                onPlaylistClicked(playlist)
            }

            binding.btnPlaylistMore.setOnClickListener {
                onPlaylistMoreClicked(playlist, it)
            }
        }

        fun updateCoverArt(playlist: PlaylistInfo) {
            if (areCoverArtsRendered) {
                if (playlist.firstSong != null) {
                    binding.ivPlaylistCover.scaleType = ImageView.ScaleType.CENTER_CROP
                    binding.ivPlaylistCover.setPadding(0, 0, 0, 0)
                    binding.ivPlaylistCover.clearColorFilter()
                    ThumbnailLoader.loadThumbnail(binding.ivPlaylistCover, playlist.firstSong)
                } else {
                    binding.ivPlaylistCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val pad = (12 * binding.root.resources.displayMetrics.density).toInt()
                    binding.ivPlaylistCover.setPadding(pad, pad, pad, pad)
                    binding.ivPlaylistCover.setImageResource(R.drawable.ic_tab_playlist)
                    binding.ivPlaylistCover.setColorFilter(
                        ContextCompat.getColor(binding.root.context, R.color.tab_active_tint),
                        PorterDuff.Mode.SRC_IN
                    )
                }
            } else {
                binding.ivPlaylistCover.setImageDrawable(null)
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
