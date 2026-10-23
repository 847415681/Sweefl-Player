package com.sweefl.player.domain

/** Per-engine snapshot. Time is a target, bounded by Media3's byte budget. */
data class NetworkPlaybackSettings(val threads: Int = 4, val bufferSeconds: Int = 30) {
    fun normalized() = copy(threads = threads.coerceIn(1, 32), bufferSeconds = bufferSeconds.coerceIn(5, 600))
    val bufferDurationMs: Int get() = normalized().bufferSeconds * 1000
    companion object {
        const val TARGET_BUFFER_BYTES = 64 * 1024 * 1024
        /** Wrong-type/corrupt preferences cannot crash playback or request unbounded resources. */
        fun fromStored(threads: Any?, bufferSeconds: Any?) = NetworkPlaybackSettings(
            threads = threads as? Int ?: 4,
            bufferSeconds = bufferSeconds as? Int ?: 30
        ).normalized()
        /** User input is validated, never silently clamped on Save. */
        fun parse(threads: String, bufferSeconds: String): NetworkPlaybackSettings? {
            val count = threads.trim().toIntOrNull() ?: return null
            val seconds = bufferSeconds.trim().toIntOrNull() ?: return null
            return if (count in 1..32 && seconds in 5..600) NetworkPlaybackSettings(count, seconds) else null
        }
    }
}
