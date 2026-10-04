package com.mewsic.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.mewsic.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var loadAnimator: ObjectAnimator? = null
    private var glowAnimator: ObjectAnimator? = null

    private data class NavTab(
        val container: View,
        val icon: View,
        val label: View
    )

    private val navTabs by lazy {
        listOf(
            NavTab(binding.tabHome, binding.ivTabHome, binding.tvTabHome),
            NavTab(binding.tabLibrary, binding.ivTabLibrary, binding.tvTabLibrary),
            NavTab(binding.tabPlaylist, binding.ivTabPlaylist, binding.tvTabPlaylist),
            NavTab(binding.tabHarbour, binding.ivTabHarbour, binding.tvTabHarbour),
            NavTab(binding.tabSettings, binding.ivTabSettings, binding.tvTabSettings)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge transparent system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply system bar insets cleanly
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupNavigation()
        startLoadingSequence()
    }

    private fun setupNavigation() {
        // Setup ViewPager2 with 5 swipeable blank canvas pages
        binding.viewPager.adapter = BlankPagesAdapter()
        binding.viewPager.offscreenPageLimit = 4

        // Synchronize page swipes with top tabs
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                highlightTab(position)
            }
        })

        // Connect tab clicks
        navTabs.forEachIndexed { index, tab ->
            tab.container.setOnClickListener {
                binding.viewPager.setCurrentItem(index, true)
            }
        }

        // Initialize Home tab as selected
        highlightTab(0)
    }

    private fun highlightTab(position: Int) {
        navTabs.forEachIndexed { index, tab ->
            val isSelected = (index == position)
            tab.container.isSelected = isSelected
            tab.icon.isSelected = isSelected
            tab.label.isSelected = isSelected
        }

        // Smoothly scroll top bar to keep selected tab centered
        val selectedContainer = navTabs.getOrNull(position)?.container ?: return
        binding.navScrollView.post {
            val scrollX = selectedContainer.left - (binding.navScrollView.width / 2) + (selectedContainer.width / 2)
            binding.navScrollView.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
        }
    }

    private fun startLoadingSequence() {
        // Reset views for loading sequence
        binding.loadingScreenContainer.alpha = 1f
        binding.loadingScreenContainer.visibility = View.VISIBLE
        binding.blankContentContainer.visibility = View.GONE

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

        // Keyframe timeline:
        // 0.0s (0%)   -> Start at 0%
        // 0.7s (45%)  -> Smoothly rushes to ~58%
        // 1.0s (65%)  -> Realistic "hiccup" / hesitation (creeps slowly from 58% to 64%)
        // 1.5s (100%) -> Accelerates smoothly to 100%
        val kf0 = Keyframe.ofFloat(0.0f, 0.0f).apply {
            interpolator = AccelerateDecelerateInterpolator()
        }
        val kf1 = Keyframe.ofFloat(0.45f, 0.58f).apply {
            interpolator = DecelerateInterpolator()
        }
        val kf2 = Keyframe.ofFloat(0.65f, 0.64f).apply { // Hiccup pause
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
                    // Slight hold at 100%, then fade out loading screen into blank page
                    binding.loadingScreenContainer.animate()
                        .alpha(0f)
                        .setDuration(400)
                        .setStartDelay(100)
                        .withEndAction {
                            binding.loadingScreenContainer.visibility = View.GONE
                            binding.blankContentContainer.visibility = View.VISIBLE
                            glowAnimator?.cancel()
                        }
                        .start()
                }
            })
            start()
        }
    }

    override fun onDestroy() {
        loadAnimator?.cancel()
        glowAnimator?.cancel()
        super.onDestroy()
    }

    private class BlankPagesAdapter : RecyclerView.Adapter<BlankPagesAdapter.PageViewHolder>() {
        override fun getItemCount(): Int = 5

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_blank_page, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            // Blank canvas ready for designing
        }

        class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    }
}
