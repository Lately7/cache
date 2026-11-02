package com.lately.msgarchive

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

class MessengerAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val scan = Runnable { scanWindow() }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != MESSENGER_PACKAGE) return
        // Messenger fires events continuously while rendering; wait for the screen to settle.
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, SETTLE_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(scan)
        super.onDestroy()
    }

    private fun scanWindow() {
        if (Settings.isPaused(this)) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != MESSENGER_PACKAGE) return

        val nodes = ArrayList<ScreenNode>()
        collect(root, nodes, 0)
        val metrics = resources.displayMetrics
        val chat = ChatParser.parse(nodes, metrics.widthPixels, metrics.heightPixels) ?: return

        val store = MessageStore.get(this)
        val now = System.currentTimeMillis()
        for (item in chat.messages) {
            if (!item.outgoing && store.seenInNotification(chat.conversation, item.text, now)) continue
            val message = Message(
                chat.conversation,
                item.sender,
                item.outgoing,
                item.text,
                now,
                "screen",
            )
            store.insert(message, ChatParser.screenKey(chat.conversation, item))
        }
        captureMissingAvatars(chat.avatars)
    }

    /** Nothing exposes the avatar image itself, so it is cropped from a screenshot that is never stored. */
    private fun captureMissingAvatars(avatars: List<ScreenAvatar>) {
        val missing = avatars
            .filter { it.node.right > it.node.left && it.node.bottom > it.node.top }
            .distinctBy { it.owner }
            .filterNot { AvatarStore.has(this, it.owner) }
        if (missing.isEmpty()) return
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val shot = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (shot != null) missing.forEach { crop(shot, it) }
                }

                override fun onFailure(errorCode: Int) = Unit
            },
        )
    }

    private fun crop(shot: Bitmap, avatar: ScreenAvatar) {
        val node = avatar.node
        val left = node.left.coerceAtLeast(0)
        val top = node.top.coerceAtLeast(0)
        val width = minOf(node.right, shot.width) - left
        val height = minOf(node.bottom, shot.height) - top
        if (width <= 0 || height <= 0) return
        AvatarStore.save(this, avatar.owner, Bitmap.createBitmap(shot, left, top, width, height))
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<ScreenNode>, depth: Int) {
        if (depth > MAX_DEPTH || node.isPassword) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (node.isEditable || text != null) {
            out.add(ScreenNode(text.orEmpty(), rect.left, rect.top, rect.right, rect.bottom, node.isEditable))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out, depth + 1) }
        }
    }

    private companion object {
        const val SETTLE_DELAY_MS = 800L
        const val MAX_DEPTH = 40
    }
}
