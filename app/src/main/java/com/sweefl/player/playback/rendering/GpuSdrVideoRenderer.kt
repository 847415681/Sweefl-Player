@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.rendering

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import com.sweefl.player.domain.PlaybackRenderEvent
import com.sweefl.player.domain.PlaybackRenderFailureOrigin

/** Restricted bridge pinned to Media3 1.8.0; re-audit color order and this hook on upgrades. */
@SuppressLint("RestrictedApi")
internal class GpuSdrVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val owner: PlaybackRenderingPipeline,
) : MediaCodecVideoRenderer(builder) {
    private var epoch = -1L
    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        epoch = owner.frames.resetRenderer()
        super.onPositionReset(positionUs, joining)
    }
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        owner.evidence.begin(this, epoch)
        var success = false
        try { super.render(positionUs, elapsedRealtimeUs); success = true }
        finally {
            owner.evidence.end(this, success)?.let { owner.frames.submitted(epoch, it) }
        }
    }
    protected override fun createPlaybackVideoGraphWrapper(
        context: Context, videoFrameReleaseControl: VideoFrameReleaseControl,
    ): PlaybackVideoGraphWrapper {
        try {
            // Preserve injected clock, playlist mode and default disabled replay-cache policy.
            val wrapper = super.createPlaybackVideoGraphWrapper(context, videoFrameReleaseControl)
            wrapper.addListener(object : PlaybackVideoGraphWrapper.Listener {
                override fun onFirstFrameRendered() { owner.record(PlaybackRenderEvent.FirstFrameReported) }
                override fun onError(videoFrameProcessingException: VideoFrameProcessingException) {
                    owner.fail(PlaybackRenderFailureOrigin.GRAPH, videoFrameProcessingException)
                }
            })
            wrapper.setRequestOpenGlToneMapping(true)
            owner.record(PlaybackRenderEvent.GraphRequested)
            return wrapper
        } catch (error: Exception) {
            owner.fail(PlaybackRenderFailureOrigin.SETUP, error)
            throw error
        }
    }
}
