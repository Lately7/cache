package com.lately.msgarchive

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
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
    val kind: String? = null,
    val media: String? = null,
)

data class SavedScreen(val ids: Map<ScreenMessage, Long>, val added: Int)

data class ThreadMatch(val name: String?, val known: List<String>, val unused: String)

data class Conversation(
    val name: String,
    val count: Int,
    val lastAt: Long,
    val lastBody: String,
    val lastOutgoing: Boolean,
    val peer: String?,
)

class MessageStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "archive.db", null, 6) {

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
                dedupe_key TEXT NOT NULL UNIQUE,
                kind TEXT,
                media TEXT,
                screen_key TEXT)""",
        )
        db.execSQL("CREATE INDEX idx_conv_seq ON messages(conversation, seq)")
        db.execSQL("CREATE INDEX idx_screen_key ON messages(screen_key)")
        createThreads(db)
    }

    private fun createThreads(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE threads(
                title TEXT NOT NULL,
                shortcut TEXT NOT NULL,
                conversation TEXT NOT NULL,
                PRIMARY KEY(title, shortcut))""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) cleanScreenRows(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN seq REAL NOT NULL DEFAULT 0")
            db.execSQL("UPDATE messages SET seq=ts")
            db.execSQL("DROP INDEX IF EXISTS idx_conv_ts")
            db.execSQL("CREATE INDEX idx_conv_seq ON messages(conversation, seq)")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN media TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE messages ADD COLUMN screen_key TEXT")
            db.execSQL("CREATE INDEX idx_screen_key ON messages(screen_key)")
        }
        if (oldVersion < 6) createThreads(db)
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

    private class Known(val id: Long, val seq: Double, val ts: Long)

    private fun Cursor.toKnown() = Known(getLong(0), getDouble(1), getLong(2))

    /** Rows already stored under this bubble's identity, newest first; a repeat of it adds a numbered key. */
    private fun occurrences(key: String): List<Known> {
        readableDatabase.rawQuery(
            """SELECT id, seq, ts FROM messages WHERE dedupe_key=? OR screen_key=?
               OR (dedupe_key>? AND dedupe_key<?) ORDER BY seq DESC, id DESC""",
            arrayOf(key, key, key + REPEAT_MARK, key + (REPEAT_MARK + 1)),
        ).use { c ->
            val out = ArrayList<Known>(c.count)
            while (c.moveToNext()) out.add(c.toKnown())
            return out
        }
    }

    /**
     * A message usually arrives as a notification first, then shows up again when the chat is opened.
     * Each notification row can only stand for one bubble, and only one placed after the bubble above,
     * or a later "ok" would be taken for an old notification's "ok" and never saved.
     */
    private fun claimNotification(
        conversation: String,
        item: ScreenMessage,
        key: String,
        above: Double?,
        now: Long,
    ): Known? {
        val since = now - TimeUnit.DAYS.toMillis(NOTIFICATION_MATCH_DAYS)
        val order = if (above == null) "DESC" else "ASC"
        // Screen rows are stamped when read, a moment after the send time a notification carries.
        val floor = if (above == null) -Double.MAX_VALUE else above - ONE_MINUTE_MS
        readableDatabase.rawQuery(
            """SELECT id, seq, ts FROM messages WHERE conversation=? AND body=? AND outgoing=?
               AND source='notification' AND screen_key IS NULL AND ts>=? AND seq>?
               ORDER BY seq $order LIMIT 1""",
            arrayOf(conversation, item.text, if (item.outgoing) "1" else "0", since.toString(), floor.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val row = c.toKnown()
            val values = ContentValues().apply { put("screen_key", key) }
            writableDatabase.update("messages", values, "id=?", arrayOf(row.id.toString()))
            return row
        }
    }

    private fun neighbour(conversation: String, sql: String, seq: Double): Known? {
        readableDatabase.rawQuery(sql, arrayOf(conversation, seq.toString())).use {
            return if (it.moveToFirst()) it.toKnown() else null
        }
    }

    private fun after(conversation: String, seq: Double) = neighbour(
        conversation,
        "SELECT id, seq, ts FROM messages WHERE conversation=? AND seq>? ORDER BY seq LIMIT 1",
        seq,
    )

    private fun before(conversation: String, seq: Double) = neighbour(
        conversation,
        "SELECT id, seq, ts FROM messages WHERE conversation=? AND seq<? ORDER BY seq DESC LIMIT 1",
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
     * Returns the row of every bubble on screen, and how many were new.
     */
    fun saveScreen(chat: ScreenChat, now: Long): SavedScreen {
        val conversation = chat.conversation
        val items = chat.messages
        writableDatabase.beginTransaction()
        try {
            val keys = ArrayList<String>(items.size)
            val found = ArrayList<Known?>(items.size)
            val used = HashSet<Long>()
            val planned = HashMap<String, Int>()
            var above: Double? = null
            items.forEachIndexed { index, item ->
                val key = ChatParser.screenKey(conversation, item)
                val rows = occurrences(key)
                val free = rows.filter { it.id !in used }
                val mark = above
                var match = if (mark != null) {
                    free.lastOrNull { it.seq > mark } ?: free.firstOrNull()
                } else {
                    val next = items.getOrNull(index + 1)?.let { occurrences(ChatParser.screenKey(conversation, it)) }.orEmpty()
                    free.firstOrNull { row -> next.any { it.seq > row.seq } } ?: free.firstOrNull()
                }
                // The same two lines can be said again later. Right after a new bubble, a row older than
                // the bubbles above is that repeat rather than a re-read, so it must not hide the message.
                if (match != null && mark != null && found.lastOrNull() == null && match.seq <= mark) match = null
                if (rows.isEmpty()) match = claimNotification(conversation, item, key, above, now)
                found.add(match)
                if (match != null) {
                    keys.add(key)
                    used.add(match.id)
                    above = match.seq
                } else {
                    val occurrence = rows.size + (planned[key] ?: 0)
                    keys.add(if (occurrence == 0) key else key + REPEAT_MARK + occurrence)
                    planned[key] = (planned[key] ?: 0) + 1
                }
            }
            var start = 0
            while (start < items.size) {
                if (found[start] != null) {
                    start++
                    continue
                }
                var end = start
                while (end < items.size && found[end] == null) end++
                saveRun(
                    conversation,
                    items.subList(start, end),
                    keys.subList(start, end),
                    found.getOrNull(start - 1),
                    found.getOrNull(end),
                    now,
                )
                start = end
            }
            val ids = HashMap<ScreenMessage, Long>()
            val visible = ArrayList<Long>(items.size)
            items.forEachIndexed { i, item ->
                (found[i]?.id ?: idForKey(keys[i]))?.let {
                    ids[item] = it
                    visible.add(it)
                }
            }
            keepScreenOrder(conversation, visible)
            writableDatabase.setTransactionSuccessful()
            return SavedScreen(ids, found.count { it == null })
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun seqOf(id: Long): Double {
        readableDatabase.rawQuery("SELECT seq FROM messages WHERE id=?", arrayOf(id.toString())).use {
            return if (it.moveToFirst()) it.getDouble(0) else 0.0
        }
    }

    /**
     * Rows are stamped when read, while notification rows carry the send time, so a message read late
     * can sort after a reply to it. The screen shows the true order; rows that disagree move to match.
     */
    private fun keepScreenOrder(conversation: String, visible: List<Long>) {
        if (visible.isEmpty()) return
        val onScreen = visible.joinToString(",")
        var previous = seqOf(visible.first())
        for (id in visible.drop(1)) {
            val seq = seqOf(id)
            if (seq > previous) {
                previous = seq
                continue
            }
            val ceiling = readableDatabase.rawQuery(
                "SELECT MIN(seq) FROM messages WHERE conversation=? AND seq>? AND id NOT IN ($onScreen)",
                arrayOf(conversation, previous.toString()),
            ).use { if (it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else null }
            val moved = if (ceiling == null) previous + 1 else maxOf((previous + ceiling) / 2, Math.nextUp(previous))
            val values = ContentValues().apply { put("seq", moved) }
            writableDatabase.update("messages", values, "id=?", arrayOf(id.toString()))
            previous = moved
        }
    }

    private fun threadsNamed(title: String): List<String> {
        readableDatabase.rawQuery(
            "SELECT DISTINCT conversation FROM messages WHERE conversation=? OR (conversation>=? AND conversation<?)",
            arrayOf(title, "$title (", "$title )"),
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            return out.filter { ChatParser.threadNumber(title, it) != null }
                .sortedBy { ChatParser.threadNumber(title, it) }
        }
    }

    private fun nextThread(title: String, taken: List<String>): String {
        val last = taken.maxOfOrNull { ChatParser.threadNumber(title, it) ?: 1 } ?: 0
        return ChatParser.threadName(title, last + 1)
    }

    private fun overlap(conversation: String, chat: ScreenChat, now: Long): Int {
        val since = now - TimeUnit.DAYS.toMillis(NOTIFICATION_MATCH_DAYS)
        return chat.messages.count { item ->
            occurrences(ChatParser.screenKey(conversation, item)).isNotEmpty() ||
                readableDatabase.rawQuery(
                    """SELECT 1 FROM messages WHERE conversation=? AND body=? AND outgoing=?
                       AND source='notification' AND screen_key IS NULL AND ts>=? LIMIT 1""",
                    arrayOf(conversation, item.text, if (item.outgoing) "1" else "0", since.toString()),
                ).use { it.moveToFirst() }
        }
    }

    /**
     * Picks which chat a screen belongs to when it is opened: the stored chat whose bubbles are showing.
     * A null name means none of them are, which is either many new messages or a namesake; the caller
     * settles that by comparing avatars. Several senders under one title is one chat, not a namesake.
     */
    fun findConversation(chat: ScreenChat, now: Long): ThreadMatch {
        val title = chat.conversation
        val known = threadsNamed(title)
        val unused = nextThread(title, known)
        if (known.isEmpty()) return ThreadMatch(title, known, unused)
        if (chat.messages.isEmpty()) return ThreadMatch(known.first(), known, unused)
        val best = known.map { it to overlap(it, chat, now) }.maxBy { it.second }
        return when {
            best.second > 0 -> ThreadMatch(best.first, known, unused)
            ChatParser.isGroup(chat.messages) -> ThreadMatch(known.first(), known, unused)
            else -> ThreadMatch(null, known, unused)
        }
    }

    /** Notifications do carry a per-chat id, so namesakes stay apart even before either chat is opened. */
    fun threadForNotification(title: String, shortcut: String?): String {
        if (shortcut == null) return threadsNamed(title).firstOrNull() ?: title
        readableDatabase.rawQuery(
            "SELECT conversation FROM threads WHERE title=? AND shortcut=?",
            arrayOf(title, shortcut),
        ).use { if (it.moveToFirst()) return it.getString(0) }
        val linked = ArrayList<String>()
        readableDatabase.rawQuery("SELECT conversation FROM threads WHERE title=?", arrayOf(title)).use { c ->
            while (c.moveToNext()) linked.add(c.getString(0))
        }
        val known = threadsNamed(title)
        val conversation = known.firstOrNull { it !in linked } ?: nextThread(title, known + linked)
        val values = ContentValues().apply {
            put("title", title)
            put("shortcut", shortcut)
            put("conversation", conversation)
        }
        writableDatabase.insertWithOnConflict("threads", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return conversation
    }

    /** One-to-one chats show a nickname on bubbles and the full name in notifications; both are one person. */
    fun isOneToOne(conversation: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT sender) FROM messages WHERE conversation=? AND outgoing=0 AND sender<>?",
            arrayOf(conversation, conversation),
        ).use { return !it.moveToFirst() || it.getInt(0) <= 1 }
    }

    private fun saveRun(
        conversation: String,
        run: List<ScreenMessage>,
        keys: List<String>,
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
            // Repeated text can match rows out of order, leaving the "lower" neighbour above the upper one.
            val floor = lower?.seq?.takeIf { it < upper.seq }
                ?: before(conversation, upper.seq)?.seq
                ?: (upper.seq - ONE_MINUTE_MS * (run.size + 1))
            seqs = ChatParser.between(floor, upper.seq, run.size)
            time = upper.ts
        }
        run.forEachIndexed { i, item ->
            val message = Message(conversation, item.sender, item.outgoing, item.text, time, "screen", item.kind)
            insert(message, keys[i], seqs[i])
        }
    }

    fun idForKey(key: String): Long? {
        readableDatabase.rawQuery("SELECT id FROM messages WHERE dedupe_key=?", arrayOf(key)).use {
            return if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    fun missingMedia(id: Long): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM messages WHERE id=? AND media IS NULL", arrayOf(id.toString())).use {
            return it.moveToFirst()
        }
    }

    fun setMedia(id: Long, file: String) {
        val values = ContentValues().apply { put("media", file) }
        writableDatabase.update("messages", values, "id=?", arrayOf(id.toString()))
    }

    fun insert(message: Message, key: String, seq: Double = message.timestamp.toDouble()): Boolean {
        val values = ContentValues().apply {
            put("seq", seq)
            put("kind", message.kind)
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
        writableDatabase.delete("threads", null, null)
    }

    /** Returns the attached files, which the caller removes from storage. */
    fun deleteConversation(conversation: String): List<String> {
        val files = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT media FROM messages WHERE conversation=? AND media IS NOT NULL",
            arrayOf(conversation),
        ).use { c ->
            while (c.moveToNext()) files.add(c.getString(0))
        }
        writableDatabase.delete("messages", "conversation=?", arrayOf(conversation))
        writableDatabase.delete("threads", "conversation=?", arrayOf(conversation))
        return files
    }

    fun exportJson(out: Writer): Int {
        var count = 0
        JsonWriter(out).use { json ->
            json.setIndent(" ")
            json.beginArray()
            readableDatabase.rawQuery(
                "SELECT conversation, sender, outgoing, body, ts, source, kind, media FROM messages ORDER BY conversation, seq, id",
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
                    json.name("kind").value(if (c.isNull(6)) null else c.getString(6))
                    json.name("media").value(if (c.isNull(7)) null else c.getString(7))
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
            "SELECT conversation, sender, outgoing, body, ts, source, kind, media FROM messages $clause",
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
                        if (c.isNull(6)) null else c.getString(6),
                        if (c.isNull(7)) null else c.getString(7),
                    ),
                )
            }
            return out
        }
    }

    companion object {
        private const val NOTIFICATION_MATCH_DAYS = 7L
        private const val ONE_MINUTE_MS = 60_000.0
        private const val REPEAT_MARK = '\u0003'

        @Volatile
        private var instance: MessageStore? = null

        fun get(context: Context): MessageStore =
            instance ?: synchronized(this) {
                instance ?: MessageStore(context.applicationContext).also { instance = it }
            }
    }
}
