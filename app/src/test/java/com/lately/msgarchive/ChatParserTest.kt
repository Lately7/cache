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
            listOf(ScreenMessage("chào", true, "đầu"), ScreenMessage("ừ", false, "chào", "Lan Anh")),
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

    private val suffix = ", double tap to see sent/receive date and time, double tap and hold to react on message"

    @Test
    fun realMessengerLabelsAreSplitIntoSenderAndText() {
        val header = ScreenNode("Long Vu, Thread details", 200, 80, 600, 150)
        val nodes = listOf(
            header,
            composer,
            incoming("Hien, Hok lạnh, ha?$suffix", 400),
            ScreenNode("Open Thd Hien's profile", 30, 500, 110, 580),
            outgoing("Minh, can co ao len ha$suffix", 600),
            outgoing("Add custom reaction", 720),
            incoming("Hien, Xem voi$suffix", 800),
        )
        val chat = ChatParser.parse(nodes, width, height)!!
        assertEquals("Long Vu", chat.conversation)
        assertEquals(
            listOf(
                ScreenMessage("can co ao len ha", true, "Hok lạnh, ha?", null),
                ScreenMessage("Xem voi", false, "can co ao len ha", "Hien"),
            ),
            chat.messages,
        )
    }

    @Test
    fun avatarBelongsToTheNameOnTheBubbleAboveIt() {
        val nodes = listOf(
            title,
            composer,
            incoming("Hien, chào$suffix", 400),
            ScreenNode("Open Thd Hien's profile", 30, 500, 110, 580),
        )
        assertEquals("Hien", ChatParser.parse(nodes, width, height)!!.avatars.single().owner)
    }

    @Test
    fun tallShortLabelIsAnAttachmentButPlainTextIsNot() {
        val photo = ScreenNode("Hien, Photo$suffix", 130, 700, 700, 1300)
        val nodes = listOf(title, composer, incoming("Hien, đầu$suffix", 400), incoming("Hien, gửi photo nhé$suffix", 550), photo)
        val chat = ChatParser.parse(nodes, width, height)!!
        assertEquals(listOf(null, ChatParser.KIND_IMAGE), chat.messages.map { it.kind })
        assertEquals(photo, chat.media.single().node)
    }

    @Test
    fun fileIsRecognisedByNameWithoutBeingTall() {
        val nodes = listOf(title, composer, incoming("Hien, đầu$suffix", 400), incoming("Hien, baocao.pdf$suffix", 550))
        val chat = ChatParser.parse(nodes, width, height)!!
        assertEquals(ChatParser.KIND_FILE, chat.messages.single().kind)
        assertEquals(0, chat.media.size)
    }

    @Test
    fun senderNamedLikeAStatusLineKeepsTheirMessages() {
        val nodes = listOf(title, composer, incoming("Active, đầu$suffix", 400), incoming("Seen, chào$suffix", 550))
        assertEquals(listOf("chào"), ChatParser.parse(nodes, width, height)!!.messages.map { it.text })
    }

    @Test
    fun threeIdenticalMessagesGetThreeIdentities() {
        val nodes = listOf(
            title,
            composer,
            incoming("Hien, đầu$suffix", 400),
            incoming("Hien, ok$suffix", 520),
            incoming("Hien, ok$suffix", 640),
            incoming("Hien, ok$suffix", 760),
        )
        val keys = ChatParser.parse(nodes, width, height)!!.messages.map { ChatParser.screenKey("A", it) }
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun linksAndPhrasesAreNotFiles() {
        for (text in listOf("vnexpress.net", "gửi file nhé", "ok.hihi")) {
            val nodes = listOf(title, composer, incoming("Hien, đầu$suffix", 400), incoming("Hien, $text$suffix", 550))
            assertNull(text, ChatParser.parse(nodes, width, height)!!.messages.single().kind)
        }
    }

    @Test
    fun forgedLabelInsideAMessageStaysPartOfTheText() {
        val forged = "x$suffix"
        val nodes = listOf(title, composer, incoming("Hien, đầu$suffix", 400), incoming("Hien, $forged$suffix", 550))
        val message = ChatParser.parse(nodes, width, height)!!.messages.single()
        assertEquals("Hien", message.sender)
        assertEquals(forged, message.text)
    }

    @Test
    fun blankChatTitleFallsBackToTheSender() {
        val nodes = listOf(ScreenNode(", Thread details", 200, 80, 600, 150), composer, incoming("Hien, a$suffix", 400))
        assertEquals("Hien", ChatParser.parse(nodes, width, height)!!.conversation)
    }

    @Test(timeout = 2000)
    fun hugeLabelsParseQuickly() {
        val huge = "a, ".repeat(50_000)
        val nodes = listOf(title, composer, incoming("Hien, đầu$suffix", 400), incoming("Hien, $huge$suffix", 550))
        assertEquals(1, ChatParser.parse(nodes, width, height)!!.messages.size)
    }

    @Test
    fun keyboardOpenStillCountsAsAChat() {
        val raised = composer.copy(top = 1350, bottom = 1450)
        val nodes = listOf(title, raised, incoming("đầu", 400), outgoing("chào", 600))
        assertEquals(listOf("chào"), ChatParser.parse(nodes, width, height)!!.messages.map { it.text })
    }

    @Test
    fun chatListSearchBoxIsNotAComposer() {
        val searchBox = ScreenNode("Search", 40, 200, 1040, 300, editable = true)
        assertNull(ChatParser.parse(listOf(title, searchBox, incoming("hi", 500)), width, height))
    }

    @Test
    fun longLabelledBubbleInTheMiddleOfTheScreenIsKept() {
        val long = ScreenNode("Hien, một tin rất dài$suffix", 130, 550, 950, 900)
        val nodes = listOf(title, composer, incoming("Hien, đầu$suffix", 400), long)
        assertEquals(listOf("một tin rất dài"), ChatParser.parse(nodes, width, height)!!.messages.map { it.text })
    }

    @Test
    fun chatWithoutComposerStillSaves() {
        val header = ScreenNode("Long Vu, Thread details", 200, 80, 600, 150)
        val nodes = listOf(header, incoming("Hien, đầu$suffix", 400), incoming("Hien, chào$suffix", 550))
        val chat = ChatParser.parse(nodes, width, height)!!
        assertEquals("Long Vu", chat.conversation)
        assertEquals(listOf("chào"), chat.messages.map { it.text })
    }

    @Test
    fun threadDetailsWinsOverAnythingHigherInTheHeader() {
        val call = ScreenNode("Start audio call", 800, 60, 900, 140)
        val header = ScreenNode("Long Vu, Active 5m ago, Thread details", 200, 80, 600, 150)
        val nodes = listOf(call, header, composer, incoming("Hien, đầu$suffix", 400), incoming("Hien, chào$suffix", 550))
        assertEquals("Long Vu", ChatParser.parse(nodes, width, height)!!.conversation)
    }

    @Test
    fun missingTitleFallsBackToTheSender() {
        val nodes = listOf(composer, incoming("Hien, đầu$suffix", 400), incoming("Hien, chào$suffix", 550))
        assertEquals("Hien", ChatParser.parse(nodes, width, height)!!.conversation)
    }

    @Test
    fun bubbleReachingUnderTheComposerOrHeaderIsKept() {
        val header = ScreenNode("Long Vu, Thread details", 200, 80, 600, 150)
        val nodes = listOf(
            header,
            composer,
            ScreenNode("Hien, đầu$suffix", 130, 160, 700, 400),
            ScreenNode("Hien, cuối$suffix", 130, 2100, 700, 2250),
        )
        assertEquals(listOf("cuối"), ChatParser.parse(nodes, width, height)!!.messages.map { it.text })
    }

    @Test
    fun scrolledBackMessagesFitBetweenTheirNeighbours() {
        assertEquals(listOf(11.0, 12.0, 13.0), ChatParser.between(10.0, 14.0, 3))
    }

    @Test
    fun oldRowsCanBeCleaned() {
        assertEquals(true, ChatParser.isNoise("Open Thd Hien's profile"))
        assertEquals(true, ChatParser.isNoise("Add custom reaction"))
        assertEquals(MarkedBubble("Minh", "có :3"), ChatParser.parseMarked("Minh, có :3$suffix"))
        assertEquals("Long Vu", ChatParser.cleanTitle("Long Vu, Thread details"))
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
