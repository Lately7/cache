package com.lately.msgarchive

/** One text-bearing node from the Messenger window, reduced to what parsing needs. */
data class ScreenNode(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val editable: Boolean = false,
    val className: String = "",
    val visible: Boolean = true,
    val clickable: Boolean = false,
)

data class ScreenMessage(
    val text: String,
    val outgoing: Boolean,
    val previousText: String,
    val sender: String? = null,
    val kind: String? = null,
)

/** A bubble with an attachment, plus where it is on screen so its picture can be cropped. */
data class ScreenMedia(val message: ScreenMessage, val node: ScreenNode)

/** An avatar on screen; [owner] is the name its messages carry, which can differ from the profile name. */
data class ScreenAvatar(val owner: String, val node: ScreenNode)

data class ScreenChat(
    val conversation: String,
    val messages: List<ScreenMessage>,
    val avatars: List<ScreenAvatar> = emptyList(),
    val media: List<ScreenMedia> = emptyList(),
)

data class MarkedBubble(val sender: String, val text: String)

/**
 * Turns a flat node list into chat messages. Kept free of Android types so it can be
 * unit-tested: Messenger exposes no stable view ids, so labels and layout are the only signals.
 */
object ChatParser {
    const val KIND_IMAGE = "image"
    const val KIND_VIDEO = "video"
    const val KIND_FILE = "file"

    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    // Messenger's accessibility label for a bubble: "Sender, text, double tap to see ...".
    private val BUBBLE = Regex("""^([^,]+), (.*), double tap to see sent/receive date and time.*$""", options)
    private val PROFILE = Regex("""^Open (.+)'s profile$""", RegexOption.IGNORE_CASE)
    private val TITLE_SUFFIX = Regex(
        """(,\s*(active|đang hoạt động|hoạt động)\b[^,]*)?,\s*Thread details$""",
        RegexOption.IGNORE_CASE,
    )

    private val NOISE = listOf(
        PROFILE,
        Regex("""^Add custom reaction$""", RegexOption.IGNORE_CASE),
        Regex("""^\d{1,2}:\d{2}(\s?(AM|PM|SA|CH))?$""", RegexOption.IGNORE_CASE),
        Regex("""^(hôm nay|hôm qua|today|yesterday)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^(thứ|t[2-7]|cn|chủ nhật)\b.*\d{1,2}:\d{2}.*""", RegexOption.IGNORE_CASE),
        Regex("""^\d{1,2}\s+tháng\s+\d{1,2}.*""", RegexOption.IGNORE_CASE),
        Regex("""^(đã xem|đã gửi|đã nhận|đang gửi|seen|sent|delivered|sending)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^(đang hoạt động|hoạt động|active)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^.*\bsent by .+ on .*$""", options),
        Regex("""^.*\bis typing\b.*$""", options),
    )

    private val KINDS = listOf(
        KIND_VIDEO to Regex("""\b(video|clip)\b""", RegexOption.IGNORE_CASE),
        KIND_IMAGE to Regex("""\b(photo|photos|image|picture|sticker|gif|ảnh|hình)\b""", RegexOption.IGNORE_CASE),
        // Whole label only: "gửi file nhé" and "vnexpress.net" are ordinary text.
        KIND_FILE to Regex(
            """^(file|attachment|tệp)$|^\S+\.(pdf|docx?|xlsx?|pptx?|zip|rar|7z|txt|csv|apk)$""",
            RegexOption.IGNORE_CASE,
        ),
    )
    private const val MAX_MEDIA_LABEL = 60
    private const val HEADER_SHARE = 0.13
    private const val AVATAR_CORNER_GAP = 40
    private const val AVATAR_MIN_CONTRAST = 40
    private const val AVATAR_MIN_DETAIL = 24
    private const val SAME_FACE_GAP = 40
    private const val MIN_BUBBLE_SHARE = 0.07
    private val LINK = Regex(
        """https?://\S+|www\.\S+|\b[\w-]+(\.[\w-]+)*\.(com|net|org|vn|io|me|co|gg|tv|dev|app)\b(/\S*)?""",
        RegexOption.IGNORE_CASE,
    )

    /** A short label on a bubble too tall for one line of text is an attachment; files are recognised by name alone. */
    fun mediaKind(text: String, node: ScreenNode, width: Int): String? {
        if (text.length > MAX_MEDIA_LABEL) return null
        val kind = KINDS.firstOrNull { it.second.containsMatchIn(text) }?.first ?: return null
        val tall = node.bottom - node.top >= width * 0.2
        return kind.takeIf { tall || it == KIND_FILE }
    }

