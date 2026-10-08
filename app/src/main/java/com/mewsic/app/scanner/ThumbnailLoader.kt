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

    // Thumbnail cache for fast list rows (128x128)
    private val thumbCache = object : LruCache<Long, Bitmap>(cacheSize / 2) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    // High resolution cache for fullscreen player and system notification (up to 1024x1024)
    private val highResCache = object : LruCache<Long, Bitmap>(cacheSize / 2) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val ioScope = CoroutineScope(Dispatchers.Main + Job())

    @Volatile
    var isListThumbnailLoadingPaused: Boolean = false
        private set

    private var listJob = Job()
    private var listScope = CoroutineScope(Dispatchers.Main + listJob)

    fun pauseListLoading() {
        isListThumbnailLoadingPaused = true
        listJob.cancel()
        listJob = Job()
        listScope = CoroutineScope(Dispatchers.Main + listJob)
    }

    fun resumeListLoading() {
        isListThumbnailLoadingPaused = false
    }

    fun loadThumbnail(imageView: ImageView, song: Song) {
        if (isListThumbnailLoadingPaused) {
            imageView.setImageDrawable(null)
            return
        }

        // Return from cache if already loaded
        val cached = thumbCache.get(song.id) ?: highResCache.get(song.id)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        // Set lightweight placeholder immediately while decoding in background
        val fallback = generateFallbackBitmap(song.artist, song.album, 128)
        imageView.setImageBitmap(fallback)

        val context = imageView.context.applicationContext
        val targetSongId = song.id
        imageView.tag = targetSongId

        listScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadBitmap(context, song)
            }

            if (bitmap != null) {
                thumbCache.put(song.id, bitmap)
                if (imageView.tag == targetSongId && !isListThumbnailLoadingPaused) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun loadHighResArt(imageView: ImageView, song: Song) {
        val targetSongId = song.id
        imageView.tag = targetSongId

        // 1. If high resolution bitmap is already cached, display immediately (0ms delay)
        val cached = highResCache.get(song.id)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        // 2. Temporarily display existing thumbnail or high-res vector fallback so there is no blank flicker
        val quickThumb = thumbCache.get(song.id)
        if (quickThumb != null) {
            imageView.setImageBitmap(quickThumb)
        } else {
            imageView.setImageBitmap(generateFallbackBitmap(song.artist, song.album, 512))
        }

        // 3. Asynchronously decode crystal-clear high-res album art (up to 1024x1024)
        ioScope.launch {
            val highRes = withContext(Dispatchers.IO) {
                loadHighResBitmap(imageView.context.applicationContext, song, 1024)
                    ?: generateFallbackBitmap(song.artist, song.album, 1024)
            }

            highResCache.put(song.id, highRes)
            if (imageView.tag == targetSongId) {
                imageView.setImageBitmap(highRes)
            }
        }
    }

    fun getCachedBitmap(songId: Long): Bitmap? = highResCache.get(songId) ?: thumbCache.get(songId)

    fun getFallbackBitmap(artist: String, album: String, size: Int = 192): Bitmap {
        return generateFallbackBitmap(artist, album, size)
    }

    fun getOrLoadBitmap(context: Context, song: Song, size: Int = 512): Bitmap {
        val cached = highResCache.get(song.id) ?: thumbCache.get(song.id)
        if (cached != null && (cached.width >= size || cached.height >= size)) return cached

        val loaded = if (size > 192) {
            loadHighResBitmap(context, song, size)
        } else {
            loadBitmap(context, song)
        }

        if (loaded != null) {
            if (size > 192) {
                highResCache.put(song.id, loaded)
            } else {
                thumbCache.put(song.id, loaded)
            }
            return loaded
        }
        return generateFallbackBitmap(song.artist, song.album, size)
    }

    /**
     * Decodes the highest-resolution embedded artwork directly from the media file or MediaStore
     */
    fun loadHighResBitmap(context: Context, song: Song, targetSize: Int = 1024): Bitmap? {
        // Priority 1: Direct ID3 / FLAC / MP4 embedded APIC picture (uncompressed original artwork)
        if (song.filePath.isNotBlank()) {
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(song.filePath)
                val rawArt = mmr.embeddedPicture
                mmr.release()
                if (rawArt != null) {
                    val bm = decodeSampledBitmap(rawArt, targetSize)
                    if (bm != null) return bm
                }
            } catch (_: Exception) {}
        }

        // Priority 2: MediaStore audio albumart stream
        try {
            val sArtworkUri = Uri.parse("content://media/external/audio/albumart")
            val albumArtUri = ContentUris.withAppendedId(sArtworkUri, song.albumId)
            context.contentResolver.openInputStream(albumArtUri)?.use { stream ->
                val bytes = stream.readBytes()
                val bm = decodeSampledBitmap(bytes, targetSize)
                if (bm != null) return bm
            }
        } catch (_: Exception) {}

        // Priority 3: API 29+ contentResolver.loadThumbnail with high-res target Size
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(song.contentUri, Size(targetSize, targetSize), null)
            } catch (_: Exception) {}
        }

        return null
    }

    private fun decodeSampledBitmap(data: ByteArray, targetSize: Int): Bitmap? {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) return null

            var inSampleSize = 1
            val maxDim = maxOf(width, height)
            while ((maxDim / (inSampleSize * 2)) >= targetSize) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
        } catch (_: Throwable) {
            return null
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
        val cornerRadius = size * (12f / 128f)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), cornerRadius, cornerRadius, bgPaint)

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
