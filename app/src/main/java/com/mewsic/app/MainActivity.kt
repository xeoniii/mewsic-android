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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import android.view.HapticFeedbackConstants
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mewsic.app.adapter.MainPagerAdapter
import com.mewsic.app.adapter.SongAdapter
import com.mewsic.app.databinding.ActivityMainBinding
import com.mewsic.app.databinding.DialogBottomSheetSongOptionsBinding
import com.mewsic.app.databinding.DialogEditSongBinding
import com.mewsic.app.model.Song
import com.mewsic.app.scanner.LibraryCache
import com.mewsic.app.scanner.MediaScanner
import com.mewsic.app.scanner.PlaylistManager
import com.mewsic.app.scanner.ThumbnailLoader
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
    private var loadAnimator: ObjectAnimator? = null
    private var glowAnimator: ObjectAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    // Audio Playback State
    private var isPlaying = false
    private var currentPlayingSong: Song? = null
    private var mediaPlayer: MediaPlayer? = null
    private var progressTrackingJob: Job? = null

    // Scanned Music Datasets
    private var allSongs: List<Song> = emptyList()
    private var recentSongs: List<Song> = emptyList()

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

    private val pagerAdapter by lazy {
        MainPagerAdapter(
            homeAdapter = homeSongAdapter,
            libraryAdapter = librarySongAdapter,
            onExploreLibraryClicked = {
                binding.viewPager.setCurrentItem(MainPagerAdapter.PAGE_LIBRARY, true)
            },
            onScaleChanged = { newScale ->
                applyUiScale(newScale)
            },
            onRescanClicked = {
                onManualRescan()
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

        // 1. Immediately load cached library so app already knows everything on cold start
        loadInitialCachedLibrary()

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
        allSongs = songs
        recentSongs = songs.sortedByDescending { maxOf(it.dateAdded, it.dateModified) }
        val stats = MediaScanner.computeLibraryStats(songs)

        homeSongAdapter.submitList(recentSongs.take(20))
        librarySongAdapter.submitList(allSongs)
        pagerAdapter.updateData(stats, allSongs.size)

        // Prime player bar with the most recently added song if idle
        if (currentPlayingSong == null && recentSongs.isNotEmpty()) {
            primePlayerBar(recentSongs.first())
        }
    }

    private fun primePlayerBar(song: Song) {
        currentPlayingSong = song
        binding.tvPlayerTitle.text = song.title
        binding.tvPlayerArtist.text = song.artist
        ThumbnailLoader.loadThumbnail(binding.ivPlayerAlbumArt, song)
        binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_play)
        isPlaying = false
        binding.playerProgressBar.pivotX = 0f
        binding.playerProgressBar.scaleX = 0f
    }

    private fun playSong(song: Song) {
        currentPlayingSong = song
        homeSongAdapter.activeSongId = song.id
        librarySongAdapter.activeSongId = song.id

        // Update player bar info
        binding.tvPlayerTitle.text = song.title
        binding.tvPlayerArtist.text = song.artist
        ThumbnailLoader.loadThumbnail(binding.ivPlayerAlbumArt, song)

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, song.contentUri)
                prepare()
                start()
                setOnCompletionListener {
                    playNextSong()
                }
            }
            isPlaying = true
            binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_pause)
            startProgressTracking()
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
        if (isPlaying && player != null) {
            player.pause()
            isPlaying = false
            binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_play)
            progressTrackingJob?.cancel()
        } else {
            if (player != null) {
                player.start()
                isPlaying = true
                binding.ivPlayPauseIcon.setImageResource(R.drawable.ic_player_pause)
                startProgressTracking()
            } else {
                currentPlayingSong?.let { playSong(it) }
            }
        }
    }

    private fun playNextSong() {
        val list = if (binding.viewPager.currentItem == MainPagerAdapter.PAGE_HOME) recentSongs else allSongs
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == currentPlayingSong?.id }
        val nextIdx = if (idx in 0 until list.size - 1) idx + 1 else 0
        playSong(list[nextIdx])
    }

    private fun playPrevSong() {
        val list = if (binding.viewPager.currentItem == MainPagerAdapter.PAGE_HOME) recentSongs else allSongs
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == currentPlayingSong?.id }
        val prevIdx = if (idx > 0) idx - 1 else list.size - 1
        playSong(list[prevIdx])
    }

    private fun startProgressTracking() {
        progressTrackingJob?.cancel()
        binding.playerProgressBar.pivotX = 0f
        progressTrackingJob = lifecycleScope.launch {
            while (isPlaying) {
                val player = mediaPlayer
                if (player != null && player.isPlaying) {
                    val duration = player.duration
                    val current = player.currentPosition
                    if (duration > 0) {
                        val fraction = (current.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        binding.playerProgressBar.scaleX = fraction
                    }
                }
                delay(500)
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

        // Progress bar initial state
        binding.playerProgressBar.pivotX = 0f
        binding.playerProgressBar.scaleX = 0f

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
            binding.btnPlayerNext.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(80)
                .withEndAction {
                    playNextSong()
                    binding.btnPlayerNext.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
                .start()
        }

        binding.btnPlayerPrev.setOnClickListener {
            binding.btnPlayerPrev.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(80)
                .withEndAction {
                    playPrevSong()
                    binding.btnPlayerPrev.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
                .start()
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
        // App content is already visible and populating behind the loading screen
        binding.loadingScreenContainer.alpha = 1f
        binding.loadingScreenContainer.visibility = View.VISIBLE
        binding.blankContentContainer.visibility = View.VISIBLE

        // Anchor scale from the left edge (0% -> 100% left-to-right fill)
        binding.loadingIndicator.pivotX = 0f
        binding.loadingIndicator.scaleX = 0f

        // Ambient glow breathing pulse
        glowAnimator?.cancel()
        glowAnimator = ObjectAnimator.ofFloat(binding.ambientGlow, "alpha", 0.5f, 1.0f).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Match bar width to header row and animate
        binding.headerRow.post {
            val headerWidth = binding.headerRow.width
            if (headerWidth > 0) {
                val params = binding.loadingTrack.layoutParams
                params.width = headerWidth
                binding.loadingTrack.layoutParams = params
            }

            binding.loadingTrack.post {
                playLoadingProgressAnimation()
            }
        }
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
            duration = 1750
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Smoothly fade out loading overlay revealing the fully-loaded app
                    binding.loadingScreenContainer.animate()
                        .alpha(0f)
                        .setDuration(350)
                        .setStartDelay(80)
                        .withEndAction {
                            binding.loadingScreenContainer.visibility = View.GONE
                            glowAnimator?.cancel()

                            // Re-align indicator with measured tabs
                            binding.tabBarContainer.post {
                                updateIndicator(binding.viewPager.currentItem, 0f)
                            }

                            // Subtle floating entrance animation for top navigation bar
                            binding.topBarWrapper.alpha = 0f
                            binding.topBarWrapper.translationY = -30f
                            binding.topBarWrapper.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(400)
                                .setInterpolator(DecelerateInterpolator())
                                .start()

                            // Subtle floating entrance animation for bottom player bar
                            binding.playerBarWrapper.alpha = 0f
                            binding.playerBarWrapper.translationY = 30f
                            binding.playerBarWrapper.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(400)
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
    // Song Context Actions (Long Press Options: Delete, Edit, Add to Playlist)
    // =========================================================================

    private fun showSongOptions(song: Song) {
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
                    Toast.makeText(this, "Added to $selectedPlaylist", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Already in $selectedPlaylist", Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showCreatePlaylistDialog(song: Song) {
        val input = android.widget.EditText(this).apply {
            hint = "Playlist Name"
            setPadding(48, 32, 48, 32)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create & Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    PlaylistManager.createPlaylist(this, name)
                    PlaylistManager.addSongToPlaylist(this, name, song.id)
                    Toast.makeText(this, "Created & Added to $name", Toast.LENGTH_SHORT).show()
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
            playNextSong()
        }

        Toast.makeText(this, "Deleted \"${song.title}\"", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        loadAnimator?.cancel()
        glowAnimator?.cancel()
        progressTrackingJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}
