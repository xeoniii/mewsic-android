package com.mewsic.app.scanner

import android.content.Context
import com.mewsic.app.model.Song
import org.json.JSONArray
import org.json.JSONObject

data class PlaylistInfo(
    val name: String,
    val trackCount: Int,
    val firstSong: Song? = null
)

object PlaylistManager {
    private const val PREFS_NAME = "mewsic_playlists"
    private const val KEY_PLAYLIST_MAP = "playlist_map_json"

    fun getPlaylists(context: Context): List<String> {
        val map = loadPlaylistMap(context)
        return map.keys.sortedBy { it.lowercase() }
    }

    fun getPlaylistInfos(context: Context, allSongs: List<Song> = emptyList()): List<PlaylistInfo> {
        val map = loadPlaylistMap(context)
        val songMap = allSongs.associateBy { it.id }
        return map.map { (name, songs) ->
            val firstId = songs.firstOrNull()
            val firstSong = if (firstId != null) songMap[firstId] else null
            PlaylistInfo(
                name = name,
                trackCount = songs.size,
                firstSong = firstSong
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun getPlaylistSongIds(context: Context, playlistName: String): List<Long> {
        val map = loadPlaylistMap(context)
        return map[playlistName]?.toList() ?: emptyList()
    }

    fun addSongToPlaylist(context: Context, playlistName: String, songId: Long): Boolean {
        val map = loadPlaylistMap(context)
        val set = map.getOrPut(playlistName) { LinkedHashSet() }
        val added = set.add(songId)
        if (added) {
            savePlaylistMap(context, map)
        }
        return added
    }

    fun removeSongFromPlaylist(context: Context, playlistName: String, songId: Long): Boolean {
        val map = loadPlaylistMap(context)
        val set = map[playlistName] ?: return false
        val removed = set.remove(songId)
        if (removed) {
            savePlaylistMap(context, map)
        }
        return removed
    }

    fun createPlaylist(context: Context, playlistName: String): Boolean {
        val map = loadPlaylistMap(context)
        if (map.containsKey(playlistName)) return false
        map[playlistName] = LinkedHashSet()
        savePlaylistMap(context, map)
        return true
    }

    fun deletePlaylist(context: Context, playlistName: String): Boolean {
        val map = loadPlaylistMap(context)
        val removed = map.remove(playlistName) != null
        if (removed) {
            savePlaylistMap(context, map)
        }
        return removed
    }

    fun renamePlaylist(context: Context, oldName: String, newName: String): Boolean {
        val map = loadPlaylistMap(context)
        if (!map.containsKey(oldName) || map.containsKey(newName)) return false
        val songIds = map.remove(oldName) ?: return false
        map[newName] = songIds
        savePlaylistMap(context, map)
        return true
    }

    private fun loadPlaylistMap(context: Context): MutableMap<String, LinkedHashSet<Long>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PLAYLIST_MAP, null) ?: return mutableMapOf()

        val result = mutableMapOf<String, LinkedHashSet<Long>>()
        try {
            val jsonObj = JSONObject(jsonStr)
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == "Favorites") continue // Clean out legacy Favorites playlist
                val jsonArr = jsonObj.getJSONArray(key)
                val set = LinkedHashSet<Long>()
                for (i in 0 until jsonArr.length()) {
                    set.add(jsonArr.getLong(i))
                }
                result[key] = set
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private fun savePlaylistMap(context: Context, map: Map<String, Set<Long>>) {
        val jsonObj = JSONObject()
        for ((key, set) in map) {
            if (key == "Favorites") continue
            val jsonArr = JSONArray()
            for (id in set) {
                jsonArr.put(id)
            }
            jsonObj.put(key, jsonArr)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYLIST_MAP, jsonObj.toString())
            .apply()
    }
}
