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
import android.util.Log
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
    private var lastTitle: String? = null

    @Volatile
    private var active = Source.MESSENGER

    @Volatile
    private var leftApp = false
    private var capturePending = false
    private var rootMisses = 0
    private var zaloShown: String? = null
    private var zaloRetries = 0

    // A chat is only trusted once its title and bubbles have held still; the two load separately.
    private var entry: String? = null
    private var entrySince = 0L
    private var staleReads = 0
    private var verdict: Pair<String, String>? = null
    private var identifying = false

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val source = Source.of(event.packageName) ?: return
        if (source != active) {
            active = source
            leftApp = true
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) Log.d(TAG, "scrolled in ${source.label}")
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
        Settings.noteCapture(this, "Dịch vụ Trợ năng vừa chạy, chưa thấy app chat nào")
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
            .setContentTitle("Đang lưu tin")
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
        val source = active
        if (leftApp) {
            leftApp = false
            openChat = null
            lastSeen = emptySet()
            lastTitle = null
            entry = null
        }
        val root = rootOf(source)
        if (root == null) {
            if (!windowRead) Settings.noteCapture(this, "Không đọc được cửa sổ ${source.label}")
            // A window that just opened has no root yet, and a still screen sends no further event.
            if (rootMisses++ < MAX_ROOT_MISSES) readAgainIn(ENTRY_STABLE_MS)
            return
        }
        rootMisses = 0
        windowRead = true

        val nodes = ArrayList<ScreenNode>()
        collect(root, nodes, 0)
        if (Settings.isDumping(this)) Diagnostics.addToDump(this, nodes)
        val metrics = resources.displayMetrics
        if (source == Source.ZALO) {
            scanZalo(nodes, metrics.widthPixels, metrics.heightPixels, settled)
            return
        }
        val parsed = ChatParser.parse(nodes, metrics.widthPixels, metrics.heightPixels)
        if (parsed == null) {
            openChat = null
            entry = null
            if (!chatRead) Settings.noteCapture(this, "Không phải màn hình chat (${nodes.size} mục)")
            return
        }

        chatRead = true
        saveParsed(parsed, source, settled)
    }

    /** Zalo's rows say nothing about who sent them, so the bubble's side is measured once the screen is still. */
    private fun scanZalo(nodes: List<ScreenNode>, width: Int, height: Int, settled: Boolean) {
        val screen = ChatParser.parseZalo(nodes, width, height)
        Log.d(TAG, "zalo read: ${if (screen == null) "not a chat" else "${screen.rows.size} rows"}, settled=$settled")
        if (screen == null) {
            openChat = null
            entry = null
            zaloShown = null
            zaloRetries = 0
            if (!chatRead) Settings.noteCapture(this, "Zalo: không phải màn hình chat (${nodes.size} mục)")
            return
        }
        chatRead = true
        val shown = screen.title + screen.rows.joinToString("\u0002") { it.text }
        if (shown == zaloShown) return
        if (!settled) {
            if (!capturePending) {
                capturePending = true
                handler.postDelayed({
                    capturePending = false
                    guarded { scanWindow(settled = true) }
                }, CAPTURE_DELAY_MS)
            }
            return
        }
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = picture(result)
                var saved = false
                var retry = true
                try {
                    if (Settings.isPaused(this@MessengerAccessibilityService)) return
                    val root = rootInActiveWindow?.takeIf { it.packageName?.toString() == Source.ZALO.packageName }
                    if (shot != null && root != null) {
                        val nodesNow = ArrayList<ScreenNode>().also { collect(root, it, 0) }
                        val current = nodesNow.toSet()
                        if (screen.rows.all { it.node in current }) {
                            val spans = screen.rows.map { bubbleSpan(shot, it.node) }
                            val sides = spans.map { span -> span?.let { ChatParser.zaloSide(it.first, it.last, shot.width) } }
                            val chat = ChatParser.zaloChat(
                                screen.title,
                                screen.rows,
                                sides,
                                System.currentTimeMillis(),
                                !screen.coveredAbove && ChatParser.atChatStart(nodesNow, screen.rows.firstOrNull()?.node),
                                MessageStore.get(this@MessengerAccessibilityService, Source.ZALO).senders(screen.title),
                                spans.map { span -> span?.let { scaledBack(shot, it) } },
                            )
                            // Only a finished save may skip the next read: an opening chat is read twice.
                            // A refusal schedules its own read, so it needs no retry here.
                            val result = saveParsed(chat, Source.ZALO, true)
                            saved = result != null
                            retry = false
                            zaloRetries = 0
                            if (result != null) {
                                zaloShown = shown
                                zaloRetries = 0
                                savePictures(shot, chat, result)
                            }
                            Log.d(TAG, "zalo ${screen.rows.size} rows, ${chat.messages.size} kept, saved=$saved")
                        } else {
                            Log.d(TAG, "zalo screen moved before the screenshot")
                        }
                    }
                } catch (e: RuntimeException) {
                    Diagnostics.record(this@MessengerAccessibilityService, e)
                } finally {
                    shot?.recycle()
                    if (!saved && retry) retryZalo()
                }
            }

            override fun onFailure(errorCode: Int) = retryZalo()
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
        } catch (e: SecurityException) {
            Diagnostics.record(this, e)
        }
    }

    /** A still Zalo screen sends no further event, so a missed read has to schedule its own retry. */
    private fun retryZalo() {
        if (zaloRetries++ < MAX_ZALO_RETRIES) readAgainIn(CAPTURE_DELAY_MS) else zaloRetries = 0
    }

    /** Copies the screenshot into ordinary memory and frees the hardware buffer whatever happens. */
    private fun picture(result: ScreenshotResult): Bitmap? = result.hardwareBuffer.use { buffer ->
        val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@use null
        try {
            wrapped.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            wrapped.recycle()
        }
    }

    /** Horizontal extent of whatever is drawn in the row, in screenshot pixels; null for an empty row. */
    private fun bubbleSpan(shot: Bitmap, node: ScreenNode): IntRange? {
        val scale = shotScale(shot)
        val top = (node.top * scale).toInt().coerceIn(0, shot.height - 1)
        val bottom = (node.bottom * scale).toInt().coerceIn(0, shot.height - 1)
        var left = shot.width
        var right = 0
        for (line in 1..SIDE_LINES) {
            val y = top + (bottom - top) * line / (SIDE_LINES + 1)
            val background = shot.getPixel(shot.width - 3, y)
            for (x in 0 until shot.width step SIDE_STEP) {
                if (ChatParser.colourGap(shot.getPixel(x, y), background) > SIDE_CONTRAST) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                }
            }
        }
        return if (right > left) left..right else null
    }

    private fun scaledBack(shot: Bitmap, span: IntRange): IntRange {
        val scale = shotScale(shot)
        return (span.first / scale).toInt()..(span.last / scale).toInt()
    }

    /** Zalo pictures are cut from the screenshot that told the sides apart, while the rows are known to be still. */
    private fun savePictures(shot: Bitmap, chat: ScreenChat, saved: SavedScreen) {
        val store = MessageStore.get(this, Source.ZALO)
        for (item in chat.media) {
            val id = saved.ids[item.message] ?: continue
            if (!store.missingMedia(id)) continue
            crop(shot, item.node)?.let { MediaStoreFiles.save(this, id, it, Source.ZALO) }
        }
    }

    /** Null while the chat is not trusted yet; a later read tries again. */
    private fun saveParsed(parsed: ScreenChat, source: Source, settled: Boolean): SavedScreen? {
        val store = MessageStore.get(this, source)
        val now = System.currentTimeMillis()
        val title = parsed.conversation
        val seen = parsed.messages.map { it.previousText + "\u0001" + it.text }.toSet()
        if (seen.isEmpty()) return null
        val thread = threadFor(parsed, seen, now, source) ?: return null
        openChat = title to thread
        lastTitle = title
        lastSeen = seen
        windowChanged = false
        scrolled = false
        entry = null
        staleReads = 0
        verdict = null
        val chat = parsed.copy(conversation = thread)
        val saved = store.saveScreen(chat, now)
        Settings.noteCapture(this, "${chat.conversation}: thấy ${chat.messages.size} tin, ${saved.added} tin mới")
        if (saved.added > 0) Diagnostics.trace(this, "$thread: lưu ${saved.added}/${chat.messages.size} tin")

        // Zalo pictures are cut by the caller from the screenshot it already holds.
        if (source == Source.ZALO) return saved
        val media = chat.media.mapNotNull { item ->
            saved.ids[item.message]?.takeIf(store::missingMedia)?.let { it to item.node }
        }
        // A clipped avatar is no longer square and would be saved stretched.
        val oneToOne = store.isOneToOne(thread, title)
        val avatars = chat.avatars
            .filter { it.node.right - it.node.left > 0 && it.node.right - it.node.left == it.node.bottom - it.node.top }
            // Namesakes can share a sender name, so a private chat's avatar belongs to the chat.
            .map { if (oneToOne) it.copy(owner = thread) else it }
            .distinctBy { it.owner }
            .filterNot { AvatarStore.has(this, source.avatar(it.owner)!!) }
        if (media.isEmpty() && avatars.isEmpty()) return saved
        if (settled) {
            capture(media, avatars, source)
        } else if (!capturePending) {
            // Pixels are still sliding during the opening animation, so look again once it has finished.
            capturePending = true
            handler.postDelayed({
                capturePending = false
                guarded { scanWindow(settled = true) }
            }, CAPTURE_DELAY_MS)
        }
        return saved
    }

    /** Null means this screen cannot be trusted yet; a later read decides. */
    private fun threadFor(parsed: ScreenChat, seen: Set<String>, now: Long, source: Source): String? {
        val title = parsed.conversation
        val fresh = openChat?.first != title || windowChanged
        // The window event can arrive after the first read, so a screen replaced without scrolling also counts.
        // Zalo sends no scroll events, so for it every scroll would look like a jump to another chat.
        val jumped = source != Source.ZALO && !scrolled && lastSeen.isNotEmpty() && seen.none { it in lastSeen }
        if (!fresh && !jumped) return openChat?.second

        if (lastTitle != null && lastTitle != title && ChatParser.mostlySame(seen, lastSeen)) {
            Diagnostics.trace(this, "Bỏ qua: tiêu đề $title nhưng tin của $lastTitle")
            if (staleReads++ < MAX_STALE_READS) readAgainIn(ENTRY_STABLE_MS)
            return null
        }

        val match = MessageStore.get(this, source).findConversation(parsed, now)
        val signature = title + "\u0002" + seen.joinToString("\u0002")
        val clock = SystemClock.uptimeMillis()
        if (entry != signature) {
            entry = signature
            entrySince = clock
        }
        // Bubbles matching no known chat are the likeliest to be a title that has not caught up yet.
        val needed = if (match.name != null && !match.guessed && fresh) ENTRY_STABLE_MS else UNKNOWN_STABLE_MS
        val waited = clock - entrySince
        if (waited < needed) {
            readAgainIn(needed - waited)
            return null
        }
        if (!fresh) return openChat?.second
        match.name?.let {
            Diagnostics.trace(this, "Mở $title: vào $it")
            return it
        }

        verdict?.takeIf { it.first == signature }?.let { return it.second }
        val avatar = parsed.avatars.firstOrNull { it.node.right - it.node.left == it.node.bottom - it.node.top }
        if (avatar == null) {
            Diagnostics.trace(this, "Mở $title: toàn tin mới, không có avatar để so, vào ${match.known.first()}")
            return match.known.first()
        }
        if (!identifying) identify(signature, match, avatar.node, source)
        return null
    }

    private fun readAgainIn(delay: Long) {
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, delay)
    }

    /** All the bubbles are new, so only the picture can tell the same person from a namesake. */
    private fun identify(signature: String, match: ThreadMatch, node: ScreenNode, source: Source) {
        identifying = true
        fun decide(thread: String, reason: String) {
            identifying = false
            verdict = signature to thread
            Diagnostics.trace(this, "${match.known.first()}: toàn tin mới, $reason, vào $thread")
            readAgainIn(0)
        }
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = picture(result)
                try {
                    val face = shot?.let { crop(it, node) }?.takeIf(::isAvatar)
                    val service = this@MessengerAccessibilityService
                    val same = face?.let { picture ->
                        match.known.firstOrNull { AvatarStore.matches(service, source.avatar(it)!!, picture) }
                    }
                    val unseen = match.known.firstOrNull { !AvatarStore.has(service, source.avatar(it)!!) }
                    when {
                        face == null -> decide(match.known.first(), "không đọc được avatar")
                        same != null -> decide(same, "cùng avatar")
                        unseen != null -> decide(unseen, "chưa có avatar để so")
                        else -> decide(match.unused, "khác avatar")
                    }
                } catch (e: RuntimeException) {
                    // Left undecided, every later screen of this chat would wait for a verdict forever.
                    Diagnostics.record(this@MessengerAccessibilityService, e)
                    decide(match.known.first(), "lỗi khi so avatar")
                } finally {
                    shot?.recycle()
                }
            }

            override fun onFailure(errorCode: Int) = decide(match.known.first(), "không chụp được màn hình")
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
        } catch (e: SecurityException) {
            Diagnostics.record(this, e)
            decide(match.known.first(), "chưa có quyền chụp màn hình")
        }
    }

    /** Chat heads and split screen leave the app on screen without being the active window. */
    private fun rootOf(source: Source): AccessibilityNodeInfo? {
        val focused = rootInActiveWindow
        if (focused?.packageName?.toString() == source.packageName) return focused
        return windows.firstNotNullOfOrNull { window ->
            window.root?.takeIf { it.packageName?.toString() == source.packageName }
        }
    }

    /** Nothing exposes these images directly, so they are cropped from a screenshot that is never stored. */
    private fun capture(media: List<Pair<Long, ScreenNode>>, avatars: List<ScreenAvatar>, source: Source) {
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = picture(result)
                try {
                    guarded { cropAll(shot, media, avatars, source) }
                } finally {
                    shot?.recycle()
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

    private fun cropAll(shot: Bitmap?, media: List<Pair<Long, ScreenNode>>, avatars: List<ScreenAvatar>, source: Source) {
        if (Settings.isPaused(this)) return
        run {
                // The user may have switched apps since the scan; never crop another app's screen.
                val root = rootInActiveWindow?.takeIf { it.packageName?.toString() == source.packageName }
                if (shot != null && root != null) {
                    // The picture was taken after the scan; a list that moved meanwhile would crop the wrong spot.
                    val current = ArrayList<ScreenNode>().also { collect(root, it, 0) }.toSet()
                    avatars.filter { it.node in current }.forEach { avatar ->
                        crop(shot, avatar.node)?.takeIf(::isAvatar)?.let {
                            AvatarStore.save(this@MessengerAccessibilityService, source.avatar(avatar.owner)!!, it)
                        }
                    }
                    media.filter { it.second in current }.forEach { (id, node) ->
                        crop(shot, node)?.let { MediaStoreFiles.save(this@MessengerAccessibilityService, id, it, source) }
                    }
                }
        }
    }

    private fun isAvatar(crop: Bitmap): Boolean {
        val w = crop.width
        val h = crop.height
        val corners = listOf(crop.getPixel(0, 0), crop.getPixel(w - 1, 0), crop.getPixel(0, h - 1), crop.getPixel(w - 1, h - 1))
        val inner = listOf(2, 3, 4).flatMap { y -> listOf(2, 3, 4).map { x -> crop.getPixel(w * x / 6, h * y / 6) } }
        return ChatParser.looksLikeAvatar(corners, inner)
    }

    /** Phones that render below panel resolution report nodes and screenshots at different sizes. */
    private fun shotScale(shot: Bitmap): Float {
        val size = Point()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size)
        return if (size.x > 0) shot.width.toFloat() / size.x else 1f
    }

    private fun crop(shot: Bitmap, node: ScreenNode): Bitmap? {
        val scale = shotScale(shot)
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
        val list = node.className?.toString().orEmpty().let { "RecyclerView" in it || "ListView" in it }
        if (list) {
            // Lists report the backward action only while they can still scroll towards the top.
            val up = node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }
            out.add(
                ScreenNode(
                    "",
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    className = node.className.toString(),
                    visible = node.isVisibleToUser,
                    canScrollUp = up,
                ),
            )
        }
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
                    node.isVisibleToUser,
                    node.isClickable,
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
        private const val ENTRY_STABLE_MS = 450L
        private const val UNKNOWN_STABLE_MS = 2000L
        private const val MAX_STALE_READS = 8
        private const val MAX_ROOT_MISSES = 3
        private const val MAX_ZALO_RETRIES = 3
        private const val TAG = "LuuTin"
        private const val SIDE_LINES = 7
        private const val SIDE_STEP = 6
        private const val SIDE_CONTRAST = 18
        private const val MAX_DEPTH = 40

        /** The settings toggle can stay on after a vendor cleaner has killed the service. */
        @Volatile
        var connected = false
            private set
    }
}
