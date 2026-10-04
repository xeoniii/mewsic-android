package com.mewsic.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mewsic.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var barAnimator: ValueAnimator? = null
    private var glowAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display with transparent system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply system bar insets cleanly
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupSmoothLoadingAnimations()
    }

    private fun setupSmoothLoadingAnimations() {
        // Subtle atmospheric breathing pulse on ambient glow
        glowAnimator = ObjectAnimator.ofFloat(binding.ambientGlow, "alpha", 0.5f, 1.0f).apply {
            duration = 1800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Align loading bar width with header row and start smooth left-to-right sweep
        binding.headerRow.post {
            val headerWidth = binding.headerRow.width
            if (headerWidth > 0) {
                val params = binding.loadingTrack.layoutParams
                params.width = headerWidth
                binding.loadingTrack.layoutParams = params
            }

            binding.loadingTrack.post {
                startBarSweepAnimation()
            }
        }
    }

    private fun startBarSweepAnimation() {
        val trackWidth = binding.loadingTrack.width.toFloat()
        val indicatorWidth = binding.loadingIndicator.width.toFloat()

        // Smooth sweep from off-screen left (-indicatorWidth) to off-screen right (trackWidth)
        barAnimator?.cancel()
        barAnimator = ValueAnimator.ofFloat(-indicatorWidth, trackWidth).apply {
            duration = 1350
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                binding.loadingIndicator.translationX = anim.animatedValue as Float
            }
            start()
        }
    }

    override fun onDestroy() {
        barAnimator?.cancel()
        glowAnimator?.cancel()
        super.onDestroy()
    }
}
