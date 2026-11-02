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

data class Conversation(
    val name: String,
    val count: Int,
    val lastAt: Long,
    val lastBody: String,
    val lastOutgoing: Boolean,
    val peer: String?,
)

class MessageStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "archive.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages(
                id INTEGER PRIMARY KEY,
                conversation TEXT NOT NULL,
                sender TEXT,
                outgoing INTEGER NOT NULL,
                body TEXT NOT NULL,
                ts INTEGER NOT NULL,
                seq REAL NOT NULL,
                source TEXT NOT NULL,
                dedupe_key TEXT NOT NULL UNIQUE)""",
        )
        db.execSQL("CREATE INDEX idx_conv_seq ON messages(conversation, seq)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) cleanScreenRows(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN seq REAL NOT NULL DEFAULT 0")
            db.execSQL("UPDATE messages SET seq=ts")
            db.execSQL("DROP INDEX IF EXISTS idx_conv_ts")
            db.execSQL("CREATE INDEX idx_conv_seq ON messages(conversation, seq)")
        }
    }

    private class OldRow(val id: Long, val conversation: String, val outgoing: Boolean, val body: String)

    /** Version 1 stored raw accessibility labels; reparse them so existing chats are kept, not lost. */
    private fun cleanScreenRows(db: SQLiteDatabase) {
        // Read everything first: changing rows under an open cursor can skip some of them.
        val rows = ArrayList<OldRow>()
        db.rawQuery(
            "SELECT id, conversation, outgoing, body FROM messages WHERE source='screen' ORDER BY conversation, ts, id",
            null,
        ).use { c ->
            while (c.moveToNext()) rows.add(OldRow(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getString(3)))
        }

        val previous = HashMap<String, String>()
        for (row in rows) {
            val id = arrayOf(row.id.toString())
            val conversation = ChatParser.cleanTitle(row.conversation)
            if (ChatParser.isNoise(row.body)) {
                db.delete("messages", "id=?", id)
                continue
            }
            val marked = ChatParser.parseMarked(row.body)
            val text = marked?.text ?: row.body
            val sender = if (row.outgoing) null else marked?.sender ?: conversation
            val message = ScreenMessage(text, row.outgoing, previous[conversation].orEmpty(), sender)
            val values = ContentValues().apply {
                put("conversation", conversation)
                put("sender", sender)
                put("body", text)
                put("dedupe_key", ChatParser.screenKey(conversation, message))
            }
            val updated = db.updateWithOnConflict("messages", values, "id=?", id, SQLiteDatabase.CONFLICT_IGNORE)
            if (updated == 0) db.delete("messages", "id=?", id)
            previous[conversation] = text
        }
    }

    private class Known(val seq: Double, val ts: Long)

    private fun known(conversation: String, item: ScreenMessage, now: Long): Known? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT seq, ts FROM messages WHERE dedupe_key=?",
            arrayOf(ChatParser.screenKey(conversation, item)),
        ).use { if (it.moveToFirst()) return Known(it.getDouble(0), it.getLong(1)) }
        // A message usually arrives as a notification first, then shows up again when the chat is opened.
        val since = now - TimeUnit.DAYS.toMillis(NOTIFICATION_MATCH_DAYS)
        db.rawQuery(
            """SELECT seq, ts FROM messages WHERE conversation=? AND body=? AND outgoing=?
               AND source='notification' AND ts>=? ORDER BY seq DESC LIMIT 1""",
            arrayOf(conversation, item.text, if (item.outgoing) "1" else "0", since.toString()),
        ).use { if (it.moveToFirst()) return Known(it.getDouble(0), it.getLong(1)) }
        return null
    }

    private fun neighbour(conversation: String, sql: String, seq: Double): Known? {
        readableDatabase.rawQuery(sql, arrayOf(conversation, seq.toString())).use {
            return if (it.moveToFirst()) Known(it.getDouble(0), it.getLong(1)) else null
        }
    }

    private fun after(conversation: String, seq: Double) = neighbour(
        conversation,
        "SELECT seq, ts FROM messages WHERE conversation=? AND seq>? ORDER BY seq LIMIT 1",
        seq,
    )

    private fun before(conversation: String, seq: Double) = neighbour(
        conversation,
        "SELECT seq, ts FROM messages WHERE conversation=? AND seq<? ORDER BY seq DESC LIMIT 1",
        seq,
    )

    private fun lastSeq(conversation: String): Double? {
        readableDatabase.rawQuery(
            "SELECT MAX(seq) FROM messages WHERE conversation=?",
            arrayOf(conversation),
        ).use { return if (it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else null }
    }

    /**
     * Saves what is on screen in chat order. Scrolling back reveals older messages after newer ones
     * were stored, so new bubbles are placed relative to the known bubbles around them. Their real
     * send time is not on screen: a scrolled-back message takes the time of the message after it.
     */
    fun saveScreen(chat: ScreenChat, now: Long) {
        val conversation = chat.conversation
        val items = chat.messages
        writableDatabase.beginTransaction()
        try {
            val found = items.map { known(conversation, it, now) }
            var start = 0
            while (start < items.size) {
                if (found[start] != null) {
                    start++
                    continue
                }
                var end = start
                while (end < items.size && found[end] == null) end++
                saveRun(conversation, items.subList(start, end), found.getOrNull(start - 1), found.getOrNull(end), now)
                start = end
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun saveRun(
        conversation: String,
        run: List<ScreenMessage>,
        lower: Known?,
        visibleUpper: Known?,
        now: Long,
    ) {
        val upper = visibleUpper ?: lower?.let { after(conversation, it.seq) }
        val seqs: List<Double>
        val time: Long
        if (upper == null) {
            val base = maxOf(now.toDouble(), (lastSeq(conversation) ?: 0.0) + 1)
            seqs = run.indices.map { base + it }
            time = now
        } else {
            val floor = lower?.seq ?: before(conversation, upper.seq)?.seq ?: (upper.seq - ONE_MINUTE_MS * (run.size + 1))
            seqs = ChatParser.between(floor, upper.seq, run.size)
            time = upper.ts
        }
        run.forEachIndexed { i, item ->
            val message = Message(conversation, item.sender, item.outgoing, item.text, time, "screen")
            insert(message, ChatParser.screenKey(conversation, item), seqs[i])
        }
    }

    fun insert(message: Message, key: String, seq: Double = message.timestamp.toDouble()): Boolean {
        val values = ContentValues().apply {
            put("seq", seq)
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

    fun conversations(): List<Conversation> {
        // Rows can share a seq, so the last message is picked by seq and id rather than MAX(seq).
        readableDatabase.rawQuery(
            """SELECT g.conversation, g.n, m.ts, m.body, m.outgoing,
                (SELECT sender FROM messages p WHERE p.conversation=g.conversation AND p.outgoing=0
                 ORDER BY p.seq DESC, p.id DESC LIMIT 1)
               FROM (SELECT conversation, COUNT(*) n,
                       (SELECT id FROM messages l WHERE l.conversation=o.conversation
                        ORDER BY l.seq DESC, l.id DESC LIMIT 1) last
                     FROM messages o GROUP BY conversation) g
               JOIN messages m ON m.id=g.last
               ORDER BY m.seq DESC, m.id DESC""",
            null,
        ).use { c ->
            val out = ArrayList<Conversation>(c.count)
            while (c.moveToNext()) {
                out.add(
                    Conversation(
                        c.getString(0),
                        c.getInt(1),
                        c.getLong(2),
                        c.getString(3),
                        c.getInt(4) == 1,
                        if (c.isNull(5)) null else c.getString(5),
                    ),
                )
            }
            return out
        }
    }

    fun messages(conversation: String): List<Message> =
        query("WHERE conversation=? ORDER BY seq, id", arrayOf(conversation))

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
                "SELECT conversation, sender, outgoing, body, ts, source FROM messages ORDER BY conversation, seq, id",
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
        private const val ONE_MINUTE_MS = 60_000.0

        @Volatile
        private var instance: MessageStore? = null

        fun get(context: Context): MessageStore =
            instance ?: synchronized(this) {
                instance ?: MessageStore(context.applicationContext).also { instance = it }
            }
    }
}
