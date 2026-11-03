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
    fun blankChatTitleIsNotAChat() {
        val nodes = listOf(ScreenNode(", Thread details", 200, 80, 600, 150), composer, incoming("Hien, a$suffix", 400))
        assertNull(ChatParser.parse(nodes, width, height))
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
    fun bubblesWithoutATitleAreNotFiledUnderTheSender() {
        val nodes = listOf(composer, incoming("Hien, đầu$suffix", 400), incoming("Hien, chào$suffix", 550))
        assertNull(ChatParser.parse(nodes, width, height))
    }

    @Test
    fun twoHeadersMeanATransition() {
        val a = ScreenNode("Long Vu, Thread details", 200, 80, 600, 150)
        val b = ScreenNode("Lan Anh, Thread details", 300, 80, 700, 150)
        assertNull(ChatParser.parse(listOf(a, b, composer, incoming("Hien, đầu$suffix", 400)), width, height))
    }

    @Test
    fun hiddenChatLendsNeitherTitleNorBubbles() {
        val hidden = ScreenNode("Long Vu, Thread details", 200, 80, 600, 150, visible = false)
        val shown = ScreenNode("Lan Anh, Thread details", 200, 80, 600, 150)
        val nodes = listOf(
            hidden,
            shown,
            composer,
            incoming("Hien, cũ$suffix", 300).copy(visible = false),
            incoming("Lan, đầu$suffix", 400),
            incoming("Lan, chào$suffix", 550),
        )
        val chat = ChatParser.parse(nodes, width, height)!!
        assertEquals("Lan Anh", chat.conversation)
        assertEquals(listOf("chào"), chat.messages.map { it.text })
    }

    @Test
    fun callButtonIsNotATitle() {
        val call = ScreenNode("Start audio call", 800, 60, 900, 140, className = "android.widget.Button")
        assertNull(ChatParser.parse(listOf(call, composer, incoming("đầu", 400)), width, height))
    }

    @Test
    fun oldBubblesUnderANewTitleAreStale() {
        assertEquals(true, ChatParser.mostlySame(setOf("a", "b", "c"), setOf("a", "b", "x")))
        assertEquals(false, ChatParser.mostlySame(setOf("a", "b", "c"), setOf("a", "y", "x")))
        assertEquals(false, ChatParser.mostlySame(emptySet(), emptySet()))
    }

    @Test
    fun sameFaceToleratesScalingNotAnotherPerson() {
        val photo = intArrayOf(0xFF8040A0.toInt(), 0xFF204060.toInt())
        val rescaled = intArrayOf(0xFF8A4AA8.toInt(), 0xFF264866.toInt())
        val other = intArrayOf(0xFF10E020.toInt(), 0xFFE0C010.toInt())
        assertEquals(true, ChatParser.sameFace(photo, rescaled))
        assertEquals(false, ChatParser.sameFace(photo, other))
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

    private val black = 0xFF000000.toInt()
    private val face = listOf(0xFFC08060.toInt(), 0xFF302010.toInt(), 0xFF804020.toInt())

    @Test
    fun circleOnPlainBackgroundIsAnAvatar() {
        assertEquals(true, ChatParser.looksLikeAvatar(List(4) { black }, face))
    }

    @Test
    fun cropThatMissedTheCircleIsRejected() {
        val shifted = listOf(black, 0xFFC08060.toInt(), black, 0xFF804020.toInt())
        assertEquals(false, ChatParser.looksLikeAvatar(shifted, face))
    }

    @Test
    fun loadingPlaceholderIsRejected() {
        assertEquals(false, ChatParser.looksLikeAvatar(List(4) { black }, List(9) { 0xFF2A2030.toInt() }))
    }

    @Test
    fun numberedThreadsBelongToTheirTitle() {
        assertEquals("Lan", ChatParser.threadName("Lan", 1))
        assertEquals("Lan (2)", ChatParser.threadName("Lan", 2))
        assertEquals(1, ChatParser.threadNumber("Lan", "Lan"))
        assertEquals(3, ChatParser.threadNumber("Lan", "Lan (3)"))
        assertNull(ChatParser.threadNumber("Lan", "Lan (bạn)"))
        assertNull(ChatParser.threadNumber("Lan", "Lan Anh"))
    }

    /** Layout taken from a real Messenger 586 chat on the iQOO; names and text are replaced. */
    private val realWidth = 1260
    private val realHeight = 2800
    private fun real(text: String, l: Int, t: Int, r: Int, b: Int, clickable: Boolean = true, editable: Boolean = false) =
        ScreenNode(text, l, t, r, b, editable = editable, clickable = clickable)

    private val realChat = listOf(
        real("Back", 12, 129, 159, 276),
        real("An Binh, Thread details", 171, 129, 790, 276),
        real("An Binh", 318, 157, 790, 210, clickable = false),
        real("Audio call", 807, 129, 954, 276),
        real("Thread details", 1101, 129, 1248, 276),
        real("Binh, hello$suffix", 160, 303, 273, 413),
        real("Binh, cuoi$suffix", 160, 419, 279, 529),
        real("Open An Binh's profile", 37, 443, 123, 529),
        real("😂", 160, 572, 344, 714),
        real("Open An Binh's profile", 37, 628, 123, 714),
        real("FRI AT 09:13", 549, 757, 711, 796, clickable = false),
        real("Bai bao hay, vnexpress.net", 160, 827, 1027, 1089),
        real("xem cai nay https://x.vn/a", 197, 852, 828, 905, clickable = false),
        real("Bai bao hay", 160, 967, 473, 1013, clickable = false),
        real("vnexpress.net", 160, 1013, 435, 1052, clickable = false),
        real("Preview link x sent by An Binh on Friday at 09:13", 1064, 903, 1174, 1013),
        real("Clip, youtube.com", 405, 1202, 1235, 1510),
        real("day ne", 442, 1227, 1198, 1326, clickable = false),
        real("Delivered Fri", 1031, 1522, 1198, 1561, clickable = false),
        real("❤ 1", 170, 1100, 260, 1150),
        real("Type a message", 488, 1614, 1039, 1743, editable = true),
        real("Open camera", 122, 1587, 244, 1771),
    )

    @Test
    fun photosInARealChatAreKept() {
        // From a real Messenger 586 chat: two sent photos around one text bubble, plus the preview icon
        // and the "Sent" receipt that must not be taken for messages.
        val nodes = listOf(
            real("Minh, Thread details", 171, 129, 406, 276),
            real("Minh", 318, 176, 406, 229, clickable = false),
            real("Preview photo sent by Minh on Monday at 10:13", 647, 619, 757, 729),
            real("Sent photo message", 794, 288, 1235, 1164),
            real("Minh, dep qua$suffix", 845, 1207, 1235, 1317, clickable = false),
            real("Preview photo sent by Minh on Monday", 647, 1865, 757, 1975),
            real("Sent photo message", 794, 1430, 1235, 2410),
            real("Sent 2m ago", 928, 2422, 1198, 2461, clickable = false),
            real("Type a message", 488, 2514, 1039, 2643, editable = true),
        )
        val chat = ChatParser.parse(nodes, realWidth, realHeight)!!
        assertEquals(listOf("dep qua", "Sent photo message"), chat.messages.map { it.text })
        assertEquals(listOf(null, ChatParser.KIND_IMAGE), chat.messages.map { it.kind })
        assertEquals(1, chat.media.size)
    }

    @Test
    fun linkCardsAndEmojiInARealChatAreKept() {
        val chat = ChatParser.parse(realChat, realWidth, realHeight)!!
        assertEquals("An Binh", chat.conversation)
        assertEquals(
            listOf(
                "cuoi",
                "😂",
                "xem cai nay https://x.vn/a\nBai bao hay\nvnexpress.net",
                "day ne",
            ),
            chat.messages.map { it.text },
        )
        assertEquals(listOf(false, false, false, true), chat.messages.map { it.outgoing })
        assertEquals(listOf("Binh", "Binh", "Binh", null), chat.messages.map { it.sender })
    }

    @Test
    fun linksAreFoundWithASafeScheme() {
        assertEquals("https://vnexpress.net/a", ChatParser.findLink("xem vnexpress.net/a nhé"))
        assertEquals("https://x.vn/b?c=1", ChatParser.findLink("https://x.vn/b?c=1."))
        assertEquals("https://www.abc.xyz", ChatParser.findLink("www.abc.xyz,"))
        assertEquals(null, ChatParser.findLink("ok.hihi nhé 12.5 tiếng"))
        assertEquals(null, ChatParser.findLink("javascript:alert(1)"))
    }

    private fun zaloNode(text: String, top: Int, bottom: Int) =
        ScreenNode(text, 0, top, 1260, bottom, className = "android.view.ViewGroup")

    private val zaloChat = listOf(
        ScreenNode("Nhom Ban", 173, 132, 758, 198, className = "android.widget.TextView"),
        ScreenNode("51 members", 173, 198, 375, 247, className = "android.widget.TextView"),
        zaloNode("bi che boi tin ghim 23:07", 263, 443),
        zaloNode("dong dau 23:07", 443, 685),
        zaloNode("toi gui", 685, 897),
        zaloNode("Mai da them Lan vao nhom", 897, 1059),
        zaloNode("nguoi khac 23:08", 1059, 1272),
        zaloNode("23:09", 1272, 1405),
        ScreenNode("Tin ghim", 12, 275, 1248, 440, className = "android.widget.FrameLayout"),
        ScreenNode("Tin nhan", 142, 2505, 811, 2653, editable = true, className = "android.widget.EditText"),
    )

    @Test
    fun zaloRowsLoseTheirTimeAndThePinnedRow() {
        val screen = ChatParser.parseZalo(zaloChat, 1260, 2800)!!
        assertEquals("Nhom Ban", screen.title)
        assertEquals(listOf("dong dau", "toi gui", "Mai da them Lan vao nhom", "nguoi khac"), screen.rows.map { it.text })
    }

    @Test
    fun zaloDateLineAndReceiptAreNotPartOfTheMessage() {
        assertEquals(ZaloText("dong y nhe", "02/11/2026", "12:07"), ChatParser.cleanZalo("12:07 02/11/2026 dong y nhe 12:07 Seen"))
        assertEquals(ZaloText("dong y", null, "23:08"), ChatParser.cleanZalo("dong y 23:08"))
        assertEquals(ZaloText("tin", "Hôm qua", "08:01"), ChatParser.cleanZalo("08:00 Hôm qua tin 08:01 Đã xem"))
        assertEquals(ZaloText("See more", "Today", "12:38"), ChatParser.cleanZalo("12:38 Today See more"))
        assertEquals(ZaloText("", "Hôm nay", "09:00"), ChatParser.cleanZalo("09:00 Hôm nay"))
    }

    @Test
    fun zaloMessagesThatOnlyLookLikeDatesStayWhole() {
        assertEquals("8:00 t2 nhé", ChatParser.cleanZalo("8:00 t2 nhé").body)
        assertEquals("8:00 thứ hai nhé", ChatParser.cleanZalo("8:00 thứ hai nhé").body)
        assertEquals("7:30 hôm nay nhé", ChatParser.cleanZalo("7:30 hôm nay nhé").body)
        assertEquals("10:30 15/11 họp lớp", ChatParser.cleanZalo("10:30 15/11 họp lớp").body)
        assertEquals("hôm nay đi ăn", ChatParser.cleanZalo("hôm nay đi ăn").body)
        assertEquals("gặp lúc 12:30 nhé", ChatParser.cleanZalo("gặp lúc 12:30 nhé").body)
        assertEquals("đã xem chưa", ChatParser.cleanZalo("đã xem chưa").body)
    }

    @Test
    fun zaloDateLineGivesTheRealSendTime() {
        val at = ChatParser.zaloTime("02/11/2026", "12:07", 0)!!
        val time = java.util.Calendar.getInstance().apply { timeInMillis = at }
        assertEquals(2026, time.get(java.util.Calendar.YEAR))
        assertEquals(10, time.get(java.util.Calendar.MONTH))
        assertEquals(2, time.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(12, time.get(java.util.Calendar.HOUR_OF_DAY))
        assertNull(ChatParser.zaloTime("T2", "12:07", 0))
        assertNull(ChatParser.zaloTime("31/02/2026", "10:00", 0))
        assertNull(ChatParser.zaloTime("28/12", "10:00", 0))
        assertNull(ChatParser.zaloTime("02/11/2026", "25:07", 0))
    }

    @Test
    fun zaloDateLineAppliesToTheRowsBelowIt() {
        val nodes = listOf(zaloNode("a", 300, 400), zaloNode("b", 400, 500), zaloNode("c", 500, 600))
        val rows = listOf(
            ZaloRow("a", nodes[0], null, "02/11/2026", "12:07"),
            ZaloRow("b", nodes[1], null),
            ZaloRow("c", nodes[2], null, null, "12:09"),
        )
        val chat = ChatParser.zaloChat("X", rows, listOf(true, true, true), 0)
        assertEquals(listOf(ChatParser.zaloTime("02/11/2026", "12:07", 0), ChatParser.zaloTime("02/11/2026", "12:09", 0)), chat.messages.map { it.sentAt })
    }

    @Test
    fun zaloAttachmentRowsFromARealChat() {
        assertEquals(ZaloTag(null, ChatParser.KIND_IMAGE, "Ảnh"), ChatParser.zaloTag("[Photo]"))
        assertEquals(ZaloTag("Nguyen Van An", ChatParser.KIND_IMAGE, "Ảnh"), ChatParser.zaloTag("Nguyen Van An [Photo]"))
        assertEquals(ZaloTag("Tran Thi Lan", ChatParser.KIND_IMAGE, "Nhãn dán"), ChatParser.zaloTag("Tran Thi Lan [Sticker]"))
        assertEquals(
            ZaloTag(null, ChatParser.KIND_FILE, "bao cao tuan 2.docx 174.9 MB Downloaded on mobile"),
            ChatParser.zaloTag("[File] bao cao tuan 2.docx 174.9 MB Downloaded on mobile"),
        )
        assertNull(ChatParser.zaloTag("gửi ảnh nhé"))
        assertNull(ChatParser.zaloTag("Message recalled"))
        val row = ChatParser.cleanZalo("11:09 26/10/2026 Nguyen Van An [Photo]")
        assertEquals("Nguyen Van An [Photo]", row.body)
    }

    @Test
    fun zaloGroupNamesAreSplitOffAndCarryThroughARun() {
        val nodes = (0..4).map { zaloNode("r$it", 300 + it * 100, 400 + it * 100) }
        val rows = listOf(
            ZaloRow("mo dau", nodes[0], null),
            ZaloRow("Ảnh", nodes[1], ChatParser.KIND_IMAGE, sender = "Nguyen Van An"),
            ZaloRow("Nguyen Van An xin chao ca nha", nodes[2], null),
            ZaloRow("tiep tuc", nodes[3], null),
            ZaloRow("toi tra loi", nodes[4], null),
        )
        // Rows drawn beside the avatar start at the left edge; the rest of a run is indented past it.
        // Measured on a real chat: 42 px beside the avatar, 144 px for the rest of the run.
        val spans = listOf(42..600, 42..600, 42..600, 144..600, 144..600)
        val chat = ChatParser.zaloChat("Nhom", rows, listOf(false, false, false, false, true), 0, false, emptySet(), spans)
        assertEquals(
            listOf("Ảnh" to "Nguyen Van An", "xin chao ca nha" to "Nguyen Van An", "tiep tuc" to "Nguyen Van An", "toi tra loi" to null),
            chat.messages.map { it.text to it.sender },
        )
        assertEquals(1, chat.media.size)
        assertEquals(42, chat.media.single().node.left)
        assertEquals(600, chat.media.single().node.right)
        // "An ơi" typed mid-run, away from the avatar, is a message, not a name.
        val mid = ChatParser.zaloChat(
            "Nhom",
            listOf(ZaloRow("x", nodes[0], null), ZaloRow("Nguyen Van An oi", nodes[1], null)),
            listOf(false, false),
            0,
            false,
            setOf("Nguyen Van An"),
            listOf(144..600, 144..600),
        )
        assertEquals("Nguyen Van An oi", mid.messages.single().text)
    }

    @Test
    fun zaloSidesMatchWhatARealChatMeasured() {
        assertEquals(false, ChatParser.zaloSide(78, 990, 1260))
        assertEquals(false, ChatParser.zaloSide(144, 1026, 1260))
        assertEquals(true, ChatParser.zaloSide(354, 1206, 1260))
        assertEquals(true, ChatParser.zaloSide(786, 1206, 1260))
        assertNull(ChatParser.zaloSide(400, 860, 1260))
    }

    @Test
    fun zaloSystemLinesAreDroppedAndOrderKept() {
        val screen = ChatParser.parseZalo(zaloChat, 1260, 2800)!!
        val chat = ChatParser.zaloChat(screen.title, screen.rows, listOf(false, true, null, false))
        assertEquals(
            listOf(
                ScreenMessage("toi gui", true, "dong dau", null),
                ScreenMessage("nguoi khac", false, "toi gui", "Nhom Ban"),
            ),
            chat.messages,
        )
    }

    @Test
    fun zaloListHasNoComposerSoItIsNotAChat() {
        assertNull(ChatParser.parseZalo(zaloChat.filterNot { it.editable }, 1260, 2800))
    }

    private fun list(canScrollUp: Boolean, visible: Boolean = true) =
        ScreenNode("", 0, 300, 1080, 2150, className = "androidx.recyclerview.widget.RecyclerView", visible = visible, canScrollUp = canScrollUp)

    @Test
    fun aHiddenListBehindTheChatDoesNotDecideTheStart() {
        val nodes = listOf(title, composer, list(false, visible = false), list(true), incoming("Hien, đầu$suffix", 400), outgoing("Minh, chào$suffix", 550))
        assertEquals(listOf("chào"), ChatParser.parse(nodes, width, height)!!.messages.map { it.text })
    }

    @Test
    fun storedZaloTextLosesOnlyTheDateLineAndReceipt() {
        assertEquals("Đi ăn không?", ChatParser.cleanStoredZalo("08:00 03/11/2026 Đi ăn không?"))
        assertEquals("Hẹn 10:30", ChatParser.cleanStoredZalo("Hẹn 10:30"))
        assertEquals("ok", ChatParser.cleanStoredZalo("ok 10:32 Đã nhận"))
        assertEquals("Ảnh", ChatParser.cleanStoredZalo("23:09 Đã xem"))
        assertEquals("Ảnh", ChatParser.cleanStoredZalo("09:00 Hôm nay"))
        assertEquals("8:00 thứ hai nhé", ChatParser.cleanStoredZalo("8:00 thứ hai nhé"))
    }

    @Test
    fun firstMessageOfAChatIsKeptAtTheTop() {
        val nodes = listOf(title, composer, list(false), incoming("Hien, đầu tiên$suffix", 400), outgoing("Minh, chào$suffix", 550))
        val chat = ChatParser.parse(nodes, width, height)!!
        assertEquals(listOf(ScreenMessage("đầu tiên", false, "", "Hien"), ScreenMessage("chào", true, "đầu tiên", null)), chat.messages)
    }

    @Test
    fun topBubbleWaitsWhileOlderMessagesExist() {
        val nodes = listOf(title, composer, list(true), incoming("Hien, đầu$suffix", 400), outgoing("Minh, chào$suffix", 550))
        assertEquals(listOf("chào"), ChatParser.parse(nodes, width, height)!!.messages.map { it.text })
        val unknown = listOf(title, composer, incoming("Hien, đầu$suffix", 400), outgoing("Minh, chào$suffix", 550))
        assertEquals(listOf("chào"), ChatParser.parse(unknown, width, height)!!.messages.map { it.text })
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
