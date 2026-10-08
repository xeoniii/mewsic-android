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
import java.io.File
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

    // High resolution cache for fullscreen player and system notification (up to 2560x2560)
    private val highResCache = object : LruCache<Long, Bitmap>(cacheSize / 2) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    // Fast blurred background cache for fullscreen player
    private val blurCache = object : LruCache<Long, Bitmap>(cacheSize / 4) {
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

    fun loadHighResArt(imageView: ImageView, song: Song, blurredView: ImageView? = null) {
        val targetSongId = song.id
        imageView.tag = targetSongId

        // 1. If high resolution bitmap is already cached, display immediately (0ms delay)
        val cached = highResCache.get(song.id)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            blurredView?.let { loadBlurredBackground(it, song) }
            return
        }

        // 2. Temporarily display existing thumbnail or high-res vector fallback so there is no blank flicker
        val quickThumb = thumbCache.get(song.id)
        if (quickThumb != null) {
            imageView.setImageBitmap(quickThumb)
            blurredView?.let { loadBlurredBackground(it, song) }
        } else {
            imageView.setImageBitmap(generateFallbackBitmap(song.artist, song.album, 512))
        }

        // 3. Asynchronously decode crystal-clear full-res album art (up to 2560x2560)
        ioScope.launch {
            val highRes = withContext(Dispatchers.IO) {
                loadHighResBitmap(imageView.context.applicationContext, song, 2560)
                    ?: generateFallbackBitmap(song.artist, song.album, 1024)
            }

            highResCache.put(song.id, highRes)
            if (imageView.tag == targetSongId) {
                imageView.setImageBitmap(highRes)
            }
            blurredView?.let { loadBlurredBackground(it, song) }
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
     * Decodes original uncompressed artwork directly from the media file or MediaStore at full resolution
     */
    fun loadHighResBitmap(context: Context, song: Song, targetSize: Int = 2560): Bitmap? {
        // Priority 1: Direct FileDescriptor via MediaMetadataRetriever on contentUri (Scoped Storage safe)
        try {
            val mmr = MediaMetadataRetriever()
            var opened = false
            try {
                context.contentResolver.openFileDescriptor(song.contentUri, "r")?.use { pfd ->
                    mmr.setDataSource(pfd.fileDescriptor)
                    opened = true
                }
            } catch (_: Throwable) {}

            if (!opened && song.filePath.isNotBlank()) {
                try {
                    val file = File(song.filePath)
                    if (file.exists() && file.canRead()) {
                        mmr.setDataSource(file.absolutePath)
                        opened = true
                    }
                } catch (_: Throwable) {}
            }

            if (opened) {
                val rawArt = mmr.embeddedPicture
                mmr.release()
                if (rawArt != null && rawArt.isNotEmpty()) {
                    val bm = decodeSampledBitmap(rawArt, targetSize)
                    if (bm != null) return bm
                }
            } else {
                mmr.release()
            }
        } catch (_: Throwable) {}

        // Priority 2: MediaStore audio albumart stream
        try {
            val sArtworkUri = Uri.parse("content://media/external/audio/albumart")
            val albumArtUri = ContentUris.withAppendedId(sArtworkUri, song.albumId)
            context.contentResolver.openInputStream(albumArtUri)?.use { stream ->
                val bytes = stream.readBytes()
                val bm = decodeSampledBitmap(bytes, targetSize)
                if (bm != null) return bm
            }
        } catch (_: Throwable) {}

        // Priority 3: API 29+ contentResolver.loadThumbnail with high-res target Size
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(song.contentUri, Size(targetSize, targetSize), null)
            } catch (_: Throwable) {}
        }

        return null
    }

    fun loadBlurredBackground(imageView: ImageView, song: Song) {
        val targetSongId = song.id
        imageView.tag = targetSongId

        val cached = blurCache.get(song.id)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        ioScope.launch {
            val source = highResCache.get(song.id)
                ?: loadHighResBitmap(imageView.context.applicationContext, song, 512)
                ?: thumbCache.get(song.id)
                ?: generateFallbackBitmap(song.artist, song.album, 256)

            val blurred = withContext(Dispatchers.Default) {
                createBlurredBitmap(source, scale = 0.10f, radius = 28)
            }

            blurCache.put(song.id, blurred)
            if (imageView.tag == targetSongId) {
                imageView.setImageBitmap(blurred)
            }
        }
    }

    private fun createBlurredBitmap(src: Bitmap, scale: Float = 0.10f, radius: Int = 28): Bitmap {
        val width = maxOf((src.width * scale).toInt(), 16)
        val height = maxOf((src.height * scale).toInt(), 16)
        val small = Bitmap.createScaledBitmap(src, width, height, true)
        return fastStackBlur(small, radius)
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

    private fun fastStackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (curY in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            for (curI in -radius..radius) {
                p = pix[yi + minOf(wm, maxOf(curI, 0))]
                sir = stack[curI + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - abs(curI)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (curI > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (curX in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curY == 0) {
                    vmin[curX] = minOf(curX + radius + 1, wm)
                }
                p = pix[yw + vmin[curX]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (curX in 0 until w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (curI in -radius..radius) {
                yi = maxOf(0, yp) + curX
                sir = stack[curI + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - abs(curI)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (curI > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (curI < hm) {
                    yp += w
                }
            }
            yi = curX
            stackpointer = radius
            for (curY in 0 until h) {
                pix[yi] = (0xff000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curX == 0) {
                    vmin[curY] = minOf(curY + r1, hm) * w
                }
                p = curX + vmin[curY]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
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
