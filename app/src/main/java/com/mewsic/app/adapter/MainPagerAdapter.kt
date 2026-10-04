package com.mewsic.app.adapter

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.mewsic.app.R
import com.mewsic.app.databinding.ItemBlankPageBinding
import com.mewsic.app.databinding.PageHarbourBinding
import com.mewsic.app.databinding.PageHomeBinding
import com.mewsic.app.databinding.PageLibraryBinding
import com.mewsic.app.databinding.PagePlaylistBinding
import com.mewsic.app.databinding.PageSettingsBinding
import com.mewsic.app.model.Song
import com.mewsic.app.scanner.LibraryStats
import com.mewsic.app.scanner.ThumbnailLoader
import com.mewsic.app.util.UiScaleManager

class MainPagerAdapter(
    private val homeAdapter: SongAdapter,
    private val libraryAdapter: SongAdapter,
    private val playlistAdapter: PlaylistAdapter,
    private val playlistSongsAdapter: SongAdapter,
    private val onExploreLibraryClicked: () -> Unit,
    private val onScaleChanged: (scale: Float) -> Unit,
    private val onRescanClicked: () -> Unit,
    private val onCreatePlaylistClicked: () -> Unit,
    private val onPlayAllPlaylistClicked: (playlistName: String) -> Unit
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
    private var playlistViewHolder: PlaylistViewHolder? = null
    private var settingsViewHolder: SettingsViewHolder? = null

    fun updateData(stats: LibraryStats?, count: Int) {
        currentStats = stats
        totalTrackCount = count
        hasSongs = count > 0
        homeViewHolder?.bind(currentStats, hasSongs)
        libraryViewHolder?.bind(totalTrackCount, hasSongs)
        settingsViewHolder?.bind(totalTrackCount)
    }

    fun openPlaylistDetail(playlistName: String, songsCount: Int, firstSong: Song? = null) {
        playlistViewHolder?.showDetail(playlistName, songsCount, firstSong)
    }

    fun closePlaylistDetail(): Boolean {
        return if (playlistViewHolder?.isDetailOpen() == true) {
            playlistViewHolder?.showOverview()
            true
        } else {
            false
        }
    }

    fun isPlaylistDetailOpen(): Boolean = playlistViewHolder?.isDetailOpen() == true

    override fun getItemCount(): Int = 5

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            PAGE_HOME -> {
                val binding = PageHomeBinding.inflate(inflater, parent, false)
                HomeViewHolder(binding)
            }
            PAGE_HARBOUR -> {
                val binding = PageHarbourBinding.inflate(inflater, parent, false)
                HarbourViewHolder(binding)
            }
            PAGE_LIBRARY -> {
                val binding = PageLibraryBinding.inflate(inflater, parent, false)
                LibraryViewHolder(binding)
            }
            PAGE_PLAYLIST -> {
                val binding = PagePlaylistBinding.inflate(inflater, parent, false)
                PlaylistViewHolder(binding)
            }
            PAGE_SETTINGS -> {
                val binding = PageSettingsBinding.inflate(inflater, parent, false)
                SettingsViewHolder(binding)
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
            is HarbourViewHolder -> {
                // Static Coming Soon layout
            }
            is LibraryViewHolder -> {
                libraryViewHolder = holder
                holder.init()
                holder.bind(totalTrackCount, hasSongs)
            }
            is PlaylistViewHolder -> {
                playlistViewHolder = holder
                holder.init()
            }
            is SettingsViewHolder -> {
                settingsViewHolder = holder
                holder.init()
                holder.bind(totalTrackCount)
            }
            is BlankViewHolder -> {
                holder.bind("")
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
        }

        fun bind(stats: LibraryStats?, hasSongs: Boolean) {
            if (stats != null) {
                binding.tvTotalTracksValue.text = stats.totalTracks.toString()
                binding.tvPlaybackTimeValue.text = stats.formattedDuration
            } else {
                binding.tvTotalTracksValue.text = "0"
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

    inner class HarbourViewHolder(
        val binding: PageHarbourBinding
    ) : RecyclerView.ViewHolder(binding.root)

    inner class LibraryViewHolder(
        val binding: PageLibraryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun init() {
            if (binding.rvLibrarySongs.adapter == null) {
                binding.rvLibrarySongs.layoutManager = LinearLayoutManager(itemView.context)
                binding.rvLibrarySongs.adapter = libraryAdapter
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

    inner class PlaylistViewHolder(
        val binding: PagePlaylistBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var activePlaylistName: String? = null

        fun init() {
            if (binding.rvPlaylists.adapter == null) {
                binding.rvPlaylists.layoutManager = LinearLayoutManager(itemView.context)
                binding.rvPlaylists.adapter = playlistAdapter
            }

            if (binding.rvPlaylistSongs.adapter == null) {
                binding.rvPlaylistSongs.layoutManager = LinearLayoutManager(itemView.context)
                binding.rvPlaylistSongs.adapter = playlistSongsAdapter
            }

            binding.btnCreatePlaylistHeader.setOnClickListener {
                onCreatePlaylistClicked()
            }

            binding.btnPlaylistBack.setOnClickListener {
                showOverview()
            }

            binding.btnPlaylistPlayAll.setOnClickListener {
                activePlaylistName?.let { onPlayAllPlaylistClicked(it) }
            }
        }

        fun showDetail(playlistName: String, songsCount: Int, firstSong: Song? = null) {
            activePlaylistName = playlistName
            binding.layoutPlaylistsOverview.visibility = View.GONE
            binding.layoutPlaylistDetail.visibility = View.VISIBLE
            binding.tvDetailPlaylistName.text = playlistName
            binding.tvDetailPlaylistCount.text = "$songsCount ${if (songsCount == 1) "track" else "tracks"}"

            if (firstSong != null) {
                binding.ivDetailPlaylistCover.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.ivDetailPlaylistCover.setPadding(0, 0, 0, 0)
                binding.ivDetailPlaylistCover.clearColorFilter()
                ThumbnailLoader.loadThumbnail(binding.ivDetailPlaylistCover, firstSong)
            } else {
                binding.ivDetailPlaylistCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (10 * binding.root.resources.displayMetrics.density).toInt()
                binding.ivDetailPlaylistCover.setPadding(pad, pad, pad, pad)
                binding.ivDetailPlaylistCover.setImageResource(R.drawable.ic_tab_playlist)
                binding.ivDetailPlaylistCover.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.tab_active_tint),
                    PorterDuff.Mode.SRC_IN
                )
            }

            if (songsCount == 0) {
                binding.playlistSongsEmptyState.visibility = View.VISIBLE
                binding.rvPlaylistSongs.visibility = View.GONE
            } else {
                binding.playlistSongsEmptyState.visibility = View.GONE
                binding.rvPlaylistSongs.visibility = View.VISIBLE
            }
        }

        fun showOverview() {
            activePlaylistName = null
            binding.layoutPlaylistsOverview.visibility = View.VISIBLE
            binding.layoutPlaylistDetail.visibility = View.GONE
        }

        fun isDetailOpen(): Boolean = binding.layoutPlaylistDetail.visibility == View.VISIBLE
    }

    inner class SettingsViewHolder(
        val binding: PageSettingsBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var isInitialized = false

        fun init() {
            if (isInitialized) return
            isInitialized = true

            val context = itemView.context
            val currentScale = UiScaleManager.getScale(context)

            // Setup Slider with current scale
            val sliderVal = currentScale.coerceIn(0.75f, 1.25f)
            binding.sliderUiScale.value = sliderVal
            binding.tvScaleValue.text = UiScaleManager.getScaleLabel(sliderVal)

            // Live updates as the slider slides
            binding.sliderUiScale.addOnChangeListener { _, value, _ ->
                binding.tvScaleValue.text = UiScaleManager.getScaleLabel(value)
            }

            // Presets
            binding.btnPresetCompact.setOnClickListener {
                binding.sliderUiScale.value = UiScaleManager.SCALE_COMPACT
                binding.tvScaleValue.text = UiScaleManager.getScaleLabel(UiScaleManager.SCALE_COMPACT)
            }

            binding.btnPresetDefault.setOnClickListener {
                binding.sliderUiScale.value = UiScaleManager.SCALE_DEFAULT
                binding.tvScaleValue.text = UiScaleManager.getScaleLabel(UiScaleManager.SCALE_DEFAULT)
            }

            binding.btnPresetLarge.setOnClickListener {
                binding.sliderUiScale.value = UiScaleManager.SCALE_LARGE
                binding.tvScaleValue.text = UiScaleManager.getScaleLabel(UiScaleManager.SCALE_LARGE)
            }

            // Apply Scale button
            binding.btnApplyScale.setOnClickListener {
                val selectedScale = binding.sliderUiScale.value
                onScaleChanged(selectedScale)
            }

            // Rescan Library button
            binding.btnRescanLibrary.setOnClickListener {
                onRescanClicked()
            }
        }

        fun bind(trackCount: Int) {
            binding.tvSettingsTrackCount.text = "$trackCount tracks indexed"
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
