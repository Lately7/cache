package com.lately.msgarchive

import android.content.Context

const val MESSENGER_PACKAGE = "com.facebook.orca"

object Settings {
    private const val PREFS = "settings"
    private const val KEY_PAUSED = "paused"
    private const val KEY_DUMP = "dump_requested"
    private const val KEY_CAPTURE = "last_capture"
    private const val KEY_CAPTURE_AT = "last_capture_at"
    private const val KEY_NOTIFICATION_AT = "last_notification_at"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaused(context: Context) = prefs(context).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    /** What the last screen scan concluded, so a capture problem can be read off the phone. */
    fun noteCapture(context: Context, result: String) {
        prefs(context).edit()
            .putString(KEY_CAPTURE, result)
            .putLong(KEY_CAPTURE_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastCapture(context: Context) = prefs(context).getString(KEY_CAPTURE, null)

    fun lastCaptureAt(context: Context) = prefs(context).getLong(KEY_CAPTURE_AT, 0L)

    fun noteNotification(context: Context) {
        prefs(context).edit().putLong(KEY_NOTIFICATION_AT, System.currentTimeMillis()).apply()
    }

    fun lastNotificationAt(context: Context) = prefs(context).getLong(KEY_NOTIFICATION_AT, 0L)

    fun requestDump(context: Context) {
        prefs(context).edit().putBoolean(KEY_DUMP, true).apply()
    }

    /** One-shot, so a screen dump can never keep running in the background. */
    fun consumeDumpRequest(context: Context): Boolean {
        if (!prefs(context).getBoolean(KEY_DUMP, false)) return false
        prefs(context).edit().putBoolean(KEY_DUMP, false).apply()
        return true
    }
}
