package com.mewsic.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mewsic.app.databinding.ItemBlankPageBinding
import com.mewsic.app.databinding.PageHomeBinding
import com.mewsic.app.databinding.PageLibraryBinding
import com.mewsic.app.scanner.LibraryStats

class MainPagerAdapter(
    private val homeAdapter: SongAdapter,
    private val libraryAdapter: SongAdapter,
    private val onExploreLibraryClicked: () -> Unit,
    private val onRequestPermissionClicked: () -> Unit,
    private val onRescanLibraryClicked: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val PAGE_HOME = 0
        const val PAGE_HARBOUR = 1
        const val PAGE_LIBRARY = 2
        const val PAGE_PLAYLIST = 3
        const val PAGE_SETTINGS = 4
    }

    private var currentStats: LibraryStats? = null
    private var hasSongs: Boolean = false
    private var totalTrackCount: Int = 0

    private var homeViewHolder: HomeViewHolder? = null
    private var libraryViewHolder: LibraryViewHolder? = null

    fun updateData(stats: LibraryStats?, count: Int) {
        currentStats = stats
        totalTrackCount = count
        hasSongs = count > 0
        homeViewHolder?.bind(currentStats, hasSongs)
        libraryViewHolder?.bind(totalTrackCount, hasSongs)
    }

    override fun getItemCount(): Int = 5

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            PAGE_HOME -> {
                val binding = PageHomeBinding.inflate(inflater, parent, false)
                HomeViewHolder(binding)
            }
            PAGE_LIBRARY -> {
                val binding = PageLibraryBinding.inflate(inflater, parent, false)
                LibraryViewHolder(binding)
            }
            else -> {
                val binding = ItemBlankPageBinding.inflate(inflater, parent, false)
                BlankViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HomeViewHolder -> {
                homeViewHolder = holder
                holder.init()
                holder.bind(currentStats, hasSongs)
            }
            is LibraryViewHolder -> {
                libraryViewHolder = holder
                holder.init()
                holder.bind(totalTrackCount, hasSongs)
            }
            is BlankViewHolder -> {
                val title = when (position) {
                    PAGE_HARBOUR -> "Harbour\nDownload & Stream Music"
                    PAGE_PLAYLIST -> "Playlists\nYour custom collections"
                    PAGE_SETTINGS -> "Settings\nApp Preferences"
                    else -> ""
                }
                holder.bind(title)
            }
        }
    }

    inner class HomeViewHolder(
        val binding: PageHomeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun init() {
            if (binding.rvRecentlyAdded.adapter == null) {
                binding.rvRecentlyAdded.layoutManager = LinearLayoutManager(itemView.context)
                binding.rvRecentlyAdded.adapter = homeAdapter
                binding.rvRecentlyAdded.isNestedScrollingEnabled = false
            }

            binding.btnExploreLibrary.setOnClickListener {
                onExploreLibraryClicked()
            }

            binding.btnScanPermission.setOnClickListener {
                onRequestPermissionClicked()
            }
        }

        fun bind(stats: LibraryStats?, hasSongs: Boolean) {
            if (stats != null) {
                binding.tvTotalTracksValue.text = stats.totalTracks.toString()
                binding.tvUniqueArtistsValue.text = stats.uniqueArtists.toString()
                binding.tvTotalAlbumsValue.text = stats.totalAlbums.toString()
                binding.tvPlaybackTimeValue.text = stats.formattedDuration
            } else {
                binding.tvTotalTracksValue.text = "0"
                binding.tvUniqueArtistsValue.text = "0"
                binding.tvTotalAlbumsValue.text = "0"
                binding.tvPlaybackTimeValue.text = "0m 00s"
            }

            if (hasSongs) {
                binding.homeEmptyState.visibility = View.GONE
                binding.rvRecentlyAdded.visibility = View.VISIBLE
            } else {
                binding.homeEmptyState.visibility = View.VISIBLE
                binding.rvRecentlyAdded.visibility = View.GONE
            }
        }
    }

    inner class LibraryViewHolder(
        val binding: PageLibraryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun init() {
            if (binding.rvLibrarySongs.adapter == null) {
                binding.rvLibrarySongs.layoutManager = LinearLayoutManager(itemView.context)
                binding.rvLibrarySongs.adapter = libraryAdapter
            }

            binding.btnRescanLibrary.setOnClickListener {
                onRescanLibraryClicked()
            }
        }

        fun bind(trackCount: Int, hasSongs: Boolean) {
            binding.tvLibraryTrackCount.text = "$trackCount tracks on this device"

            if (hasSongs) {
                binding.libraryEmptyState.visibility = View.GONE
                binding.rvLibrarySongs.visibility = View.VISIBLE
            } else {
                binding.libraryEmptyState.visibility = View.VISIBLE
                binding.rvLibrarySongs.visibility = View.GONE
            }
        }
    }

    inner class BlankViewHolder(
        private val binding: ItemBlankPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(title: String) {
            binding.tvBlankPageTitle.text = title
        }
    }
}
