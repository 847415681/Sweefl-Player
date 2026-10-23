package com.sweefl.player.domain

/** Bounded scheduled-frame history; decoder counters can advance before a frame is displayed. */
class PresentedFrameTracker(private val capacity: Int = 32) {
    private data class Frame(val releaseNs: Long, val token: Int)
    private val frames = ArrayList<Frame>()
    private var sequence = 0
    private var presented = -1
    private var closed = false
    init { require(capacity > 0) }
    @Synchronized fun scheduled(releaseNs: Long) {
        if (closed) return
        if (releaseNs <= 0) { frames.clear(); presented = -1; return }
        sequence = (sequence + 1) and Int.MAX_VALUE
        frames.add(Frame(releaseNs, sequence))
        frames.sortBy { it.releaseNs }
        while (frames.size > capacity) frames.removeAt(0)
    }
    /** Caller supplies a cutoff allowing the Surface buffer to settle after its release deadline. */
    @Synchronized fun tokenAt(cutoffNs: Long): Int {
        if (closed) return -1
        while (frames.isNotEmpty() && frames[0].releaseNs <= cutoffNs) presented = frames.removeAt(0).token
        return presented
    }
    @Synchronized fun reset() { frames.clear(); presented = -1 }
    @Synchronized fun close() { closed = true; reset() }
}
