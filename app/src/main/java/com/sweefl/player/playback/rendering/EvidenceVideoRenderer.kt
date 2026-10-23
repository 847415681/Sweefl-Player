@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.sweefl.player.playback.rendering

import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/** Native path only: release to Surface successfully returned, not compositor latch proof. */
internal class EvidenceVideoRenderer(builder: MediaCodecVideoRenderer.Builder,
    private val evidence: FrameEvidenceBridge) : MediaCodecVideoRenderer(builder) {
    private var epoch = -1L
    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        epoch = evidence.frames.resetRenderer()
        super.onPositionReset(positionUs, joining)
    }
    override fun renderOutputBufferV21(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long) {
        super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs)
        evidence.frames.submitted(epoch, releaseTimeNs)
    }
    override fun renderOutputBuffer(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long) {
        super.renderOutputBuffer(codec, index, presentationTimeUs)
        evidence.frames.submitted(epoch, System.nanoTime())
    }
}
