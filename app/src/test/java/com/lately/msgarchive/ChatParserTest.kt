package com.lately.msgarchive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatParserTest {
    private val width = 1080
    private val height = 2400
    private val title = ScreenNode("Lan Anh", 200, 80, 600, 150)
    private val composer = ScreenNode("Nhắn tin", 200, 2200, 900, 2300, editable = true)

    private fun incoming(text: String, top: Int) = ScreenNode(text, 130, top, 700, top + 100)
    private fun outgoing(text: String, top: Int) = ScreenNode(text, 500, top, 1040, top + 100)

    @Test
    fun chatListWithoutComposerIsIgnored() {
        assertNull(ChatParser.parse(listOf(title, incoming("hi", 500)), width, height))
    }

    @Test
    fun directionComesFromTheCloserScreenEdge() {
        val nodes = listOf(title, composer, incoming("đầu", 400), outgoing("chào", 600), incoming("ừ", 800))
        val chat = ChatParser.parse(nodes, width, height)!!
        assertEquals("Lan Anh", chat.conversation)
        assertEquals(
            listOf(ScreenMessage("chào", true, "đầu"), ScreenMessage("ừ", false, "chào")),
            chat.messages,
        )
    }

    @Test
    fun timestampsReceiptsAndCentredNoticesAreDropped() {
        val nodes = listOf(
            title,
            composer,
            incoming("đầu", 400),
            ScreenNode("14:05", 480, 560, 600, 590),
            ScreenNode("Lan Anh đã đặt biệt danh", 200, 620, 880, 660),
            outgoing("chào", 700),
            outgoing("Đã xem", 820),
        )
        assertEquals(listOf("chào"), ChatParser.parse(nodes, width, height)!!.messages.map { it.text })
    }

    @Test
    fun nestedNodesWithTheSameTextCountOnce() {
        val nodes = listOf(title, composer, incoming("đầu", 400), outgoing("chào", 600), outgoing("chào", 610))
        assertEquals(1, ChatParser.parse(nodes, width, height)!!.messages.size)
    }

    @Test
    fun composerTextIsNotAMessage() {
        val draft = composer.copy(text = "đang gõ dở")
        val nodes = listOf(title, draft, incoming("đầu", 400), outgoing("chào", 600))
        assertEquals(listOf("chào"), ChatParser.parse(nodes, width, height)!!.messages.map { it.text })
    }

    @Test
    fun repeatedTextIsDistinctWhenItsPredecessorDiffers() {
        val first = ChatParser.screenKey("A", ScreenMessage("ok", true, "x"))
        val again = ChatParser.screenKey("A", ScreenMessage("ok", true, "x"))
        val later = ChatParser.screenKey("A", ScreenMessage("ok", true, "y"))
        assertEquals(first, again)
        assertNotEquals(first, later)
    }
}
