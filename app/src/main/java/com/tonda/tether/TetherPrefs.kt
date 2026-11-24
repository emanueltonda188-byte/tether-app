package com.tonda.tether

import android.content.Context

/**
 * Tiny settings store (SharedPreferences). Two settings in v1:
 *  - proxy port (default 8080)
 *  - auto-start on boot (default off)
 * Plus the ColorOS checklist state.
 */
class TetherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("tether", Context.MODE_PRIVATE)

    var proxyPort: Int
        get() = prefs.getInt(KEY_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    /** First-launch ColorOS checklist completed? */
    var colorOsSetupDone: Boolean
        get() = prefs.getBoolean(KEY_COLOROS_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_COLOROS_DONE, value).apply()

    companion object {
        private const val KEY_PORT = "proxy_port"
        private const val KEY_AUTOSTART = "auto_start"
        private const val KEY_COLOROS_DONE = "coloros_setup_done"
    }
}
