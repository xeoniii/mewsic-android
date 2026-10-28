package com.mewsic.app.harbour

import android.content.Context
import android.media.MediaPlayer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mewsic.app.R
import com.mewsic.app.databinding.PageHarbourBinding
import com.mewsic.app.harbour.model.DownloadState
import com.mewsic.app.harbour.model.HarbourTrack
import com.mewsic.app.scanner.MediaScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wires all Harbour logic to [PageHarbourBinding]:
 *  - Provider chip selection
 *  - Search (coroutine-based, debounced)
 *  - Inline audio streaming via [MediaPlayer]
 *  - One-tap downloading with progress + lyrics auto-fetch
 *  - Library refresh after download
 *
 * Call [attach] once from [MainPagerAdapter.HarbourViewHolder.init].
 * Call [detach] when the view is recycled.
 */
class HarbourController(
    private val binding: PageHarbourBinding,
    private val lifecycleOwner: LifecycleOwner,
    private val onSilentRescan: () -> Unit
) {
    private val context: Context get() = binding.root.context

    private var selectedProvider = "itunes"
    private var lastQuery = ""
    private var searchJob: Job? = null

    // Inline preview player (separate from main MediaPlayer)
    private var previewPlayer: MediaPlayer? = null
    private var streamingTrackId: String? = null

    private val adapter = HarbourTrackAdapter(
        onStreamClicked  = { track -> toggleStream(track) },
        onDownloadClicked = { track -> downloadTrack(track) }
    )

    fun attach() {
        setupRecyclerView()
        setupProviderChips()
        setupSearchBar()
        showIdle()
    }

    fun detach() {
        previewPlayer?.stop()
        previewPlayer?.release()
        previewPlayer = null
    }

    // -------------------------------------------------------------------------
    // RecyclerView
    // -------------------------------------------------------------------------
    private fun setupRecyclerView() {
        binding.rvHarbourResults.layoutManager = LinearLayoutManager(context)
        binding.rvHarbourResults.adapter = adapter
        binding.rvHarbourResults.isNestedScrollingEnabled = false
    }

    // -------------------------------------------------------------------------
    // Provider chip selection
    // -------------------------------------------------------------------------
    private fun setupProviderChips() {
        val chips = mapOf(
            "itunes"     to binding.chipItunes,
            "jiosaavn"   to binding.chipJioSaavn,
            "youtube"    to binding.chipYoutube,
            "soundcloud" to binding.chipSoundCloud
        )
        chips.forEach { (id, view) ->
            view.setOnClickListener {
                selectedProvider = id
                chips.forEach { (cid, cv) ->
                    val isActive = cid == id
                    cv.background = ContextCompat.getDrawable(
                        context,
                        if (isActive) R.drawable.bg_btn_apply else R.drawable.bg_song_card_idle
                    )
                    cv.setTextColor(ContextCompat.getColor(
                        context,
                        if (isActive) R.color.tab_active_tint else R.color.tab_inactive_tint
                    ))
                }
                // Re-search with new provider if there's already a query
                if (lastQuery.isNotBlank()) doSearch(lastQuery)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Search bar wiring
    // -------------------------------------------------------------------------
    private fun setupSearchBar() {
        // Show/hide clear button
        binding.etHarbourSearch.addTextChangedListener { text ->
            binding.ivHarbourClear.visibility =
                if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        // Clear button
        binding.ivHarbourClear.setOnClickListener {
            binding.etHarbourSearch.text?.clear()
            showIdle()
            adapter.submitList(emptyList())
        }

        // IME Search action
        binding.etHarbourSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = binding.etHarbourSearch.text.toString().trim()
                if (q.isNotBlank()) doSearch(q)
                hideKeyboard()
                true
            } else false
        }

        // Search button click
        binding.btnHarbourSearch.setOnClickListener {
            val q = binding.etHarbourSearch.text.toString().trim()
            if (q.isNotBlank()) doSearch(q)
            hideKeyboard()
        }

        // Retry button
        binding.btnHarbourRetry.setOnClickListener {
            if (lastQuery.isNotBlank()) doSearch(lastQuery)
        }
    }

    // -------------------------------------------------------------------------
    // Search execution
    // -------------------------------------------------------------------------
    private fun doSearch(query: String) {
        lastQuery = query
        searchJob?.cancel()
        searchJob = lifecycleOwner.lifecycleScope.launch {
            showLoading()
            try {
                val results = HarbourSearchEngine.search(query, selectedProvider)
                if (results.isEmpty()) {
                    showError("No results found for \"$query\".")
                } else {
                    showResults(results)
                }
            } catch (e: Exception) {
                showError(e.message ?: "Unknown error occurred.")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inline streaming (iTunes 30s preview or JioSaavn vlink)
    // -------------------------------------------------------------------------
    private fun toggleStream(track: HarbourTrack) {
        if (streamingTrackId == track.id) {
            // Stop current
            previewPlayer?.stop()
            previewPlayer?.release()
            previewPlayer = null
            streamingTrackId = null
            adapter.streamingId = null
            return
        }

        // Stop any existing preview
        previewPlayer?.stop()
        previewPlayer?.release()
        previewPlayer = null

        streamingTrackId = track.id
        adapter.streamingId = track.id

        lifecycleOwner.lifecycleScope.launch {
            val url = if (track.streamUrl.isNotBlank()) {
                track.streamUrl
            } else {
                HarbourAudioResolver.resolveStream(track)?.url
            }

            if (url.isNullOrBlank() || streamingTrackId != track.id) {
                if (streamingTrackId == track.id) {
                    streamingTrackId = null
                    adapter.streamingId = null
                }
                return@launch
            }

            val player = MediaPlayer()
            previewPlayer = player
            try {
                player.setDataSource(url)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener {
                    streamingTrackId = null
                    adapter.streamingId = null
                    it.release()
                    previewPlayer = null
                }
                player.setOnErrorListener { _, _, _ ->
                    streamingTrackId = null
                    adapter.streamingId = null
                    player.release()
                    previewPlayer = null
                    true
                }
                player.prepareAsync()
            } catch (e: Exception) {
                player.release()
                previewPlayer = null
                streamingTrackId = null
                adapter.streamingId = null
            }
        }
    }

    // -------------------------------------------------------------------------
    // Download
    // -------------------------------------------------------------------------
    private fun downloadTrack(track: HarbourTrack) {
        adapter.setDownloadState(track.id, DownloadState.DOWNLOADING, 0f)
        lifecycleOwner.lifecycleScope.launch {
            HarbourDownloader.download(
                context   = context,
                track     = track,
                onProgress = { pct ->
                    binding.root.post {
                        adapter.setDownloadState(track.id, DownloadState.DOWNLOADING, pct)
                    }
                },
                onDone    = { _ ->
                    binding.root.post {
                        adapter.setDownloadState(track.id, DownloadState.DONE, 100f)
                        // Refresh local library so new track appears immediately
                        onSilentRescan()
                    }
                },
                onError   = { e ->
                    e.printStackTrace()
                    binding.root.post {
                        adapter.setDownloadState(track.id, DownloadState.ERROR, 0f)
                    }
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // State visibility helpers
    // -------------------------------------------------------------------------
    private fun showLoading() {
        binding.harbourLoadingState.visibility = View.VISIBLE
        binding.harbourEmptyState.visibility   = View.GONE
        binding.harbourErrorState.visibility   = View.GONE
        binding.rvHarbourResults.visibility    = View.GONE
    }

    private fun showIdle() {
        binding.harbourLoadingState.visibility = View.GONE
        binding.harbourEmptyState.visibility   = View.VISIBLE
        binding.harbourErrorState.visibility   = View.GONE
        binding.rvHarbourResults.visibility    = View.GONE
    }

    private fun showError(msg: String) {
        binding.tvHarbourErrorMsg.text         = msg
        binding.harbourLoadingState.visibility = View.GONE
        binding.harbourEmptyState.visibility   = View.GONE
        binding.harbourErrorState.visibility   = View.VISIBLE
        binding.rvHarbourResults.visibility    = View.GONE
    }

    private fun showResults(tracks: List<HarbourTrack>) {
        adapter.submitList(tracks)
        binding.harbourLoadingState.visibility = View.GONE
        binding.harbourEmptyState.visibility   = View.GONE
        binding.harbourErrorState.visibility   = View.GONE
        binding.rvHarbourResults.visibility    = View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etHarbourSearch.windowToken, 0)
    }
}
