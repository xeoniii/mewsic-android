package com.mewsic.app.scanner

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.mewsic.app.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

object ThumbnailLoader {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val memoryCache = object : LruCache<Long, Bitmap>(cacheSize) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val ioScope = CoroutineScope(Dispatchers.Main + Job())

    fun loadThumbnail(imageView: ImageView, song: Song) {
        val cached = memoryCache.get(song.id)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        // Set placeholder immediately while loading in background
        val fallback = generateFallbackBitmap(song.artist, song.album, 128)
        imageView.setImageBitmap(fallback)

        val context = imageView.context.applicationContext
        val targetSongId = song.id
        imageView.tag = targetSongId

        ioScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadBitmap(context, song)
            }

            if (bitmap != null) {
                memoryCache.put(song.id, bitmap)
                if (imageView.tag == targetSongId) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun loadBitmap(context: Context, song: Song): Bitmap? {
        // Method 1: API 29+ contentResolver.loadThumbnail
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(song.contentUri, Size(128, 128), null)
            } catch (_: Exception) {}
        }

        // Method 2: MediaStore Audio Album Art URI
        try {
            val sArtworkUri = Uri.parse("content://media/external/audio/albumart")
            val albumArtUri = ContentUris.withAppendedId(sArtworkUri, song.albumId)
            context.contentResolver.openInputStream(albumArtUri)?.use { stream ->
                val bm = BitmapFactory.decodeStream(stream)
                if (bm != null) return bm
            }
        } catch (_: Exception) {}

        // Method 3: MediaMetadataRetriever embedded picture
        if (song.filePath.isNotBlank()) {
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(song.filePath)
                val rawArt = mmr.embeddedPicture
                mmr.release()
                if (rawArt != null) {
                    val bm = BitmapFactory.decodeByteArray(rawArt, 0, rawArt.size)
                    if (bm != null) return bm
                }
            } catch (_: Exception) {}
        }

        return null
    }

    /**
     * Generates a deterministic colored square with the artist's uppercase initial,
     * mirroring the Mewsic PC app's CoverArt fallback styling.
     */
    fun generateFallbackBitmap(artist: String, album: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Generate deterministic HSL color like PC app
        var hash = 0
        val seed = artist + album
        for (i in seed.indices) {
            hash = seed[i].code + ((hash shl 5) - hash)
        }
        val hue = abs(hash % 360).toFloat()
        val color = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.35f))

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 12f, 12f, bgPaint)

        val initial = artist.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(220, 255, 255, 255)
            this.textSize = size * 0.45f
            this.textAlign = Paint.Align.CENTER
            this.isFakeBoldText = true
        }

        val textBounds = Rect()
        textPaint.getTextBounds(initial, 0, initial.length, textBounds)
        val y = (size / 2f) + (textBounds.height() / 2f)
        canvas.drawText(initial, size / 2f, y, textPaint)

        return bitmap
    }
}
