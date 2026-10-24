package dev.hermano.hreceiver

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Буфер на телефоне (предложение, п. 3.1): SQLite, 7 дней,
 * удаление только после подтверждения сервера (acked_to).
 */
class LocalBuffer(context: Context) :
    SQLiteOpenHelper(context, "hr_buffer.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE readings (
                ts INTEGER PRIMARY KEY,
                bpm INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    fun put(ts: Long, bpm: Int) {
        val cv = ContentValues().apply {
            put("ts", ts)
            put("bpm", bpm)
        }
        writableDatabase.insertWithOnConflict("readings", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun unsent(limit: Int = 500): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        readableDatabase.rawQuery("SELECT ts, bpm FROM readings ORDER BY ts LIMIT ?", arrayOf(limit.toString()))
            .use { c ->
                while (c.moveToNext()) out.add(c.getLong(0) to c.getInt(1))
            }
        return out
    }

    fun markSent(ackedTo: Long) {
        writableDatabase.execSQL("DELETE FROM readings WHERE ts <= ?", arrayOf(ackedTo))
    }

    fun count(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM readings", null).use { it.moveToFirst(); it.getLong(0) }

    fun pruneOlderThan(cutoff: Long) {
        writableDatabase.execSQL("DELETE FROM readings WHERE ts < ?", arrayOf(cutoff))
    }
}
