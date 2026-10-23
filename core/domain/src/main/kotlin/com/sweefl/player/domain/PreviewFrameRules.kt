package com.sweefl.player.domain

/** Standard direct MediaCodec output stamps the Surface with releaseTimeNs. A timestamp
 * join rejects an older queued texture; period offsets convert sample time to window time.
 * Returns actual frame time, never the requested time masquerading as a decoded result. */
fun matchPreviewFrame(presentationUs: Long, periodOffsetUs: Long, targetMs: Long,
    releaseTimeNs: Long, surfaceTimestampNs: Long): Long? {
    if (releaseTimeNs <= 0 || releaseTimeNs != surfaceTimestampNs || targetMs < 0) return null
    return try {
        val windowUs = Math.addExact(presentationUs, periodOffsetUs)
        val difference = Math.subtractExact(windowUs, Math.multiplyExact(targetMs, 1000L))
        if (difference !in -100_000L..500_000L) null else windowUs.coerceAtLeast(0) / 1000
    } catch (_: ArithmeticException) { null }
}
