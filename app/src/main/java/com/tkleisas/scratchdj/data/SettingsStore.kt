package com.tkleisas.scratchdj.data

import android.content.Context
import com.tkleisas.scratchdj.model.WaveformMode
import com.tkleisas.scratchdj.ui.theme.ScratchThemeName
import com.tkleisas.scratchdj.model.WaveformWindowMath
import com.tkleisas.scratchdj.model.WaveformWindowUnit

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

    var theme: ScratchThemeName
        get() = runCatching {
            ScratchThemeName.valueOf(
                preferences.getString("theme", ScratchThemeName.Classic.name)
                    ?: ScratchThemeName.Classic.name,
            )
        }.getOrDefault(ScratchThemeName.Classic)
        set(value) {
            preferences.edit().putString("theme", value.name).apply()
        }

    var waveformMode: WaveformMode
        get() = runCatching {
            WaveformMode.valueOf(
                preferences.getString("waveform_mode", WaveformMode.Full.name)
                    ?: WaveformMode.Full.name,
            )
        }.getOrDefault(WaveformMode.Full)
        set(value) {
            preferences.edit().putString("waveform_mode", value.name).apply()
        }

    var waveformWindowUnit: WaveformWindowUnit
        get() = runCatching {
            WaveformWindowUnit.valueOf(
                preferences.getString("waveform_window_unit", WaveformWindowUnit.Seconds.name)
                    ?: WaveformWindowUnit.Seconds.name,
            )
        }.getOrDefault(WaveformWindowUnit.Seconds)
        set(value) {
            preferences.edit().putString("waveform_window_unit", value.name).apply()
        }

    var waveformWindowSeconds: Float
        get() = preferences.getFloat(
            "waveform_window_seconds",
            WaveformWindowMath.DEFAULT_SECONDS,
        )
        set(value) {
            preferences.edit().putFloat("waveform_window_seconds", value).apply()
        }

    var waveformWindowBeats: Float
        get() = preferences.getFloat(
            "waveform_window_beats",
            WaveformWindowMath.DEFAULT_BEATS,
        )
        set(value) {
            preferences.edit().putFloat("waveform_window_beats", value).apply()
        }

    var fxPayload: String
        get() = preferences.getString("fx_payload", "") ?: ""
        set(value) {
            preferences.edit().putString("fx_payload", value).apply()
        }
}
