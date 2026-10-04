package com.mewsic.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.mewsic.app.MainActivity
import com.mewsic.app.adapter.SongAdapter
import com.mewsic.app.data.DummyData
import com.mewsic.app.databinding.FragmentLibraryBinding

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as? MainActivity
        val dummySongs = DummyData.getDummySongs(100)

        binding.tvTrackCount.text = "${dummySongs.size} Tracks • 60 FPS RecyclerView Benchmark"

        val adapter = SongAdapter(dummySongs) { selectedSong ->
            mainActivity?.playSong(selectedSong)
        }

        binding.rvSongs.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
