package com.lately.msgarchive

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/**
 * Runs the ordering logic against real SQLite, which JVM tests cannot do. A bare Instrumentation
 * keeps AndroidX out of the project. It wipes the archive, so run it on an emulator only:
 * adb shell am instrument -w com.lately.msgarchive.test/com.lately.msgarchive.StoreCheck
 */
class StoreCheck : Instrumentation() {
    private val chat = "A"
    private val base = 1_800_000_000_000L

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val failures = ArrayList<String>()
        val store = MessageStore.get(targetContext)
        fun bodies() = store.messages(chat).map { it.body }
        fun expect(label: String, want: List<String>) {
            if (bodies() != want) failures.add("$label: ${bodies()}")
        }
        fun screen(now: Long, vararg lines: Pair<String, String>) {
            val messages = lines.map { (previous, text) -> ScreenMessage(text, false, previous, "B") }
            store.saveScreen(ScreenChat(chat, messages), now)
        }

        store.deleteAll()
        screen(base, "b" to "c", "c" to "d", "d" to "e")
        expect("first scan", listOf("c", "d", "e"))

        screen(base + 1000, "b" to "c", "c" to "d", "d" to "e")
        expect("re-read adds nothing", listOf("c", "d", "e"))

        screen(base + 2000, "a" to "b", "b" to "c", "c" to "d")
        expect("scrolling back goes above", listOf("b", "c", "d", "e"))

        screen(base + 3000, "z" to "a", "a" to "b")
        expect("scrolling back further", listOf("a", "b", "c", "d", "e"))

        screen(base + 4000, "d" to "e", "e" to "f", "f" to "g")
        expect("new messages go below", listOf("a", "b", "c", "d", "e", "f", "g"))

        store.insert(Message(chat, "B", false, "h", base + 5000, "notification"), "n1")
        screen(base + 6000, "f" to "g", "g" to "h", "h" to "i")
        expect("notification is not doubled", listOf("a", "b", "c", "d", "e", "f", "g", "h", "i"))

