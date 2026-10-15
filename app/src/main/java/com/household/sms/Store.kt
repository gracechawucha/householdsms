package com.household.sms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Settings and status, kept in plain SharedPreferences. */
object Cfg {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun url(c: Context): String = p(c).getString("url", "") ?: ""
    fun secret(c: Context): String = p(c).getString("secret", "") ?: ""
    fun device(c: Context): String = p(c).getString("device", "") ?: ""

    fun save(c: Context, url: String, secret: String, device: String) {
        p(c).edit().putString("url", url.trim()).putString("secret", secret.trim()).putString("device", device.trim()).apply()
    }

    fun configured(c: Context) = url(c).isNotBlank() && secret(c).isNotBlank()

    fun lastOk(c: Context): Long = p(c).getLong("lastOk", 0L)
    fun lastError(c: Context): String = p(c).getString("lastError", "") ?: ""
    fun lastSweep(c: Context): Long = p(c).getLong("lastSweep", 0L)
    fun lastSweepNote(c: Context): String = p(c).getString("lastSweepNote", "") ?: ""

    fun okNow(c: Context) = p(c).edit().putLong("lastOk", System.currentTimeMillis()).putString("lastError", "").apply()
    fun error(c: Context, msg: String) = p(c).edit().putString("lastError", msg).apply()
    fun sweepDone(c: Context, note: String) =
        p(c).edit().putLong("lastSweep", System.currentTimeMillis()).putString("lastSweepNote", note).apply()
}

class Pending(val id: Long, val code: String, val body: String, val smsTime: Long)

/**
 * The phone-side safety net. A message is written here BEFORE we try the network,
 * and only deleted once the server has confirmed it. Nothing is lost if the phone is
 * offline, asleep or the server is down: it just waits and retries.
 */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "queue.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE pending (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, " +
                "body TEXT NOT NULL UNIQUE, sms_time INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE sent (code TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Queue a message. Returns false if the very same text is already waiting. */
    fun enqueue(code: String, body: String, smsTime: Long): Boolean {
        val v = ContentValues().apply {
            put("code", code)
            put("body", body)
            put("sms_time", smsTime)
        }
        return writableDatabase.insertWithOnConflict("pending", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun pending(limit: Int = 200): List<Pending> {
        val out = ArrayList<Pending>()
        readableDatabase.rawQuery("SELECT id, code, body, sms_time FROM pending ORDER BY sms_time ASC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out.add(Pending(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
        }
        return out
    }

    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM pending", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun delete(id: Long) {
        writableDatabase.delete("pending", "id = ?", arrayOf(id.toString()))
    }

    fun markSent(code: String) {
        if (code.isBlank()) return
        val v = ContentValues().apply { put("code", code) }
        writableDatabase.insertWithOnConflict("sent", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun isSent(code: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM sent WHERE code = ?", arrayOf(code)).use { it.moveToFirst() }

    fun clearSent() {
        writableDatabase.delete("sent", null, null)
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx).also { inst = it } }
    }
}
