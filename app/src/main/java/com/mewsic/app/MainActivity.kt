package com.mewsic.app

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mewsic.app.adapter.MainPagerAdapter
import com.mewsic.app.adapter.PlaylistAdapter
import com.mewsic.app.adapter.SongAdapter
import com.mewsic.app.databinding.ActivityMainBinding
import com.mewsic.app.databinding.DialogBottomSheetSongOptionsBinding
import com.mewsic.app.databinding.DialogEditSongBinding
import com.mewsic.app.model.Song
import com.mewsic.app.scanner.LibraryCache
import com.mewsic.app.scanner.MediaScanner
import com.mewsic.app.scanner.PlaylistInfo
import com.mewsic.app.scanner.PlaylistManager
import com.mewsic.app.scanner.ThumbnailLoader
import com.mewsic.app.service.MusicPlaybackController
import com.mewsic.app.service.MusicPlaybackService
import com.mewsic.app.util.UiScaleManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_TAB = "extra_start_tab"
        const val EXTRA_SKIP_LOADING = "extra_skip_loading"
    }

    private lateinit var binding: ActivityMainBinding
    private var glowAnimator: ObjectAnimator? = null
    private var loadAnimator: ObjectAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    enum class RepeatMode { OFF, ALL, ONE }

    // Audio Playback State
    private var isPlaying = false
    private var currentPlayingSong: Song? = null
    private var mediaPlayer: MediaPlayer? = null
    private var progressTrackingJob: Job? = null
    private var currentSongToken = 0L
    private var isFullscreenPlayerOpen = false
    private var isUserSeeking = false
    private var isShuffle = false
    private var repeatMode = RepeatMode.ALL

    // Scanned Music Datasets
    private var allSongs: List<Song> = emptyList()
    private var recentSongs: List<Song> = emptyList()
    private var currentOpenPlaylist: String? = null
    private var currentPlaylistSongs: List<Song> = emptyList()

    private val homeSongAdapter by lazy {
        SongAdapter(
            onSongClicked = { song, _ -> playSong(song) },
            onSongLongClicked = { song, _ -> showSongOptions(song) }
        )
    }

    private val librarySongAdapter by lazy {
        SongAdapter(
            onSongClicked = { song, _ -> playSong(song) },
            onSongLongClicked = { song, _ -> showSongOptions(song) }
        )
    }

    private val playlistAdapter by lazy {
        PlaylistAdapter(
            onPlaylistClicked = { playlist ->
                openPlaylist(playlist.name)
            },
            onPlaylistMoreClicked = { playlist, anchorView ->
                showPlaylistMoreMenu(playlist, anchorView)
            }
        )
    }

    private val playlistSongsAdapter by lazy {
        SongAdapter(
            onSongClicked = { song, _ -> playSong(song) },
            onSongLongClicked = { song, _ -> showSongOptions(song, inPlaylist = currentOpenPlaylist) }
        )
    }

    private val pagerAdapter by lazy {
        MainPagerAdapter(
            homeAdapter = homeSongAdapter,
            libraryAdapter = librarySongAdapter,
            playlistAdapter = playlistAdapter,
            playlistSongsAdapter = playlistSongsAdapter,
            onExploreLibraryClicked = {
                binding.viewPager.setCurrentItem(MainPagerAdapter.PAGE_LIBRARY, true)
            },
            onScaleChanged = { newScale ->
                applyUiScale(newScale)
            },
            onRescanClicked = {
                onManualRescan()
            },
            onCreatePlaylistClicked = {
                showCreatePlaylistDialog()
            },
            onPlayAllPlaylistClicked = { playlistName ->
                playAllPlaylist(playlistName)
            }
        )
    }

    // Permission launcher for reading audio files on device
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            silentBackgroundScan()
        }
    }

    // Permission launcher for posting notifications on Android 13+
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission result handled automatically by system */ }

    private data class NavTab(
        val touchTarget: View,
        val icon: ImageView
    )

    private val navTabs by lazy {
        listOf(
            NavTab(binding.tabHome, binding.ivTabHome),
            NavTab(binding.tabHarbour, binding.ivTabHarbour),
            NavTab(binding.tabLibrary, binding.ivTabLibrary),
            NavTab(binding.tabPlaylist, binding.ivTabPlaylist),
            NavTab(binding.tabSettings, binding.ivTabSettings)
        )
    }

    override fun attachBaseContext(newBase: Context) {
        val wrapped = UiScaleManager.wrapContext(newBase)
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Prevent duplicate instances if launched from home screen / adb when already running
        if (!isTaskRoot
            && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            && intent.action != null
            && intent.action == Intent.ACTION_MAIN
        ) {
            finish()
            return
        }

        // Enforce dark mode explicitly across all vendor skins (MIUI, ColorOS, OneUI)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge transparent system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Prevent Android 10+ / MIUI Smart Dark Mode from auto-inverting custom dark colors
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.isForceDarkAllowed = false
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply system bar insets cleanly
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupNavigation()
        setupPlayerBar()
        setupFullscreenPlayer()
        setupMusicPlaybackController()
        checkAndRequestNotificationPermission()

        // Handle system back navigation (fullscreen player -> nested playlist detail -> Home tab -> background task)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreenPlayerOpen) {
                    closeFullscreenPlayer()
                } else if (binding.viewPager.currentItem == MainPagerAdapter.PAGE_PLAYLIST && pagerAdapter.isPlaylistDetailOpen()) {
                    pagerAdapter.closePlaylistDetail()
                    currentOpenPlaylist = null
                } else if (binding.viewPager.currentItem != MainPagerAdapter.PAGE_HOME) {
                    binding.viewPager.setCurrentItem(MainPagerAdapter.PAGE_HOME, true)
                } else {
                    moveTaskToBack(true)
                }
            }
        })

        // 1. Immediately load cached library so app already knows everything on cold start
        loadInitialCachedLibrary()
        loadPlaylists()

        // 2. Start audio scanner concurrently so the app is fully ready while loading screen plays
        checkAndRequestAudioPermission()

        // Check if loading sequence should be skipped (e.g., after scale restart)
        val skipLoading = intent.getBooleanExtra(EXTRA_SKIP_LOADING, false)
        val startTab = intent.getIntExtra(EXTRA_START_TAB, MainPagerAdapter.PAGE_HOME)

        if (skipLoading) {
            binding.loadingScreenContainer.visibility = View.GONE
            binding.blankContentContainer.visibility = View.VISIBLE
            binding.viewPager.setCurrentItem(startTab, false)
            binding.tabBarContainer.post {
                updateIndicator(startTab, 0f)
            }
        } else {
            // Cold start branding animation
            startLoadingSequence()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Orientation changed without activity reload - re-align indicator to active tab
        binding.tabBarContainer.post {
            updateIndicator(binding.viewPager.currentItem, 0f)
        }
    }

    private fun setupNavigation() {
        // Explicitly set indicator background color to prevent vendor inversion
        binding.activeIndicator.background?.setTint(ContextCompat.getColor(this, R.color.tab_active_bg))

        // Setup ViewPager2 with MainPagerAdapter (Home, Harbour, Library, Playlist, Settings)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 4

        // Synchronize page swipes with top tabs via real-time smooth tracking
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                updateIndicator(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                triggerIconBounce(position)
            }
        })

        // Connect tab clicks with tactile micro-bounce and smooth page scroll
        navTabs.forEachIndexed { index, tab ->
            tab.touchTarget.setOnClickListener {
                triggerIconBounce(index)
                binding.viewPager.setCurrentItem(index, true)
            }
        }

        // Align indicator initial position once layout completes
        binding.tabBarContainer.post {
            updateIndicator(0, 0f)
        }
    }

    private fun updateIndicator(position: Int, positionOffset: Float) {
        if (navTabs.isEmpty()) return
        val currentTab = navTabs.getOrNull(position) ?: return
        val nextTab = navTabs.getOrNull(position + 1) ?: currentTab

        val currentProgress = position.toFloat() + positionOffset
        val currentTabWidth = currentTab.touchTarget.width.toFloat()
        val targetCenterX = if (currentTabWidth > 0f) {
            val currentCenterX = binding.tabIconsRow.left + currentTab.touchTarget.left + (currentTabWidth / 2f)
            val nextCenterX = binding.tabIconsRow.left + nextTab.touchTarget.left + (nextTab.touchTarget.width.toFloat() / 2f)
            currentCenterX + positionOffset * (nextCenterX - currentCenterX)
        } else {
            val rowWidth = binding.tabIconsRow.width.toFloat()
            if (rowWidth <= 0f) return
            val tabWidth = rowWidth / navTabs.size.toFloat()
            binding.tabIconsRow.left + (currentProgress + 0.5f) * tabWidth
        }

        binding.activeIndicator.translationX = targetCenterX - (binding.activeIndicator.width / 2f)

        // Smoothly interpolate icon colors
        val activeColor = ContextCompat.getColor(this, R.color.tab_active_tint)
        val inactiveColor = ContextCompat.getColor(this, R.color.tab_inactive_tint)

        navTabs.forEachIndexed { index, tab ->
            val distance = kotlin.math.abs(currentProgress - index.toFloat())
            val factor = (1f - distance).coerceIn(0f, 1f)
            val blendedColor = argbEvaluator.evaluate(factor, inactiveColor, activeColor) as Int
            tab.icon.setColorFilter(blendedColor, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun triggerIconBounce(index: Int) {
        val icon = navTabs.getOrNull(index)?.icon ?: return
        icon.animate().cancel()
        icon.scaleX = 0.8f
        icon.scaleY = 0.8f
        icon.animate()
            .scaleX(1.18f)
            .scaleY(1.18f)
            .setDuration(180)
            .setInterpolator(OvershootInterpolator(3.0f))
            .withEndAction {
                icon.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    /**
     * Instantly populates the UI with cached songs from disk/memory
     * so that the app immediately displays content without waiting for a scan.
     */
    private fun loadInitialCachedLibrary() {
        lifecycleScope.launch {
            val cached = LibraryCache.loadCachedSongs(this@MainActivity)
            if (cached.isNotEmpty()) {
                applySongsToUI(cached)
            }
        }
    }

    private fun checkAndRequestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            silentBackgroundScan()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupMusicPlaybackController() {
        MusicPlaybackController.onPlayPause = {
            runOnUiThread { togglePlayPause() }
        }
        MusicPlaybackController.onNext = {
            runOnUiThread { playNextSong() }
        }
        MusicPlaybackController.onPrev = {
            runOnUiThread { playPrevSong() }
        }
        MusicPlaybackController.onSeekTo = { pos ->
            runOnUiThread {
                mediaPlayer?.seekTo(pos.toInt())
                binding.fullPlayerSeekBar.progress = pos.toInt()
                binding.tvFullCurrentTime.text = formatTimeMs(pos)
            }
        }
        MusicPlaybackController.onStop = {
            runOnUiThread { stopPlayback() }
        }
    }

    private fun stopPlayback() {
        if (mediaPlayer == null && !isPlaying) return
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_play)
        binding.ivFullPlayPause.setImageResource(R.drawable.ic_player_play)
        progressTrackingJob?.cancel()
        MusicPlaybackService.stop(this)
    }

    private fun onManualRescan() {
        Toast.makeText(this, "Scanning media on device...", Toast.LENGTH_SHORT).show()
        checkAndRequestAudioPermission()
    }

    /**
     * Silently scans the device in the background and smoothly updates
     * the UI via DiffUtil only if additions or changes occurred.
     */
    private fun silentBackgroundScan() {
        lifecycleScope.launch {
            val scannedSongs = MediaScanner.scanDeviceAudio(this@MainActivity)

            // Only update if dataset actually changed or was previously empty
            if (hasLibraryChanged(allSongs, scannedSongs)) {
                applySongsToUI(scannedSongs)
                LibraryCache.saveCachedSongs(this@MainActivity, scannedSongs)
            }
        }
    }

    private fun hasLibraryChanged(current: List<Song>, fresh: List<Song>): Boolean {
        if (current.size != fresh.size) return true
        val currentIds = current.map { it.id }.toSet()
        return fresh.any { it.id !in currentIds }
    }

    private fun applySongsToUI(songs: List<Song>) {
        allSongs = songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        recentSongs = songs.sortedByDescending { maxOf(it.dateAdded, it.dateModified) }
        val stats = MediaScanner.computeLibraryStats(songs)

        homeSongAdapter.submitList(recentSongs.take(20))
        librarySongAdapter.submitList(allSongs)
        pagerAdapter.updateData(stats, allSongs.size)
        loadPlaylists()
    }

    private fun playSong(song: Song) {
        currentPlayingSong = song
        homeSongAdapter.activeSongId = song.id
        librarySongAdapter.activeSongId = song.id
        playlistSongsAdapter.activeSongId = song.id

        // 1. Instantly update mini player bar UI (0ms delay)
        binding.tvPlayerTitle.text = song.title
        binding.tvPlayerArtist.text = song.artist
        ThumbnailLoader.loadThumbnail(binding.ivPlayerAlbumArt, song)
        binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_pause)

        // Instantly update fullscreen player UI (0ms delay)
        binding.tvFullTitle.text = song.title
        binding.tvFullArtist.text = song.artist
        ThumbnailLoader.loadThumbnail(binding.ivFullAlbumArt, song)
        binding.ivFullPlayPause.setImageResource(R.drawable.ic_player_pause)
        binding.fullPlayerSeekBar.max = song.durationMs.toInt()
        binding.fullPlayerSeekBar.progress = 0
        binding.tvFullCurrentTime.text = "0:00"
        binding.tvFullTotalTime.text = song.durationFormatted
        binding.tvFullPlayerContextTitle.text = currentOpenPlaylist ?: "Your Library"

        // 2. Start/update persistent foreground playback notification immediately
        MusicPlaybackService.startOrUpdate(
            context = this,
            song = song,
            isPlaying = true,
            durationMs = song.durationMs,
            positionMs = 0L
        )

        // 3. Prepare & play audio asynchronously without blocking the UI thread
        val token = System.currentTimeMillis().also { currentSongToken = it }
        try {
            val player = mediaPlayer ?: MediaPlayer().also { mediaPlayer = it }
            player.reset()
            player.setDataSource(applicationContext, song.contentUri)
            player.setOnPreparedListener { mp ->
                if (currentSongToken == token) {
                    mp.start()
                    isPlaying = true
                    binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_pause)
                    binding.ivFullPlayPause.setImageResource(R.drawable.ic_player_pause)
                    binding.fullPlayerSeekBar.max = mp.duration
                    binding.tvFullTotalTime.text = formatTimeMs(mp.duration.toLong())
                    startProgressTracking()

                    // Refresh service with exact duration & position from player
                    MusicPlaybackService.startOrUpdate(
                        context = this@MainActivity,
                        song = song,
                        isPlaying = true,
                        durationMs = mp.duration.toLong(),
                        positionMs = mp.currentPosition.toLong()
                    )
                }
            }
            player.setOnCompletionListener {
                if (repeatMode == RepeatMode.ONE) {
                    currentPlayingSong?.let { playSong(it) }
                } else {
                    playNextSong(fromCompletion = true)
                }
            }
            player.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun togglePlayPause() {
        if (currentPlayingSong == null) {
            val firstSong = recentSongs.firstOrNull() ?: allSongs.firstOrNull()
            if (firstSong != null) {
                playSong(firstSong)
                return
            }
        }

        val player = mediaPlayer
        val song = currentPlayingSong
        if (isPlaying && player != null) {
            player.pause()
            isPlaying = false
            binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_play)
            binding.ivFullPlayPause.setImageResource(R.drawable.ic_player_play)
            progressTrackingJob?.cancel()
            if (song != null) {
                MusicPlaybackService.startOrUpdate(
                    context = this,
                    song = song,
                    isPlaying = false,
                    durationMs = player.duration.toLong(),
                    positionMs = player.currentPosition.toLong()
                )
            }
        } else {
            if (player != null) {
                player.start()
                isPlaying = true
                binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_pause)
                binding.ivFullPlayPause.setImageResource(R.drawable.ic_player_pause)
                startProgressTracking()
                if (song != null) {
                    MusicPlaybackService.startOrUpdate(
                        context = this,
                        song = song,
                        isPlaying = true,
                        durationMs = player.duration.toLong(),
                        positionMs = player.currentPosition.toLong()
                    )
                }
            } else {
                currentPlayingSong?.let { playSong(it) }
            }
        }
    }

    private fun playNextSong(fromCompletion: Boolean = false) {
        val list = when (binding.viewPager.currentItem) {
            MainPagerAdapter.PAGE_HOME -> recentSongs
            MainPagerAdapter.PAGE_PLAYLIST -> if (currentPlaylistSongs.isNotEmpty()) currentPlaylistSongs else allSongs
            else -> allSongs
        }
        if (list.isEmpty()) return

        val idx = list.indexOfFirst { it.id == currentPlayingSong?.id }

        if (isShuffle && list.size > 1) {
            var randIdx: Int
            do {
                randIdx = (0 until list.size).random()
            } while (randIdx == idx)
            playSong(list[randIdx])
            return
        }

        if (idx == list.size - 1 && repeatMode == RepeatMode.OFF && fromCompletion) {
            // Reached end of playback queue with repeat off
            isPlaying = false
            binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_play)
            binding.ivFullPlayPause.setImageResource(R.drawable.ic_player_play)
            progressTrackingJob?.cancel()
            currentPlayingSong?.let {
                MusicPlaybackService.startOrUpdate(
                    context = this,
                    song = it,
                    isPlaying = false,
                    durationMs = it.durationMs,
                    positionMs = it.durationMs
                )
            }
            return
        }

        val nextIdx = if (idx in 0 until list.size - 1) idx + 1 else 0
        playSong(list[nextIdx])
    }

    private fun playPrevSong() {
        val player = mediaPlayer
        if (player != null && player.isPlaying && player.currentPosition > 3000) {
            player.seekTo(0)
            binding.fullPlayerSeekBar.progress = 0
            binding.tvFullCurrentTime.text = "0:00"
            currentPlayingSong?.let {
                MusicPlaybackService.startOrUpdate(
                    context = this,
                    song = it,
                    isPlaying = true,
                    durationMs = player.duration.toLong(),
                    positionMs = 0L
                )
            }
            return
        }

        val list = when (binding.viewPager.currentItem) {
            MainPagerAdapter.PAGE_HOME -> recentSongs
            MainPagerAdapter.PAGE_PLAYLIST -> if (currentPlaylistSongs.isNotEmpty()) currentPlaylistSongs else allSongs
            else -> allSongs
        }
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == currentPlayingSong?.id }
        val prevIdx = if (idx > 0) idx - 1 else list.size - 1
        playSong(list[prevIdx])
    }

    private fun startProgressTracking() {
        progressTrackingJob?.cancel()
        progressTrackingJob = lifecycleScope.launch {
            while (isPlaying) {
                val player = mediaPlayer
                if (player != null && player.isPlaying && !isUserSeeking) {
                    val duration = player.duration
                    val current = player.currentPosition
                    if (duration > 0) {
                        binding.fullPlayerSeekBar.max = duration
                        binding.fullPlayerSeekBar.progress = current
                        binding.tvFullCurrentTime.text = formatTimeMs(current.toLong())
                        binding.tvFullTotalTime.text = formatTimeMs(duration.toLong())
                    }
                }
                delay(400)
            }
        }
    }

    private fun setupPlayerBar() {
        // Enforce rounded outline clipping on Android 10+
        binding.playerBarCard.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = 18f * resources.displayMetrics.density
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        binding.playerBarCard.clipToOutline = true

        // Initial state when nothing is playing yet
        binding.tvPlayerTitle.text = "Nothing playing"
        binding.tvPlayerArtist.text = "Select a track to listen"
        binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_play)
        binding.ivPlayerAlbumArt.setImageResource(R.drawable.ic_album_art_placeholder)

        // Clicking anywhere on the player bar expands fullscreen player
        binding.playerBarCard.setOnClickListener {
            openFullscreenPlayer()
        }

        // Toggle play/pause state with tactile micro-bounce
        binding.btnPlayerPlayPause.setOnClickListener {
            binding.btnPlayerPlayPause.animate()
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(90)
                .withEndAction {
                    togglePlayPause()
                    binding.btnPlayerPlayPause.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .setInterpolator(OvershootInterpolator(2.5f))
                        .start()
                }
                .start()
        }

        binding.btnPlayerNext.setOnClickListener {
            playNextSong()
            binding.btnPlayerNext.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(70)
                .withEndAction {
                    binding.btnPlayerNext.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
                .start()
        }

        binding.btnPlayerPrev.setOnClickListener {
            playPrevSong()
            binding.btnPlayerPrev.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(70)
                .withEndAction {
                    binding.btnPlayerPrev.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
                .start()
        }
    }

    private fun setupFullscreenPlayer() {
        binding.btnMinimizePlayer.setOnClickListener {
            closeFullscreenPlayer()
        }

        binding.btnFullPlayPause.setOnClickListener {
            binding.btnFullPlayPause.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(90)
                .withEndAction {
                    togglePlayPause()
                    binding.btnFullPlayPause.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .setInterpolator(OvershootInterpolator(2.5f))
                        .start()
                }
                .start()
        }

        binding.btnFullNext.setOnClickListener {
            playNextSong()
            binding.btnFullNext.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(70)
                .withEndAction {
                    binding.btnFullNext.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
                .start()
        }

        binding.btnFullPrev.setOnClickListener {
            playPrevSong()
            binding.btnFullPrev.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(70)
                .withEndAction {
                    binding.btnFullPrev.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
                .start()
        }

        binding.btnFullShuffle.setOnClickListener {
            toggleShuffle()
        }

        binding.btnFullRepeat.setOnClickListener {
            toggleRepeat()
        }

        binding.btnFullPlayerMore.setOnClickListener {
            currentPlayingSong?.let { song ->
                showSongOptions(song, inPlaylist = currentOpenPlaylist)
            }
        }

        binding.fullPlayerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvFullCurrentTime.text = formatTimeMs(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val player = mediaPlayer
                if (player != null && seekBar != null) {
                    player.seekTo(seekBar.progress)
                    currentPlayingSong?.let { song ->
                        MusicPlaybackService.startOrUpdate(
                            context = this@MainActivity,
                            song = song,
                            isPlaying = isPlaying,
                            durationMs = player.duration.toLong(),
                            positionMs = seekBar.progress.toLong()
                        )
                    }
                }
                isUserSeeking = false
            }
        })
    }

    private fun openFullscreenPlayer() {
        if (isFullscreenPlayerOpen) return
        isFullscreenPlayerOpen = true

        syncFullscreenPlayerState()

        val rootHeight = binding.rootContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        binding.fullPlayerContainer.visibility = View.VISIBLE
        binding.fullPlayerContainer.translationY = rootHeight.toFloat()
        binding.fullPlayerContainer.alpha = 0.6f

        binding.fullPlayerContainer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()

        binding.playerBarWrapper.animate()
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(220)
            .withEndAction {
                binding.playerBarWrapper.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun closeFullscreenPlayer() {
        if (!isFullscreenPlayerOpen) return
        isFullscreenPlayerOpen = false

        binding.playerBarWrapper.visibility = View.VISIBLE
        binding.playerBarWrapper.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260)
            .start()

        val rootHeight = binding.rootContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        binding.fullPlayerContainer.animate()
            .translationY(rootHeight.toFloat())
            .alpha(0.6f)
            .setDuration(280)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                binding.fullPlayerContainer.visibility = View.GONE
            }
            .start()
    }

    private fun syncFullscreenPlayerState() {
        val song = currentPlayingSong
        if (song != null) {
            binding.tvFullTitle.text = song.title
            binding.tvFullArtist.text = song.artist
            ThumbnailLoader.loadThumbnail(binding.ivFullAlbumArt, song)
            val duration = mediaPlayer?.duration?.takeIf { it > 0 } ?: song.durationMs.toInt()
            val currentPos = mediaPlayer?.currentPosition ?: 0

            binding.fullPlayerSeekBar.max = duration
            binding.fullPlayerSeekBar.progress = currentPos
            binding.tvFullCurrentTime.text = formatTimeMs(currentPos.toLong())
            binding.tvFullTotalTime.text = formatTimeMs(duration.toLong())
        } else {
            binding.tvFullTitle.text = "Nothing playing"
            binding.tvFullArtist.text = "Select a track to listen"
            binding.ivFullAlbumArt.setImageResource(R.drawable.ic_album_art_placeholder)
            binding.fullPlayerSeekBar.progress = 0
            binding.tvFullCurrentTime.text = "0:00"
            binding.tvFullTotalTime.text = "0:00"
        }

        binding.tvFullPlayerContextTitle.text = currentOpenPlaylist ?: "Your Library"
        binding.ivFullPlayPause.setImageResource(if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        updateShuffleRepeatIcons()
    }

    private fun toggleShuffle() {
        isShuffle = !isShuffle
        updateShuffleRepeatIcons()
        Toast.makeText(this, if (isShuffle) "Shuffle On" else "Shuffle Off", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
            RepeatMode.OFF -> RepeatMode.ALL
        }
        updateShuffleRepeatIcons()
        val msg = when (repeatMode) {
            RepeatMode.ALL -> "Repeat All"
            RepeatMode.ONE -> "Repeat Current Track"
            RepeatMode.OFF -> "Repeat Off"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateShuffleRepeatIcons() {
        val activeColor = ContextCompat.getColor(this, R.color.brand_emerald)
        val inactiveColor = Color.parseColor("#64748B")

        binding.ivFullShuffle.setColorFilter(if (isShuffle) activeColor else inactiveColor)
        binding.ivFullRepeat.setColorFilter(if (repeatMode != RepeatMode.OFF) activeColor else inactiveColor)
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%d:%02d", m, s)
        }
    }

    private fun applyUiScale(newScale: Float) {
        UiScaleManager.setScale(this, newScale)
        Toast.makeText(this, "UI scale set to ${UiScaleManager.getScaleLabel(newScale)}", Toast.LENGTH_SHORT).show()

        // Restart activity seamlessly right into the Settings tab
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_START_TAB, MainPagerAdapter.PAGE_SETTINGS)
            putExtra(EXTRA_SKIP_LOADING, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        finish()
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun startLoadingSequence() {
        // App content is already loaded and populating behind the loading screen
        binding.loadingScreenContainer.alpha = 1f
        binding.loadingScreenContainer.visibility = View.VISIBLE
        binding.blankContentContainer.visibility = View.VISIBLE

        // Anchor scale from the left edge (0% -> 100% left-to-right fill)
        binding.loadingIndicator.pivotX = 0f
        binding.loadingIndicator.scaleX = 0f

        // Ambient glow breathing pulse
        glowAnimator?.cancel()
        glowAnimator = ObjectAnimator.ofFloat(binding.ambientGlow, "alpha", 0.4f, 0.9f).apply {
            duration = 1200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        playLoadingProgressAnimation()
    }

    private fun playLoadingProgressAnimation() {
        loadAnimator?.cancel()

        val kf0 = Keyframe.ofFloat(0.0f, 0.0f).apply {
            interpolator = AccelerateDecelerateInterpolator()
        }
        val kf1 = Keyframe.ofFloat(0.45f, 0.58f).apply {
            interpolator = DecelerateInterpolator()
        }
        val kf2 = Keyframe.ofFloat(0.65f, 0.64f).apply {
            interpolator = AccelerateDecelerateInterpolator()
        }
        val kf3 = Keyframe.ofFloat(1.0f, 1.0f).apply {
            interpolator = AccelerateDecelerateInterpolator()
        }

        val pvh = PropertyValuesHolder.ofKeyframe(View.SCALE_X, kf0, kf1, kf2, kf3)

        loadAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.loadingIndicator, pvh).apply {
            duration = 1600
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Smoothly fade out loading overlay revealing the fully-loaded app
                    binding.loadingScreenContainer.animate()
                        .alpha(0f)
                        .setDuration(350)
                        .withEndAction {
                            binding.loadingScreenContainer.visibility = View.GONE
                            glowAnimator?.cancel()

                            // Re-align indicator with measured tabs
                            binding.tabBarContainer.post {
                                updateIndicator(binding.viewPager.currentItem, 0f)
                            }

                            // Floating entrance animation for top navigation bar
                            binding.topBarWrapper.alpha = 0f
                            binding.topBarWrapper.translationY = -30f
                            binding.topBarWrapper.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(350)
                                .setInterpolator(DecelerateInterpolator())
                                .start()

                            // Floating entrance animation for bottom player bar
                            binding.playerBarWrapper.alpha = 0f
                            binding.playerBarWrapper.translationY = 30f
                            binding.playerBarWrapper.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(350)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                        }
                        .start()
                }
            })
            start()
        }
    }

    // =========================================================================
    // Playlists & Detail View Management
    // =========================================================================

    private fun loadPlaylists() {
        val playlists = PlaylistManager.getPlaylistInfos(this, allSongs)
        playlistAdapter.submitList(playlists)
        currentOpenPlaylist?.let { name ->
            refreshCurrentPlaylistSongs(name)
        }
    }

    private fun openPlaylist(playlistName: String) {
        currentOpenPlaylist = playlistName
        refreshCurrentPlaylistSongs(playlistName)
    }

    private fun refreshCurrentPlaylistSongs(playlistName: String) {
        val songIds = PlaylistManager.getPlaylistSongIds(this, playlistName)
        val songMap = allSongs.associateBy { it.id }
        currentPlaylistSongs = songIds.mapNotNull { songMap[it] }
        playlistSongsAdapter.submitList(currentPlaylistSongs)
        pagerAdapter.openPlaylistDetail(playlistName, currentPlaylistSongs.size, currentPlaylistSongs.firstOrNull())
    }

    private fun playAllPlaylist(playlistName: String) {
        if (currentPlaylistSongs.isNotEmpty()) {
            playSong(currentPlaylistSongs.first())
        } else {
            Toast.makeText(this, "Playlist is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPlaylistMoreMenu(playlist: PlaylistInfo, anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        popup.menu.add(0, 1, 0, "Rename")
        popup.menu.add(0, 2, 1, "Delete")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    showRenamePlaylistDialog(playlist.name)
                    true
                }
                2 -> {
                    showDeletePlaylistDialog(playlist.name)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenamePlaylistDialog(oldName: String) {
        val input = android.widget.EditText(this).apply {
            setText(oldName)
            selectAll()
            setPadding(48, 32, 48, 32)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename Playlist")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank() && newName != oldName) {
                    val success = PlaylistManager.renamePlaylist(this, oldName, newName)
                    if (success) {
                        if (currentOpenPlaylist == oldName) {
                            currentOpenPlaylist = newName
                        }
                        loadPlaylists()
                        Toast.makeText(this, "Renamed to $newName", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Playlist with that name already exists", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePlaylistDialog(playlistName: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Playlist")
            .setMessage("Are you sure you want to delete \"$playlistName\"? The songs will remain on your device.")
            .setPositiveButton("Delete") { _, _ ->
                PlaylistManager.deletePlaylist(this, playlistName)
                if (currentOpenPlaylist == playlistName) {
                    pagerAdapter.closePlaylistDetail()
                    currentOpenPlaylist = null
                }
                loadPlaylists()
                Toast.makeText(this, "Deleted \"$playlistName\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // Song Context Actions (Long Press Options: Delete, Edit, Add to Playlist)
    // =========================================================================

    private fun showSongOptions(song: Song, inPlaylist: String? = null) {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val bottomSheetDialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val sheetBinding = DialogBottomSheetSongOptionsBinding.inflate(layoutInflater)
        bottomSheetDialog.setContentView(sheetBinding.root)

        // Bind song header preview
        sheetBinding.tvOptionSongTitle.text = song.title
        val subtitle = if (song.album.isNotBlank() && song.album != "Unknown Album") {
            "${song.artist} • ${song.album}"
        } else {
            song.artist
        }
        sheetBinding.tvOptionSongSubtitle.text = subtitle
        sheetBinding.tvOptionSongDuration.text = song.durationFormatted
        ThumbnailLoader.loadThumbnail(sheetBinding.ivOptionAlbumArt, song)

        // 1. Add to playlist
        sheetBinding.btnOptionAddToPlaylist.setOnClickListener {
            bottomSheetDialog.dismiss()
            showAddToPlaylistDialog(song)
        }

        // Optional: Remove from current playlist
        if (inPlaylist != null) {
            sheetBinding.btnOptionRemoveFromPlaylist.visibility = View.VISIBLE
            sheetBinding.tvOptionRemoveFromPlaylistSubtitle.text = "Remove track from $inPlaylist"
            sheetBinding.btnOptionRemoveFromPlaylist.setOnClickListener {
                bottomSheetDialog.dismiss()
                PlaylistManager.removeSongFromPlaylist(this, inPlaylist, song.id)
                loadPlaylists()
                Toast.makeText(this, "Removed from $inPlaylist", Toast.LENGTH_SHORT).show()
            }
        } else {
            sheetBinding.btnOptionRemoveFromPlaylist.visibility = View.GONE
        }

        // 2. Edit song details
        sheetBinding.btnOptionEdit.setOnClickListener {
            bottomSheetDialog.dismiss()
            showEditSongDialog(song)
        }

        // 3. Delete track
        sheetBinding.btnOptionDelete.setOnClickListener {
            bottomSheetDialog.dismiss()
            showDeleteSongDialog(song)
        }

        bottomSheetDialog.show()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        val playlists = PlaylistManager.getPlaylists(this).toMutableList()
        val options = playlists + "＋ Create New Playlist"

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Add to Playlist")
        builder.setItems(options.toTypedArray()) { _, which ->
            if (which == playlists.size) {
                showCreatePlaylistDialog(song)
            } else {
                val selectedPlaylist = playlists[which]
                val added = PlaylistManager.addSongToPlaylist(this, selectedPlaylist, song.id)
                if (added) {
                    loadPlaylists()
                    Toast.makeText(this, "Added to $selectedPlaylist", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Already in $selectedPlaylist", Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showCreatePlaylistDialog(song: Song? = null) {
        val input = android.widget.EditText(this).apply {
            hint = "Playlist Name"
            setPadding(48, 32, 48, 32)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val created = PlaylistManager.createPlaylist(this, name)
                    if (created) {
                        if (song != null) {
                            PlaylistManager.addSongToPlaylist(this, name, song.id)
                            Toast.makeText(this, "Created & Added to $name", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Created playlist \"$name\"", Toast.LENGTH_SHORT).show()
                        }
                        loadPlaylists()
                    } else {
                        Toast.makeText(this, "Playlist already exists", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditSongDialog(song: Song) {
        val editBinding = DialogEditSongBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(editBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        editBinding.etEditTitle.setText(song.title)
        editBinding.etEditArtist.setText(if (song.artist != "Unknown Artist") song.artist else "")
        editBinding.etEditAlbum.setText(if (song.album != "Unknown Album") song.album else "")

        editBinding.btnEditCancel.setOnClickListener {
            dialog.dismiss()
        }

        editBinding.btnEditSave.setOnClickListener {
            val newTitle = editBinding.etEditTitle.text.toString().trim()
            val newArtist = editBinding.etEditArtist.text.toString().trim().ifEmpty { "Unknown Artist" }
            val newAlbum = editBinding.etEditAlbum.text.toString().trim().ifEmpty { "Unknown Album" }

            if (newTitle.isNotBlank()) {
                val updatedSong = song.copy(
                    title = newTitle,
                    artist = newArtist,
                    album = newAlbum
                )

                // Update datasets in-memory and disk cache
                allSongs = allSongs.map { if (it.id == song.id) updatedSong else it }
                applySongsToUI(allSongs)
                lifecycleScope.launch {
                    LibraryCache.saveCachedSongs(this@MainActivity, allSongs)
                }

                // Update current playing view if applicable
                if (currentPlayingSong?.id == song.id) {
                    currentPlayingSong = updatedSong
                    binding.tvPlayerTitle.text = newTitle
                    binding.tvPlayerArtist.text = newArtist
                    binding.tvFullTitle.text = newTitle
                    binding.tvFullArtist.text = newArtist
                }

                Toast.makeText(this, "Updated \"$newTitle\"", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Title cannot be blank", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showDeleteSongDialog(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("Delete Track")
            .setMessage("Are you sure you want to delete \"${song.title}\" from your device?")
            .setPositiveButton("Delete") { _, _ ->
                performDeleteSong(song)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDeleteSong(song: Song) {
        try {
            val file = java.io.File(song.filePath)
            if (file.exists()) {
                file.delete()
            }
            contentResolver.delete(song.contentUri, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Remove from list and update UI
        allSongs = allSongs.filter { it.id != song.id }
        applySongsToUI(allSongs)
        lifecycleScope.launch {
            LibraryCache.saveCachedSongs(this@MainActivity, allSongs)
        }

        // Advance playback if currently playing
        if (currentPlayingSong?.id == song.id) {
            if (allSongs.isNotEmpty()) {
                playNextSong()
            } else {
                currentPlayingSong = null
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                isPlaying = false
                binding.tvPlayerTitle.text = "Nothing playing"
                binding.tvPlayerArtist.text = "Select a track to listen"
                binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_play)
                binding.ivPlayerAlbumArt.setImageResource(R.drawable.ic_album_art_placeholder)
                syncFullscreenPlayerState()
                MusicPlaybackService.stop(this)
            }
        }

        Toast.makeText(this, "Deleted \"${song.title}\"", Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        loadAnimator?.cancel()
        glowAnimator?.cancel()
        progressTrackingJob?.cancel()
        MusicPlaybackController.onPlayPause = null
        MusicPlaybackController.onNext = null
        MusicPlaybackController.onPrev = null
        MusicPlaybackController.onSeekTo = null
        MusicPlaybackController.onStop = null
        if (!isPlaying || isFinishing) {
            mediaPlayer?.release()
            mediaPlayer = null
            MusicPlaybackService.stop(this)
        }
        super.onDestroy()
    }
}
