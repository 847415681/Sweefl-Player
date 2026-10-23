package com.sweefl.player.domain

/** Scheduling limits do not alter the actual-frame timestamp acceptance window. */
object PreviewRequestPolicy {
    const val COALESCE_MS = 80L
    const val COLD_TIMEOUT_MS = 8_000L
    const val WARM_TIMEOUT_MS = 5_000L
    const val IDLE_RETENTION_MS = 1_200L
    const val MAX_RECOVERIES = 1
    const val REQUEST_BYTES = 12L * 1024 * 1024
    const val GESTURE_BYTES = 32L * 1024 * 1024
}

enum class PreviewTimeoutAction { ADVANCE_LATEST, RETRY, UNAVAILABLE }

/** Obsolete work must not poison the newest target. Recovery is bounded per gesture. */
fun previewTimeoutAction(inFlightTarget: Long, latestTarget: Long, recoveries: Int,
    budgetExhausted: Boolean): PreviewTimeoutAction = when {
    budgetExhausted || latestTarget < 0 -> PreviewTimeoutAction.UNAVAILABLE
    inFlightTarget != latestTarget -> PreviewTimeoutAction.ADVANCE_LATEST
    recoveries < PreviewRequestPolicy.MAX_RECOVERIES -> PreviewTimeoutAction.RETRY
    else -> PreviewTimeoutAction.UNAVAILABLE
}

/** Seeking exactly to EOF may emit no frame. Stay within the original acceptance window. */
fun previewDecodePosition(targetMs: Long, durationMs: Long): Long =
    if (durationMs > 0) targetMs.coerceIn(0L, (durationMs - 50L).coerceAtLeast(0L))
    else targetMs.coerceAtLeast(0L)
