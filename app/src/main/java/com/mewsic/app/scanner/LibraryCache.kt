package com.mewsic.app.scanner

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.mewsic.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object LibraryCache {
    private const val FILE_NAME = "cached_library.json"
    private var memoryCache: List<Song>? = null

    suspend fun loadCachedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        memoryCache?.let { return@withContext it }

        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@withContext emptyList()

        try {
            val jsonStr = file.readText()
            val array = JSONArray(jsonStr)
            val list = ArrayList<Song>(array.length())

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getLong("id")
                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                list.add(
                    Song(
                        id = id,
                        title = obj.getString("title"),
                        artist = obj.getString("artist"),
                        album = obj.getString("album"),
                        albumId = obj.getLong("albumId"),
                        durationMs = obj.getLong("durationMs"),
                        filePath = obj.optString("filePath", ""),
                        dateAdded = obj.optLong("dateAdded", 0L),
                        dateModified = obj.optLong("dateModified", 0L),
                        size = obj.optLong("size", 0L),
                        contentUri = contentUri
                    )
                )
            }
            memoryCache = list
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun saveCachedSongs(context: Context, songs: List<Song>) = withContext(Dispatchers.IO) {
        memoryCache = songs
        try {
            val array = JSONArray()
            for (song in songs) {
                val obj = JSONObject().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("album", song.album)
                    put("albumId", song.albumId)
                    put("durationMs", song.durationMs)
                    put("filePath", song.filePath)
                    put("dateAdded", song.dateAdded)
                    put("dateModified", song.dateModified)
                    put("size", song.size)
                }
                array.put(obj)
            }
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