        screen(base + 7000, "h" to "i", "i" to "ok", "ok" to "ok")
        expect("repeated text is kept", listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "ok", "ok"))

        val last = store.conversations().single()
        if (last.lastBody != "ok" || last.count != 11) failures.add("summary: $last")

        // An old notification's "ok" must not swallow a later "ok" said with the chat open.
        store.deleteAll()
        store.insert(Message(chat, "B", false, "ok", base, "notification"), "n2")
        screen(base + 1000, "a" to "ok", "ok" to "b", "b" to "c")
        expect("notification stands for its bubble", listOf("ok", "b", "c"))
        screen(base + 2000, "b" to "c", "c" to "ok", "ok" to "d")
        expect("later ok is saved", listOf("ok", "b", "c", "ok", "d"))
        screen(base + 3000, "b" to "c", "c" to "ok", "ok" to "d")
        expect("re-read of claimed row adds nothing", listOf("ok", "b", "c", "ok", "d"))
        screen(base + 4000, "a" to "ok", "ok" to "b")
        expect("claimed row is still recognised", listOf("ok", "b", "c", "ok", "d"))

        // The same two lines said again must be saved, and only once.
        store.deleteAll()
        screen(base, "x" to "1", "1" to "2")
        screen(base + 1000, "x" to "1", "1" to "2", "2" to "m", "m" to "n")
        expect("before the repeat", listOf("1", "2", "m", "n"))
        screen(base + 2000, "m" to "n", "n" to "1", "1" to "2")
        expect("repeated pair is new", listOf("1", "2", "m", "n", "1", "2"))
        screen(base + 3000, "m" to "n", "n" to "1", "1" to "2")
        screen(base + 4000, "n" to "1", "1" to "2")
        expect("repeated pair reread", listOf("1", "2", "m", "n", "1", "2"))
        screen(base + 5000, "n" to "1", "1" to "2", "2" to "z")
        expect("message after repeat goes last", listOf("1", "2", "m", "n", "1", "2", "z"))

        store.deleteAll()
        screen(base, "x" to "1", "1" to "2", "2" to "1", "1" to "2")
        expect("pair twice on one screen", listOf("1", "2", "1", "2"))
        screen(base + 1000, "x" to "1", "1" to "2", "2" to "1", "1" to "2")
        expect("pair twice reread", listOf("1", "2", "1", "2"))

        // The screen's order wins over stamps: a late read must not sort after the reply to it.
        store.deleteAll()
        store.insert(Message(chat, "B", false, "reply", base + 100, "notification"), "n3")
        screen(base + 200, "x" to "said")
        screen(base + 300, "x" to "said", "said" to "reply", "reply" to "next")
        expect("late read keeps screen order", listOf("said", "reply", "next"))
        screen(base + 400, "x" to "said", "said" to "reply", "reply" to "next")
        expect("order is stable", listOf("said", "reply", "next"))

        // An old stretch containing a line that repeats later must stay where it is.
        store.deleteAll()
        screen(base, "x" to "1", "1" to "2", "2" to "m", "m" to "n")
        screen(base + 1000, "m" to "n", "n" to "1", "1" to "2")
        screen(base + 2000, "1" to "2", "2" to "m")
        expect("old stretch is not reordered", listOf("1", "2", "m", "n", "1", "2"))

        // Namesakes: the first chat keeps the title, an unseen one gets a number, re-opening finds its own.
        store.deleteAll()
        fun chatOf(vararg lines: Pair<String, String>) =
            ScreenChat("Lan", lines.map { (previous, text) -> ScreenMessage(text, false, previous, "Lan") })
        val first = chatOf("a" to "b", "b" to "c")
        val second = chatOf("p" to "q", "q" to "r")
        if (store.findConversation(first, base).name != "Lan") failures.add("first namesake")
        store.saveScreen(first, base)
        val unknown = store.findConversation(second, base + 1000)
        if (unknown.name != null || unknown.unused != "Lan (2)") failures.add("second namesake: $unknown")
        store.saveScreen(second.copy(conversation = unknown.unused), base + 1000)
        if (store.findConversation(first, base + 2000).name != "Lan") failures.add("first reopened")
        if (store.findConversation(second, base + 2000).name != "Lan (2)") failures.add("second reopened")
        val busy = ScreenChat("Lan", listOf(ScreenMessage("u", false, "t", "A"), ScreenMessage("v", false, "u", "B")))
        if (store.findConversation(busy, base + 3000).name != "Lan") failures.add("group split")

        store.deleteAll()
        val one = store.threadForNotification("Lan", "s1")
        store.insert(Message(one, one, false, "m1", base, "notification"), "s1a")
        val two = store.threadForNotification("Lan", "s2")
        store.insert(Message(two, two, false, "m2", base, "notification"), "s2a")
        if (one != "Lan" || two != "Lan (2)") failures.add("notification threads: $one, $two")
        if (store.threadForNotification("Lan", "s1") != "Lan") failures.add("notification thread not stable")
        if (store.threadForNotification("Khác", "s1") != "Khác") failures.add("thread id leaked across titles")
        store.deleteConversation("Lan (2)")
        if (store.conversations().map { it.name } != listOf("Lan")) failures.add("delete one chat")

        // Hostile text must survive storage and search unchanged.
        store.deleteAll()
        val hostile = listOf("'; DROP TABLE messages;--", "100%", "a_b", "back\\slash", "😀‮gnp.exe", "dòng\nmới", "x".repeat(200_000))
        hostile.forEachIndexed { i, text ->
            store.insert(Message("Tên 'lạ\" %_/../x", "B", false, text, base + i, "notification"), "h$i")
        }
        if (store.messages("Tên 'lạ\" %_/../x").map { it.body } != hostile) failures.add("hostile text changed")
        if (store.search("100%").size != 1) failures.add("percent is a wildcard")
        if (store.search("a_b").size != 1) failures.add("underscore is a wildcard")
        if (store.search("back\\slash").size != 1) failures.add("backslash search")

        // Capture threads write while the screen reads.
        store.deleteAll()
        val threads = List(4) { t ->
            Thread {
                try {
                    repeat(200) { i ->
                        store.saveScreen(ScreenChat("C$t", listOf(ScreenMessage("m$i", false, "m${i - 1}", "B"))), base + i)
                        store.conversations()
                    }
                } catch (e: RuntimeException) {
                    synchronized(failures) { failures.add("thread: $e") }
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        if (store.conversations().sumOf { it.count } != 800) failures.add("concurrent count wrong")

        store.deleteAll()
        val result = Bundle().apply {
            putString(REPORT_KEY_STREAMRESULT, if (failures.isEmpty()) "STORE OK\n" else failures.joinToString("\n", postfix = "\n"))
        }
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private companion object {
        const val REPORT_KEY_STREAMRESULT = "stream"
    }
}
