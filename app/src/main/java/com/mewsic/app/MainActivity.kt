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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mewsic.app.adapter.AddToPlaylistAdapter
import com.mewsic.app.adapter.LyricsAdapter
import com.mewsic.app.adapter.MainPagerAdapter
import com.mewsic.app.adapter.PlaylistAdapter
import com.mewsic.app.adapter.QueueAdapter
import com.mewsic.app.adapter.SongAdapter
import com.mewsic.app.databinding.ActivityMainBinding
import com.mewsic.app.databinding.DialogAddToPlaylistBinding
import com.mewsic.app.databinding.DialogBottomSheetQueueBinding
import com.mewsic.app.databinding.DialogBottomSheetSongOptionsBinding
import com.mewsic.app.databinding.DialogCreatePlaylistBinding
import com.mewsic.app.databinding.DialogEditSongBinding
import com.mewsic.app.databinding.DialogSelectSongsForPlaylistBinding
import com.mewsic.app.model.Song
import com.mewsic.app.scanner.LibraryCache
import com.mewsic.app.scanner.MediaScanner
import com.mewsic.app.scanner.PlaylistInfo
import com.mewsic.app.scanner.PlaylistManager
import com.mewsic.app.scanner.ThumbnailLoader
import com.mewsic.app.service.MusicPlaybackController
import com.mewsic.app.service.MusicPlaybackService
import com.mewsic.app.util.UiScaleManager
import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import com.mewsic.app.harbour.AudioTagEmbedder
import com.mewsic.app.harbour.HarbourLyricsService
import com.mewsic.app.harbour.HarbourSearchEngine
import com.mewsic.app.scanner.LyricsExtractor
import com.mewsic.app.scanner.LyricsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_TAB = "extra_start_tab"
        const val EXTRA_SKIP_LOADING = "extra_skip_loading"
    }

    private lateinit var binding: ActivityMainBinding
    private var glowAnimator: ObjectAnimator? = null
    private var loadAnimator: ObjectAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    private var onImagePickedCallback: ((Uri) -> Unit)? = null
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onImagePickedCallback?.invoke(it) }
    }

    enum class RepeatMode { OFF, ALL, ONE }

    // Audio Playback State
    private var isPlaying = false
    private var currentPlayingSong: Song? = null
    private val playbackQueue = mutableListOf<Song>()
    private var currentQueueIndex = -1
    private var mediaPlayer: MediaPlayer? = null
    private var progressTrackingJob: Job? = null
    private var currentSongToken = 0L
    private var isFullscreenPlayerOpen = false
    private var isUserSeeking = false
    private var isShuffle = false
    private var repeatMode = RepeatMode.ALL
    private var isShowingLyrics = false
    private var isUserScrollingLyrics = false
    private var lastUserLyricTouchTime = 0L
    private var lyricsJob: Job? = null
    private var lyricsHintAnimator: ValueAnimator? = null

    private val lyricsAdapter by lazy {
        LyricsAdapter(
            onLineClicked = { line, _ ->
                if (line.timeMs >= 0) {
                    mediaPlayer?.seekTo(line.timeMs.toInt())
                    binding.fullPlayerSeekBar.progress = line.timeMs.toInt()
                    binding.tvFullCurrentTime.text = formatTimeMs(line.timeMs)
                    currentPlayingSong?.let { song ->
                        MusicPlaybackService.startOrUpdate(
                            context = this@MainActivity,
                            song = song,
                            isPlaying = isPlaying,
                            durationMs = mediaPlayer?.duration?.toLong() ?: song.durationMs,
                            positionMs = line.timeMs
                        )
                    }
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        )
    }

    // Scanned Music Datasets
    private var allSongs: List<Song> = emptyList()
    private var recentSongs: List<Song> = emptyList()
    private var currentOpenPlaylist: String? = null
    private var currentPlaylistSongs: List<Song> = emptyList()

    private val homeSongAdapter by lazy {
        SongAdapter(
            onSongClicked = { song, _ -> playSong(song, recentSongs) },
            onSongLongClicked = { song, _ -> showSongOptions(song) }
        )
    }

    private val librarySongAdapter by lazy {
        SongAdapter(
            onSongClicked = { song, _ -> playSong(song, allSongs) },
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
            onSongClicked = { song, _ -> playSong(song, currentPlaylistSongs) },
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
            },
            lifecycleOwner  = this,
            onSilentRescan  = { silentBackgroundScan() }
        ).apply {
            onAddSongsToPlaylistClicked = { playlistName ->
                showAddSongsToPlaylistDialog(playlistName)
            }
            onRenamePlaylistClicked = { playlistName ->
                showRenamePlaylistDialog(playlistName)
            }
            onDeletePlaylistClicked = { playlistName ->
                showDeletePlaylistDialog(playlistName)
            }
        }
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

    private fun playSong(song: Song, newQueue: List<Song>? = null) {
        currentPlayingSong = song
        if (newQueue != null) {
            playbackQueue.clear()
            playbackQueue.addAll(newQueue)
            val idx = playbackQueue.indexOfFirst { it.id == song.id }
            currentQueueIndex = if (idx != -1) idx else {
                playbackQueue.add(0, song)
                0
            }
        } else {
            val idx = playbackQueue.indexOfFirst { it.id == song.id }
            if (idx != -1) {
                currentQueueIndex = idx
            } else {
                if (currentQueueIndex in 0 until playbackQueue.size) {
                    playbackQueue.add(currentQueueIndex + 1, song)
                    currentQueueIndex++
                } else {
                    playbackQueue.add(song)
                    currentQueueIndex = playbackQueue.size - 1
                }
            }
        }

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
        if (isFullscreenPlayerOpen) {
            ThumbnailLoader.loadHighResArt(binding.ivFullAlbumArt, song, binding.ivFullPlayerBlurredBg)
        }
        if (isShowingLyrics) {
            loadLyricsForCurrentSong()
        }
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
        if (playbackQueue.isEmpty()) {
            val list = when (binding.viewPager.currentItem) {
                MainPagerAdapter.PAGE_HOME -> recentSongs
                MainPagerAdapter.PAGE_PLAYLIST -> if (currentPlaylistSongs.isNotEmpty()) currentPlaylistSongs else allSongs
                else -> allSongs
            }
            if (list.isEmpty()) return
            playbackQueue.addAll(list)
            val idx = playbackQueue.indexOfFirst { it.id == currentPlayingSong?.id }
            currentQueueIndex = if (idx != -1) idx else 0
        }

        if (playbackQueue.isEmpty()) return

        if (isShuffle && playbackQueue.size > 1) {
            var randIdx: Int
            do {
                randIdx = (0 until playbackQueue.size).random()
            } while (randIdx == currentQueueIndex && playbackQueue.size > 1)
            currentQueueIndex = randIdx
            playSong(playbackQueue[currentQueueIndex])
            return
        }

        if (currentQueueIndex == playbackQueue.size - 1 && repeatMode == RepeatMode.OFF && fromCompletion) {
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

        val nextIdx = if (currentQueueIndex in 0 until playbackQueue.size - 1) currentQueueIndex + 1 else 0
        currentQueueIndex = nextIdx
        playSong(playbackQueue[currentQueueIndex])
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

        if (playbackQueue.isEmpty()) {
            val list = when (binding.viewPager.currentItem) {
                MainPagerAdapter.PAGE_HOME -> recentSongs
                MainPagerAdapter.PAGE_PLAYLIST -> if (currentPlaylistSongs.isNotEmpty()) currentPlaylistSongs else allSongs
                else -> allSongs
            }
            if (list.isEmpty()) return
            playbackQueue.addAll(list)
            val idx = playbackQueue.indexOfFirst { it.id == currentPlayingSong?.id }
            currentQueueIndex = if (idx != -1) idx else 0
        }

        if (playbackQueue.isEmpty()) return
        val prevIdx = if (currentQueueIndex > 0) currentQueueIndex - 1 else playbackQueue.size - 1
        currentQueueIndex = prevIdx
        playSong(playbackQueue[currentQueueIndex])
    }

    private fun addSongToPlayNext(song: Song) {
        if (playbackQueue.isEmpty() || currentPlayingSong == null) {
            playSong(song)
            Toast.makeText(this, "Playing now: ${song.title}", Toast.LENGTH_SHORT).show()
            return
        }

        val insertPos = (currentQueueIndex + 1).coerceIn(0, playbackQueue.size)
        playbackQueue.add(insertPos, song)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        Toast.makeText(this, "Will play next: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun addSongToEndOfQueue(song: Song) {
        if (playbackQueue.isEmpty() || currentPlayingSong == null) {
            playSong(song)
            Toast.makeText(this, "Playing now: ${song.title}", Toast.LENGTH_SHORT).show()
            return
        }

        playbackQueue.add(song)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        Toast.makeText(this, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun showQueueBottomSheet() {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        val bottomSheetDialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val queueBinding = DialogBottomSheetQueueBinding.inflate(layoutInflater)
        bottomSheetDialog.setContentView(queueBinding.root)

        bottomSheetDialog.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.70).toInt()

        // Helper to refresh header counts & Now Playing card
        fun refreshQueueHeader() {
            val upcoming = if (currentQueueIndex in 0 until playbackQueue.size - 1) {
                playbackQueue.subList(currentQueueIndex + 1, playbackQueue.size)
            } else emptyList()

            val totalUpcomingMs = upcoming.sumOf { it.durationMs }
            val formattedDur = formatTimeMs(totalUpcomingMs)
            queueBinding.tvQueueCountDuration.text = "${upcoming.size} tracks · $formattedDur"

            val current = currentPlayingSong
            if (current != null) {
                queueBinding.tvNowPlayingSectionLabel.visibility = View.VISIBLE
                queueBinding.cardNowPlaying.visibility = View.VISIBLE
                queueBinding.tvNowPlayingTitle.text = current.title
                queueBinding.tvNowPlayingArtist.text = current.artist
                queueBinding.tvNowPlayingDuration.text = current.durationFormatted
                ThumbnailLoader.loadThumbnail(queueBinding.ivNowPlayingArt, current)
            } else {
                queueBinding.tvNowPlayingSectionLabel.visibility = View.GONE
                queueBinding.cardNowPlaying.visibility = View.GONE
            }

            if (upcoming.isEmpty()) {
                queueBinding.layoutQueueEmpty.visibility = View.VISIBLE
                queueBinding.rvQueueTracks.visibility = View.GONE
                queueBinding.btnQueueClear.visibility = View.GONE
            } else {
                queueBinding.layoutQueueEmpty.visibility = View.GONE
                queueBinding.rvQueueTracks.visibility = View.VISIBLE
                queueBinding.btnQueueClear.visibility = View.VISIBLE
            }
        }

        val upcomingList = if (currentQueueIndex in 0 until playbackQueue.size - 1) {
            playbackQueue.subList(currentQueueIndex + 1, playbackQueue.size).toMutableList()
        } else mutableListOf()

        lateinit var itemTouchHelper: ItemTouchHelper

        val queueAdapter = QueueAdapter(
            queueList = upcomingList,
            onSongClicked = { pos, _ ->
                val realIdx = currentQueueIndex + 1 + pos
                if (realIdx in 0 until playbackQueue.size) {
                    currentQueueIndex = realIdx
                    playSong(playbackQueue[currentQueueIndex])
                    bottomSheetDialog.dismiss()
                }
            },
            onRemoveClicked = { pos, _ ->
                val realIdx = currentQueueIndex + 1 + pos
                if (realIdx in 0 until playbackQueue.size) {
                    playbackQueue.removeAt(realIdx)
                }
                upcomingList.removeAt(pos)
                queueBinding.rvQueueTracks.adapter?.notifyItemRemoved(pos)
                refreshQueueHeader()
            },
            onStartDrag = { viewHolder ->
                itemTouchHelper.startDrag(viewHolder)
            },
            onQueueChanged = {
                if (currentQueueIndex in 0 until playbackQueue.size) {
                    val head = playbackQueue.subList(0, currentQueueIndex + 1).toList()
                    playbackQueue.clear()
                    playbackQueue.addAll(head)
                    playbackQueue.addAll(upcomingList)
                }
                refreshQueueHeader()
            }
        )

        val touchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                    queueAdapter.moveItem(from, to)
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val realIdx = currentQueueIndex + 1 + pos
                    if (realIdx in 0 until playbackQueue.size) {
                        playbackQueue.removeAt(realIdx)
                    }
                    queueAdapter.removeItem(pos)
                    refreshQueueHeader()
                }
            }
        }

        itemTouchHelper = ItemTouchHelper(touchHelperCallback)
        itemTouchHelper.attachToRecyclerView(queueBinding.rvQueueTracks)

        queueBinding.rvQueueTracks.layoutManager = LinearLayoutManager(this)
        queueBinding.rvQueueTracks.adapter = queueAdapter

        queueBinding.btnQueueClear.setOnClickListener {
            if (currentQueueIndex in 0 until playbackQueue.size) {
                val head = playbackQueue.subList(0, currentQueueIndex + 1).toList()
                playbackQueue.clear()
                playbackQueue.addAll(head)
            } else {
                playbackQueue.clear()
            }
            upcomingList.clear()
            queueAdapter.notifyDataSetChanged()
            refreshQueueHeader()
            Toast.makeText(this, "Upcoming queue cleared", Toast.LENGTH_SHORT).show()
        }

        queueBinding.btnQueueClose.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        queueBinding.cardNowPlaying.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        refreshQueueHeader()
        bottomSheetDialog.show()
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

                        // Real-time synced lyrics active line highlighting and smooth auto-scrolling
                        if (isFullscreenPlayerOpen && isShowingLyrics && lyricsAdapter.isSynced()) {
                            val curPos = current.toLong()
                            val lines = lyricsAdapter.getLines()
                            if (lines.isNotEmpty()) {
                                var targetIdx = -1
                                for (i in lines.indices) {
                                    if (lines[i].timeMs <= curPos) {
                                        targetIdx = i
                                    } else {
                                        break
                                    }
                                }
                                if (targetIdx != -1) {
                                    val changed = lyricsAdapter.setActiveIndex(targetIdx)
                                    val now = System.currentTimeMillis()
                                    if (changed && (!isUserScrollingLyrics || now - lastUserLyricTouchTime > 3000L)) {
                                        isUserScrollingLyrics = false
                                        val layoutManager = binding.rvLyrics.layoutManager as? LinearLayoutManager
                                        val rvHeight = binding.rvLyrics.height
                                        val offset = if (rvHeight > 0) (rvHeight * 0.35f).toInt() else 150
                                        layoutManager?.scrollToPositionWithOffset(targetIdx, offset)
                                    }
                                }
                            }
                        }
                    }
                }
                delay(250)
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

        // Swipe up or tap anywhere on player bar to expand fullscreen player
        var playerBarTouchStartY = 0f
        var playerBarTouchStartX = 0f
        var isPlayerBarSwipingUp = false

        val playerBarGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (diffY < -40 && Math.abs(velocityY) > 80 && Math.abs(diffY) > Math.abs(diffX)) {
                    openFullscreenPlayer()
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                openFullscreenPlayer()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

        val playerBarTouchListener = View.OnTouchListener { _, event ->
            playerBarGestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    playerBarTouchStartY = event.rawY
                    playerBarTouchStartX = event.rawX
                    isPlayerBarSwipingUp = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - playerBarTouchStartY
                    val dx = event.rawX - playerBarTouchStartX
                    if (!isPlayerBarSwipingUp && dy < -20 && Math.abs(dy) > Math.abs(dx) * 1.2) {
                        isPlayerBarSwipingUp = true
                        syncFullscreenPlayerState()
                        binding.fullPlayerContainer.visibility = View.VISIBLE
                    }
                    if (isPlayerBarSwipingUp) {
                        val screenHeight = binding.rootContainer.height.takeIf { it > 0 }?.toFloat()
                            ?: resources.displayMetrics.heightPixels.toFloat()
                        val currentY = maxOf(0f, screenHeight + dy)
                        binding.fullPlayerContainer.translationY = currentY
                        val progress = 1f - (currentY / screenHeight).coerceIn(0f, 1f)
                        binding.playerBarWrapper.alpha = (1f - progress).coerceIn(0f, 1f)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = event.rawY - playerBarTouchStartY
                    val dx = event.rawX - playerBarTouchStartX
                    if (isPlayerBarSwipingUp) {
                        if (dy < -80) {
                            openFullscreenPlayer()
                        } else {
                            val screenHeight = binding.rootContainer.height.takeIf { it > 0 }?.toFloat()
                                ?: resources.displayMetrics.heightPixels.toFloat()
                            binding.fullPlayerContainer.animate()
                                .translationY(screenHeight)
                                .setDuration(220)
                                .withEndAction {
                                    binding.fullPlayerContainer.visibility = View.GONE
                                }
                                .start()
                            binding.playerBarWrapper.animate()
                                .alpha(1f)
                                .setDuration(200)
                                .start()
                        }
                        isPlayerBarSwipingUp = false
                        true
                    } else if (Math.abs(dy) < 25 && Math.abs(dx) < 25) {
                        openFullscreenPlayer()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        binding.playerBarCard.setOnTouchListener(playerBarTouchListener)
        binding.playerBarWrapper.setOnTouchListener(playerBarTouchListener)
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
            binding.ivFullNext.animate()
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(70)
                .withEndAction {
                    binding.ivFullNext.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
                .start()
        }

        binding.btnFullPrev.setOnClickListener {
            playPrevSong()
            binding.ivFullPrev.animate()
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(70)
                .withEndAction {
                    binding.ivFullPrev.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
                .start()
        }

        binding.btnFullRewind.setOnClickListener {
            seekRelative(-5000L)
            binding.ivFullRewind.animate()
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(70)
                .withEndAction {
                    binding.ivFullRewind.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
                .start()
        }

        binding.btnFullForward.setOnClickListener {
            seekRelative(5000L)
            binding.ivFullForward.animate()
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(70)
                .withEndAction {
                    binding.ivFullForward.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
                .start()
        }

        binding.btnFullShuffle.setOnClickListener {
            toggleShuffle()
        }

        binding.btnFullRepeat.setOnClickListener {
            toggleRepeat()
        }

        binding.btnFullPlayerQueue.setOnClickListener {
            showQueueBottomSheet()
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

        binding.rvLyrics.layoutManager = LinearLayoutManager(this)
        binding.rvLyrics.adapter = lyricsAdapter

        binding.rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    isUserScrollingLyrics = true
                    lastUserLyricTouchTime = System.currentTimeMillis()
                }
            }
        })

        var lyricsTouchStartX = 0f
        var lyricsTouchStartY = 0f
        binding.rvLyrics.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lyricsTouchStartX = e.rawX
                        lyricsTouchStartY = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - lyricsTouchStartX
                        val dy = e.rawY - lyricsTouchStartY
                        // Swipe down when at top of lyrics -> return to cover art!
                        if (dy > 45 && dy > Math.abs(dx) * 1.3 && !rv.canScrollVertically(-1)) {
                            showCoverArtView()
                            return true
                        }
                        // Swipe left for next song, right for prev song while in lyrics
                        if (Math.abs(dx) > 80 && Math.abs(dx) > Math.abs(dy) * 1.5) {
                            if (dx < 0) {
                                swipeToNextSong()
                            } else {
                                swipeToPrevSong()
                            }
                            return true
                        }
                    }
                }
                return false
            }
        })

        setupCardSwipeGesture()
    }

    private fun swipeToNextSong() {
        val width = binding.cardFullAlbumArt.width.takeIf { it > 0 }?.toFloat() ?: 600f
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        if (!isShowingLyrics) {
            binding.cardFullAlbumArt.animate()
                .translationX(-width * 0.6f)
                .alpha(0f)
                .setDuration(160)
                .withEndAction {
                    playNextSong()
                    binding.cardFullAlbumArt.translationX = width * 0.6f
                    binding.cardFullAlbumArt.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator(1.4f))
                        .start()
                }
                .start()

            binding.layoutSongInfo.animate()
                .translationX(-width * 0.25f)
                .alpha(0f)
                .setDuration(160)
                .withEndAction {
                    binding.layoutSongInfo.translationX = width * 0.25f
                    binding.layoutSongInfo.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator(1.4f))
                        .start()
                }
                .start()
        } else {
            playNextSong()
        }
    }

    private fun swipeToPrevSong() {
        val width = binding.cardFullAlbumArt.width.takeIf { it > 0 }?.toFloat() ?: 600f
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        if (!isShowingLyrics) {
            binding.cardFullAlbumArt.animate()
                .translationX(width * 0.6f)
                .alpha(0f)
                .setDuration(160)
                .withEndAction {
                    playPrevSong()
                    binding.cardFullAlbumArt.translationX = -width * 0.6f
                    binding.cardFullAlbumArt.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator(1.4f))
                        .start()
                }
                .start()

            binding.layoutSongInfo.animate()
                .translationX(width * 0.25f)
                .alpha(0f)
                .setDuration(160)
                .withEndAction {
                    binding.layoutSongInfo.translationX = -width * 0.25f
                    binding.layoutSongInfo.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator(1.4f))
                        .start()
                }
                .start()
        } else {
            playPrevSong()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCardSwipeGesture() {
        var startRawX = 0f
        var startRawY = 0f
        var isDraggingDown = false
        var isDraggingUp = false
        var isHorizontalSwipe = false
        var wasShowingLyricsOnDown = false
        var flingHandled = false

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 40
            private val SWIPE_VELOCITY_THRESHOLD = 70

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.rawX - e1.rawX
                val diffY = e2.rawY - e1.rawY

                if (Math.abs(diffX) > Math.abs(diffY)) {
                    // Horizontal swipe: left for next song, right for previous song
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        flingHandled = true
                        if (diffX < 0) {
                            swipeToNextSong()
                            return true
                        } else {
                            swipeToPrevSong()
                            return true
                        }
                    }
                } else if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY < 0) {
                        // Swipe up -> open lyrics (only if not already showing lyrics)
                        if (!isShowingLyrics && !wasShowingLyricsOnDown) {
                            flingHandled = true
                            showLyricsView()
                            return true
                        }
                    } else {
                        // Swipe down
                        flingHandled = true
                        if (isShowingLyrics || wasShowingLyricsOnDown) {
                            // If currently showing lyrics or gesture started on lyrics (even if empty lyrics),
                            // swiping down MUST ONLY return to cover art, NEVER exit fullscreen player!
                            showCoverArtView()
                            return true
                        } else {
                            // Only close fullscreen player if gesture was completely on cover art view
                            closeFullscreenPlayer()
                            return true
                        }
                    }
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

        val playerTouchListener = View.OnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                flingHandled = false
                wasShowingLyricsOnDown = isShowingLyrics
                startRawX = event.rawX
                startRawY = event.rawY
                isDraggingDown = false
                isDraggingUp = false
                isHorizontalSwipe = false
                gestureDetector.onTouchEvent(event)
                return@OnTouchListener true
            }

            gestureDetector.onTouchEvent(event)

            if (flingHandled) {
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    isDraggingDown = false
                    isDraggingUp = false
                    isHorizontalSwipe = false
                    binding.cardFullAlbumArt.translationX = 0f
                    binding.layoutSongInfo.translationX = 0f
                    flingHandled = false
                }
                return@OnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY

                    if (!isDraggingDown && !isDraggingUp && !isHorizontalSwipe) {
                        if (Math.abs(dx) > 30 && Math.abs(dx) > Math.abs(dy) * 1.2) {
                            isHorizontalSwipe = true
                        } else if (dy > 20 && dy > Math.abs(dx) * 1.2) {
                            isDraggingDown = true
                            if (!isShowingLyrics && !wasShowingLyricsOnDown) {
                                binding.playerBarWrapper.visibility = View.VISIBLE
                            }
                        } else if (dy < -20 && Math.abs(dy) > Math.abs(dx) * 1.2 && !isShowingLyrics && !wasShowingLyricsOnDown) {
                            isDraggingUp = true
                        }
                    }

                    if (isHorizontalSwipe) {
                        if (!isShowingLyrics && !wasShowingLyricsOnDown) {
                            binding.cardFullAlbumArt.translationX = dx * 0.45f
                            binding.layoutSongInfo.translationX = dx * 0.20f
                        }
                        true
                    } else if (isDraggingDown) {
                        if (isShowingLyrics || wasShowingLyricsOnDown) {
                            val currentY = maxOf(0f, dy)
                            binding.layoutLyricsSection.translationY = currentY * 0.35f
                        } else {
                            val currentY = maxOf(0f, dy)
                            binding.fullPlayerContainer.translationY = currentY
                            val slideDistance = binding.fullPlayerContainer.height.takeIf { it > 0 }?.toFloat()
                                ?: resources.displayMetrics.heightPixels.toFloat()
                            binding.playerBarWrapper.alpha = (currentY / slideDistance).coerceIn(0f, 1f)
                        }
                        true
                    } else if (isDraggingUp) {
                        binding.layoutCoverSection.translationY = minOf(0f, dy * 0.35f)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY

                    if (isHorizontalSwipe) {
                        if (dx < -70) {
                            swipeToNextSong()
                        } else if (dx > 70) {
                            swipeToPrevSong()
                        } else {
                            binding.cardFullAlbumArt.animate().translationX(0f).setDuration(200).start()
                            binding.layoutSongInfo.animate().translationX(0f).setDuration(200).start()
                        }
                        isHorizontalSwipe = false
                        true
                    } else if (isDraggingUp) {
                        if (dy < -70) {
                            showLyricsView()
                        } else {
                            binding.layoutCoverSection.animate().translationY(0f).setDuration(200).start()
                        }
                        isDraggingUp = false
                        true
                    } else if (isDraggingDown) {
                        if (isShowingLyrics || wasShowingLyricsOnDown) {
                            // Touch started in lyrics -> swiping down ONLY returns to cover, NEVER exits fullscreen
                            if (dy > 40) {
                                showCoverArtView()
                            } else {
                                binding.layoutLyricsSection.animate().translationY(0f).setDuration(200).start()
                            }
                        } else {
                            // Touch started on cover art -> swiping down closes player
                            if (dy > 160) {
                                closeFullscreenPlayer()
                            } else {
                                binding.fullPlayerContainer.animate()
                                    .translationY(0f)
                                    .setDuration(220)
                                    .setInterpolator(DecelerateInterpolator(1.5f))
                                    .start()
                                binding.playerBarWrapper.animate()
                                    .alpha(0f)
                                    .setDuration(180)
                                    .withEndAction {
                                        binding.playerBarWrapper.visibility = View.INVISIBLE
                                    }
                                    .start()
                            }
                        }
                        isDraggingDown = false
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        binding.cardFullAlbumArt.setOnTouchListener(playerTouchListener)
        binding.ivFullAlbumArt.setOnTouchListener(playerTouchListener)
        binding.layoutCoverSection.setOnTouchListener(playerTouchListener)
        binding.layoutSongInfo.setOnTouchListener(playerTouchListener)
        binding.fullPlayerHeader.setOnTouchListener(playerTouchListener)
        binding.layoutLyricsSection.setOnTouchListener(playerTouchListener)
        binding.layoutNoLyrics.setOnTouchListener(playerTouchListener)
        binding.layoutLyricsMiniHeader.setOnTouchListener(playerTouchListener)
        binding.tvLyricsHint.setOnTouchListener(playerTouchListener)
        binding.tvLyricsHint.setOnClickListener { showLyricsView() }
    }

    private fun seekRelative(deltaMs: Long) {
        val player = mediaPlayer ?: return
        val currentPos = player.currentPosition
        val duration = player.duration
        val targetPos = (currentPos + deltaMs).coerceIn(0L, duration.toLong()).toInt()
        player.seekTo(targetPos)
        binding.fullPlayerSeekBar.progress = targetPos
        binding.tvFullCurrentTime.text = formatTimeMs(targetPos.toLong())
        currentPlayingSong?.let { song ->
            MusicPlaybackService.startOrUpdate(
                context = this,
                song = song,
                isPlaying = isPlaying,
                durationMs = duration.toLong(),
                positionMs = targetPos.toLong()
            )
        }
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun showLyricsView(animate: Boolean = true) {
        if (isShowingLyrics) return
        isShowingLyrics = true
        lyricsHintAnimator?.cancel()
        loadLyricsForCurrentSong()

        val height = binding.fullPlayerContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        if (!animate) {
            binding.layoutCoverSection.visibility = View.GONE
            binding.layoutCoverSection.translationY = 0f
            binding.layoutCoverSection.alpha = 1f
            binding.layoutLyricsSection.visibility = View.VISIBLE
            binding.layoutLyricsSection.translationY = 0f
            binding.layoutLyricsSection.alpha = 1f
            return
        }

        binding.layoutLyricsSection.visibility = View.VISIBLE
        binding.layoutLyricsSection.translationY = height * 0.35f
        binding.layoutLyricsSection.alpha = 0f

        binding.layoutCoverSection.animate()
            .translationY(-height * 0.25f)
            .alpha(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                binding.layoutCoverSection.visibility = View.GONE
                binding.layoutCoverSection.translationY = 0f
                binding.layoutCoverSection.alpha = 1f
            }
            .start()

        binding.layoutLyricsSection.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()
    }

    private fun showCoverArtView(animate: Boolean = true) {
        if (!isShowingLyrics && binding.layoutCoverSection.visibility == View.VISIBLE) return
        isShowingLyrics = false
        playLyricsHintBlinkAnimation()

        val height = binding.fullPlayerContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        if (!animate) {
            binding.layoutLyricsSection.visibility = View.GONE
            binding.layoutLyricsSection.translationY = 0f
            binding.layoutLyricsSection.alpha = 1f
            binding.layoutCoverSection.visibility = View.VISIBLE
            binding.layoutCoverSection.translationY = 0f
            binding.layoutCoverSection.alpha = 1f
            return
        }

        binding.layoutCoverSection.visibility = View.VISIBLE
        binding.layoutCoverSection.translationY = -height * 0.25f
        binding.layoutCoverSection.alpha = 0f

        binding.layoutLyricsSection.animate()
            .translationY(height * 0.35f)
            .alpha(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                binding.layoutLyricsSection.visibility = View.GONE
                binding.layoutLyricsSection.translationY = 0f
                binding.layoutLyricsSection.alpha = 1f
            }
            .start()

        binding.layoutCoverSection.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()
    }

    private fun toggleCoverOrLyrics() {
        if (isShowingLyrics) {
            showCoverArtView()
        } else {
            showLyricsView()
        }
    }

    private fun playLyricsHintBlinkAnimation() {
        lyricsHintAnimator?.cancel()
        val hintView = binding.tvLyricsHint
        hintView.visibility = View.VISIBLE
        hintView.alpha = 0.35f

        // Fading blink twice: 0.35 -> 0.95 -> 0.20 -> 0.95 -> 0.38 (stays there at low opacity)
        val animator = ValueAnimator.ofFloat(0.35f, 0.95f, 0.20f, 0.95f, 0.38f).apply {
            duration = 1600L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                hintView.alpha = animation.animatedValue as Float
            }
        }
        lyricsHintAnimator = animator
        animator.start()
    }

    private fun loadLyricsForCurrentSong() {
        val song = currentPlayingSong ?: return
        binding.tvLyricsMiniTitle.text = song.title
        binding.tvLyricsMiniArtist.text = song.artist

        lyricsJob?.cancel()
        lyricsJob = lifecycleScope.launch {
            binding.layoutNoLyrics.visibility = View.GONE
            binding.rvLyrics.visibility = View.VISIBLE

            val lyricsData = LyricsExtractor.getLyricsData(this@MainActivity, song)
            if (currentPlayingSong?.id == song.id) {
                if (!lyricsData.isEmpty) {
                    lyricsAdapter.submitLyrics(lyricsData)
                    binding.layoutNoLyrics.visibility = View.GONE
                    binding.rvLyrics.visibility = View.VISIBLE

                    // Immediately position active line
                    val curPos = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    if (lyricsData.isSynced && lyricsData.lines.isNotEmpty()) {
                        var targetIdx = -1
                        for (i in lyricsData.lines.indices) {
                            if (lyricsData.lines[i].timeMs <= curPos) {
                                targetIdx = i
                            } else {
                                break
                            }
                        }
                        if (targetIdx != -1) {
                            lyricsAdapter.setActiveIndex(targetIdx)
                            val layoutManager = binding.rvLyrics.layoutManager as? LinearLayoutManager
                            val rvHeight = binding.rvLyrics.height
                            val offset = if (rvHeight > 0) (rvHeight * 0.35f).toInt() else 150
                            layoutManager?.scrollToPositionWithOffset(targetIdx, offset)
                        }
                    }
                } else {
                    lyricsAdapter.submitLyrics(LyricsData(false, emptyList()))
                    binding.rvLyrics.visibility = View.GONE
                    binding.layoutNoLyrics.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun openFullscreenPlayer() {
        if (isFullscreenPlayerOpen) return
        isFullscreenPlayerOpen = true

        syncFullscreenPlayerState()

        val slideDistance = binding.fullPlayerContainer.height.takeIf { it > 0 }?.toFloat()
            ?: binding.rootContainer.height.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.heightPixels.toFloat()

        binding.fullPlayerContainer.visibility = View.VISIBLE
        if (binding.fullPlayerContainer.translationY <= 0f || binding.fullPlayerContainer.translationY >= slideDistance) {
            binding.fullPlayerContainer.translationY = slideDistance
        }
        binding.fullPlayerContainer.alpha = 1f

        binding.fullPlayerContainer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()

        if (!isShowingLyrics) {
            playLyricsHintBlinkAnimation()
        }

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

        val slideDistance = binding.fullPlayerContainer.height.takeIf { it > 0 }?.toFloat()
            ?: binding.rootContainer.height.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.heightPixels.toFloat()

        binding.fullPlayerContainer.animate()
            .translationY(slideDistance)
            .alpha(1f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                binding.fullPlayerContainer.visibility = View.GONE
                binding.fullPlayerContainer.translationY = 0f
                binding.ivFullAlbumArt.setImageDrawable(null)
                binding.ivFullPlayerBlurredBg.setImageDrawable(null)
                lyricsHintAnimator?.cancel()
                showCoverArtView(animate = false)
            }
            .start()
    }

    private fun syncFullscreenPlayerState() {
        val song = currentPlayingSong
        if (song != null) {
            binding.tvFullTitle.text = song.title
            binding.tvFullArtist.text = song.artist
            ThumbnailLoader.loadHighResArt(binding.ivFullAlbumArt, song, binding.ivFullPlayerBlurredBg)
            if (isShowingLyrics) {
                loadLyricsForCurrentSong()
            } else {
                showCoverArtView(animate = false)
            }
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
            binding.ivFullPlayerBlurredBg.setImageDrawable(null)
            showCoverArtView(animate = false)
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

        val totalSeconds = (currentPlaylistSongs.sumOf { it.durationMs } / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val formattedDuration = if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)

        pagerAdapter.openPlaylistDetail(playlistName, currentPlaylistSongs.size, currentPlaylistSongs.firstOrNull(), formattedDuration)
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
        val createBinding = DialogCreatePlaylistBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(createBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        createBinding.tvCreatePlaylistTitle.text = "Rename Playlist"
        createBinding.tvCreatePlaylistSubtitle.text = "Update collection name"
        createBinding.tvCreatePlaylistSubmitText.text = "SAVE CHANGES"
        createBinding.etCreatePlaylistName.setText(oldName)
        createBinding.etCreatePlaylistName.selectAll()

        createBinding.btnCreatePlaylistClose.setOnClickListener {
            dialog.dismiss()
        }

        createBinding.btnCreatePlaylistCancel.setOnClickListener {
            dialog.dismiss()
        }

        createBinding.btnCreatePlaylistSubmit.setOnClickListener {
            val newName = createBinding.etCreatePlaylistName.text.toString().trim()
            if (newName.isNotBlank() && newName != oldName) {
                val success = PlaylistManager.renamePlaylist(this, oldName, newName)
                if (success) {
                    if (currentOpenPlaylist == oldName) {
                        currentOpenPlaylist = newName
                    }
                    loadPlaylists()
                    Toast.makeText(this, "Renamed to $newName", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Playlist with that name already exists", Toast.LENGTH_SHORT).show()
                }
            } else {
                dialog.dismiss()
            }
        }

        dialog.show()
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
            if (song.year > 0) "${song.artist} • ${song.album} (${song.year})" else "${song.artist} • ${song.album}"
        } else {
            if (song.year > 0) "${song.artist} • ${song.year}" else song.artist
        }
        sheetBinding.tvOptionSongSubtitle.text = subtitle
        sheetBinding.tvOptionSongDuration.text = song.durationFormatted
        ThumbnailLoader.loadThumbnail(sheetBinding.ivOptionAlbumArt, song)

        // 0a. Play Next
        sheetBinding.btnOptionPlayNext.setOnClickListener {
            bottomSheetDialog.dismiss()
            addSongToPlayNext(song)
        }

        // 0b. Add to Queue
        sheetBinding.btnOptionAddToQueue.setOnClickListener {
            bottomSheetDialog.dismiss()
            addSongToEndOfQueue(song)
        }

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
        val addBinding = DialogAddToPlaylistBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(addBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        addBinding.tvAddSongTitleSubtitle.text = song.title

        addBinding.btnAddPlaylistClose.setOnClickListener {
            dialog.dismiss()
        }

        addBinding.btnCreateNewPlaylistFromAdd.setOnClickListener {
            dialog.dismiss()
            showCreatePlaylistDialog(song)
        }

        val playlists = PlaylistManager.getPlaylistInfos(this, allSongs)
        val existingPlaylists = PlaylistManager.getPlaylistsContainingSong(this, song.id)

        if (playlists.isEmpty()) {
            addBinding.tvNoPlaylistsHint.visibility = View.VISIBLE
            addBinding.rvAddToPlaylist.visibility = View.GONE
        } else {
            addBinding.tvNoPlaylistsHint.visibility = View.GONE
            addBinding.rvAddToPlaylist.visibility = View.VISIBLE
            addBinding.rvAddToPlaylist.layoutManager = LinearLayoutManager(this)

            val adapter = AddToPlaylistAdapter(existingPlaylists) { selectedPlaylist ->
                val added = PlaylistManager.addSongToPlaylist(this, selectedPlaylist.name, song.id)
                if (added) {
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    loadPlaylists()
                    Toast.makeText(this, "Added to ${selectedPlaylist.name}", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Already in ${selectedPlaylist.name}", Toast.LENGTH_SHORT).show()
                }
            }
            adapter.submitList(playlists)
            addBinding.rvAddToPlaylist.adapter = adapter
        }

        dialog.show()
    }

    private fun showCreatePlaylistDialog(song: Song? = null) {
        val createBinding = DialogCreatePlaylistBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(createBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        createBinding.btnCreatePlaylistClose.setOnClickListener {
            dialog.dismiss()
        }

        createBinding.btnCreatePlaylistCancel.setOnClickListener {
            dialog.dismiss()
        }

        createBinding.btnCreatePlaylistSubmit.setOnClickListener {
            val name = createBinding.etCreatePlaylistName.text.toString().trim()
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
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Playlist with that name already exists", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Please enter a playlist name", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showAddSongsToPlaylistDialog(playlistName: String) {
        val selectBinding = DialogSelectSongsForPlaylistBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(selectBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        selectBinding.tvSelectSongsTitle.text = "Add Tracks to \"$playlistName\""
        selectBinding.tvSelectSongsSubtitle.text = "Tap any track to add it to this playlist"

        selectBinding.btnSelectSongsClose.setOnClickListener {
            dialog.dismiss()
        }

        val songAdapter = SongAdapter(
            onSongClicked = { song, _ ->
                val added = PlaylistManager.addSongToPlaylist(this, playlistName, song.id)
                if (added) {
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    refreshCurrentPlaylistSongs(playlistName)
                    loadPlaylists()
                    Toast.makeText(this, "Added \"${song.title}\" to $playlistName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "\"${song.title}\" is already in $playlistName", Toast.LENGTH_SHORT).show()
                }
            },
            onSongLongClicked = { _, _ -> }
        )

        selectBinding.rvSelectSongsList.layoutManager = LinearLayoutManager(this)
        selectBinding.rvSelectSongsList.adapter = songAdapter
        songAdapter.submitList(allSongs)

        selectBinding.etSearchSongsToAdd.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim() ?: ""
                if (q.isBlank()) {
                    songAdapter.submitList(allSongs)
                } else {
                    val filtered = allSongs.filter {
                        it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
                    }
                    songAdapter.submitList(filtered)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialog.show()
    }

    private fun downloadCoverBytes(urlStr: String): ByteArray? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mewsic/1.0.0")
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else null
        } catch (_: Exception) {
            null
        }
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
        editBinding.etEditGenre.setText(song.genre)
        editBinding.etEditYear.setText(if (song.year > 0) song.year.toString() else "")

        // Initial cover preview from thumbnail loader
        ThumbnailLoader.loadThumbnail(editBinding.ivEditCoverPreview, song)

        var pendingCoverBytes: ByteArray? = null
        var pendingCoverBitmap: Bitmap? = null

        fun updateBadge(text: String) {
            val hasTimestamps = Regex("""\[\d{1,2}:\d{2}""").containsMatchIn(text)
            when {
                hasTimestamps -> {
                    editBinding.tvEditLyricsBadge.text = "Synced (LRC)"
                    editBinding.tvEditLyricsBadge.visibility = View.VISIBLE
                }
                text.isNotBlank() -> {
                    editBinding.tvEditLyricsBadge.text = "Plain"
                    editBinding.tvEditLyricsBadge.visibility = View.VISIBLE
                }
                else -> {
                    editBinding.tvEditLyricsBadge.text = "No Lyrics"
                    editBinding.tvEditLyricsBadge.visibility = View.VISIBLE
                }
            }
        }

        // Fetch existing raw lyrics in background
        lifecycleScope.launch {
            val raw = LyricsExtractor.getRawLyrics(this@MainActivity, song)
            if (!raw.isNullOrBlank()) {
                editBinding.etEditLyrics.setText(raw)
                updateBadge(raw)
            } else {
                updateBadge("")
            }
        }

        editBinding.etEditLyrics.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateBadge(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        editBinding.btnEditClose.setOnClickListener {
            dialog.dismiss()
        }

        editBinding.btnEditCancel.setOnClickListener {
            dialog.dismiss()
        }

        // Local File Picker
        editBinding.btnEditLocalFile.setOnClickListener {
            onImagePickedCallback = { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                pendingCoverBytes = bytes
                                pendingCoverBitmap = bmp
                                withContext(Dispatchers.Main) {
                                    editBinding.ivEditCoverPreview.setImageBitmap(bmp)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            pickImageLauncher.launch("image/*")
        }

        // Fetch Cover from online
        editBinding.btnEditFetchCover.setOnClickListener {
            val title = editBinding.etEditTitle.text.toString().trim().ifEmpty { song.title }
            val artist = editBinding.etEditArtist.text.toString().trim().ifEmpty { song.artist }
            val q = "$artist $title".trim()
            editBinding.pbEditLoading.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.IO) {
                var tracks = HarbourSearchEngine.search(q, "itunes")
                if (tracks.isEmpty()) {
                    tracks = HarbourSearchEngine.search(q, "jiosaavn")
                }
                val coverUrl = tracks.firstOrNull { it.coverArtUrl.isNotBlank() }?.coverArtUrl
                var bmp: Bitmap? = null
                var bytes: ByteArray? = null
                if (!coverUrl.isNullOrBlank()) {
                    bytes = downloadCoverBytes(coverUrl)
                    if (bytes != null) {
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                withContext(Dispatchers.Main) {
                    editBinding.pbEditLoading.visibility = View.GONE
                    if (bmp != null && bytes != null) {
                        pendingCoverBytes = bytes
                        pendingCoverBitmap = bmp
                        editBinding.ivEditCoverPreview.setImageBitmap(bmp)
                        Toast.makeText(this@MainActivity, "Cover art fetched", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "No cover art found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Fetch Lyrics
        editBinding.btnEditFetchLyrics.setOnClickListener {
            val title = editBinding.etEditTitle.text.toString().trim().ifEmpty { song.title }
            val artist = editBinding.etEditArtist.text.toString().trim().ifEmpty { song.artist }
            val album = editBinding.etEditAlbum.text.toString().trim()
            editBinding.pbEditLoading.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.IO) {
                val lyrics = HarbourLyricsService.fetchLyrics(
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = song.durationMs / 1000.0
                )
                withContext(Dispatchers.Main) {
                    editBinding.pbEditLoading.visibility = View.GONE
                    if (!lyrics.isNullOrBlank()) {
                        editBinding.etEditLyrics.setText(lyrics)
                        updateBadge(lyrics)
                        Toast.makeText(this@MainActivity, "Lyrics fetched", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "No lyrics found online", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Fetch All Metadata
        editBinding.btnEditFetchMetadata.setOnClickListener {
            val title = editBinding.etEditTitle.text.toString().trim().ifEmpty { song.title }
            val artist = editBinding.etEditArtist.text.toString().trim().ifEmpty { song.artist }
            val q = "$artist $title".trim()
            editBinding.pbEditLoading.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.IO) {
                var tracks = HarbourSearchEngine.search(q, "itunes")
                if (tracks.isEmpty()) {
                    tracks = HarbourSearchEngine.search(q, "jiosaavn")
                }
                val best = tracks.firstOrNull()
                val coverUrl = best?.coverArtUrl
                var coverBytes: ByteArray? = null
                var coverBmp: Bitmap? = null
                if (!coverUrl.isNullOrBlank()) {
                    coverBytes = downloadCoverBytes(coverUrl)
                    if (coverBytes != null) {
                        coverBmp = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
                    }
                }
                val fetchedLyrics = HarbourLyricsService.fetchLyrics(
                    title = best?.title ?: title,
                    artist = best?.artist ?: artist,
                    album = best?.album ?: "",
                    durationSeconds = song.durationMs / 1000.0
                )
                withContext(Dispatchers.Main) {
                    editBinding.pbEditLoading.visibility = View.GONE
                    if (best != null) {
                        editBinding.etEditTitle.setText(best.title)
                        editBinding.etEditArtist.setText(best.artist)
                        if (best.album.isNotBlank()) editBinding.etEditAlbum.setText(best.album)
                        if (best.year.isNotBlank()) editBinding.etEditYear.setText(best.year)
                        if (best.genre.isNotBlank()) editBinding.etEditGenre.setText(best.genre)
                    }
                    if (coverBmp != null && coverBytes != null) {
                        pendingCoverBytes = coverBytes
                        pendingCoverBitmap = coverBmp
                        editBinding.ivEditCoverPreview.setImageBitmap(coverBmp)
                    }
                    if (!fetchedLyrics.isNullOrBlank()) {
                        editBinding.etEditLyrics.setText(fetchedLyrics)
                        updateBadge(fetchedLyrics)
                    }
                    Toast.makeText(this@MainActivity, "Metadata fetched", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Save
        editBinding.btnEditSave.setOnClickListener {
            val newTitle = editBinding.etEditTitle.text.toString().trim()
            if (newTitle.isBlank()) {
                Toast.makeText(this, "Title cannot be blank", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val newArtist = editBinding.etEditArtist.text.toString().trim().ifEmpty { "Unknown Artist" }
            val newAlbum = editBinding.etEditAlbum.text.toString().trim().ifEmpty { "Unknown Album" }
            val newGenre = editBinding.etEditGenre.text.toString().trim()
            val newYear = editBinding.etEditYear.text.toString().trim()
            val newLyrics = editBinding.etEditLyrics.text.toString().trim()

            editBinding.pbEditLoading.visibility = View.VISIBLE
            editBinding.btnEditSave.isEnabled = false

            lifecycleScope.launch(Dispatchers.IO) {
                // 1. Embed audio tags directly into file
                var writeSuccess = false
                try {
                    val file = File(song.filePath)
                    if (file.exists() && file.canWrite()) {
                        val rawBytes = file.readBytes()
                        val ext = file.extension.ifBlank { "mp3" }
                        val updatedBytes = AudioTagEmbedder.embed(
                            audioBytes = rawBytes,
                            extension = ext,
                            title = newTitle,
                            artist = newArtist,
                            album = newAlbum,
                            year = newYear,
                            genre = newGenre,
                            lyrics = newLyrics.takeIf { it.isNotBlank() },
                            coverArtBytes = pendingCoverBytes
                        )
                        file.writeBytes(updatedBytes)
                        writeSuccess = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (!writeSuccess) {
                    try {
                        val rawBytes = contentResolver.openInputStream(song.contentUri)?.use { it.readBytes() }
                        if (rawBytes != null && rawBytes.isNotEmpty()) {
                            val ext = song.filePath.substringAfterLast('.', "mp3")
                            val updatedBytes = AudioTagEmbedder.embed(
                                audioBytes = rawBytes,
                                extension = ext,
                                title = newTitle,
                                artist = newArtist,
                                album = newAlbum,
                                year = newYear,
                                genre = newGenre,
                                lyrics = newLyrics.takeIf { it.isNotBlank() },
                                coverArtBytes = pendingCoverBytes
                            )
                            contentResolver.openOutputStream(song.contentUri, "wt")?.use { out ->
                                out.write(updatedBytes)
                                writeSuccess = true
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 2. Update MediaStore values
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.TITLE, newTitle)
                        put(MediaStore.Audio.Media.ARTIST, newArtist)
                        put(MediaStore.Audio.Media.ALBUM, newAlbum)
                        if (newYear.isNotBlank()) {
                            newYear.toIntOrNull()?.let { put(MediaStore.Audio.Media.YEAR, it) }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && newGenre.isNotBlank()) {
                            put(MediaStore.Audio.Media.GENRE, newGenre)
                        }
                    }
                    contentResolver.update(song.contentUri, values, null, null)
                } catch (_: Exception) {}

                // 3. Save lyrics sidecar & internal cache
                if (newLyrics.isNotBlank()) {
                    LyricsExtractor.saveLyrics(this@MainActivity, song, newLyrics)
                }

                // 4. Update thumbnail caches
                if (pendingCoverBitmap != null) {
                    ThumbnailLoader.updateCachedBitmap(song.id, pendingCoverBitmap!!)
                } else {
                    ThumbnailLoader.invalidate(song.id)
                }

                // 5. Update data models
                val updatedSong = song.copy(
                    title = newTitle,
                    artist = newArtist,
                    album = newAlbum,
                    year = newYear.toIntOrNull() ?: 0,
                    genre = newGenre
                )
                allSongs = allSongs.map { if (it.id == song.id) updatedSong else it }
                LibraryCache.saveCachedSongs(this@MainActivity, allSongs)

                withContext(Dispatchers.Main) {
                    applySongsToUI(allSongs)

                    if (currentPlayingSong?.id == song.id) {
                        currentPlayingSong = updatedSong
                        binding.tvPlayerTitle.text = newTitle
                        binding.tvPlayerArtist.text = newArtist
                        binding.tvFullTitle.text = newTitle
                        binding.tvFullArtist.text = newArtist
                        if (pendingCoverBitmap != null) {
                            binding.ivPlayerAlbumArt.setImageBitmap(pendingCoverBitmap)
                            binding.ivFullAlbumArt.setImageBitmap(pendingCoverBitmap)
                        }
                        loadLyricsForCurrentSong()
                    }

                    Toast.makeText(this@MainActivity, "Metadata saved", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
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
                val base = song.filePath.substringBeforeLast('.')
                java.io.File("$base.lrc").delete()
                java.io.File("$base.meta.json").delete()
                java.io.File("$base.jpg").delete()
            }
            contentResolver.delete(song.contentUri, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Remove from list and update UI
        playbackQueue.removeAll { it.id == song.id }
        currentQueueIndex = playbackQueue.indexOfFirst { it.id == currentPlayingSong?.id }
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
        lyricsJob?.cancel()
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
