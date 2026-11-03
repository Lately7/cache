package com.lately.msgarchive

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor

class MessengerAccessibilityService : AccessibilityService() {
    // Walking the node tree is one IPC per node; on the main thread that can get the service killed as unresponsive.
    private val worker = HandlerThread("capture").apply { start() }
    private val handler = Handler(worker.looper)
    private val executor = Executor { handler.post(it) }
    private val scan = Runnable {
        deadline = 0L
        guarded { scanWindow() }
    }

    @Volatile
    private var deadline = 0L

    // Leaving Messenger always ends in a failed read, which must not replace the useful result before it.
    private var windowRead = false
    private var chatRead = false

    // Same-name chats are told apart once, on opening; scrolling back must not look like a new person.
    private var openChat: Pair<String, String>? = null

    @Volatile
    private var windowChanged = false

    @Volatile
    private var scrolled = false

    private var lastSeen = emptySet<String>()
    private var capturePending = false

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != MESSENGER_PACKAGE) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> windowChanged = true
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scrolled = true
        }
        // Messenger fires events while rendering, so wait for the screen to settle. A typing bubble or
        // a GIF never settles, and waiting without a limit would mean never saving that chat.
        val now = SystemClock.uptimeMillis()
        if (deadline == 0L) deadline = now + MAX_WAIT_MS
        handler.removeCallbacks(scan)
        handler.postAtTime(scan, minOf(now + SETTLE_DELAY_MS, deadline))
    }

    /**
     * Android removes an accessibility service from the enabled list when its app is force-stopped,
     * which is what aggressive vendor cleaners do. A foreground service makes that far less likely.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Settings.noteCapture(this, "Dịch vụ Trợ năng vừa chạy, chưa thấy Messenger")
        guarded { goForeground() }
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Đang lưu tin", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Đang lưu tin Messenger")
            .setContentText("Chỉ lưu trên máy này. Chạm để mở.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        handler.removeCallbacks(scan)
        worker.quitSafely()
        super.onDestroy()
    }

    /** An uncaught error here makes Android stop the service until it is re-enabled, so failures stay in the log. */
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Diagnostics.record(this, e)
        }
    }

    private fun scanWindow(settled: Boolean = false) {
        if (Settings.isPaused(this)) return
        val root = messengerRoot()
        if (root == null) {
            if (!windowRead) Settings.noteCapture(this, "Không đọc được cửa sổ Messenger")
            return
        }
        windowRead = true

        val nodes = ArrayList<ScreenNode>()
        collect(root, nodes, 0)
        if (Settings.consumeDumpRequest(this)) Diagnostics.writeDump(this, nodes)
        val metrics = resources.displayMetrics
        val parsed = ChatParser.parse(nodes, metrics.widthPixels, metrics.heightPixels)
        if (parsed == null) {
            openChat = null
            lastSeen = emptySet()
            if (!chatRead) Settings.noteCapture(this, "Không phải màn hình chat (${nodes.size} mục)")
            return
        }

        chatRead = true
        val store = MessageStore.get(this)
        val now = System.currentTimeMillis()
        val title = parsed.conversation
        val seen = parsed.messages.map { it.previousText + "" + it.text }.toSet()
        // The window event can arrive after the first read, so a screen replaced without scrolling also counts.
        val jumped = !scrolled && seen.isNotEmpty() && lastSeen.isNotEmpty() && seen.none { it in lastSeen }
        scrolled = false
        val opened = openChat?.takeIf { it.first == title && !windowChanged && !jumped }?.second
        val thread = opened ?: store.resolveConversation(parsed, now)
        if (parsed.messages.isNotEmpty()) {
            openChat = title to thread
            windowChanged = false
            lastSeen = seen
        }
        val chat = parsed.copy(conversation = thread)
        val saved = store.saveScreen(chat, now)
        Settings.noteCapture(this, "${chat.conversation}: thấy ${chat.messages.size} tin, ${saved.added} tin mới")

        val media = chat.media.mapNotNull { item ->
            saved.ids[item.message]?.takeIf(store::missingMedia)?.let { it to item.node }
        }
        // A clipped avatar is no longer square and would be saved stretched.
        val oneToOne = store.isOneToOne(thread)
        val avatars = chat.avatars
            .filter { it.node.right - it.node.left > 0 && it.node.right - it.node.left == it.node.bottom - it.node.top }
            // Namesakes can share a sender name, so a private chat's avatar belongs to the chat.
            .map { if (oneToOne) it.copy(owner = thread) else it }
            .distinctBy { it.owner }
            .filterNot { AvatarStore.has(this, it.owner) }
        if (media.isEmpty() && avatars.isEmpty()) return
        if (settled) {
            capture(media, avatars)
        } else if (!capturePending) {
            // Pixels are still sliding during the opening animation, so look again once it has finished.
            capturePending = true
            handler.postDelayed({
                capturePending = false
                guarded { scanWindow(settled = true) }
            }, CAPTURE_DELAY_MS)
        }
    }

    /** Chat heads and split screen leave Messenger on screen without being the active window. */
    private fun messengerRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == MESSENGER_PACKAGE) return active
        return windows.firstNotNullOfOrNull { window ->
            window.root?.takeIf { it.packageName?.toString() == MESSENGER_PACKAGE }
        }
    }

    /** Nothing exposes these images directly, so they are cropped from a screenshot that is never stored. */
    private fun capture(media: List<Pair<Long, ScreenNode>>, avatars: List<ScreenAvatar>) {
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) = guarded {
                val buffer = result.hardwareBuffer
                val shot = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                // The user may have switched apps since the scan; never crop another app's screen.
                val root = rootInActiveWindow?.takeIf { it.packageName?.toString() == MESSENGER_PACKAGE }
                if (shot != null && root != null) {
                    // The picture was taken after the scan; a list that moved meanwhile would crop the wrong spot.
                    val current = ArrayList<ScreenNode>().also { collect(root, it, 0) }.toSet()
                    avatars.filter { it.node in current }.forEach { avatar ->
                        crop(shot, avatar.node)?.takeIf(::isAvatar)?.let {
                            AvatarStore.save(this@MessengerAccessibilityService, avatar.owner, it)
                        }
                    }
                    media.filter { it.second in current }.forEach { (id, node) ->
                        crop(shot, node)?.let { MediaStoreFiles.save(this@MessengerAccessibilityService, id, it) }
                    }
                }
            }

            override fun onFailure(errorCode: Int) = Unit
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
        } catch (e: SecurityException) {
            // Thrown when an updated app has not yet been re-enabled and lacks the screenshot capability.
            Diagnostics.record(this, e)
        }
    }

    private fun isAvatar(crop: Bitmap): Boolean {
        val w = crop.width
        val h = crop.height
        val corners = listOf(crop.getPixel(0, 0), crop.getPixel(w - 1, 0), crop.getPixel(0, h - 1), crop.getPixel(w - 1, h - 1))
        val inner = listOf(2, 3, 4).flatMap { y -> listOf(2, 3, 4).map { x -> crop.getPixel(w * x / 6, h * y / 6) } }
        return ChatParser.looksLikeAvatar(corners, inner)
    }

    private fun crop(shot: Bitmap, node: ScreenNode): Bitmap? {
        // Phones that render below panel resolution report nodes and screenshots at different sizes.
        val size = Point()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size)
        val scale = if (size.x > 0) shot.width.toFloat() / size.x else 1f
        val left = (node.left * scale).toInt().coerceAtLeast(0)
        val top = (node.top * scale).toInt().coerceAtLeast(0)
        val width = minOf((node.right * scale).toInt(), shot.width) - left
        val height = minOf((node.bottom * scale).toInt(), shot.height) - top
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(shot, left, top, width, height)
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<ScreenNode>, depth: Int) {
        if (depth > MAX_DEPTH || node.isPassword) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (node.isEditable || text != null) {
            out.add(
                ScreenNode(
                    text.orEmpty(),
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    node.isEditable,
                    node.className?.toString().orEmpty(),
                ),
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out, depth + 1) }
        }
    }

    companion object {
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        private const val SETTLE_DELAY_MS = 500L
        private const val MAX_WAIT_MS = 1500L
        private const val CAPTURE_DELAY_MS = 1000L
        private const val MAX_DEPTH = 40

        /** The settings toggle can stay on after a vendor cleaner has killed the service. */
        @Volatile
        var connected = false
            private set
    }
}
