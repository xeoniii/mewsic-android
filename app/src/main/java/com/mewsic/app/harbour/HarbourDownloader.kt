package com.mewsic.app.harbour

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mewsic.app.harbour.model.HarbourTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Downloads a [HarbourTrack] to [Music/Mewsic/] with:
 *  - Crystal clear album cover art embedded directly inside audio file (covr/APIC)
 *  - High-bitrate audio (320kbps JioSaavn CDN / YouTube extraction)
 *  - Precise ID3/MP4 metadata (Title, Artist, Album, Year) embedded
 *  - Synchronized and plain lyrics embedded + saved as sidecar (.lrc)
 *  - High-res cover art and metadata saved as sidecars (.jpg, .meta.json) and cached internally
 *
 * Emits byte-level progress via [onProgress] (0–100f).
 * After completion calls [onDone] with the file path or [onError] on failure.
 */
object HarbourDownloader {

    private const val MEWSIC_DIR = "Mewsic"
    private const val TIMEOUT_MS = 30_000
    private const val BUFFER_SIZE = 8_192

    suspend fun download(
        context: Context,
        track: HarbourTrack,
        onProgress: (Float) -> Unit,
        onDone: (audioPath: String) -> Unit,
        onError: (Exception) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. Resolve full audio stream (JioSaavn 320kbps CDN with YouTube fallback)
            val resolved = HarbourAudioResolver.resolveStream(track)
                ?: throw IllegalStateException("Could not resolve full audio stream for '${track.title}'")

            val finalTitle = track.title.ifBlank { resolved.title }.trim()
            val finalArtist = track.artist.ifBlank { resolved.artist }.trim()

            // Ensure album is the actual album, never empty and never "Mewsic"
            val rawAlbum = (if (track.album == "YouTube" || track.album.isBlank()) resolved.album else track.album).trim()
            val finalAlbum = if (rawAlbum.isBlank() || rawAlbum.equals("Mewsic", ignoreCase = true) || rawAlbum.equals("<unknown>", ignoreCase = true)) {
                resolved.album.ifBlank { finalTitle }
            } else {
                rawAlbum
            }

            val finalYear = track.year.ifBlank { resolved.year }.trim()
            val finalCoverUrl = track.coverArtUrl.ifBlank { resolved.coverArtUrl }.trim()

            // 2. Sanitise filename using resolved extension
            val safeArtist = finalArtist.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
            val safeTitle  = finalTitle.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
            val fileName   = "$safeArtist - $safeTitle.${resolved.extension}"

            // 3. Pre-fetch lyrics and high-res cover art concurrently
            val lyricsContent = try {
                HarbourLyricsService.fetchLyrics(
                    title = finalTitle,
                    artist = finalArtist,
                    album = finalAlbum,
                    durationSeconds = track.durationSeconds
                )
            } catch (_: Exception) { null }

            val coverBytes = if (finalCoverUrl.isNotBlank()) {
                downloadCoverBytes(finalCoverUrl)
            } else null

            // 4. Fetch raw audio bytes with progress
            val rawAudioBytes = downloadWithProgress(resolved.url, onProgress)

            // 5. Embed cover art, metadata, and lyrics directly into audio file
            val taggedAudioBytes = AudioTagEmbedder.embed(
                audioBytes = rawAudioBytes,
                extension = resolved.extension,
                title = finalTitle,
                artist = finalArtist,
                album = finalAlbum,
                year = finalYear,
                lyrics = lyricsContent,
                coverArtBytes = coverBytes
            )

            // 6. Write tagged audio to MediaStore (Android 10+) or direct file path
            val audioPath = saveAudioToStorage(
                context = context,
                bytes = taggedAudioBytes,
                fileName = fileName,
                mimeType = resolved.mimeType,
                title = finalTitle,
                artist = finalArtist,
                album = finalAlbum,
                year = finalYear
            )

            val base = audioPath.substringBeforeLast('.')

            // 7. Save cover art (both sidecar .jpg and internal cache)
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                // Sidecar next to audio
                runCatching { File("$base.jpg").writeBytes(coverBytes) }
                // App internal covers cache
                runCatching {
                    val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }
                    File(coversDir, "$safeArtist - $safeTitle.jpg").writeBytes(coverBytes)
                }
            }

            // 8. Save lyrics (.lrc sidecar and internal cache)
            if (!lyricsContent.isNullOrBlank()) {
                // Sidecar next to audio
                runCatching { File("$base.lrc").writeText(lyricsContent, Charsets.UTF_8) }
                // App internal lyrics cache
                runCatching {
                    val lyricsDir = File(context.filesDir, "lyrics").also { it.mkdirs() }
                    File(lyricsDir, "$safeArtist - $safeTitle.lrc").writeText(lyricsContent, Charsets.UTF_8)
                }
            }

            // 9. Save structured metadata sidecar (.meta.json and internal cache)
            val metaJson = JSONObject().apply {
                put("title", finalTitle)
                put("artist", finalArtist)
                put("album", finalAlbum)
                put("year", finalYear)
            }
            runCatching { File("$base.meta.json").writeText(metaJson.toString(2), Charsets.UTF_8) }
            runCatching {
                val metaDir = File(context.filesDir, "meta").also { it.mkdirs() }
                File(metaDir, "$safeArtist - $safeTitle.meta.json").writeText(metaJson.toString(2), Charsets.UTF_8)
            }

            // 10. Trigger system MediaScanner so track shows up in Library immediately
            val lrcPath = "$base.lrc"
            val metaPath = "$base.meta.json"
            val jpgPath = "$base.jpg"
            MediaScannerConnection.scanFile(
                context,
                arrayOf(audioPath, lrcPath, metaPath, jpgPath),
                arrayOf(resolved.mimeType, "text/plain", "application/json", "image/jpeg"),
                null
            )

            onDone(audioPath)
        } catch (e: Exception) {
            onError(e)
        }
    }

    // -------------------------------------------------------------------------
    // Stream bytes with progress reporting
    // -------------------------------------------------------------------------
    private fun downloadWithProgress(urlStr: String, onProgress: (Float) -> Unit): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout    = TIMEOUT_MS
            setRequestProperty("User-Agent", "Mewsic/1.0.0")
        }
        val total = conn.contentLength.toLong().coerceAtLeast(1L)
        val buffer = ByteArray(BUFFER_SIZE)
        var downloaded = 0L
        val out = ByteArrayOutputStream()
        conn.inputStream.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                downloaded += read
                onProgress((downloaded.toFloat() / total * 100f).coerceIn(0f, 100f))
            }
        }
        conn.disconnect()
        return out.toByteArray()
    }

    private fun downloadCoverBytes(coverUrl: String): ByteArray? {
        return try {
            val conn = (URL(coverUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout    = 12_000
                setRequestProperty("User-Agent", "Mewsic/1.0.0")
            }
            val bytes = conn.inputStream.readBytes()
            conn.disconnect()
            bytes.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Save audio bytes to Music/Mewsic/ — MediaStore (API 29+) or direct file
    // -------------------------------------------------------------------------
    private fun saveAudioToStorage(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        title: String,
        artist: String,
        album: String,
        year: String
    ): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, bytes, fileName, mimeType, title, artist, album, year)
        } else {
            saveDirectToFile(bytes, fileName)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        title: String,
        artist: String,
        album: String,
        year: String
    ): String {
        val resolver = context.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$MEWSIC_DIR")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, album)
            val yr = year.toIntOrNull() ?: 0
            if (yr > 0) {
                put(MediaStore.Audio.Media.YEAR, yr)
            }
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("MediaStore insert failed")

        resolver.openOutputStream(uri)?.use { it.write(bytes) }

        cv.clear()
        cv.put(MediaStore.Audio.Media.IS_PENDING, 0)
        cv.put(MediaStore.Audio.Media.TITLE, title)
        cv.put(MediaStore.Audio.Media.ARTIST, artist)
        cv.put(MediaStore.Audio.Media.ALBUM, album)
        val yr = year.toIntOrNull() ?: 0
        if (yr > 0) {
            putYear(cv, yr)
        }
        resolver.update(uri, cv, null, null)

        val realPath = getRealPath(context, uri)
        val fallbackPath = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "$MEWSIC_DIR/$fileName"
        ).absolutePath

        return realPath?.takeIf { !it.startsWith("content:") && it.isNotBlank() } ?: fallbackPath
    }

    private fun putYear(cv: ContentValues, year: Int) {
        cv.put(MediaStore.Audio.Media.YEAR, year)
    }

    private fun saveDirectToFile(bytes: ByteArray, fileName: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            MEWSIC_DIR
        ).also { it.mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }

    private fun getRealPath(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
    }
}
