package dev.hermano.hreceiver

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Буфер на телефоне (предложение, п. 3.1): SQLite, 7 дней,
 * удаление только после подтверждения сервера (acked_to).
 *
 * Пишет BLE-поток, читает/удаляет поток отправки. SQLiteOpenHelper держит одно
 * соединение и сериализует доступ — это безопасно. WAL — чтобы чтение пачки
 * не блокировало запись очередного удара.
 */
class LocalBuffer(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "hr_buffer.db", null, 1) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE readings (
                ts INTEGER PRIMARY KEY,
                bpm INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    /** Никогда не бросает: ошибка записи одного удара не должна убить BLE-поток. */
    fun put(ts: Long, bpm: Int): Boolean = try {
        val cv = ContentValues().apply {
            put("ts", ts)
            put("bpm", bpm)
        }
        writableDatabase.insertWithOnConflict("readings", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    } catch (_: Exception) {
        false
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

    fun count(): Long = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM readings", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
    } catch (_: Exception) {
        -1L
    }

    fun pruneOlderThan(cutoff: Long) {
        try {
            writableDatabase.execSQL("DELETE FROM readings WHERE ts < ?", arrayOf(cutoff))
        } catch (_: Exception) {}
    }
}
