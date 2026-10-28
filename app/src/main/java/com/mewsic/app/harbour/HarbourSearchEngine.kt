package com.mewsic.app.harbour

import com.mewsic.app.harbour.model.HarbourTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Searches multiple providers (iTunes, JioSaavn, YouTube) and returns
 * a list of [HarbourTrack] results. Mirrors the desktop harbour_search logic.
 */
object HarbourSearchEngine {

    private const val USER_AGENT = "Mewsic/1.0.0 (https://github.com/xeoniii/Mewsic)"
    private const val TIMEOUT_MS = 12_000

    suspend fun search(query: String, provider: String): List<HarbourTrack> =
        withContext(Dispatchers.IO) {
            when (provider) {
                "itunes"     -> searchItunes(query)
                "jiosaavn"   -> searchJioSaavn(query)
                "youtube"    -> searchYouTube(query)
                "soundcloud" -> searchSoundCloud(query)
                else         -> searchItunes(query)
            }
        }

    // -------------------------------------------------------------------------
    // iTunes Search — free Apple API, no auth, instant, 30s preview URLs
    // -------------------------------------------------------------------------
    private fun searchItunes(query: String): List<HarbourTrack> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&entity=song&limit=30"
        val json = fetchJson(url) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        val tracks = mutableListOf<HarbourTrack>()
        for (i in 0 until results.length()) {
            val t = results.getJSONObject(i)
            val rawArt = t.optString("artworkUrl100", "")
            val thumbUrl = rawArt.replace("100x100", "160x160")
            val highResCover = rawArt.replace("100x100", "600x600")
            val previewUrl = t.optString("previewUrl", "")
            val releaseDate = t.optString("releaseDate", "")
            val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else ""
            tracks.add(
                HarbourTrack(
                    id            = t.optLong("trackId", 0).toString(),
                    title         = t.optString("trackName", ""),
                    artist        = t.optString("artistName", ""),
                    album         = t.optString("collectionName", ""),
                    durationSeconds = t.optDouble("trackTimeMillis", 0.0) / 1000.0,
                    coverArtUrl   = highResCover,
                    thumbnailUrl  = thumbUrl,
                    streamUrl     = previewUrl,
                    downloadQuery = "${t.optString("artistName", "")} - ${t.optString("trackName", "")}",
                    provider      = "itunes",
                    year          = year,
                    genre         = t.optString("primaryGenreName", "")
                )
            )
        }
        return tracks
    }

    // -------------------------------------------------------------------------
    // JioSaavn — direct search API (no auth), high-bitrate stream links
    // -------------------------------------------------------------------------
    private fun searchJioSaavn(query: String): List<HarbourTrack> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&includeMetaTags=1&query=$encoded"
        val json = fetchJson(url) ?: return searchItunes(query) // fallback
        val songs = json.optJSONObject("songs")?.optJSONArray("data") ?: return searchItunes(query)
        val tracks = mutableListOf<HarbourTrack>()
        for (i in 0 until songs.length()) {
            val s = songs.getJSONObject(i)
            val rawImage = s.optString("image", "")
            val thumbUrl = rawImage.replace("50x50", "150x150")
            val highResCover = rawImage.replace("50x50", "500x500")
            val moreInfo = s.optJSONObject("more_info")
            val previewUrl = moreInfo?.optString("vlink", "") ?: ""

            val title = s.optString("title", "")
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'").trim()

            val rawArtist = (moreInfo?.optString("primary_artists", "") ?: "")
                .ifBlank { moreInfo?.optString("singers", "") ?: "" }
                .ifBlank { moreInfo?.optString("music", "") ?: "" }
                .ifBlank { s.optString("description", "") }
            val artist = rawArtist
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'").trim()

            val rawAlbum = s.optString("album", "")
                .ifBlank { moreInfo?.optString("album", "") ?: "" }
            val album = rawAlbum
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'").trim()

            val year = (moreInfo?.optString("year", "") ?: "")
                .ifBlank { s.optString("year", "") }

            val genre = (moreInfo?.optString("genre", "") ?: "")

            tracks.add(
                HarbourTrack(
                    id            = s.optString("id", ""),
                    title         = title,
                    artist        = artist,
                    album         = album.ifBlank { title },
                    durationSeconds = 0.0,
                    coverArtUrl   = highResCover,
                    thumbnailUrl  = thumbUrl,
                    streamUrl     = previewUrl,
                    downloadQuery = "$artist - $title",
                    provider      = "jiosaavn",
                    year          = year,
                    genre         = genre
                )
            )
        }
        return if (tracks.isNotEmpty()) tracks else searchItunes(query)
    }

    // -------------------------------------------------------------------------
    // YouTube — uses the Piped REST API (no API key, CORS-friendly)
    // -------------------------------------------------------------------------
    private fun searchYouTube(query: String): List<HarbourTrack> {
        // Piped public instance — free, no auth, returns JSON search results
        val encoded = URLEncoder.encode("$query official audio", "UTF-8")
        val url = "https://pipedapi.kavin.rocks/search?q=$encoded&filter=music_songs"
        val json = fetchJson(url)
        val items = json?.optJSONArray("items") ?: run {
            // Fallback to iTunes if Piped fails
            return searchItunes(query)
        }
        val tracks = mutableListOf<HarbourTrack>()
        for (i in 0 until minOf(items.length(), 30)) {
            val item = items.getJSONObject(i)
            val rawTitle = item.optString("title", "Unknown Title")
            val uploader = item.optString("uploaderName", "Unknown Artist")
            val (artist, title) = if (rawTitle.contains(" - ")) {
                val parts = rawTitle.split(" - ", limit = 2)
                Pair(parts[0].trim(), parts[1].trim())
            } else {
                Pair(uploader, rawTitle)
            }
            val thumbnails = item.optJSONArray("thumbnails")
            val thumbSmall = if (thumbnails != null && thumbnails.length() > 0)
                thumbnails.getJSONObject(0).optString("url", "") else ""
            val thumbHigh = if (thumbnails != null && thumbnails.length() > 0)
                thumbnails.getJSONObject(thumbnails.length() - 1).optString("url", "") else ""
            val videoId = item.optString("url", "").removePrefix("/watch?v=")
            val duration = item.optDouble("duration", 0.0)
            tracks.add(
                HarbourTrack(
                    id            = videoId,
                    title         = title,
                    artist        = artist,
                    album         = "YouTube",
                    durationSeconds = duration,
                    coverArtUrl   = thumbHigh,
                    thumbnailUrl  = thumbSmall.ifBlank { thumbHigh },
                    streamUrl     = "", // Resolved on-demand via streaming
                    downloadQuery = "https://www.youtube.com/watch?v=$videoId",
                    provider      = "youtube"
                )
            )
        }
        return if (tracks.isNotEmpty()) tracks else searchItunes(query)
    }

    // -------------------------------------------------------------------------
    // SoundCloud — uses Piped's SoundCloud search path
    // -------------------------------------------------------------------------
    private fun searchSoundCloud(query: String): List<HarbourTrack> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://pipedapi.kavin.rocks/search?q=$encoded&filter=all"
        val json = fetchJson(url)
        val items = json?.optJSONArray("items")
        if (items == null || items.length() == 0) return searchItunes(query)
        val tracks = mutableListOf<HarbourTrack>()
        for (i in 0 until minOf(items.length(), 30)) {
            val item = items.getJSONObject(i)
            val rawTitle = item.optString("title", "Unknown Title")
            val uploader = item.optString("uploaderName", "Unknown Artist")
            val (artist, title) = if (rawTitle.contains(" - ")) {
                val parts = rawTitle.split(" - ", limit = 2)
                Pair(parts[0].trim(), parts[1].trim())
            } else {
                Pair(uploader, rawTitle)
            }
            val thumbnails = item.optJSONArray("thumbnails")
            val thumbSmall = if (thumbnails != null && thumbnails.length() > 0)
                thumbnails.getJSONObject(0).optString("url", "") else ""
            val thumbHigh = if (thumbnails != null && thumbnails.length() > 0)
                thumbnails.getJSONObject(thumbnails.length() - 1).optString("url", "") else thumbSmall
            val videoUrl = item.optString("url", "")
            tracks.add(
                HarbourTrack(
                    id            = videoUrl,
                    title         = title,
                    artist        = artist,
                    album         = "SoundCloud",
                    durationSeconds = item.optDouble("duration", 0.0),
                    coverArtUrl   = thumbHigh,
                    thumbnailUrl  = thumbSmall.ifBlank { thumbHigh },
                    streamUrl     = "",
                    downloadQuery = "$artist - $title",
                    provider      = "soundcloud"
                )
            )
        }
        return if (tracks.isNotEmpty()) tracks else searchItunes(query)
    }

    // -------------------------------------------------------------------------
    // HTTP helper
    // -------------------------------------------------------------------------
    private fun fetchJson(urlStr: String): JSONObject? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            // JioSaavn wraps in an object; iTunes uses "results" array at root level
            try { JSONObject(body) } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }
}
