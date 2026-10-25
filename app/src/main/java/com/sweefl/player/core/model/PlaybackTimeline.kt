package com.sweefl.player.core.model

/** Coordinates are milliseconds; live sources may use wall time. Never convert epochs to Float. */
data class PlaybackTimeline(
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val positionMs: Long = 0L,
    val isLive: Boolean = false,
    val seekable: Boolean = false,
    val atLiveEdge: Boolean = false
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val liveOffsetMs: Long get() = (endMs - positionMs).coerceAtLeast(0L)
    fun clamp(position: Long): Long = position.coerceIn(startMs, maxOf(startMs, endMs))
}
