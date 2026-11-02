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
