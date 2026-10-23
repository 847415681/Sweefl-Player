@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.sweefl.player.playback.rendering

import androidx.media3.exoplayer.DecoderCounters
import com.sweefl.player.domain.SubmittedFrameTracker

/** Only one enabled video renderer can own a batch. Metadata alone is never published. */
internal class FrameEvidenceBridge {
    val frames = SubmittedFrameTracker()
    private var batch: Batch? = null
    private data class Batch(val owner: Any, val epoch: Long, var releaseNs: Long? = null)
    var metadataCount = 0
        private set
    @Synchronized fun begin(owner: Any, epoch: Long) { check(batch == null); metadataCount = 0; batch = Batch(owner, epoch) }
    @Synchronized fun metadata(@Suppress("UNUSED_PARAMETER") ptsUs: Long, releaseNs: Long) {
        if (batch != null) { metadataCount++; batch?.releaseNs = releaseNs }
    }
    @Synchronized fun end(owner: Any, success: Boolean): Long? {
        val value = batch
        check(value?.owner === owner)
        batch = null
        return if (success) value?.releaseNs else null
    }
}

/** Wait for the real counter reference before consuming even a lone paused frame. */
internal class FfmpegFrameEvidence(private val bridge: FrameEvidenceBridge) {
    private var counters: DecoderCounters? = null
    private var epoch = -1L
    private var generation = 0L
    private val enableCallbacks = java.util.ArrayDeque<Long>()
    @Synchronized fun enabled(value: DecoderCounters) {
        val callbackGeneration = enableCallbacks.pollFirst() ?: return
        if (callbackGeneration == generation) counters = value
    }
    @Synchronized fun startEnable() {
        generation++; enableCallbacks.addLast(generation); counters = null; reset()
    }
    @Synchronized fun reset() { epoch = bridge.frames.resetRenderer() }
    @Synchronized fun begin(): Int? {
        val value = counters ?: return null
        bridge.begin(this, epoch)
        return value.renderedOutputBufferCount
    }
    @Synchronized fun end(before: Int, success: Boolean) {
        val releaseNs = bridge.end(this, success)
        if (releaseNs != null && bridge.metadataCount > 0 &&
            (counters?.renderedOutputBufferCount ?: before) - before == bridge.metadataCount) {
            bridge.frames.submitted(epoch, releaseNs)
        }
    }
}
