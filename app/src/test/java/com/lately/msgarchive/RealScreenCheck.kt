package com.lately.msgarchive

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Runs the parser on a screen saved from a phone with `uiautomator dump`. The file holds private chat
 * text, so it is never part of the repository: the test only runs when LUUTIN_SCREEN names one.
 */
class RealScreenCheck {
    private fun nodes(file: File): Pair<List<ScreenNode>, Pair<Int, Int>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = ArrayList<ScreenNode>()
        var width = 0
        var height = 0
        val all = doc.getElementsByTagName("node")
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            val b = Regex("""\d+""").findAll(e.getAttribute("bounds")).map { it.value.toInt() }.toList()
            width = maxOf(width, b[2])
            height = maxOf(height, b[3])
            val text = e.getAttribute("text").ifBlank { e.getAttribute("content-desc") }
            val cls = e.getAttribute("class")
            val list = "RecyclerView" in cls || "ListView" in cls
            if (list) {
                out.add(ScreenNode("", b[0], b[1], b[2], b[3], className = cls, canScrollUp = e.getAttribute("scrollable") == "true"))
            }
            val editable = cls.endsWith("EditText")
            if (text.isNotBlank() || editable) {
                out.add(ScreenNode(text, b[0], b[1], b[2], b[3], editable, cls, clickable = e.getAttribute("clickable") == "true"))
            }
        }
        return out to (width to height)
    }

    @Test
    fun parseSavedScreen() {
        val path = System.getenv("LUUTIN_SCREEN")
        assumeTrue(path != null && File(path).exists())
        val (nodes, size) = nodes(File(path!!))
        val chat = ChatParser.parse(nodes, size.first, size.second)
        val report = StringBuilder("size=$size nodes=${nodes.size}\n")
        if (chat == null) {
            report.append("NOT A CHAT\n")
        } else {
            report.append("title len=${chat.conversation.length}\n")
            chat.messages.forEach { m ->
                report.append("${if (m.outgoing) "OUT" else "in "} kind=${m.kind} len=${m.text.length} prevLen=${m.previousText.length}\n")
            }
            report.append("media=${chat.media.size} avatars=${chat.avatars.size}\n")
        }
        File(System.getenv("LUUTIN_REPORT") ?: "$path.report.txt").writeText(report.toString())
    }
}
