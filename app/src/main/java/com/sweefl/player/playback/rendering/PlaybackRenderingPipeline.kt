@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.rendering

import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.sweefl.player.domain.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One immutable request per main playback session. Never attach this to seek-preview players. */
class PlaybackRenderingPipeline(val settings: PlaybackRenderSettings) : AutoCloseable {
    internal val evidence = FrameEvidenceBridge()
    val frames get() = evidence.frames
    fun onFrameMetadata(ptsUs: Long, releaseNs: Long) = evidence.metadata(ptsUs, releaseNs)
    private val mutableStatus = MutableStateFlow(PlaybackRenderStatus(settings.mode))
    val status: StateFlow<PlaybackRenderStatus> = mutableStatus.asStateFlow()
    private val gpuRendererCreated = AtomicBoolean(false)
    private var boundPlayer: ExoPlayer? = null
    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // Network/source errors remain distinct from GL failures.
            fail(PlaybackRenderFailureOrigin.PLAYER, error)
        }
        override fun onRenderedFirstFrame() {
            if (settings.mode == PlaybackRenderMode.NATIVE) record(PlaybackRenderEvent.FirstFrameReported)
        }
    }

    /** Caller supplies the complete original selector/adapter/fallback/event/timing configuration. */
    fun createVideoRenderer(builder: MediaCodecVideoRenderer.Builder): MediaCodecVideoRenderer {
        check(!status.value.released) { "Rendering pipeline is closed" }
        if (settings.mode == PlaybackRenderMode.NATIVE) return EvidenceVideoRenderer(builder, evidence)
        try {
            checkGpuContract()
            check(gpuRendererCreated.compareAndSet(false, true)) { "One GPU renderer per pipeline" }
            return GpuSdrVideoRenderer(builder.setVideoSink(null), this)
        } catch (error: Exception) {
            fail(PlaybackRenderFailureOrigin.SETUP, error)
            throw error
        }
    }

    /** Bind on applicationLooper before prepare. Native never calls setVideoEffects, even empty. */
    fun bind(player: ExoPlayer) {
        check(Looper.myLooper() == player.applicationLooper)
        check(boundPlayer == null && !status.value.released)
        check(player.playbackState == Player.STATE_IDLE) { "Bind before prepare" }
        try {
            if (settings.mode == PlaybackRenderMode.GPU_SDR) {
                checkGpuContract()
                check(gpuRendererCreated.get()) { "The GPU renderer factory must be wired first" }
            }
            boundPlayer = player
            player.addListener(listener)
            if (settings.mode == PlaybackRenderMode.GPU_SDR) player.setVideoEffects(createEffects())
        } catch (error: Exception) {
            fail(PlaybackRenderFailureOrigin.SETUP, error)
            player.removeListener(listener)
            boundPlayer = null
            throw error
        }
    }

    override fun close() {
        val player = boundPlayer
        if (player != null) check(Looper.myLooper() == player.applicationLooper)
        record(PlaybackRenderEvent.Released)
        player?.removeListener(listener)
        boundPlayer = null
    }
    internal fun record(event: PlaybackRenderEvent) { mutableStatus.update { it.accept(event) } }
    internal fun fail(origin: PlaybackRenderFailureOrigin, error: Exception) {
        record(PlaybackRenderEvent.Failed(PlaybackRenderFailure(origin,
            "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(512))))
    }
    private fun checkGpuContract() {
        check(Build.VERSION.SDK_INT >= 29) { "GPU_SDR requires Android 10 or newer" }
        check(MediaLibraryInfo.VERSION == "1.8.0") { "GPU_SDR bridge is audited for Media3 1.8.0 only" }
    }
    private fun createEffects(): List<Effect> = buildList {
        add(SdrEvidenceEffect(this@PlaybackRenderingPipeline))
        if (settings.brightness != 0f) add(Brightness(settings.brightness))
        if (settings.contrast != 0f) add(Contrast(settings.contrast))
        if (settings.saturation != 0f) add(HslAdjustment.Builder().adjustSaturation(settings.saturation).build())
    }
}

/** Outer GlEffect prevents the optimizer from bypassing our working-space evidence/check. */
private class SdrEvidenceEffect(private val owner: PlaybackRenderingPipeline) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int) = false
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        try {
            if (useHdr) throw VideoFrameProcessingException("Refusing SDR-only adjustments in HDR working space")
            val identity = object : GlMatrixTransformation {
                private val matrix = GlUtil.create4x4IdentityMatrix()
                override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray = matrix
                override fun configure(inputWidth: Int, inputHeight: Int): Size {
                    owner.record(PlaybackRenderEvent.SdrEffectConfigured(inputWidth, inputHeight))
                    return Size(inputWidth, inputHeight)
                }
            }
            val program = identity.toGlShaderProgram(context, false)
            owner.record(PlaybackRenderEvent.SdrShaderCreated)
            return program
        } catch (error: Exception) {
            owner.fail(PlaybackRenderFailureOrigin.EFFECT, error)
            throw VideoFrameProcessingException.from(error)
        }
    }
}
