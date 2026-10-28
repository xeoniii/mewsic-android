package com.mewsic.app.harbour.model

/**
 * Represents a track result from any Harbour search provider.
 * Mirrors the desktop [HarbourSearchResult] structure.
 */
data class HarbourTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Double,
    val coverArtUrl: String,
    val streamUrl: String,          // Direct audio stream URL (preview or full)
    val downloadQuery: String,      // Query to download: either a direct URL or "Artist - Title" search
    val provider: String,           // "itunes" | "jiosaavn" | "youtube" | "soundcloud"
    val year: String = "",
    val genre: String = "",
    val thumbnailUrl: String = coverArtUrl,
    val hasPreview: Boolean = streamUrl.isNotBlank()
) {
    val durationFormatted: String get() {
        if (durationSeconds <= 0) return ""
        val total = durationSeconds.toLong()
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }
}

/** Download state tracked per-item in the adapter */
enum class DownloadState {
    IDLE,
    DOWNLOADING,
    DONE,
    ERROR
}
