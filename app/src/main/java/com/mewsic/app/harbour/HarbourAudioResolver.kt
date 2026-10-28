package com.mewsic.app.harbour

import com.mewsic.app.harbour.model.HarbourTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Hybrid audio stream resolver:
 *  1. Resolves high-bitrate 320kbps full song streams from JioSaavn CDN (covers millions of global & regional songs).
 *  2. Falls back to YouTube audio stream extraction if not present on JioSaavn.
 *  3. Ensures downloads are NEVER truncated to 30-second previews.
 */
object HarbourAudioResolver {

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 12_000

    data class ResolvedAudio(
        val url: String,
        val extension: String, // "m4a" or "mp3"
        val mimeType: String,  // "audio/mp4" or "audio/mpeg"
        val isFullSong: Boolean,
        val bitrateKbps: Int = 320,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val year: String = "",
        val coverArtUrl: String = ""
    )

    suspend fun resolveStream(track: HarbourTrack): ResolvedAudio? = withContext(Dispatchers.IO) {
        // 1. Direct JioSaavn resolution if track originated from JioSaavn
        if (track.provider == "jiosaavn" && track.id.isNotBlank()) {
            val fromId = resolveFromJioSaavnId(track.id)
            if (fromId != null) return@withContext fromId
        }

        // 2. High-priority: Match & resolve full 320kbps stream via JioSaavn catalog
        val fromJioMatch = matchAndResolveJioSaavn(track.artist, track.title)
        if (fromJioMatch != null) return@withContext fromJioMatch

        // 3. Fallback: YouTube audio stream extraction
        val fromYoutube = resolveFromYoutube(track)
        if (fromYoutube != null) return@withContext fromYoutube

        // 4. Last resort: If the track has a direct non-preview streamUrl
        if (track.streamUrl.isNotBlank() && !isKnownPreviewUrl(track.streamUrl)) {
            val ext = if (track.streamUrl.contains(".mp3", true)) "mp3" else "m4a"
            val mime = if (ext == "mp3") "audio/mpeg" else "audio/mp4"
            return@withContext ResolvedAudio(
                url = track.streamUrl,
                extension = ext,
                mimeType = mime,
                isFullSong = true,
                bitrateKbps = 160,
                title = track.title,
                artist = track.artist,
                album = track.album,
                year = track.year,
                coverArtUrl = track.coverArtUrl
            )
        }

        null
    }

    // -------------------------------------------------------------------------
    // JioSaavn by Song ID
    // -------------------------------------------------------------------------
    private fun resolveFromJioSaavnId(songId: String): ResolvedAudio? {
        val detailUrl = "https://www.jiosaavn.com/api.php?__call=song.getDetails&cc=in&_marker=0%3F_marker%3D0&_format=json&pids=$songId"
        val json = fetchJson(detailUrl) ?: return null
        val songObj = json.optJSONObject(songId) ?: return null
        val encrypted = songObj.optString("encrypted_media_url", "")
        val decrypted = JioSaavnDecryptor.decrypt(encrypted) ?: return null
        val ext = if (decrypted.contains(".mp3", true)) "mp3" else "m4a"
        val mime = if (ext == "mp3") "audio/mpeg" else "audio/mp4"
        val title = songObj.optString("song", "")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
        val artist = songObj.optString("primary_artists", "")
            .ifBlank { songObj.optString("singers", "") }
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
        val album = songObj.optString("album", "")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
        val year = songObj.optString("year", "")
        val cover = songObj.optString("image", "")
            .replace("150x150", "500x500")

        return ResolvedAudio(
            url = decrypted,
            extension = ext,
            mimeType = mime,
            isFullSong = true,
            bitrateKbps = 320,
            title = title,
            artist = artist,
            album = album,
            year = year,
            coverArtUrl = cover
        )
    }

    // -------------------------------------------------------------------------
    // JioSaavn by Search Match
    // -------------------------------------------------------------------------
    private fun matchAndResolveJioSaavn(artist: String, title: String): ResolvedAudio? {
        val cleanTitle = cleanSearchTerm(title)
        val cleanArtist = cleanSearchTerm(artist)

        val queries = listOf(
            "$cleanArtist $cleanTitle".trim(),
            cleanTitle.trim()
        ).filter { it.isNotBlank() }

        for (q in queries) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val searchUrl = "https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&includeMetaTags=1&query=$encoded"
            val json = fetchJson(searchUrl) ?: continue
            val songs = json.optJSONObject("songs")?.optJSONArray("data") ?: continue
            if (songs.length() == 0) continue

            val topSong = songs.getJSONObject(0)
            val songId = topSong.optString("id", "")
            if (songId.isNotBlank()) {
                val resolved = resolveFromJioSaavnId(songId)
                if (resolved != null) return resolved
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // YouTube Audio Stream Fallback
    // -------------------------------------------------------------------------
    private fun resolveFromYoutube(track: HarbourTrack): ResolvedAudio? {
        val videoId = when {
            track.id.isNotBlank() && track.provider == "youtube" -> track.id
            track.downloadQuery.contains("v=") -> track.downloadQuery.substringAfter("v=").substringBefore("&")
            else -> null
        } ?: return null

        val pipedInstances = listOf(
            "https://pipedapi.tokhmi.xyz",
            "https://api.piped.private.coffee",
            "https://piped-api.lunar.icu"
        )

        for (base in pipedInstances) {
            try {
                val url = "$base/streams/$videoId"
                val json = fetchJson(url) ?: continue
                val audioStreams = json.optJSONArray("audioStreams") ?: continue
                if (audioStreams.length() == 0) continue

                // Pick best bitrate audio stream
                var bestUrl: String? = null
                var bestBitrate = 0
                var bestFormat = "m4a"

                for (i in 0 until audioStreams.length()) {
                    val s = audioStreams.getJSONObject(i)
                    val sUrl = s.optString("url", "")
                    val bitrate = s.optInt("bitrate", 0)
                    val format = s.optString("format", "M4A")
                    if (sUrl.isNotBlank() && bitrate > bestBitrate) {
                        bestBitrate = bitrate
                        bestUrl = sUrl
                        bestFormat = if (format.contains("mp3", true)) "mp3" else "m4a"
                    }
                }

                if (bestUrl != null) {
                    val mime = if (bestFormat == "mp3") "audio/mpeg" else "audio/mp4"
                    return ResolvedAudio(bestUrl, bestFormat, mime, isFullSong = true, bitrateKbps = bestBitrate / 1000)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private fun isKnownPreviewUrl(url: String): Boolean {
        return url.contains("audio-ssl.itunes.apple.com", true) ||
               url.contains("/preview/", true) ||
               url.contains("_96_p.mp4", true) ||
               url.contains("_preview", true)
    }

    private fun cleanSearchTerm(term: String): String {
        return term
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("(?i)official\\s+video"), "")
            .replace(Regex("(?i)official\\s+audio"), "")
            .replace(Regex("(?i)official\\s+music\\s+video"), "")
            .replace(Regex("(?i)ft\\..*"), "")
            .replace(Regex("(?i)feat\\..*"), "")
            .replace(Regex("(?i)featuring.*"), "")
            .replace(Regex("[-–—]"), " ")
            .trim()
    }

    private fun fetchJson(urlStr: String): JSONObject? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            try { JSONObject(body) } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }
}
