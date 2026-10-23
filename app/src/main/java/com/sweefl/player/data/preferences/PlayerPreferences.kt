package com.sweefl.player.data.preferences

import android.content.Context
import com.sweefl.player.domain.DoubleTapAction
import com.sweefl.player.domain.PlaybackInteractionSettings

class PlayerPreferences(context: Context) {
    private val store = context.getSharedPreferences("player_preferences", Context.MODE_PRIVATE)
    var glassEnabled: Boolean
        get() = store.getBoolean("realtime_glass", true)
        set(value) { store.edit().putBoolean("realtime_glass", value).apply() }
    var defaultUserAgent: String
        get() = store.getString("default_user_agent", "SweeflPlayer/0.2.0 (Android)") ?: "SweeflPlayer/0.2.0 (Android)"
        set(value) { store.edit().putString("default_user_agent", value).apply() }

    var networkPlayback: com.sweefl.player.domain.NetworkPlaybackSettings
        get() = store.all.let { values -> com.sweefl.player.domain.NetworkPlaybackSettings.fromStored(
            values["network_threads"], values["network_buffer_seconds"]) }
        set(value) {
            val normalized = value.normalized()
            store.edit().putInt("network_threads", normalized.threads)
                .putInt("network_buffer_seconds", normalized.bufferSeconds).apply()
        }

    fun observeNetworkPlayback(onChanged: (com.sweefl.player.domain.NetworkPlaybackSettings) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "network_threads" || key == "network_buffer_seconds") onChanged(networkPlayback)
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        return { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var glassBlurAmount: Int
        get() = (store.all["glass_blur_amount"] as? Int ?: 0).coerceIn(0, 100)
        set(value) { store.edit().putInt("glass_blur_amount", value.coerceIn(0, 100)).apply() }

    fun observeGlassBlurAmount(onChanged: (Int) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "glass_blur_amount") onChanged(glassBlurAmount)
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        return { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var glassRefreshMode: com.sweefl.player.domain.GlassRefreshMode
        get() = com.sweefl.player.domain.GlassRefreshMode.decode(store.getString("glass_refresh_mode", null))
        set(value) { store.edit().putString("glass_refresh_mode", value.name).apply() }

    fun observeGlassRefresh(onChanged: (com.sweefl.player.domain.GlassRefreshMode) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "glass_refresh_mode") onChanged(glassRefreshMode)
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        return { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var interactions: PlaybackInteractionSettings
        get() = PlaybackInteractionSettings(
            showSeekPreview = store.getBoolean("seek_preview", true),
            longPressSpeed = com.sweefl.player.domain.normalizeLongPressSpeed(
                runCatching { store.getFloat("long_press_speed", com.sweefl.player.domain.DEFAULT_LONG_PRESS_SPEED) }
                    .getOrDefault(com.sweefl.player.domain.DEFAULT_LONG_PRESS_SPEED)),
            leftDoubleTap = DoubleTapAction.decode(store.getString("double_tap_left", null), DoubleTapAction.BACK_10),
            centerDoubleTap = DoubleTapAction.decode(store.getString("double_tap_center", null), DoubleTapAction.PLAY_PAUSE),
            rightDoubleTap = DoubleTapAction.decode(store.getString("double_tap_right", null), DoubleTapAction.FORWARD_10),
        )
        set(value) {
            store.edit().putBoolean("seek_preview", value.showSeekPreview)
                .putString("double_tap_left", value.leftDoubleTap.name)
                .putString("double_tap_center", value.centerDoubleTap.name)
                .putString("double_tap_right", value.rightDoubleTap.name)
                .putFloat("long_press_speed", com.sweefl.player.domain.normalizeLongPressSpeed(value.longPressSpeed)).apply()
        }

    fun observeInteractions(onChanged: (PlaybackInteractionSettings) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "seek_preview" || key == "long_press_speed" || key?.startsWith("double_tap_") == true) onChanged(interactions)
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        return { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Returns an unsubscribe handle; storage does not depend on Compose. */
    fun observeGlass(onChanged: (Boolean) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "realtime_glass") onChanged(glassEnabled)
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        return { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