    /**
     * A circular avatar on a plain background has four matching corners and a varied middle. Anything
     * else is a crop of the wrong spot or Messenger's grey placeholder, and is better left unsaved.
     */
    fun looksLikeAvatar(corners: List<Int>, inner: List<Int>): Boolean {
        val ring = corners.first()
        if (corners.any { colourGap(it, ring) > AVATAR_CORNER_GAP }) return false
        val apart = inner.maxOf { colourGap(it, ring) }
        val varied = inner.maxOf { colourGap(it, inner.first()) }
        return apart > AVATAR_MIN_CONTRAST && varied > AVATAR_MIN_DETAIL
    }

    private fun colourGap(a: Int, b: Int) = listOf(16, 8, 0).maxOf { shift ->
        kotlin.math.abs((a shr shift and 0xFF) - (b shr shift and 0xFF))
    }

    /** Messenger exposes no thread id on screen, so a second chat with the same title gets a numbered name. */
    fun threadName(title: String, number: Int) = if (number <= 1) title else "$title ($number)"

    fun threadNumber(title: String, name: String): Int? {
        if (name == title) return 1
        if (!name.startsWith("$title (") || !name.endsWith(")")) return null
        return name.substring(title.length + 2, name.length - 1).toIntOrNull()?.takeIf { it > 1 }
    }

    /** Mean colour distance between two same-size avatar samples; a person's picture stays close to itself. */
    fun sameFace(a: IntArray, b: IntArray): Boolean {
        if (a.isEmpty() || a.size != b.size) return false
        return a.indices.sumOf { colourGap(a[it], b[it]) } / a.size <= SAME_FACE_GAP
    }

    /**
     * Link cards and big single emoji carry no "double tap" label. Unlike dates and read receipts they
     * can be tapped, and unlike reaction chips they are at least as tall as a line of text in a bubble.
     */
    private fun isUnlabelledBubble(node: ScreenNode, width: Int) =
        node.clickable && node.bottom - node.top >= width * MIN_BUBBLE_SHARE && !isNoise(node.text)

    private fun contains(outer: ScreenNode, inner: ScreenNode) = inner !== outer &&
        inner.left >= outer.left && inner.right <= outer.right && inner.top >= outer.top && inner.bottom <= outer.bottom

    /** A card's own label leaves out what was typed above the preview, which only its children show. */
    private fun cardText(card: ScreenNode, nodes: List<ScreenNode>): String {
        val lines = nodes.filter { !it.clickable && it.text.isNotBlank() && contains(card, it) }.map { it.text.trim() }
        return lines.distinct().joinToString("\n").ifEmpty { card.text.trim() }
    }

    /** A title that changes while the old bubbles stay is a chat still loading, not a chat with new content. */
    fun mostlySame(seen: Set<String>, previous: Set<String>) =
        seen.isNotEmpty() && seen.count { it in previous } * 2 > seen.size

