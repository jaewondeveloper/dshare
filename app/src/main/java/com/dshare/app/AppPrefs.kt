package com.dshare.app

import android.content.Context

/** Persists the last-used pairing code and ports so they stay stable across app
 *  restarts where possible (the OS may still hand out a different port if the
 *  preferred one is taken, so callers must be ready to fall back). */
object AppPrefs {
    private const val PREFS_NAME = "dshare_prefs"
    private const val KEY_CODE = "pairing_code"
    private const val KEY_HTTPS_PORT = "https_port"
    private const val KEY_REDIRECT_PORT = "redirect_port"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedCode(context: Context): String? = prefs(context).getString(KEY_CODE, null)

    fun saveCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_CODE, code).apply()
    }

    fun getSavedHttpsPort(context: Context): Int = prefs(context).getInt(KEY_HTTPS_PORT, 0)

    fun getSavedRedirectPort(context: Context): Int = prefs(context).getInt(KEY_REDIRECT_PORT, 0)

    fun savePorts(context: Context, httpsPort: Int, redirectPort: Int) {
        prefs(context).edit()
            .putInt(KEY_HTTPS_PORT, httpsPort)
            .putInt(KEY_REDIRECT_PORT, redirectPort)
            .apply()
    }
}
