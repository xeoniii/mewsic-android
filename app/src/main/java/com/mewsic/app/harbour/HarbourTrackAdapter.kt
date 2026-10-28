package com.mewsic.app.harbour

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mewsic.app.R
import com.mewsic.app.databinding.ItemHarbourTrackBinding
import com.mewsic.app.harbour.model.DownloadState
import com.mewsic.app.harbour.model.HarbourTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class HarbourTrackAdapter(
    private val onStreamClicked: (HarbourTrack) -> Unit,
    private val onDownloadClicked: (HarbourTrack) -> Unit
) : ListAdapter<HarbourTrack, HarbourTrackAdapter.ViewHolder>(TrackDiffCallback()) {

    companion object {
        private val thumbnailCache = object : LruCache<String, Bitmap>(60) {
            override fun sizeOf(key: String, value: Bitmap): Int = 1
        }
    }

    // Tracks which songs are actively streaming (playing)
    var streamingId: String? = null
        set(value) {
            val old = field
            field = value
            // Refresh only the cards that changed state
            if (old != value) {
                currentList.forEachIndexed { i, t ->
                    if (t.id == old || t.id == value) notifyItemChanged(i)
                }
            }
        }

    // Tracks per-item download state + progress
    private val downloadStates = HashMap<String, DownloadState>()
    private val downloadProgress = HashMap<String, Float>()

    fun setDownloadState(id: String, state: DownloadState, progress: Float = 0f) {
        downloadStates[id] = state
        downloadProgress[id] = progress
        val pos = currentList.indexOfFirst { it.id == id }
        if (pos != -1) notifyItemChanged(pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHarbourTrackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemHarbourTrackBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var coverJob: Job? = null
        private val ioScope = CoroutineScope(Dispatchers.Main + Job())

        fun bind(track: HarbourTrack) {
            binding.tvHarbourTitle.text = track.title
            binding.tvHarbourArtistAlbum.text = buildString {
                append(track.artist)
                if (track.album.isNotBlank() && track.album != "YouTube" && track.album != "SoundCloud") {
                    append(" · ")
                    append(track.album)
                }
            }

            // Duration
            val dur = track.durationFormatted
            if (dur.isNotBlank()) {
                binding.tvHarbourDuration.text = dur
                binding.tvHarbourDuration.visibility = View.VISIBLE
            } else {
                binding.tvHarbourDuration.visibility = View.GONE
            }

            // Cover art — load fast small thumbnail via URL with memory caching
            val thumbUrl = track.thumbnailUrl.ifBlank { track.coverArtUrl }
            val cachedBmp = thumbnailCache.get(thumbUrl)
            if (cachedBmp != null) {
                binding.ivHarbourCover.setImageBitmap(cachedBmp)
            } else {
                binding.ivHarbourCover.setImageResource(R.drawable.ic_album_art_placeholder)
                coverJob?.cancel()
                coverJob = ioScope.launch {
                    val bmp = loadBitmap(thumbUrl)
                    if (bmp != null) {
                        thumbnailCache.put(thumbUrl, bmp)
                        binding.ivHarbourCover.setImageBitmap(bmp)
                    }
                }
            }

            // Stream button
            val isStreaming = track.id == streamingId
            binding.ivHarbourStreamIcon.setImageResource(
                if (isStreaming) R.drawable.ic_player_pause else R.drawable.ic_player_play
            )
            val streamTint = if (isStreaming)
                ContextCompat.getColor(binding.root.context, R.color.tab_active_tint)
            else
                ContextCompat.getColor(binding.root.context, R.color.tab_inactive_tint)
            binding.ivHarbourStreamIcon.setColorFilter(streamTint)

            binding.btnHarbourStream.setOnClickListener { onStreamClicked(track) }

            // Download button state
            val dlState = downloadStates[track.id] ?: DownloadState.IDLE
            val progress = downloadProgress[track.id] ?: 0f
            when (dlState) {
                DownloadState.IDLE -> {
                    binding.ivHarbourDownloadIcon.setImageResource(R.drawable.ic_download)
                    binding.ivHarbourDownloadIcon.setColorFilter(
                        ContextCompat.getColor(binding.root.context, R.color.tab_inactive_tint)
                    )
                    binding.flDownloadOverlay.visibility = View.GONE
                    binding.btnHarbourDownload.isEnabled = true
                }
                DownloadState.DOWNLOADING -> {
                    binding.ivHarbourDownloadIcon.setImageResource(R.drawable.ic_download)
                    binding.ivHarbourDownloadIcon.setColorFilter(
                        ContextCompat.getColor(binding.root.context, R.color.tab_active_tint)
                    )
                    binding.flDownloadOverlay.visibility = View.VISIBLE
                    binding.tvDownloadPercent.text = "${progress.toInt()}%"
                    binding.btnHarbourDownload.isEnabled = false
                }
                DownloadState.DONE -> {
                    binding.ivHarbourDownloadIcon.setImageResource(R.drawable.ic_check)
                    binding.ivHarbourDownloadIcon.setColorFilter(
                        ContextCompat.getColor(binding.root.context, R.color.tab_active_tint)
                    )
                    binding.flDownloadOverlay.visibility = View.GONE
                    binding.btnHarbourDownload.isEnabled = false
                }
                DownloadState.ERROR -> {
                    binding.ivHarbourDownloadIcon.setImageResource(R.drawable.ic_download)
                    binding.ivHarbourDownloadIcon.setColorFilter(
                        ContextCompat.getColor(binding.root.context, R.color.tab_inactive_tint)
                    )
                    binding.flDownloadOverlay.visibility = View.GONE
                    binding.btnHarbourDownload.isEnabled = true
                }
            }
            binding.btnHarbourDownload.setOnClickListener {
                if (dlState == DownloadState.IDLE || dlState == DownloadState.ERROR) {
                    onDownloadClicked(track)
                }
            }
        }

        private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            try {
                val conn = URL(url).openConnection().apply {
                    connectTimeout = 5_000
                    readTimeout    = 5_000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val bytes = conn.getInputStream().use { it.readBytes() }
                if (bytes.isEmpty()) return@withContext null

                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                var inSampleSize = 1
                val maxDim = maxOf(options.outWidth, options.outHeight)
                while (maxDim / (inSampleSize * 2) >= 120) {
                    inSampleSize *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            } catch (_: Exception) { null }
        }
    }

    class TrackDiffCallback : DiffUtil.ItemCallback<HarbourTrack>() {
        override fun areItemsTheSame(a: HarbourTrack, b: HarbourTrack) = a.id == b.id
        override fun areContentsTheSame(a: HarbourTrack, b: HarbourTrack) = a == b
    }
}
