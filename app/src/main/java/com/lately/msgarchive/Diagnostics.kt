package com.lately.msgarchive

import android.app.Application
import android.content.Context
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** The phone is not attached to a debugger, so failures must be readable from inside the app. */
object Diagnostics {
    private const val ERROR_FILE = "last-error.txt"
    private const val DUMP_FILE = "screen-dump.txt"
    private const val MAX_TEXT = 120
    private const val MAX_DUMP_LINES = 800
    private const val TRACE_FILE = "capture-log.txt"
    private const val MAX_TRACE_LINES = 200
    private val traceLock = Any()
    private val traceTime = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault())

    fun record(context: Context, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        try {
            File(context.filesDir, ERROR_FILE).writeText("${Date()}\n$trace")
        } catch (_: IOException) {
            // Runs inside crash handling, usually when storage is full; there is nowhere left to report to.
        }
    }

    /** Which chat each screen and notification went to; chat names only, never message text. */
    fun trace(context: Context, line: String) = synchronized(traceLock) {
        val file = File(context.filesDir, TRACE_FILE)
        try {
            val old = if (file.exists()) file.readLines() else emptyList()
            if (old.lastOrNull()?.substringAfter(" | ") == line) return@synchronized
            val entry = "${traceTime.format(Date())} | $line"
            file.writeText((old + entry).takeLast(MAX_TRACE_LINES).joinToString("\n"))
        } catch (_: IOException) {
            // A log must never stop a message from being saved.
        }
    }

    fun traceLog(context: Context) = File(context.filesDir, TRACE_FILE).takeIf { it.exists() }?.readText()

    fun lastError(context: Context) = File(context.filesDir, ERROR_FILE).takeIf { it.exists() }?.readText()

    /** Messenger's labels for photos and files are unknown until seen; text is cut short to limit what is copied. */
    private val dumpLock = Any()

    fun clearDump(context: Context) = synchronized(dumpLock) {
        File(context.filesDir, DUMP_FILE).delete()
    }

    /** Collects what scrolls past, because one screen rarely holds every kind of bubble. */
    fun addToDump(context: Context, nodes: List<ScreenNode>) = synchronized(dumpLock) {
        try {
            val file = File(context.filesDir, DUMP_FILE)
            val known = if (file.exists()) file.readLines() else emptyList()
            val fresh = nodes.map {
                val flags = (if (it.editable) "edit " else "") + (if (it.visible) "" else "hidden ") + (if (it.clickable) "click" else "")
                "${it.className.substringAfterLast('.')}|${it.right - it.left}x${it.bottom - it.top}@${it.left}|$flags|" +
                    it.text.take(MAX_TEXT).replace('\n', ' ')
            }.distinct().filterNot { it in known }
            if (fresh.isNotEmpty()) file.writeText((known + fresh).takeLast(MAX_DUMP_LINES).joinToString("\n"))
        } catch (_: IOException) {
            // A diagnostic dump must never stop capture.
        }
    }

    fun lastDump(context: Context) = synchronized(dumpLock) {
        File(context.filesDir, DUMP_FILE).takeIf { it.exists() }?.readText()
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Backup.applyPendingRestore(this)
        if (Settings.firstRunWithCheckedAvatars(this)) AvatarStore.deleteAll(this)
        Backup.schedule(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Diagnostics.record(this, error)
            previous?.uncaughtException(thread, error)
        }
    }
}
