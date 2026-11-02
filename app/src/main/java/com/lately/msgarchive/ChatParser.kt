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

data class ScreenMessage(val text: String, val outgoing: Boolean, val previousText: String)

data class ScreenChat(val conversation: String, val messages: List<ScreenMessage>)

/**
 * Turns a flat node list into chat messages. Kept free of Android types so it can be
 * unit-tested: Messenger exposes no stable view ids, so layout is the only usable signal.
 */
object ChatParser {
    private val NOISE = listOf(
        Regex("""^\d{1,2}:\d{2}(\s?(AM|PM|SA|CH))?$""", RegexOption.IGNORE_CASE),
        Regex("""^(hôm nay|hôm qua|today|yesterday)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^(thứ|t[2-7]|cn|chủ nhật)\b.*\d{1,2}:\d{2}.*""", RegexOption.IGNORE_CASE),
        Regex("""^\d{1,2}\s+tháng\s+\d{1,2}.*""", RegexOption.IGNORE_CASE),
        Regex("""^(đã xem|đã gửi|đã nhận|đang gửi|seen|sent|delivered|sending)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^(đang hoạt động|hoạt động|active)\b.*""", RegexOption.IGNORE_CASE),
    )

    private fun isNoise(text: String) = text.isBlank() || NOISE.any { it.matches(text.trim()) }

    /** Returns null unless this looks like an open chat; a composer box is what separates it from the chat list. */
    fun parse(nodes: List<ScreenNode>, width: Int, height: Int): ScreenChat? {
        val composer = nodes.filter { it.editable && it.top > height * 0.6 }.minByOrNull { it.top } ?: return null
        val headerBottom = (height * 0.13).toInt()

        val title = nodes
            .filter { !it.editable && it.bottom <= headerBottom && it.left > width * 0.08 && !isNoise(it.text) }
            .minWithOrNull(compareBy({ it.top }, { it.left }))
            ?.text?.trim() ?: return null

        val bubbles = nodes
            .filter { !it.editable && it.top >= headerBottom && it.bottom <= composer.top && !isNoise(it.text) }
            .sortedWith(compareBy({ it.top }, { it.left }))

        val ordered = ArrayList<Pair<String, Boolean>>()
        var last: ScreenNode? = null
        for (node in bubbles) {
            // Messenger often repeats a bubble's text on a parent and a child node.
            val prev = last
            if (prev != null && prev.text == node.text && node.top < prev.bottom) continue
            val leftGap = node.left
            val rightGap = width - node.right
            // Centred lines are system notices ("X đã đặt biệt danh..."), not messages.
            if (kotlin.math.abs(leftGap - rightGap) < width * 0.03) continue
            ordered.add(node.text.trim() to (rightGap < leftGap))
            last = node
        }

        // The top bubble has no predecessor yet, so its identity would change on scroll; it is saved once it has one.
        val messages = ordered.drop(1).mapIndexed { i, (text, outgoing) ->
            ScreenMessage(text, outgoing, ordered[i].first)
        }
        return ScreenChat(title, messages)
    }

    /** Same text repeated in a chat is legitimate; the preceding bubble tells re-reads apart from new messages. */
    fun screenKey(conversation: String, message: ScreenMessage) =
        listOf("screen", conversation, message.outgoing, message.previousText, message.text).joinToString("\u0001")

    fun notificationKey(conversation: String, sender: String?, timestamp: Long, text: String) =
        listOf("notif", conversation, sender.orEmpty(), timestamp, text).joinToString("\u0001")
}
