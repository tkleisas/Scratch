package com.tkleisas.scratchdj.data

import android.content.Context

class SettingsStore(
    context: Context,
    name: String = "scratch_settings",
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    var autoplayA: Boolean
        get() = preferences.getBoolean("autoplay_a", true)
        set(value) {
            preferences.edit().putBoolean("autoplay_a", value).apply()
        }

    var autoplayB: Boolean
        get() = preferences.getBoolean("autoplay_b", true)
        set(value) {
            preferences.edit().putBoolean("autoplay_b", value).apply()
        }

    var crossfadeSeconds: Float
        get() = preferences.getFloat("crossfade_seconds", 0.0f)
        set(value) {
            preferences.edit().putFloat("crossfade_seconds", value).apply()
        }
}
