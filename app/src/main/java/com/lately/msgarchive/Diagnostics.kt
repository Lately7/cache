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
    private const val MAX_TEXT = 60

    fun record(context: Context, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        try {
            File(context.filesDir, ERROR_FILE).writeText("${Date()}\n$trace")
        } catch (_: IOException) {
            // Runs inside crash handling, usually when storage is full; there is nowhere left to report to.
        }
    }

    fun lastError(context: Context) = File(context.filesDir, ERROR_FILE).takeIf { it.exists() }?.readText()

    /** Messenger's labels for photos and files are unknown until seen; text is cut short to limit what is copied. */
    fun writeDump(context: Context, nodes: List<ScreenNode>) {
        val lines = nodes.joinToString("\n") {
            "${it.className.substringAfterLast('.')}|${it.left},${it.top},${it.right},${it.bottom}|" +
                "${if (it.editable) "edit" else ""}|${it.text.take(MAX_TEXT).replace('\n', ' ')}"
        }
        File(context.filesDir, DUMP_FILE).writeText(lines)
    }

    fun lastDump(context: Context) = File(context.filesDir, DUMP_FILE).takeIf { it.exists() }?.readText()
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Settings.firstRunWithCheckedAvatars(this)) AvatarStore.deleteAll(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Diagnostics.record(this, error)
            previous?.uncaughtException(thread, error)
        }
    }
}
