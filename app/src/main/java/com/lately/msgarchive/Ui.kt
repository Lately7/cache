package com.lately.msgarchive

import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

/** Messenger's colours; the framework theme has no bubble colours to borrow. */
class Palette(context: Context) {
    private val dark =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    val background = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFF5F5F5.toInt() else 0xFF050505.toInt()
    val muted = if (dark) 0xFFA0A0A0.toInt() else 0xFF65676B.toInt()
    val field = if (dark) 0xFF262626.toInt() else 0xFFF0F2F5.toInt()
    val bubbleIn = if (dark) 0xFF303030.toInt() else 0xFFE4E6EB.toInt()
    val bubbleOut = 0xFF0084FF.toInt()
    val onBubbleOut = 0xFFFFFFFF.toInt()
}

fun Context.avatarView(sizeDp: Int) = ImageView(this).apply {
    scaleType = ImageView.ScaleType.CENTER_CROP
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
    }
    clipToOutline = true
    layoutParams = android.widget.LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

/** Corners follow a run of bubbles: tight where a bubble touches its neighbour, as in Messenger. */
fun Context.bubble(color: Int, outgoing: Boolean, joinsAbove: Boolean, joinsBelow: Boolean) = GradientDrawable().apply {
    setColor(color)
    val wide = dp(18).toFloat()
    val tight = dp(4).toFloat()
    val top = if (joinsAbove) tight else wide
    val bottom = if (joinsBelow) tight else wide
    cornerRadii = if (outgoing) {
        floatArrayOf(wide, wide, top, top, bottom, bottom, wide, wide)
    } else {
        floatArrayOf(top, top, wide, wide, wide, wide, bottom, bottom)
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

private fun sameDay(a: Long, b: Long) = dayFormat.format(Date(a)) == dayFormat.format(Date(b))

fun shortTime(time: Long): String {
    val now = System.currentTimeMillis()
    return if (sameDay(time, now)) timeFormat.format(Date(time)) else dayFormat.format(Date(time))
}

fun separatorTime(time: Long): String {
    val now = System.currentTimeMillis()
    val clock = timeFormat.format(Date(time))
    return if (sameDay(time, now)) clock else "${dayFormat.format(Date(time))} $clock"
}
