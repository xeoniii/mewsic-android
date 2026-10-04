package com.mewsic.app.data

import com.mewsic.app.model.Song

object DummyData {

    private val titles = listOf(
        "Midnight City", "Neon Dreams", "Solar Flare", "Starlight Echo", "Velvet Waves",
        "Deep Horizon", "Electric Pulse", "Lunar Voyage", "Cybernetic Rain", "Cosmic Drift",
        "Golden Hour", "Subway Rhythm", "Crystal Sky", "Aura", "Quantum Leap",
        "Retrograde", "Synth Haven", "Afterglow", "Lost in Sound", "Emerald Valley"
    )

    private val artists = listOf(
        "Kavinsky", "Daft Pulse", " Tycho Sound", "Lofi Beats", "The Midnight",
        "ChilledCow", "Boards of Canada", "Deadmau5", "Odesza", "Bonobo"
    )

    private val albums = listOf(
        "Nightdrive", "Endless Summer", "Discovery", "Mirage", "Equinox",
        "Odyssey", "Renaissance", "Crossroads", "Visions", "Metropolis"
    )

    fun getDummySongs(count: Int = 100): List<Song> {
        val list = ArrayList<Song>(count)
        for (i in 1..count) {
            val title = "${titles[(i - 1) % titles.size]} #${i}"
            val artist = artists[(i - 1) % artists.size]
            val album = albums[(i - 1) % albums.size]
            val minutes = 2 + (i % 4)
            val seconds = String.format("%02d", (i * 7) % 60)
            list.add(
                Song(
                    id = i.toLong(),
                    title = title,
                    artist = artist,
                    duration = "$minutes:$seconds",
                    album = album
                )
            )
        }
        return list
    }
}
