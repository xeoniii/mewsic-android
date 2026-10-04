package com.mewsic.app.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PlaylistInfo(
    val name: String,
    val trackCount: Int,
    val isDefault: Boolean = false
)

object PlaylistManager {
    private const val PREFS_NAME = "mewsic_playlists"
    private const val KEY_PLAYLIST_MAP = "playlist_map_json"
    const val DEFAULT_PLAYLIST = "Favorites"

    fun getPlaylists(context: Context): List<String> {
        val map = loadPlaylistMap(context)
        if (!map.containsKey(DEFAULT_PLAYLIST)) {
            map[DEFAULT_PLAYLIST] = mutableSetOf()
            savePlaylistMap(context, map)
        }
        return map.keys.sortedWith(compareByDescending<String> { it == DEFAULT_PLAYLIST }.thenBy { it })
    }

    fun getPlaylistInfos(context: Context): List<PlaylistInfo> {
        val map = loadPlaylistMap(context)
        if (!map.containsKey(DEFAULT_PLAYLIST)) {
            map[DEFAULT_PLAYLIST] = mutableSetOf()
            savePlaylistMap(context, map)
        }
        return map.map { (name, songs) ->
            PlaylistInfo(
                name = name,
                trackCount = songs.size,
                isDefault = (name == DEFAULT_PLAYLIST)
            )
        }.sortedWith(compareByDescending<PlaylistInfo> { it.isDefault }.thenBy { it.name })
    }

    fun getPlaylistSongIds(context: Context, playlistName: String): Set<Long> {
        val map = loadPlaylistMap(context)
        return map[playlistName] ?: emptySet()
    }

    fun addSongToPlaylist(context: Context, playlistName: String, songId: Long): Boolean {
        val map = loadPlaylistMap(context)
        val set = map.getOrPut(playlistName) { mutableSetOf() }
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
        map[playlistName] = mutableSetOf()
        savePlaylistMap(context, map)
        return true
    }

    fun deletePlaylist(context: Context, playlistName: String): Boolean {
        if (playlistName == DEFAULT_PLAYLIST) return false
        val map = loadPlaylistMap(context)
        val removed = map.remove(playlistName) != null
        if (removed) {
            savePlaylistMap(context, map)
        }
        return removed
    }

    fun renamePlaylist(context: Context, oldName: String, newName: String): Boolean {
        if (oldName == DEFAULT_PLAYLIST) return false
        val map = loadPlaylistMap(context)
        if (!map.containsKey(oldName) || map.containsKey(newName)) return false
        val songIds = map.remove(oldName) ?: return false
        map[newName] = songIds
        savePlaylistMap(context, map)
        return true
    }

    private fun loadPlaylistMap(context: Context): MutableMap<String, MutableSet<Long>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PLAYLIST_MAP, null) ?: return mutableMapOf(DEFAULT_PLAYLIST to mutableSetOf())

        val result = mutableMapOf<String, MutableSet<Long>>()
        try {
            val jsonObj = JSONObject(jsonStr)
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val jsonArr = jsonObj.getJSONArray(key)
                val set = mutableSetOf<Long>()
                for (i in 0 until jsonArr.length()) {
                    set.add(jsonArr.getLong(i))
                }
                result[key] = set
            }
        } catch (e: Exception) {
            e.printStackTrace()
            result[DEFAULT_PLAYLIST] = mutableSetOf()
        }
        if (!result.containsKey(DEFAULT_PLAYLIST)) {
            result[DEFAULT_PLAYLIST] = mutableSetOf()
        }
        return result
    }

    private fun savePlaylistMap(context: Context, map: Map<String, Set<Long>>) {
        val jsonObj = JSONObject()
        for ((key, set) in map) {
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
