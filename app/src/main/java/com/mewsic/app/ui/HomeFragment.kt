package com.mewsic.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.mewsic.app.MainActivity
import com.mewsic.app.R
import com.mewsic.app.data.DummyData
import com.mewsic.app.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as? MainActivity

        binding.btnOpenLibrary.setOnClickListener {
            mainActivity?.navigateToTab(R.id.menu_library)
        }

        binding.cardMix1.setOnClickListener {
            val song = DummyData.getDummySongs().firstOrNull()
            if (song != null) mainActivity?.playSong(song)
        }

        binding.cardMix2.setOnClickListener {
            val song = DummyData.getDummySongs().getOrNull(1)
            if (song != null) mainActivity?.playSong(song)
        }

        binding.cardMix3.setOnClickListener {
            val song = DummyData.getDummySongs().getOrNull(2)
            if (song != null) mainActivity?.playSong(song)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
