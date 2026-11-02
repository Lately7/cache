package com.lately.msgarchive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonWriter
import java.io.Writer
import java.util.concurrent.TimeUnit

data class Message(
    val conversation: String,
    val sender: String?,
    val outgoing: Boolean,
    val body: String,
    val timestamp: Long,
    val source: String,
)

data class Conversation(val name: String, val count: Int, val lastAt: Long, val lastBody: String)

class MessageStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "archive.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages(
                id INTEGER PRIMARY KEY,
                conversation TEXT NOT NULL,
                sender TEXT,
                outgoing INTEGER NOT NULL,
                body TEXT NOT NULL,
                ts INTEGER NOT NULL,
                source TEXT NOT NULL,
                dedupe_key TEXT NOT NULL UNIQUE)""",
        )
        db.execSQL("CREATE INDEX idx_conv_ts ON messages(conversation, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(message: Message, key: String): Boolean {
        val values = ContentValues().apply {
            put("conversation", message.conversation)
            put("sender", message.sender)
            put("outgoing", if (message.outgoing) 1 else 0)
            put("body", message.body)
            put("ts", message.timestamp)
            put("source", message.source)
            put("dedupe_key", key)
        }
        return writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** A message usually arrives as a notification first, then shows up again when the chat is opened. */
    fun seenInNotification(conversation: String, body: String, now: Long): Boolean {
        val since = now - TimeUnit.DAYS.toMillis(NOTIFICATION_MATCH_DAYS)
        readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE conversation=? AND body=? AND source='notification' AND ts>=? LIMIT 1",
            arrayOf(conversation, body, since.toString()),
        ).use { return it.moveToFirst() }
    }

    fun conversations(): List<Conversation> {
        // SQLite returns the row holding MAX(ts) for the bare body column.
        readableDatabase.rawQuery(
            "SELECT conversation, COUNT(*), MAX(ts), body FROM messages GROUP BY conversation ORDER BY MAX(ts) DESC",
            null,
        ).use { c ->
            val out = ArrayList<Conversation>(c.count)
            while (c.moveToNext()) out.add(Conversation(c.getString(0), c.getInt(1), c.getLong(2), c.getString(3)))
            return out
        }
    }

    fun messages(conversation: String): List<Message> =
        query("WHERE conversation=? ORDER BY ts, id", arrayOf(conversation))

    fun search(term: String): List<Message> {
        val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return query("WHERE body LIKE ? ESCAPE '\\' ORDER BY ts DESC LIMIT 500", arrayOf("%$escaped%"))
    }

    fun deleteAll() {
        writableDatabase.delete("messages", null, null)
    }

    fun exportJson(out: Writer): Int {
        var count = 0
        JsonWriter(out).use { json ->
            json.setIndent(" ")
            json.beginArray()
            readableDatabase.rawQuery(
                "SELECT conversation, sender, outgoing, body, ts, source FROM messages ORDER BY conversation, ts, id",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    json.beginObject()
                    json.name("conversation").value(c.getString(0))
                    json.name("sender").value(c.getString(1))
                    json.name("direction").value(if (c.getInt(2) == 1) "sent" else "received")
                    json.name("body").value(c.getString(3))
                    json.name("timestamp").value(c.getLong(4))
                    json.name("source").value(c.getString(5))
                    json.endObject()
                    count++
                }
            }
            json.endArray()
        }
        return count
    }

    private fun query(clause: String, args: Array<String>): List<Message> {
        readableDatabase.rawQuery(
            "SELECT conversation, sender, outgoing, body, ts, source FROM messages $clause",
            args,
        ).use { c ->
            val out = ArrayList<Message>(c.count)
            while (c.moveToNext()) {
                out.add(
                    Message(
                        c.getString(0),
                        if (c.isNull(1)) null else c.getString(1),
                        c.getInt(2) == 1,
                        c.getString(3),
                        c.getLong(4),
                        c.getString(5),
                    ),
                )
            }
            return out
        }
    }

    companion object {
        private const val NOTIFICATION_MATCH_DAYS = 7L

        @Volatile
        private var instance: MessageStore? = null

        fun get(context: Context): MessageStore =
            instance ?: synchronized(this) {
                instance ?: MessageStore(context.applicationContext).also { instance = it }
            }
    }
}
