package com.mewsic.app.harbour

import com.mewsic.app.scanner.LyricsData
import com.mewsic.app.scanner.LyricsExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches synced and plain lyrics from LRCLib — the same backend used by Mewsic desktop.
 *
 * Priority:  syncedLyrics (LRC timestamps) → plainLyrics → null
 *
 * After fetching:
 *  - Saves a sidecar .lrc file next to the audio so [LyricsExtractor] picks it up instantly.
 */
object HarbourLyricsService {

    private const val USER_AGENT = "Mewsic/1.0.0 (https://github.com/xeoniii/Mewsic)"
    private const val TIMEOUT_MS = 10_000

    /**
     * Fetches lyrics for a track and returns the raw LRC/plain string,
     * or null if nothing found. Synced lyrics are preferred.
     */
    suspend fun fetchLyrics(title: String, artist: String, album: String = "", durationSeconds: Double = 0.0): String? =
        withContext(Dispatchers.IO) {
            // 1. Try the precise GET endpoint (best match)
            val precise = fetchByTrackDetails(title, artist, album, durationSeconds)
            if (!precise.isNullOrBlank()) return@withContext precise

            // 2. Fallback to free-text search
            fetchBySearch("$artist $title")
        }

    /**
     * Fetches and saves a .lrc sidecar file next to the audio file at [audioPath].
     * Returns the raw lyrics string (synced LRC preferred) or null.
     */
    suspend fun fetchAndSaveLyrics(
        title: String,
        artist: String,
        album: String,
        durationSeconds: Double,
        audioPath: String
    ): String? = withContext(Dispatchers.IO) {
        val raw = fetchLyrics(title, artist, album, durationSeconds) ?: return@withContext null
        saveLrcSidecar(audioPath, raw)
        raw
    }

    /**
     * Parses a raw LRC/plain string into a [LyricsData] model using the existing parser.
     */
    fun parse(raw: String): LyricsData = LyricsExtractor.parseLyricsData(raw)

    // -------------------------------------------------------------------------
    // Internal: LRCLib GET (precise match)
    // -------------------------------------------------------------------------
    private fun fetchByTrackDetails(title: String, artist: String, album: String, duration: Double): String? {
        return try {
            val sb = StringBuilder("https://lrclib.net/api/get?")
            sb.append("track_name=").append(URLEncoder.encode(title, "UTF-8"))
            sb.append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
            if (album.isNotBlank()) sb.append("&album_name=").append(URLEncoder.encode(album, "UTF-8"))
            if (duration > 0) sb.append("&duration=").append(duration.toLong())

            val conn = openConnection(sb.toString())
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return null }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            extractBestLyrics(body)
        } catch (_: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // Internal: LRCLib search (fuzzy match fallback)
    // -------------------------------------------------------------------------
    private fun fetchBySearch(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$encoded"
            val conn = openConnection(url)
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return null }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            // Result is a JSON array — pick the first item
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
            if (arr.length() == 0) return null
            extractBestLyrics(arr.getJSONObject(0).toString())
        } catch (_: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // Internal: extract syncedLyrics or plainLyrics from a single result JSON
    // -------------------------------------------------------------------------
    private fun extractBestLyrics(json: String): String? {
        return try {
            val obj = org.json.JSONObject(json)
            // Prefer synced (LRC format with timestamps)
            val synced = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
            val plain  = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() }
            synced ?: plain
        } catch (_: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // Internal: write .lrc sidecar next to the audio file
    // -------------------------------------------------------------------------
    private fun saveLrcSidecar(audioPath: String, lyricsContent: String) {
        try {
            val base = audioPath.substringBeforeLast('.')
            val lrcFile = File("$base.lrc")
            lrcFile.writeText(lyricsContent, Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun openConnection(urlStr: String): HttpURLConnection =
        (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout    = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
}
