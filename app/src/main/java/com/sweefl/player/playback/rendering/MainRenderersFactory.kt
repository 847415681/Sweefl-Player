@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.rendering

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.sweefl.player.domain.FfmpegPlaybackRules
import com.sweefl.player.domain.PlaybackConfig
import com.sweefl.player.domain.PlaybackRenderMode
import com.sweefl.player.playback.subtitles.ExternalSubtitleRenderersFactory
import com.sweefl.player.playback.subtitles.ExternalSubtitleSync
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import java.util.ArrayList

/** Main player only. FFmpeg is opt-in; platform renderers remain available for unsupported tracks. */
internal class MainRenderersFactory(context: Context, subtitles: ExternalSubtitleSync,
    private val rendering: PlaybackRenderingPipeline,
    private val decodeMode: String = PlaybackConfig.DECODE_AUTO,
) : ExternalSubtitleRenderersFactory(context, subtitles) {
    override fun buildVideoRenderers(context: Context, extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>) {
        if (FfmpegPlaybackRules.preferVideo(decodeMode, rendering.settings.mode)) {
            val evidence = FfmpegFrameEvidence(rendering.evidence)
            // Kotlin delegation does not forward Java default interface methods here.
            // Forward every callback explicitly or size/first-frame events silently disappear.
            val events = object : VideoRendererEventListener {
                override fun onVideoDecoderInitialized(name: String, timestampMs: Long, durationMs: Long) = eventListener.onVideoDecoderInitialized(name, timestampMs, durationMs)
                override fun onVideoInputFormatChanged(format: Format, evaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) = eventListener.onVideoInputFormatChanged(format, evaluation)
                override fun onDroppedFrames(count: Int, elapsedMs: Long) = eventListener.onDroppedFrames(count, elapsedMs)
                override fun onVideoFrameProcessingOffset(totalUs: Long, count: Int) = eventListener.onVideoFrameProcessingOffset(totalUs, count)
                override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) = eventListener.onVideoSizeChanged(size)
                override fun onRenderedFirstFrame(output: Any, renderTimeMs: Long) = eventListener.onRenderedFirstFrame(output, renderTimeMs)
                override fun onVideoDecoderReleased(name: String) = eventListener.onVideoDecoderReleased(name)
                override fun onVideoDisabled(counters: androidx.media3.exoplayer.DecoderCounters) = eventListener.onVideoDisabled(counters)
                override fun onVideoCodecError(error: Exception) = eventListener.onVideoCodecError(error)
                override fun onVideoEnabled(counters: androidx.media3.exoplayer.DecoderCounters) {
                    evidence.enabled(counters)
                    eventListener.onVideoEnabled(counters)
                }
            }
            out.add(SdrOnlyFfmpegRenderer(FfmpegVideoRenderer(allowedVideoJoiningTimeMs,
                eventHandler, events, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY), evidence))
        }
        // Native FFmpeg output bypasses the GPU graph: never register it in GPU_SDR mode.
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler).setEventListener(eventListener)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        out.add(rendering.createVideoRenderer(builder))
    }

    override fun buildAudioRenderers(context: Context, extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>) {
        // This exact vendor image SIGSEGVs in CCodecConfig while configuring AAC/ADTS
        // (HLS); native process death cannot be caught by Media3 decoder fallback.
        val brokenCodec2Image = android.os.Build.FINGERPRINT ==
            "Xiaomi/raphael/raphael:15/AQ3A.240812.002/OS2.0.203.0.VOCCNXM:user/release-keys"
        if (FfmpegPlaybackRules.preferAudio(decodeMode) || brokenCodec2Image) {
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        }
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, audioSink, eventHandler, eventListener, out)
    }
}

/** The native class and supportsFormat are final; gate track selection through delegated capabilities. */
internal class SdrOnlyFfmpegRenderer(private val delegate: Renderer,
    private val evidence: FfmpegFrameEvidence) : Renderer by delegate {
    override fun enable(configuration: androidx.media3.exoplayer.RendererConfiguration,
        formats: Array<Format>, stream: androidx.media3.exoplayer.source.SampleStream,
        positionUs: Long, joining: Boolean, mayRenderStartOfStream: Boolean,
        startPositionUs: Long, offsetUs: Long,
        mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId) {
        evidence.startEnable()
        delegate.enable(configuration, formats, stream, positionUs, joining,
            mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
    }
    // Kotlin interface delegation does not forward inherited Java default methods.
    // ExoPlayer grants this after enabling a paused renderer: swallowing it leaves the
    // restored Surface black until playback starts, even though decoding is ready.
    override fun enableMayRenderStartOfStream() = delegate.enableMayRenderStartOfStream()
    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) =
        delegate.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        delegate.getDurationToProgressUs(positionUs, elapsedRealtimeUs)
    override fun release() = delegate.release()

    override fun resetPosition(positionUs: Long) {
        evidence.reset()
        delegate.resetPosition(positionUs)
    }
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val before = evidence.begin() ?: return
        var success = false
        try { delegate.render(positionUs, elapsedRealtimeUs); success = true }
        finally { evidence.end(before, success) }
    }
    private val gatedCapabilities = object : RendererCapabilities by delegate.capabilities {
        override fun supportsFormat(format: Format): Int {
            val color = format.colorInfo
            val allowed = FfmpegPlaybackRules.allowsVideo(format.sampleMimeType, format.codecs,
                color?.colorTransfer == C.COLOR_TRANSFER_ST2084,
                color?.colorTransfer == C.COLOR_TRANSFER_HLG,
                color?.colorSpace == C.COLOR_SPACE_BT2020,
                color?.hdrStaticInfo != null)
            return if (allowed) delegate.capabilities.supportsFormat(format)
                else RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }

        override fun setListener(listener: RendererCapabilities.Listener) {
            // ExoPlayer identifies renderers by identity, not by their delegated capabilities.
            delegate.capabilities.setListener { listener.onRendererCapabilitiesChanged(this@SdrOnlyFfmpegRenderer) }
        }

        override fun clearListener() = delegate.capabilities.clearListener()
    }

    override fun getCapabilities(): RendererCapabilities = gatedCapabilities
}
