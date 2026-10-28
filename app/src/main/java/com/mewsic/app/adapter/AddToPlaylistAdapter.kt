package com.mewsic.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mewsic.app.R
import com.mewsic.app.databinding.ItemAddToPlaylistBinding
import com.mewsic.app.scanner.PlaylistInfo

class AddToPlaylistAdapter(
    private val existingSongPlaylistNames: Set<String>,
    private val onPlaylistSelected: (playlist: PlaylistInfo) -> Unit
) : RecyclerView.Adapter<AddToPlaylistAdapter.ViewHolder>() {

    private var items: List<PlaylistInfo> = emptyList()

    fun submitList(list: List<PlaylistInfo>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAddToPlaylistBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemAddToPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: PlaylistInfo) {
            binding.tvAddToPlaylistName.text = playlist.name
            binding.tvAddToPlaylistTrackCount.text =
                "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"}"

            val isAlreadyAdded = existingSongPlaylistNames.contains(playlist.name)
            if (isAlreadyAdded) {
                binding.tvAddedBadge.visibility = View.VISIBLE
                binding.ivAddToPlaylistIcon.setImageResource(R.drawable.ic_check)
            } else {
                binding.tvAddedBadge.visibility = View.GONE
                binding.ivAddToPlaylistIcon.setImageResource(R.drawable.ic_add_circle_outline)
            }

            binding.root.setOnClickListener {
                onPlaylistSelected(playlist)
            }
        }
    }
}
