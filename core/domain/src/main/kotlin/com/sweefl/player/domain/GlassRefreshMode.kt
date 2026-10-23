package com.sweefl.player.domain

/** Requested decorative refresh rate; never exceeds the display's reported refresh rate. */
enum class GlassRefreshMode(val label: String, private val fps: Float?) {
    FPS_30("30 fps", 30f), FPS_60("60 fps", 60f), FPS_90("90 fps", 90f), DISPLAY("跟随屏幕刷新率", null);

    fun targetHz(displayHz: Float): Float {
        val display = if (displayHz.isFinite() && displayHz > 0f) displayHz else 60f
        return minOf(fps ?: display, display)
    }
    fun intervalNanos(displayHz: Float): Long = (1_000_000_000.0 / targetHz(displayHz)).toLong().coerceAtLeast(1L)

    companion object {
        fun decode(value: String?): GlassRefreshMode = entries.firstOrNull { it.name == value } ?: FPS_60
    }
}
