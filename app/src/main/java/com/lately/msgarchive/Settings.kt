package com.lately.msgarchive

import android.content.Context

const val MESSENGER_PACKAGE = "com.facebook.orca"

object Settings {
    private const val PREFS = "settings"
    private const val KEY_PAUSED = "paused"

    fun isPaused(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, paused).apply()
    }
}
