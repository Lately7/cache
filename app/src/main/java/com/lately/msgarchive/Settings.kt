package com.lately.msgarchive

import android.content.Context

const val MESSENGER_PACKAGE = "com.facebook.orca"

object Settings {
    private const val PREFS = "settings"
    private const val KEY_PAUSED = "paused"
    private const val KEY_DUMP = "dump_requested"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaused(context: Context) = prefs(context).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()
    }

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
