package com.sweefl.player.feature.player.platform

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class PlayerWindowController(private val activity: Activity) {
    private val audio = activity.getSystemService(AudioManager::class.java)
    fun brightness(): Float = activity.window.attributes.screenBrightness.takeIf { it >= 0f }
        ?: (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
    fun volume(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    fun setBrightness(value: Float) {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
    }
    fun setVolume(value: Float) {
        val next = (value * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt()
        if (next != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
    }
    fun showSystemBars(visible: Boolean) {
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) insets.show(WindowInsetsCompat.Type.systemBars()) else insets.hide(WindowInsetsCompat.Type.systemBars())
    }
    fun rotate(landscape: Boolean) {
        activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}
