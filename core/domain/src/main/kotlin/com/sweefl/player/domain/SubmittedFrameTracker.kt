package com.sweefl.player.domain

import java.util.concurrent.atomic.AtomicLong

/** Submitted output, NOT proof of compositor presentation. All deadlines use nanoTime. */
class SubmittedFrameTracker {
    private data class Frame(val token: Int, val releaseNs: Long)
    private val pending = ArrayList<Frame>()
    private var sequence = 0
    private var eligible = -1
    private var closed = false
    private var revision = ids.incrementAndGet()
    private var rendererEpoch = -1L
    private var waitingForSeekBarrier = false
    private data class Retained(val epoch: Long, val eligible: Int, val pending: List<Frame>)
    private var retained: Retained? = null
    @Synchronized fun requestSeek(): Long {
        if (rendererEpoch >= 0) retained = Retained(rendererEpoch, eligible, pending.toList())
        invalidate(); waitingForSeekBarrier = true
        return revision
    }
    @Synchronized fun armSeek(request: Long) {
        if (request == revision) waitingForSeekBarrier = false
    }

    /** FIFO acknowledgement after seekToInternal: only a genuine no-reset may retain output. */
    @Synchronized fun completeSeek(request: Long) {
        if (closed || request != revision || waitingForSeekBarrier) return
        val previous = retained ?: return
        rendererEpoch = previous.epoch
        eligible = previous.eligible
        pending.addAll(previous.pending)
        retained = null
    }

    @Synchronized fun revision(): Long = revision
    /** Synchronous application-thread barrier. Old renderer work cannot satisfy it. */
    @Synchronized fun invalidate() {
        revision = ids.incrementAndGet(); rendererEpoch = -1L
        pending.clear(); eligible = -1
    }
    /** Called on the playback thread BEFORE resetting renderer output. */
    @Synchronized fun resetRenderer(): Long {
        if (waitingForSeekBarrier) return -1L
        invalidate(); retained = null
        if (!closed) rendererEpoch = revision
        return rendererEpoch
    }
    @Synchronized fun submitted(epoch: Long, releaseNs: Long) {
        if (closed || epoch < 0 || epoch != rendererEpoch) return
        pending.add(Frame(++sequence, releaseNs))
        pending.sortBy { it.releaseNs }
        while (pending.size > 32) pending.removeAt(0)
    }
    @Synchronized fun tokenAt(cutoffNs: Long): Int {
        while (pending.isNotEmpty() && pending.first().releaseNs <= cutoffNs) {
            eligible = maxOf(eligible, pending.removeAt(0).token)
        }
        return eligible
    }
    @Synchronized fun close() { invalidate(); closed = true }
    private companion object { val ids = AtomicLong() }
}
