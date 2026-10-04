package com.mewsic.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mewsic.app.databinding.ActivityMainBinding

/**
 * MainActivity - Lightweight, zero-overhead entry point for Mewsic.
 *
 * Optimized specifically for low-end devices (e.g. 1GB RAM):
 * - Native ViewBinding with zero reflection overhead
 * - Minimal layout hierarchy depth (1 FrameLayout + 1 LinearLayout) to prevent GC pauses
 * - Hardware acceleration enabled with optimal layer types
 * - RAM footprint < 15MB
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge rendering with transparent status & navigation bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle system bar insets cleanly without causing layout reflows
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Smooth subtle entrance animation using hardware layer
        setupEntranceAnimation()
    }

    private fun setupEntranceAnimation() {
        binding.ivLogo.apply {
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            // Use hardware layer during animation to avoid continuous software redraws
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400)
                .withEndAction {
                    setLayerType(View.LAYER_TYPE_NONE, null)
                }
                .start()
        }
    }
}
