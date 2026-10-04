package com.mewsic.app.util

import android.content.Context
import android.content.res.Configuration

object UiScaleManager {
    private const val PREFS_NAME = "mewsic_prefs"
    private const val KEY_UI_SCALE = "ui_scale_factor"

    const val SCALE_COMPACT = 0.85f
    const val SCALE_DEFAULT = 1.00f
    const val SCALE_LARGE = 1.15f

    const val MIN_SCALE = 0.75f
    const val MAX_SCALE = 1.25f

    fun getScale(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_UI_SCALE, SCALE_DEFAULT).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun setScale(context: Context, scale: Float) {
        val clamped = (Math.round(scale * 100f) / 100f).coerceIn(MIN_SCALE, MAX_SCALE)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_UI_SCALE, clamped)
            .apply()
    }

    fun getScalePercentage(scale: Float): Int = Math.round(scale * 100f)

    fun getScaleLabel(scale: Float): String {
        val pct = getScalePercentage(scale)
        return when {
            pct <= 85 -> "$pct% (Compact)"
            pct in 98..102 -> "$pct% (Default)"
            pct >= 115 -> "$pct% (Large)"
            else -> "$pct%"
        }
    }

    /**
     * Wraps the given context with an overridden Configuration
     * that applies both densityDpi and fontScale scaling.
     */
    fun wrapContext(baseContext: Context): Context {
        val scale = getScale(baseContext)
        val config = Configuration(baseContext.resources.configuration)
        val metrics = baseContext.resources.displayMetrics
        val baseDpi = metrics.densityDpi

        if (scale != 1.0f) {
            config.densityDpi = (baseDpi * scale).toInt()
            config.fontScale = scale
        }

        return baseContext.createConfigurationContext(config)
    }
}
