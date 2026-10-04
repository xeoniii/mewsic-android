package com.mewsic.app.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mewsic.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LibraryStats(
    val totalTracks: Int,
    val uniqueArtists: Int,
    val totalAlbums: Int,
    val totalDurationSeconds: Long,
    val formattedDuration: String
)

object MediaScanner {

    suspend fun scanDeviceAudio(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songList = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.IS_MUSIC
        )

        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf("20000") // Query anything >= 20 seconds from database, then classifier refines
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        val collectionUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        try {
            context.contentResolver.query(
                collectionUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val isMusicCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Track"
                    val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val album = cursor.getString(albumCol) ?: "Unknown Album"
                    val albumId = cursor.getLong(albumIdCol)
                    val durationMs = cursor.getLong(durationCol)
                    val filePath = cursor.getString(dataCol) ?: ""
                    val dateAdded = cursor.getLong(dateAddedCol)
                    val dateModified = cursor.getLong(dateModifiedCol)
                    val size = cursor.getLong(sizeCol)
                    val isMusic = cursor.getInt(isMusicCol) != 0

                    // Run the intelligent SongClassifier to exclude voice notes, whatsapp audio, call records
                    val isSong = SongClassifier.classifyAudio(
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        filePath = filePath,
                        isMusicFlag = isMusic
                    )

                    if (isSong) {
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        songList.add(
                            Song(
                                id = id,
                                title = title,
                                artist = if (artist.equals("<unknown>", ignoreCase = true)) "Unknown Artist" else artist,
                                album = if (album.equals("<unknown>", ignoreCase = true)) "Unknown Album" else album,
                                albumId = albumId,
                                durationMs = durationMs,
                                filePath = filePath,
                                dateAdded = dateAdded,
                                dateModified = dateModified,
                                size = size,
                                contentUri = contentUri
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        songList
    }

    fun computeLibraryStats(songs: List<Song>): LibraryStats {
        val totalTracks = songs.size
        val uniqueArtists = songs.map { it.artist.lowercase().trim() }.filter { it.isNotBlank() && it != "unknown artist" }.distinct().size
        val uniqueAlbums = songs.map { it.album.lowercase().trim() }.filter { it.isNotBlank() && it != "unknown album" }.distinct().size
        val totalDurationSeconds = songs.sumOf { it.durationMs } / 1000

        val h = totalDurationSeconds / 3600
        val m = (totalDurationSeconds % 3600) / 60
        val s = totalDurationSeconds % 60
        val formatted = if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"

        return LibraryStats(
            totalTracks = totalTracks,
            uniqueArtists = if (uniqueArtists > 0) uniqueArtists else 1,
            totalAlbums = if (uniqueAlbums > 0) uniqueAlbums else 1,
            totalDurationSeconds = totalDurationSeconds,
            formattedDuration = formatted
        )
    }
}
