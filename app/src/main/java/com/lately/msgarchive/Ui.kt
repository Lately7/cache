package com.lately.msgarchive

import android.app.Activity
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsetsController
import android.widget.ImageView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

/** Messenger's dark-mode colours; the framework theme has no bubble colours to borrow. */
class Palette(context: Context) {
    val background = context.getColor(R.color.background)
    val surface = context.getColor(R.color.surface)
    val text = context.getColor(R.color.text)
    val muted = context.getColor(R.color.muted)
    val field = 0xFF3A3B3C.toInt()
    val divider = 0xFF2F3031.toInt()
    val bubbleIn = 0xFF3E4042.toInt()
    val bubbleOut = context.getColor(R.color.accent)
    val onBubbleOut = 0xFFFFFFFF.toInt()
    val linkIn = 0xFF8AB4FF.toInt()
}

/** OEM themes can force dark icons onto the bars; the app is always black, so they must stay light. */
fun Activity.matchSystemBars() {
    val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    window.insetsController?.setSystemBarsAppearance(0, light)
}

/** A hairline under a header, as Messenger separates its toolbar from the list. */
fun Context.divider(colors: Palette) = View(this).apply {
    setBackgroundColor(colors.divider)
    layoutParams = android.widget.LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2))
}

/** Icons are drawn white and tinted here, so one drawable serves every state. */
fun Context.iconButton(icon: Int, label: String, tint: Int, onClick: () -> Unit) = ImageView(this).apply {
    setImageResource(icon)
    imageTintList = android.content.res.ColorStateList.valueOf(tint)
    scaleType = ImageView.ScaleType.CENTER
    contentDescription = label
    setOnClickListener { onClick() }
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
