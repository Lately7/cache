package com.lately.msgarchive

/** One text-bearing node from the Messenger window, reduced to what parsing needs. */
data class ScreenNode(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val editable: Boolean = false,
)

data class ScreenMessage(
    val text: String,
    val outgoing: Boolean,
    val previousText: String,
    val sender: String? = null,
)

/** An avatar on screen; [owner] is the name its messages carry, which can differ from the profile name. */
data class ScreenAvatar(val owner: String, val node: ScreenNode)

data class ScreenChat(
    val conversation: String,
    val messages: List<ScreenMessage>,
    val avatars: List<ScreenAvatar> = emptyList(),
)

data class MarkedBubble(val sender: String, val text: String)

/**
 * Turns a flat node list into chat messages. Kept free of Android types so it can be
 * unit-tested: Messenger exposes no stable view ids, so labels and layout are the only signals.
 */
object ChatParser {
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    // Messenger's accessibility label for a bubble: "Sender, text, double tap to see ...".
    private val BUBBLE = Regex("""^([^,]+), (.*), double tap to see sent/receive date and time.*$""", options)
    private val PROFILE = Regex("""^Open (.+)'s profile$""", RegexOption.IGNORE_CASE)
    private val TITLE_SUFFIX = Regex(""",\s*Thread details$""", RegexOption.IGNORE_CASE)

    private val NOISE = listOf(
        PROFILE,
        Regex("""^Add custom reaction$""", RegexOption.IGNORE_CASE),
        Regex("""^\d{1,2}:\d{2}(\s?(AM|PM|SA|CH))?$""", RegexOption.IGNORE_CASE),
        Regex("""^(hôm nay|hôm qua|today|yesterday)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^(thứ|t[2-7]|cn|chủ nhật)\b.*\d{1,2}:\d{2}.*""", RegexOption.IGNORE_CASE),
        Regex("""^\d{1,2}\s+tháng\s+\d{1,2}.*""", RegexOption.IGNORE_CASE),
        Regex("""^(đã xem|đã gửi|đã nhận|đang gửi|seen|sent|delivered|sending)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^(đang hoạt động|hoạt động|active)\b.*""", RegexOption.IGNORE_CASE),
    )

    fun isNoise(text: String) = text.isBlank() || NOISE.any { it.matches(text.trim()) }

    fun cleanTitle(title: String) = title.trim().replace(TITLE_SUFFIX, "")

    fun parseMarked(text: String): MarkedBubble? =
        BUBBLE.matchEntire(text.trim())?.let { MarkedBubble(it.groupValues[1].trim(), it.groupValues[2].trim()) }

    /** Returns null unless this looks like an open chat; a composer box is what separates it from the chat list. */
    fun parse(nodes: List<ScreenNode>, width: Int, height: Int): ScreenChat? {
        val composer = nodes.filter { it.editable && it.top > height * 0.6 }.minByOrNull { it.top } ?: return null
        val headerBottom = (height * 0.13).toInt()

        val title = nodes
            .filter { !it.editable && it.bottom <= headerBottom && it.left > width * 0.08 && !isNoise(it.text) }
            .minWithOrNull(compareBy({ it.top }, { it.left }))
            ?.text?.let(::cleanTitle) ?: return null

        val body = nodes
            .filter { !it.editable && it.top >= headerBottom && it.bottom <= composer.top }
            .sortedWith(compareBy({ it.top }, { it.left }))
        // The label is only present in some Messenger languages; without it, fall back to layout alone.
        val labelled = body.any { parseMarked(it.text) != null }

        val ordered = ArrayList<ScreenMessage>()
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
            if (isNoise(node.text)) continue
            val marked = parseMarked(node.text)
            if (labelled && marked == null) continue

            // Messenger often repeats a bubble's text on a parent and a child node.
            val prev = last
            if (prev != null && prev.text == node.text && node.top < prev.bottom) continue
            val leftGap = node.left
            val rightGap = width - node.right
            // Centred lines are system notices ("X đã đặt biệt danh..."), not messages.
            if (kotlin.math.abs(leftGap - rightGap) < width * 0.03) continue
            val outgoing = rightGap < leftGap
            val sender = if (outgoing) null else marked?.sender ?: title
            ordered.add(ScreenMessage(marked?.text ?: node.text.trim(), outgoing, "", sender))
            last = node
        }

        // The top bubble has no predecessor yet, so its identity would change on scroll; it is saved once it has one.
        val messages = ordered.drop(1).mapIndexed { i, message ->
            message.copy(previousText = ordered[i].text)
        }
        return ScreenChat(title, messages, avatars)
    }

    /** Same text repeated in a chat is legitimate; the preceding bubble tells re-reads apart from new messages. */
    fun screenKey(conversation: String, message: ScreenMessage) =
        listOf("screen", conversation, message.outgoing, message.previousText, message.text).joinToString("\u0001")

    fun notificationKey(conversation: String, sender: String?, timestamp: Long, text: String) =
        listOf("notif", conversation, sender.orEmpty(), timestamp, text).joinToString("\u0001")
}
