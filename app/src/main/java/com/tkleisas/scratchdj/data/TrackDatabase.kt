package com.tkleisas.scratchdj.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TrackDatabase(
    context: Context,
    name: String = "scratch_library.db",
) : SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                display_name TEXT NOT NULL,
                extension TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                uri TEXT NOT NULL,
                bpm REAL NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_tracks_title ON $TABLE(title COLLATE NOCASE)")
        db.execSQL("CREATE INDEX idx_tracks_artist ON $TABLE(artist COLLATE NOCASE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN bpm REAL NOT NULL DEFAULT 0")
        }
    }

    fun replaceAll(tracks: List<Track>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val knownBpm = HashMap<Long, Float>()
            db.rawQuery("SELECT id, bpm FROM $TABLE", null).use { cursor ->
                while (cursor.moveToNext()) {
                    knownBpm[cursor.getLong(0)] = cursor.getFloat(1)
                }
            }

            db.delete(TABLE, null, null)
            val statement = db.compileStatement(
                """
                INSERT INTO $TABLE
                    (id, title, artist, album, display_name, extension, duration_ms, uri, bpm)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            )
            for (track in tracks) {
                statement.clearBindings()
                statement.bindLong(1, track.id)
                statement.bindString(2, track.title)
                statement.bindString(3, track.artist)
                statement.bindString(4, track.album)
                statement.bindString(5, track.displayName)
                statement.bindString(6, track.extension)
                statement.bindLong(7, track.durationMs)
                statement.bindString(8, track.uri)
                statement.bindDouble(9, (knownBpm[track.id]?.takeIf { it > 0f } ?: track.bpm).toDouble())
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun search(term: String, limit: Int = DEFAULT_LIMIT, offset: Int = 0): List<Track> {
        val db = readableDatabase
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        val cursor = if (SearchQuery.isBlank(term)) {
            db.rawQuery(
                """
                SELECT $COLUMNS FROM $TABLE
                ORDER BY artist COLLATE NOCASE, title COLLATE NOCASE
                LIMIT ? OFFSET ?
                """.trimIndent(),
                arrayOf(safeLimit.toString(), safeOffset.toString()),
            )
        } else {
            val pattern = SearchQuery.likePattern(term)
            db.rawQuery(
                """
                SELECT $COLUMNS FROM $TABLE
                WHERE title LIKE ? ESCAPE '\'
                   OR artist LIKE ? ESCAPE '\'
                   OR album LIKE ? ESCAPE '\'
                ORDER BY artist COLLATE NOCASE, title COLLATE NOCASE
                LIMIT ? OFFSET ?
                """.trimIndent(),
                arrayOf(pattern, pattern, pattern, safeLimit.toString(), safeOffset.toString()),
            )
        }

        return cursor.use {
            val tracks = ArrayList<Track>(it.count)
            while (it.moveToNext()) {
                tracks.add(
                    Track(
                        id = it.getLong(0),
                        title = it.getString(1) ?: "",
                        artist = it.getString(2) ?: "",
                        album = it.getString(3) ?: "",
                        displayName = it.getString(4) ?: "",
                        extension = it.getString(5) ?: "",
                        durationMs = it.getLong(6),
                        uri = it.getString(7) ?: "",
                        bpm = it.getFloat(8),
                    ),
                )
            }
            tracks
        }
    }

    fun count(term: String = ""): Int {
        val db = readableDatabase
        if (SearchQuery.isBlank(term)) {
            return db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        }
        val pattern = SearchQuery.likePattern(term)
        return db.rawQuery(
            """
            SELECT COUNT(*) FROM $TABLE
            WHERE title LIKE ? ESCAPE '\'
               OR artist LIKE ? ESCAPE '\'
               OR album LIKE ? ESCAPE '\'
            """.trimIndent(),
            arrayOf(pattern, pattern, pattern),
        ).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun updateBpm(trackId: Long, bpm: Float) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET bpm = ? WHERE id = ?",
            arrayOf<Any?>(bpm, trackId),
        )
    }

    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }

    private companion object {
        const val VERSION = 2
        const val TABLE = "tracks"
        const val DEFAULT_LIMIT = Int.MAX_VALUE
        const val COLUMNS =
            "id, title, artist, album, display_name, extension, duration_ms, uri, bpm"
    }
}
