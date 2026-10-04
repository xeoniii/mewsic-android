package com.mewsic.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.mewsic.app.MainActivity
import com.mewsic.app.R
import com.mewsic.app.databinding.FragmentPlayerBinding
import com.mewsic.app.model.Song

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as? MainActivity
        updateUI(mainActivity?.currentSong, mainActivity?.isPlaying ?: true)

        binding.fabPlayPause.setOnClickListener {
            mainActivity?.togglePlayPause()
        }

        binding.btnNext.setOnClickListener {
            mainActivity?.playNext()
        }

        binding.btnPrevious.setOnClickListener {
            mainActivity?.playPrevious()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val totalSeconds = 225 // 3:45 approx
                    val currentSec = (totalSeconds * (progress / 100f)).toInt()
                    val mins = currentSec / 60
                    val secs = currentSec % 60
                    binding.tvTimeCurrent.text = String.format("%02d:%02d", mins, secs)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    fun updateUI(song: Song?, isPlaying: Boolean) {
        if (_binding == null) return
        val currentSong = song ?: return

        binding.tvPlayerTitle.text = currentSong.title
        binding.tvPlayerArtist.text = "${currentSong.artist} • ${currentSong.album}"
        binding.tvTimeTotal.text = currentSong.duration

        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.fabPlayPause.setImageResource(iconRes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