    /** First web address in a message, always with a scheme; only http and https are ever opened. */
    fun findLink(text: String): String? {
        val found = LINK.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':') ?: return null
        return if (found.startsWith("http", ignoreCase = true)) found else "https://$found"
    }

    fun isGroup(messages: List<ScreenMessage>) =
        messages.filterNot { it.outgoing }.mapNotNull { it.sender }.distinct().size > 1

    fun isNoise(text: String) =text.isBlank() || NOISE.any { it.matches(text.trim()) }

    fun cleanTitle(title: String) = title.trim().replace(TITLE_SUFFIX, "")

    fun parseMarked(text: String): MarkedBubble? =
        BUBBLE.matchEntire(text.trim())?.let { MarkedBubble(it.groupValues[1].trim(), it.groupValues[2].trim()) }

    private fun titleNode(nodes: List<ScreenNode>, width: Int, height: Int): ScreenNode? {
        // "Thread details" names the header outright; position is only a guess, used when it is missing.
        val top = nodes.filter { !it.editable && it.bottom <= height * 0.3 }
        top.firstOrNull { TITLE_SUFFIX.containsMatchIn(it.text.trim()) }?.let { return it }
        return top
            .filter { it.bottom <= height * HEADER_SHARE && it.left > width * 0.08 && !isNoise(it.text) }
            .filterNot { "Button" in it.className }
            .minWithOrNull(compareBy({ it.top }, { it.left }))
    }

    /** Returns null unless this looks like an open chat: a composer box or a labelled bubble tells it from the chat list. */
    fun parse(allNodes: List<ScreenNode>, width: Int, height: Int): ScreenChat? {
        // A chat kept alive behind the current screen must not lend its title or bubbles to this one.
        val shown = allNodes.filter { it.visible }
        val nodes = if (shown.any { !it.editable && parseMarked(it.text) != null }) shown else allNodes
        // Two chats in one tree means a transition; the next read will show only one of them.
        val headers = nodes.filter { !it.editable && TITLE_SUFFIX.containsMatchIn(it.text.trim()) }
        if (headers.map { cleanTitle(it.text) }.distinct().size > 1) return null

        // The label is only present in some Messenger languages; without it, fall back to layout alone.
        val labelled = nodes.any { !it.editable && parseMarked(it.text) != null }
        // The keyboard pushes the composer up to mid-screen; the chat list's search box stays near the top.
        val composer = nodes.filter { it.editable && it.top > height * 0.3 }.maxByOrNull { it.top }
        // Message requests and blocked chats have no composer but are still chats.
        if (composer == null && !labelled) return null
        val composerTop = composer?.top ?: height

        // No title yet means the header is still loading. Guessing would file these bubbles under the
        // wrong chat, while waiting costs nothing: the same bubbles are read again a moment later.
        val header = titleNode(nodes, width, height)
        val title = header?.text?.let(::cleanTitle)?.takeIf { it.isNotBlank() } ?: return null

        // Tall bubbles are clipped by the list, so they can poke above the usual header line or into the composer.
        val headerBottom = if (labelled) header?.bottom ?: 0 else (height * HEADER_SHARE).toInt()
        val body = nodes
            .filter { node ->
                !node.editable && node.top >= headerBottom &&
                    (if (labelled) node.top < composerTop else node.bottom <= composerTop)
            }
            .sortedWith(compareBy({ it.top }, { it.left }))

        val ordered = ArrayList<ScreenMessage>()
        val orderedNodes = ArrayList<ScreenNode>()
        val avatars = ArrayList<ScreenAvatar>()
        var last: ScreenNode? = null
        for (node in body) {
            val profile = PROFILE.matchEntire(node.text.trim())
            if (profile != null) {
                // Avatars sit beside the last bubble of a run, so that bubble names their owner.
                val owner = ordered.lastOrNull()?.takeUnless { it.outgoing }?.sender ?: profile.groupValues[1]
                avatars.add(ScreenAvatar(owner, node))
                continue
            }
            // Label first: a sender called "Active" or "Seen" must not look like a status line.
            val marked = parseMarked(node.text)
            if (marked == null && isNoise(node.text)) continue
            if (marked?.text?.isBlank() == true) continue
            // The attachment buttons beside the composer start a little higher than the box itself.
            if (labelled && marked == null && (node.bottom > composerTop || !isUnlabelledBubble(node, width))) continue

            // Messenger often repeats a bubble's text on a parent and a child node.
            val prev = last
            if (prev != null && prev.text == node.text && node.top < prev.bottom) continue
            val leftGap = node.left
            val rightGap = width - node.right
            // Centred lines are system notices ("X đã đặt biệt danh..."), not messages. A long labelled
            // bubble can be just as centred, and the label proves it is a message.
            if (marked == null && kotlin.math.abs(leftGap - rightGap) < width * 0.03) continue
            val outgoing = rightGap < leftGap
            val nearby = ordered.lastOrNull { !it.outgoing }?.sender
            val sender = if (outgoing) null else marked?.sender ?: nearby ?: title
            val text = marked?.text ?: if (labelled) cardText(node, nodes) else node.text.trim()
            ordered.add(ScreenMessage(text, outgoing, "", sender, mediaKind(text, node, width)))
            orderedNodes.add(node)
            last = node
        }

        // The top bubble has no predecessor yet, so its identity would change on scroll; it is saved once it has one.
        var repeats = 0
        val messages = ordered.drop(1).mapIndexed { i, message ->
            val previous = ordered[i].text
            repeats = if (message.text == previous) repeats + 1 else 0
            // "ok, ok, ok" would otherwise give the second and third bubble the same identity.
            message.copy(previousText = if (repeats > 1) "$previous\u0002$repeats" else previous)
        }
        val media = messages.mapIndexedNotNull { i, message ->
            if (message.kind == KIND_IMAGE || message.kind == KIND_VIDEO) ScreenMedia(message, orderedNodes[i + 1]) else null
        }
        return ScreenChat(title, messages, avatars, media)
    }

    /** Evenly spaced sort positions strictly between two neighbours, for messages found while scrolling back. */
    fun between(lower: Double, upper: Double, count: Int): List<Double> {
        val step = (upper - lower) / (count + 1)
        return List(count) { lower + step * (it + 1) }
    }

    /** Same text repeated in a chat is legitimate; the preceding bubble tells re-reads apart from new messages. */
    fun screenKey(conversation: String, message: ScreenMessage) =
        listOf("screen", conversation, message.outgoing, message.previousText, message.text).joinToString("\u0001")

    fun notificationKey(conversation: String, sender: String?, timestamp: Long, text: String) =
        listOf("notif", conversation, sender.orEmpty(), timestamp, text).joinToString("\u0001")
}
