package com.mewsic.app.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.mewsic.app.ui.HomeFragment
import com.mewsic.app.ui.LibraryFragment
import com.mewsic.app.ui.PlayerFragment

class MainPagerAdapter(
    activity: FragmentActivity,
    val homeFragment: HomeFragment = HomeFragment(),
    val libraryFragment: LibraryFragment = LibraryFragment(),
    val playerFragment: PlayerFragment = PlayerFragment()
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> homeFragment
            1 -> libraryFragment
            2 -> playerFragment
            else -> homeFragment
        }
    }
}
