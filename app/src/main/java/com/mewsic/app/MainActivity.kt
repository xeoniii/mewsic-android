package com.mewsic.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.mewsic.app.data.DummyData
import com.mewsic.app.databinding.ActivityMainBinding
import com.mewsic.app.model.Song
import com.mewsic.app.ui.HomeFragment
import com.mewsic.app.ui.LibraryFragment
import com.mewsic.app.ui.PlayerFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val homeFragment = HomeFragment()
    private val libraryFragment = LibraryFragment()
    private val playerFragment = PlayerFragment()

    var currentSong: Song = DummyData.getDummySongs().first()
        private set
    var isPlaying: Boolean = true
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge rendering
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply system bar insets without causing layout shifts
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.rootContainer.setPadding(0, systemBars.top, 0, 0)
            binding.bottomNav.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        setupBottomNavigation()
        setupMiniPlayer()

        // Set default fragment
        if (savedInstanceState == null) {
            setFragment(homeFragment)
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_home -> {
                    setFragment(homeFragment)
                    true
                }
                R.id.menu_library -> {
                    setFragment(libraryFragment)
                    true
                }
                R.id.menu_player -> {
                    setFragment(playerFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupMiniPlayer() {
        updateMiniPlayerUI()

        binding.miniPlayerBar.setOnClickListener {
            navigateToTab(R.id.menu_player)
        }

        binding.btnMiniPlayPause.setOnClickListener {
            togglePlayPause()
        }
    }

    fun navigateToTab(menuItemId: Int) {
        binding.bottomNav.selectedItemId = menuItemId
    }

    fun playSong(song: Song) {
        currentSong = song
        isPlaying = true
        updateMiniPlayerUI()
        playerFragment.updateUI(currentSong, isPlaying)
    }

    fun togglePlayPause() {
        isPlaying = !isPlaying
        updateMiniPlayerUI()
        playerFragment.updateUI(currentSong, isPlaying)
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

    private fun setFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
