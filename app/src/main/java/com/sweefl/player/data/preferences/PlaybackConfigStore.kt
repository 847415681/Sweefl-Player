package com.sweefl.player.data.preferences

import com.sweefl.player.domain.PlaybackConfig

import android.content.Context
import com.sweefl.player.data.common.uriStorageKey

internal class PlaybackConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_config", Context.MODE_PRIVATE)
    fun renderSettings(uri: String): com.sweefl.player.domain.PlaybackRenderSettings {
        val key = uriStorageKey(uri)
        val all = prefs.all
        return com.sweefl.player.domain.PlaybackRenderSettings.fromStored(
            all["render_mode_$key"] as? String, (all["render_brightness_$key"] as? Float) ?: 0f,
            (all["render_contrast_$key"] as? Float) ?: 0f, (all["render_saturation_$key"] as? Float) ?: 0f)
    }
    fun saveRenderSettings(uri: String, value: com.sweefl.player.domain.PlaybackRenderSettings) {
        val key = uriStorageKey(uri)
        prefs.edit().putString("render_mode_$key", value.mode.name).putFloat("render_brightness_$key", value.brightness)
            .putFloat("render_contrast_$key", value.contrast).putFloat("render_saturation_$key", value.saturation).apply()
    }
    fun subtitleOffsetMs(uri: String): Long = com.sweefl.player.domain.SubtitleSyncRules.clampOffsetMs(
        (prefs.all["subtitle_offset_ms_${uriStorageKey(uri)}"] as? Long) ?: 0L)
    fun saveSubtitleOffsetMs(uri: String, value: Long) {
        prefs.edit().putLong("subtitle_offset_ms_${uriStorageKey(uri)}",
            com.sweefl.player.domain.SubtitleSyncRules.clampOffsetMs(value)).apply()
    }
    fun userAgent(uri: String): String? = prefs.getString("ua_${uriStorageKey(uri)}", null)
    fun mimeType(uri: String): String? = prefs.getString("mime_${uriStorageKey(uri)}", null)
    fun saveMimeType(uri: String, mime: String?) { prefs.edit().putString("mime_${uriStorageKey(uri)}", mime).apply() }
    fun saveUserAgent(uri: String, userAgent: String?) {
        prefs.edit().putString("ua_${uriStorageKey(uri)}", PlaybackConfig.validateUserAgent(userAgent)).apply()
    }
}
