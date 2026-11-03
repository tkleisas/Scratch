package com.tkleisas.scratchdj.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryRepository(
    private val context: Context,
    private val database: TrackDatabase,
) {

    suspend fun sync(): Int = withContext(Dispatchers.IO) {
        val tracks = scanMediaStore()
        database.replaceAll(tracks)
        tracks.size
    }

    fun search(term: String, limit: Int = 500): List<Track> = database.search(term, limit)

    fun count(): Int = database.count()

    private fun scanMediaStore(): List<Track> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
        )

        val tracks = LinkedHashMap<Long, Track>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameColumn) ?: continue
                if (!AudioFormats.isSupported(displayName)) continue

                val id = cursor.getLong(idColumn)
                val extension = AudioFormats.extensionOf(displayName) ?: continue
                tracks[id] = Track(
                    id = id,
                    title = cursor.getString(titleColumn)
                        ?: displayName.substringBeforeLast('.'),
                    artist = cursor.getString(artistColumn) ?: "Unknown artist",
                    album = cursor.getString(albumColumn) ?: "",
                    displayName = displayName,
                    extension = extension,
                    durationMs = cursor.getLong(durationColumn),
                    uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id,
                    ).toString(),
                )
            }
        }
        return tracks.values.toList()
    }
}
