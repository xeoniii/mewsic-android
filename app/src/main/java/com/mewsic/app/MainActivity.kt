package com.mewsic.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private val argbEvaluator = ArgbEvaluator()
    private var isPlaying = true

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
        setupPlayerBar()
        startLoadingSequence()
    }

    private fun setupNavigation() {
        // Setup ViewPager2 with 5 swipeable blank canvas pages
        binding.viewPager.adapter = BlankPagesAdapter()
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

    private fun setupPlayerBar() {
        // Toggle play/pause state with tactile micro-bounce
        binding.btnPlayerPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            binding.btnPlayerPlayPause.animate()
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(90)
                .withEndAction {
                    binding.ivPlayPauseIcon.setImageResource(
                        if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                    )
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
                    binding.btnPlayerPrev.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
                .start()
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
                    // Slight hold at 100%, then fade out loading screen into main screen
                    binding.loadingScreenContainer.animate()
                        .alpha(0f)
                        .setDuration(400)
                        .setStartDelay(100)
                        .withEndAction {
                            binding.loadingScreenContainer.visibility = View.GONE
                            binding.blankContentContainer.visibility = View.VISIBLE
                            glowAnimator?.cancel()

                            // Re-align indicator with measured tabs
                            binding.tabBarContainer.post {
                                updateIndicator(binding.viewPager.currentItem, 0f)
                            }

                            // Premium floating entrance animation for top navigation bar
                            binding.topBarWrapper.alpha = 0f
                            binding.topBarWrapper.translationY = -40f
                            binding.topBarWrapper.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(450)
                                .setInterpolator(DecelerateInterpolator())
                                .start()

                            // Premium floating entrance animation for bottom player bar
                            binding.playerBarWrapper.alpha = 0f
                            binding.playerBarWrapper.translationY = 40f
                            binding.playerBarWrapper.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(450)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
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
            // Blank canvas ready for custom UI design
        }

        class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    }
}
