package vn.aquastream.tv

import android.content.Context

/** Keep original preference file/keys and existing custom step values. */
class PlayerSettings(c: Context) {
    private val prefs = c.getSharedPreferences("seek", Context.MODE_PRIVATE)
    var speedHundredths: Int
        get() = prefs.getInt("speed_hundredths", 100).coerceIn(25, 400)
        set(value) { prefs.edit().putInt("speed_hundredths", value.coerceIn(25, 400)).apply() }
    var autoNext: Boolean
        get() = prefs.getBoolean("auto_next", true)
        set(value) { prefs.edit().putBoolean("auto_next", value).apply() }
    fun step(forward: Boolean) = prefs.getInt(if (forward) "forward" else "back", 2).coerceAtLeast(1)
    var feedback: Boolean
        get() = prefs.getBoolean("seek_feedback", true)
        set(value) { prefs.edit().putBoolean("seek_feedback", value).apply() }
    var timeoutSeconds: Int
        get() = prefs.getInt("osd_timeout", 5).coerceIn(1, 120)
        set(value) { prefs.edit().putInt("osd_timeout", value.coerceIn(1, 120)).apply() }
    fun saveStep(forward: Boolean, seconds: Int) { prefs.edit().putInt(if (forward) "forward" else "back", seconds.coerceAtLeast(1)).apply() }
}
