package com.lately.msgarchive

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.TimeUnit

class ConversationActivity : Activity() {
    private lateinit var colors: Palette
    private lateinit var name: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        name = intent.getStringExtra(EXTRA_NAME) ?: run {
            finish()
            return
        }
        colors = Palette(this)
        val messages = MessageStore.get(this).messages(name)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(colors.background)
        }
        root.addView(header(messages.lastOrNull { !it.outgoing }?.sender))
        root.addView(
            ListView(this).apply {
                divider = null
                selector = android.graphics.drawable.ColorDrawable(0)
                clipToPadding = false
                setPadding(dp(10), dp(8), dp(10), dp(12))
                adapter = BubbleAdapter(messages)
                setSelection(messages.size - 1)
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        setContentView(root)
        matchSystemBars(colors)
    }

    private fun header(peer: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), dp(12), dp(8))
        addView(
            TextView(context).apply {
                text = "‹"
                textSize = 32f
                setTextColor(colors.bubbleOut)
                gravity = Gravity.CENTER
                contentDescription = "Quay lại"
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        addView(avatarView(36).apply { setImageBitmap(AvatarStore.load(context, name, peer)) })
        addView(
            TextView(context).apply {
                text = name
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
    }

    private class Holder(val time: TextView, val line: LinearLayout, val avatar: ImageView, val bubble: TextView)

    private inner class BubbleAdapter(val messages: List<Message>) : BaseAdapter() {
        override fun getCount() = messages.size
        override fun getItem(position: Int) = messages[position]
        override fun getItemId(position: Int) = position.toLong()

        private fun startsNewBlock(position: Int): Boolean {
            if (position == 0) return true
            return messages[position].timestamp - messages[position - 1].timestamp > BLOCK_GAP_MS
        }

        private fun joins(position: Int, other: Int): Boolean {
            val a = messages.getOrNull(position) ?: return false
            val b = messages.getOrNull(other) ?: return false
            return a.outgoing == b.outgoing && a.sender == b.sender && !startsNewBlock(maxOf(position, other))
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: createRow()
            val holder = view.tag as Holder
            val message = messages[position]
            val joinsAbove = joins(position, position - 1)
            val joinsBelow = joins(position, position + 1)

            holder.time.visibility = if (startsNewBlock(position)) View.VISIBLE else View.GONE
            holder.time.text = separatorTime(message.timestamp)

            holder.line.gravity = if (message.outgoing) Gravity.END else Gravity.START
            holder.line.setPadding(0, if (joinsAbove) dp(1) else dp(6), 0, 0)
            holder.avatar.visibility = when {
                message.outgoing -> View.GONE
                joinsBelow -> View.INVISIBLE
                else -> View.VISIBLE
            }
            if (holder.avatar.visibility == View.VISIBLE) {
                holder.avatar.setImageBitmap(AvatarStore.load(this@ConversationActivity, message.sender, name))
            }

            holder.bubble.text = message.body
            holder.bubble.setTextColor(if (message.outgoing) colors.onBubbleOut else colors.text)
            val fill = if (message.outgoing) colors.bubbleOut else colors.bubbleIn
            holder.bubble.background = bubble(fill, message.outgoing, joinsAbove, joinsBelow)
            return view
        }

        private fun createRow(): View {
            val time = TextView(this@ConversationActivity).apply {
                textSize = 12f
                setTextColor(colors.muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(6))
            }
            val avatar = avatarView(28).apply {
                (layoutParams as LinearLayout.LayoutParams).apply {
                    gravity = Gravity.BOTTOM
                    marginEnd = dp(8)
                }
            }
            val bubbleView = TextView(this@ConversationActivity).apply {
                textSize = 16f
                setLineSpacing(0f, 1.1f)
                setPadding(dp(13), dp(8), dp(13), dp(8))
                maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
                setTextIsSelectable(true)
            }
            val line = LinearLayout(this@ConversationActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(avatar)
                addView(bubbleView)
            }
            return LinearLayout(this@ConversationActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(time, LinearLayout.LayoutParams(-1, -2))
                addView(line, LinearLayout.LayoutParams(-1, -2))
                tag = Holder(time, line, avatar, bubbleView)
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        private val BLOCK_GAP_MS = TimeUnit.MINUTES.toMillis(30)
    }
}
