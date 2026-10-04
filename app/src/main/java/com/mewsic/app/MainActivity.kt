package com.mewsic.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayoutMediator
import com.mewsic.app.adapter.MainPagerAdapter
import com.mewsic.app.data.DummyData
import com.mewsic.app.databinding.ActivityMainBinding
import com.mewsic.app.model.Song

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter

    var currentSong: Song = DummyData.getDummySongs().first()
        private set
    var isPlaying: Boolean = true
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply system bar insets cleanly
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                systemBars.top,
                binding.topBar.paddingRight,
                binding.topBar.paddingBottom
            )
            binding.miniPlayerContainer.setPadding(
                binding.miniPlayerContainer.paddingLeft,
                binding.miniPlayerContainer.paddingTop,
                binding.miniPlayerContainer.paddingRight,
                systemBars.bottom
            )
            insets
        }

        setupViewPagerAndTabs()
        setupMiniPlayer()
    }

    private fun setupViewPagerAndTabs() {
        pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.apply {
            adapter = pagerAdapter
            // Keep all 3 pages in memory for instantaneous zero-lag swiping
            offscreenPageLimit = 2
        }

        val tabTitles = arrayOf("DISCOVER", "LIBRARY", "PLAYER")

        // Synchronize TabLayout with ViewPager2 (WhatsApp-style horizontal fling & indicator tracking)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    private fun setupMiniPlayer() {
        updateMiniPlayerUI()

        binding.miniPlayerBar.setOnClickListener {
            navigateToTab(2) // Jump to Now Playing tab
        }

        binding.btnMiniPlayPause.setOnClickListener {
            togglePlayPause()
        }
    }

    fun navigateToTab(position: Int) {
        binding.viewPager.setCurrentItem(position, true)
    }

    fun playSong(song: Song) {
        currentSong = song
        isPlaying = true
        updateMiniPlayerUI()
        pagerAdapter.playerFragment.updateUI(currentSong, isPlaying)
    }

    fun togglePlayPause() {
        isPlaying = !isPlaying
        updateMiniPlayerUI()
        pagerAdapter.playerFragment.updateUI(currentSong, isPlaying)
    }

    fun playNext() {
        val songs = DummyData.getDummySongs()
        val currentIndex = songs.indexOfFirst { it.id == currentSong.id }
        val nextIndex = (currentIndex + 1) % songs.size
        playSong(songs[nextIndex])
    }

    fun playPrevious() {
        val songs = DummyData.getDummySongs()
        val currentIndex = songs.indexOfFirst { it.id == currentSong.id }
        val prevIndex = if (currentIndex <= 0) songs.size - 1 else currentIndex - 1
        playSong(songs[prevIndex])
    }

    private fun updateMiniPlayerUI() {
        binding.tvMiniTitle.text = currentSong.title
        binding.tvMiniArtist.text = "${currentSong.artist} • ${currentSong.album}"
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnMiniPlayPause.setImageResource(icon)
    }
}
