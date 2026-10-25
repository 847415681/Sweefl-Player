package com.sweefl.player.domain

/** Bilibili replay uses a positive CDN delay; zero must be represented by no query parameter. */
object LiveReplayRules {
    fun clampTarget(targetMs: Long, nowMs: Long, startedAtMs: Long, windowSeconds: Int): Long =
        targetMs.coerceIn(maxOf(startedAtMs, nowMs - windowSeconds.coerceAtLeast(0) * 1000L).coerceAtMost(nowMs), nowMs)

    fun delaySeconds(targetMs: Long, nowMs: Long, playlistDurationMs: Long = 0L): Long? {
        val lag = nowMs - targetMs
        if (lag <= 3_000L) return null
        // The returned playlist starts before its delayed edge. Compensate using the measured window.
        return ((lag - playlistDurationMs.coerceIn(0L, 30_000L) + 999L) / 1000L).coerceAtLeast(1L)
    }

    fun replayPosition(anchorEpochMs: Long, anchorWindowOffsetMs: Long, windowOffsetMs: Long, positionMs: Long): Long =
        anchorEpochMs + (windowOffsetMs - anchorWindowOffsetMs) + positionMs
}
