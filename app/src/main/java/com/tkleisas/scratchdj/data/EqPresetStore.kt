package com.tkleisas.scratchdj.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class EqPresetStore(
    context: Context,
    name: String = "scratch_eq_presets.db",
) : SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                name TEXT PRIMARY KEY,
                payload TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun save(name: String, payload: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO $TABLE(name, payload, updated_at) VALUES (?, ?, ?)",
            arrayOf<Any?>(name, payload, System.currentTimeMillis()),
        )
    }

    fun names(): List<String> {
        return readableDatabase.rawQuery(
            "SELECT name FROM $TABLE ORDER BY updated_at DESC, name ASC",
            null,
        ).use { cursor ->
            val names = ArrayList<String>(cursor.count)
            while (cursor.moveToNext()) {
                names.add(cursor.getString(0))
            }
            names
        }
    }

    fun payload(name: String): String? {
        return readableDatabase.rawQuery(
            "SELECT payload FROM $TABLE WHERE name = ?",
            arrayOf(name),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun delete(name: String) {
        writableDatabase.delete(TABLE, "name = ?", arrayOf(name))
    }

    private companion object {
        const val VERSION = 1
        const val TABLE = "eq_presets"
    }
}
