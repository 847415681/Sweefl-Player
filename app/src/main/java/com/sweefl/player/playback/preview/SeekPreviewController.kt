@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.preview

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.sweefl.player.domain.PlaybackConfig
import com.sweefl.player.domain.PreviewRequestPolicy as Policy
import com.sweefl.player.domain.PreviewTimeoutAction
import com.sweefl.player.domain.matchPreviewFrame
import com.sweefl.player.domain.previewTimeoutAction
import com.sweefl.player.domain.previewDecodePosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class PreviewFrameState(val targetMs: Long = -1, val frameMs: Long? = null,
    val message: String = "加载预览…")

/** One paused, silent decoder, one in-flight request and one conflated latest target.
 * The owner may retain this controller between gestures, but an idle decoder lives only
 * briefly. Neither scheduling nor retries weaken the metadata/latched-texture timestamp join. */
internal class SeekPreviewController(private val context: Context, private val source: PreviewSource,
    private val diagnostics: Boolean = false) {
    private val mutableState = MutableStateFlow(PreviewFrameState())
    val state = mutableState.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val networkCache = PreviewNetworkCache(context, source)
    private var engine: ExoPlayer? = null
    private var view: TextureView? = null
    private var surface: Surface? = null
    @Volatile private var closed = false
    @Volatile private var engineEpoch = 0L
    private var visible = false
    private var terminal = false
    private var desired = -1L
    private var unavailableTarget = -1L
    private var serial = 0L
    private var endAdjustedGeneration = -1L
    private var recoveries = 0
    @Volatile private var flight: Request? = null
    private val metadata = ConcurrentLinkedDeque<FrameMetadata>()
    private var periodOffsetUs: Long? = null
    private var textureTimestamp = 0L
    private var verifiedTarget = -1L
    private var verifiedFrame: Long? = null
    private var verifiedTimestamp = 0L
    private var videoAspect = 16f / 9f
    private val requestBytes = AtomicLong()
    private val gestureBytes = AtomicLong()
    @Volatile private var budgetExhausted = false
    private var deadline: Runnable? = null
    private var dispatchScheduled = false
    private val dispatch = Runnable { dispatchScheduled = false; startLatest() }
    private val idleRelease = Runnable {
        if (!visible || surface == null) { finishFlight(); clearVerified(); releaseEngine() }
    }
    private val textureCheck = object : Runnable {
        override fun run() {
            if (closed || !visible || flight == null) return
            textureTimestamp = view?.takeIf { it.isAvailable }?.surfaceTexture?.timestamp ?: 0L
            confirmFrame()
            if (!closed && visible && flight != null) handler.postDelayed(this, 24)
        }
    }
    private data class Request(val generation: Long, val target: Long)
    private data class FrameMetadata(val generation: Long, val presentationUs: Long, val releaseNs: Long)
    private class PreviewBudgetException : IOException("Preview byte budget reached")

    /** Leading bounded coalescing, rather than a debounce that restarts on every movement. */
    fun show(target: Long) {
        if (closed) return
        handler.removeCallbacks(idleRelease)
        val rounded = bucket(target)
        if (!visible) {
            visible = true; terminal = false; unavailableTarget = -1; recoveries = 0
            gestureBytes.set(0); budgetExhausted = false
        }
        if (desired != rounded) {
            desired = rounded; unavailableTarget = -1
            if (verifiedFrame == null || view?.surfaceTexture?.timestamp != verifiedTimestamp)
                mutableState.value = PreviewFrameState(rounded)
        }
        if (!terminal) scheduleDispatch()
    }

    fun hide() {
        if (closed) return
        visible = false
        finishFlight()
        cancelDispatch()
        mutableState.value = PreviewFrameState()
        handler.removeCallbacks(idleRelease)
        handler.postDelayed(idleRelease, Policy.IDLE_RETENTION_MS)
    }

    /** Disabled preview and backgrounding release immediately, without poisoning this owner. */
    fun suspend() {
        hide()
        handler.removeCallbacks(idleRelease)
        clearVerified()
        releaseEngine()
    }

    fun attach(texture: TextureView) {
        if (closed) return
        if (view != null && view !== texture) detach(view!!)
        view = texture
        texture.isOpaque = false
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                if (closed || view !== texture) return
                surface = Surface(value)
                textureTimestamp = 0
                clearVerified()
                // Bind the new output only after startLatest establishes its request;
                // a paused renderer may emit its first frame immediately on surface change.
                fitVideo()
                if (visible) scheduleDispatch()
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { fitVideo() }
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                if (view === texture) detachSurface()
                return true
            }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
                if (closed || view !== texture) return
                textureTimestamp = value.timestamp
                if (verifiedFrame != null && textureTimestamp != verifiedTimestamp) {
                    // Even a cached frame is valid only while those exact pixels remain latched.
                    clearVerified()
                    mutableState.value = PreviewFrameState(desired)
                    if (visible) scheduleDispatch()
                }
                confirmFrame()
                handler.removeCallbacks(textureCheck)
                if (flight != null) handler.post(textureCheck)
            }
        }
    }

    fun detach(texture: TextureView) {
        if (view !== texture) return
        texture.surfaceTextureListener = null
        detachSurface()
        view = null
    }

    private fun detachSurface() {
        finishFlight(); clearVerified()
        engine?.clearVideoSurface()
        surface?.release(); surface = null
        textureTimestamp = 0
        mutableState.value = PreviewFrameState(desired)
        handler.removeCallbacks(idleRelease)
        handler.postDelayed(idleRelease, Policy.IDLE_RETENTION_MS)
    }

    fun close() {
        if (closed) return
        closed = true; visible = false
        handler.removeCallbacksAndMessages(null)
        try {
            finishFlight(); clearVerified(); releaseEngine()
        } finally {
            networkCache.close()
            view?.surfaceTextureListener = null
            surface?.release(); surface = null; view = null
        }
    }

    private fun scheduleDispatch(delayMs: Long = Policy.COALESCE_MS) {
        if (closed || !visible || terminal || dispatchScheduled) return
        dispatchScheduled = true
        handler.postDelayed(dispatch, delayMs)
    }
    private fun cancelDispatch() { handler.removeCallbacks(dispatch); dispatchScheduled = false }
    private fun clearVerified() { verifiedTarget = -1; verifiedFrame = null; verifiedTimestamp = 0 }
    private fun finishFlight() {
        deadline?.let(handler::removeCallbacks); deadline = null
        handler.removeCallbacks(textureCheck)
        flight = null; metadata.clear()
    }

    private fun startLatest() {
        if (closed || !visible || terminal || flight != null || surface == null || desired < 0 || unavailableTarget == desired) return
        if (verifiedTarget == desired && verifiedFrame != null && verifiedTimestamp > 0 &&
            view?.surfaceTexture?.timestamp == verifiedTimestamp) {
            mutableState.value = PreviewFrameState(desired, verifiedFrame, "")
            return
        }
        if (view?.surfaceTexture?.timestamp != verifiedTimestamp) clearVerified()
        val request = Request(++serial, desired)
        flight = request; metadata.clear(); periodOffsetUs = null
        requestBytes.set(0)
        if (verifiedFrame == null) mutableState.value = PreviewFrameState(desired)
        val cold = engine == null
        val timeout = Runnable { if (flight === request) onTimeout(request) }
        deadline = timeout
        handler.postDelayed(timeout, if (cold) Policy.COLD_TIMEOUT_MS else Policy.WARM_TIMEOUT_MS)
        try {
            val current = engine
            if (current == null) {
                val created = createEngine()
                engine = created
                trace("created active=${active.incrementAndGet()}")
                created.setMediaItem(MediaItem.Builder().setUri(source.uri).setMimeType(source.mimeType).build(), request.target)
                created.prepare()
            } else {
                current.setVideoSurface(surface)
                endAdjustedGeneration = request.generation
                current.seekTo(previewDecodePosition(request.target, current.duration))
            }
            updatePeriod()
            handler.post(textureCheck)
            trace("request generation=${request.generation} target=${request.target} cold=$cold")
        } catch (_: RuntimeException) { recover("renderer initialization") }
    }

    private fun onTimeout(request: Request) {
        trace("timeout target=${request.target} latest=$desired recoveries=$recoveries")
        when (previewTimeoutAction(request.target, desired, recoveries, budgetExhausted)) {
            PreviewTimeoutAction.ADVANCE_LATEST -> {
                // Keep the prepared source and decoder; an obsolete timeout isn't a failure
                // of the newest target. Only this one latest seek is submitted.
                finishFlight(); clearVerified(); startLatest()
            }
            PreviewTimeoutAction.RETRY -> recover("timeout")
            PreviewTimeoutAction.UNAVAILABLE -> unavailable("预览暂不可用", budgetExhausted)
        }
    }

    private fun recover(reason: String) {
        if (closed) return
        if (!visible) { finishFlight(); releaseEngine(); return }
        if (budgetExhausted || recoveries >= Policy.MAX_RECOVERIES) {
            // Repeated loader/decoder errors are gesture-wide: otherwise each pointer
            // bucket could rebuild the same failing engine (for example an HTTP 404).
            unavailable("预览暂不可用", true); return
        }
        recoveries++
        finishFlight(); clearVerified(); releaseEngine()
        mutableState.value = PreviewFrameState(desired, message = "重试预览…")
        trace("retry reason=$reason attempt=$recoveries latest=$desired")
        scheduleDispatch(200)
    }

    private fun createEngine(): ExoPlayer {
        val epoch = ++engineEpoch
        val counter = object : TransferListener {
            override fun onTransferInitializing(dataSource: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(dataSource: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferEnd(dataSource: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onBytesTransferred(dataSource: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork && epoch == engineEpoch && !closed) {
                    val bytes = requestBytes.addAndGet(bytesTransferred.toLong())
                    val total = gestureBytes.addAndGet(bytesTransferred.toLong())
                    if (bytes > Policy.REQUEST_BYTES || total > Policy.GESTURE_BYTES) {
                        budgetExhausted = true
                        throw PreviewBudgetException()
                    }
                }
            }
        }
        val headers = com.sweefl.player.playback.engine.playbackRequestHeaders(source.requestHeaders)
        val http = DefaultHttpDataSource.Factory().setUserAgent(headers["User-Agent"] ?: source.userAgent ?: PlaybackConfig.DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(3_000).setReadTimeoutMs(3_000).setTransferListener(counter)
        // The counter belongs only to HTTP upstream; cache-file hits never consume network budgets.
        val media = DefaultMediaSourceFactory(networkCache.factory(DefaultDataSource.Factory(context, http)))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
        val buffers = DefaultLoadControl.Builder().setBufferDurationsMs(250, 750, 50, 50)
            .setTargetBufferBytes(2 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        return ExoPlayer.Builder(context).setMediaSourceFactory(media).setLoadControl(buffers).build().apply {
            playWhenReady = false; volume = 0f
            setSeekParameters(SeekParameters.EXACT)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setMaxVideoSize(320, 180).setForceLowestBitrate(true).build()
            setVideoSurface(surface)
            setVideoFrameMetadataListener { presentationUs, releaseNs, _, _ ->
                val request = flight
                if (!closed && epoch == engineEpoch && request != null) {
                    metadata.addLast(FrameMetadata(request.generation, presentationUs, releaseNs))
                    while (metadata.size > 8) metadata.pollFirst()
                    handler.post { if (epoch == engineEpoch) confirmFrame() }
                }
            }
            addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) { if (epoch == engineEpoch) { updatePeriod(); confirmFrame() } }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (epoch == engineEpoch) { updatePeriod(); confirmFrame() }
                }
                override fun onPlaybackStateChanged(playbackState: Int) { if (epoch == engineEpoch) { updatePeriod(); confirmFrame() } }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (epoch != engineEpoch) return
                    if (videoSize.height > 0) videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    fitVideo()
                }
                override fun onTracksChanged(tracks: Tracks) {
                    if (epoch == engineEpoch && !tracks.isEmpty && tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO })
                        unavailable("此媒体没有视频画面", true)
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (epoch != engineEpoch) return
                    if (generateSequence<Throwable>(error) { it.cause }.any { it is PreviewBudgetException }) budgetExhausted = true
                    recover(error.errorCodeName)
                }
            })
        }
    }

    private fun updatePeriod() {
        val player = engine ?: return
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        if (timeline.periodCount != 1 || player.isCurrentMediaItemLive) {
            unavailable("当前流暂不支持画面预览", true); return
        }
        periodOffsetUs = timeline.getPeriod(player.currentPeriodIndex, Timeline.Period()).positionInWindowUs
        flight?.let { request ->
            val position = previewDecodePosition(request.target, player.duration)
            if (position != request.target && endAdjustedGeneration != request.generation) {
                endAdjustedGeneration = request.generation
                player.seekTo(position)
            }
        }
    }

    private fun confirmFrame() {
        if (closed || !visible || terminal) return
        val request = flight ?: return
        val offset = periodOffsetUs ?: return
        val frame = metadata.lastOrNull { it.generation == request.generation && it.releaseNs > 0 && it.releaseNs == textureTimestamp } ?: return
        // Validate the request that produced these exact pixels. During continuous dragging
        // it may lag the latest target; expose its ACTUAL timestamp and mark it as updating.
        // Never label an older verified frame as the newest target.
        val frameMs = matchPreviewFrame(frame.presentationUs, offset, request.target, frame.releaseNs, textureTimestamp)
        if (frameMs == null) {
            if (request.target != desired) { finishFlight(); clearVerified(); startLatest() }
            return
        }
        finishFlight()
        verifiedTarget = request.target; verifiedFrame = frameMs; verifiedTimestamp = textureTimestamp
        mutableState.value = PreviewFrameState(request.target, frameMs, "")
        trace("ready target=${request.target} frame=$frameMs releaseNs=${frame.releaseNs} textureNs=$textureTimestamp bytes=${gestureBytes.get()} latest=$desired")
        if (desired != request.target) scheduleDispatch()
    }

    private fun unavailable(message: String, permanentForGesture: Boolean) {
        if (closed) return
        terminal = permanentForGesture; unavailableTarget = desired
        finishFlight(); clearVerified(); cancelDispatch()
        mutableState.value = PreviewFrameState(desired, message = message)
        trace("unavailable reason=$message bytes=${gestureBytes.get()}")
        releaseEngine()
    }
    private fun releaseEngine() {
        val player = engine ?: return
        engine = null; engineEpoch++
        player.clearVideoSurface(); player.release()
        trace("released active=${active.decrementAndGet()}")
    }
    private fun fitVideo() {
        val texture = view ?: return
        if (texture.width < 1 || texture.height < 1 || videoAspect <= 0) return
        val aspect = texture.width.toFloat() / texture.height
        val scaleX = if (videoAspect < aspect) videoAspect / aspect else 1f
        val scaleY = if (videoAspect > aspect) aspect / videoAspect else 1f
        texture.setTransform(Matrix().apply { setScale(scaleX, scaleY, texture.width / 2f, texture.height / 2f) })
    }
    private fun trace(message: String) { if (diagnostics) Log.d("SweeflPreview", message) }
    companion object {
        private val active = AtomicInteger()
        fun bucket(target: Long): Long = target.coerceAtLeast(0) / 500 * 500
    }
}
