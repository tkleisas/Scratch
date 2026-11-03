package com.tkleisas.scratchdj.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class QueuePresetSummary(
    val name: String,
    val scope: String,
)

data class QueuePresetData(
    val summary: QueuePresetSummary,
    val deckA: List<Track>,
    val deckB: List<Track>,
)

class QueuePresetStore(
    context: Context,
    name: String = "scratch_queue_presets.db",
) : SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $PRESETS (
                name TEXT PRIMARY KEY,
                scope TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $ITEMS (
                preset_name TEXT NOT NULL,
                deck TEXT NOT NULL,
                position INTEGER NOT NULL,
                track_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                display_name TEXT NOT NULL,
                extension TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                uri TEXT NOT NULL,
                bpm REAL NOT NULL DEFAULT 0,
                PRIMARY KEY (preset_name, deck, position)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $ITEMS")
        db.execSQL("DROP TABLE IF EXISTS $PRESETS")
        onCreate(db)
    }

    fun save(name: String, scope: String, deckA: List<Track>, deckB: List<Track>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO $PRESETS(name, scope, updated_at) VALUES (?, ?, ?)",
                arrayOf<Any?>(name, scope, System.currentTimeMillis()),
            )
            db.delete(ITEMS, "preset_name = ?", arrayOf(name))
            val statement = db.compileStatement(
                """
                INSERT INTO $ITEMS
                    (preset_name, deck, position, track_id, title, artist, album,
                     display_name, extension, duration_ms, uri, bpm)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            )
            fun insertItems(deck: String, tracks: List<Track>) {
                tracks.forEachIndexed { index, track ->
                    statement.clearBindings()
                    statement.bindString(1, name)
                    statement.bindString(2, deck)
                    statement.bindLong(3, index.toLong())
                    statement.bindLong(4, track.id)
                    statement.bindString(5, track.title)
                    statement.bindString(6, track.artist)
                    statement.bindString(7, track.album)
                    statement.bindString(8, track.displayName)
                    statement.bindString(9, track.extension)
                    statement.bindLong(10, track.durationMs)
                    statement.bindString(11, track.uri)
                    statement.bindDouble(12, track.bpm.toDouble())
                    statement.executeInsert()
                }
            }
            insertItems("A", deckA)
            insertItems("B", deckB)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun summaries(): List<QueuePresetSummary> {
        return readableDatabase.rawQuery(
            "SELECT name, scope FROM $PRESETS ORDER BY updated_at DESC, name ASC",
            null,
        ).use { cursor ->
            val result = ArrayList<QueuePresetSummary>(cursor.count)
            while (cursor.moveToNext()) {
                result.add(QueuePresetSummary(cursor.getString(0), cursor.getString(1)))
            }
            result
        }
    }

    fun load(name: String): QueuePresetData? {
        val scope = readableDatabase.rawQuery(
            "SELECT scope FROM $PRESETS WHERE name = ?",
            arrayOf(name),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return null

        val deckA = ArrayList<Track>()
        val deckB = ArrayList<Track>()
        readableDatabase.rawQuery(
            """
            SELECT deck, track_id, title, artist, album, display_name, extension,
                   duration_ms, uri, bpm
            FROM $ITEMS
            WHERE preset_name = ?
            ORDER BY deck ASC, position ASC
            """.trimIndent(),
            arrayOf(name),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val track = Track(
                    id = cursor.getLong(1),
                    title = cursor.getString(2) ?: "",
                    artist = cursor.getString(3) ?: "",
                    album = cursor.getString(4) ?: "",
                    displayName = cursor.getString(5) ?: "",
                    extension = cursor.getString(6) ?: "",
                    durationMs = cursor.getLong(7),
                    uri = cursor.getString(8) ?: "",
                    bpm = cursor.getFloat(9),
                )
                if (cursor.getString(0) == "A") deckA.add(track) else deckB.add(track)
            }
        }
        return QueuePresetData(QueuePresetSummary(name, scope), deckA, deckB)
    }

    fun delete(name: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(ITEMS, "preset_name = ?", arrayOf(name))
            db.delete(PRESETS, "name = ?", arrayOf(name))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val VERSION = 1
        const val PRESETS = "queue_presets"
        const val ITEMS = "queue_preset_items"
    }
}
