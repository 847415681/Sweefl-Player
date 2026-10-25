package com.sweefl.player.core.designsystem

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import kotlin.math.min

internal data class GlassVideoFrame(val bitmap: Bitmap, val origin: Offset, val width: Float, val height: Float) {
    val shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
        if (Build.VERSION.SDK_INT >= 33) setFilterMode(android.graphics.BitmapShader.FILTER_MODE_LINEAR)
    }
}
/** Readiness for mounting a complete optical control group, without exposing frame internals. */
val LocalGlassReady = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }
class GlassCapturePolicy(val modeName: String, val intervalNanos: (Float) -> Long, val diagnostics: Boolean = false)
val LocalGlassCapturePolicy = staticCompositionLocalOf { GlassCapturePolicy("FPS_60", { 16_666_666L }) }
internal val LocalGlassVideoFrame = staticCompositionLocalOf<State<GlassVideoFrame?>> { mutableStateOf(null) }

internal fun glassContentReady(enabled: Boolean, videoBackdrop: Boolean, frameAvailable: Boolean): Boolean =
    !enabled || Build.VERSION.SDK_INT < 33 || !videoBackdrop || frameAvailable

/** Shared SDR decoration; the primary SurfaceView remains the native video/HDR renderer. */
@Composable
fun VideoGlassProvider(source: SurfaceView?, playing: Boolean, active: Boolean, enabled: Boolean,
    videoWidth: Int = 0, videoHeight: Int = 0, contentRevision: Long = 0L, buffering: Boolean = false,
    videoFrameToken: (Long) -> Int = { -1 }, videoFrameRevision: () -> Long = { -1L },
    handoff: OpticalSeekHandoff? = null, videoExpected: Boolean = true,
    content: @Composable () -> Unit) {
    val frame = remember(source) { mutableStateOf<GlassVideoFrame?>(null) }
    val currentPlaying = rememberUpdatedState(playing)
    val currentBuffering = rememberUpdatedState(buffering)
    val currentActive = rememberUpdatedState(active)
    val encodedSize = rememberUpdatedState(videoWidth to videoHeight)
    val revision = rememberUpdatedState(contentRevision)
    val frameToken = rememberUpdatedState(videoFrameToken)
    val frameRevision = rememberUpdatedState(videoFrameRevision)
    val refreshMode = rememberUpdatedState(LocalGlassCapturePolicy.current)
    val captureRef = remember { arrayOfNulls<GlassCapture>(1) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(source, enabled, owner, videoExpected) {
        var capture: GlassCapture? = null
        fun start() {
            if (capture == null && source != null && enabled && videoExpected && Build.VERSION.SDK_INT >= 33) {
                capture = GlassCapture(source, { currentActive.value || handoff?.running == true }, { currentPlaying.value }, { currentBuffering.value }, { refreshMode.value }, { encodedSize.value }, { revision.value }, { cutoffNs -> frameToken.value(cutoffNs) }, { frameRevision.value() },
                    onInvalidated = { handoff?.cancel(completePending = true) },
                    onEligible = { handoff?.accept(it, true) }) { frame.value = it }
                captureRef[0] = capture
                capture?.start()
            }
        }
        fun stop() {
            if (captureRef[0] === capture) captureRef[0] = null
            capture?.stop(); capture = null; frame.value = null
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> start()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) start()
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        captureRef[0]?.onPlaybackChanged()
        handoff?.supported = enabled && Build.VERSION.SDK_INT >= 33 && view.isHardwareAccelerated && source != null
        handoff?.visible = active && frame.value != null
        if (handoff?.phase == 1) handoff.pin(frame.value)
        if ((!enabled || !active) && handoff?.running == true) handoff.cancel(completePending = true)
    }
    val phase = handoff?.phase ?: 0
    val epoch = handoff?.epoch ?: 0L
    DisposableEffect(view, handoff, phase, epoch) {
        val callback = Runnable { view.post { handoff?.committed(epoch, phase) } }
        if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated && phase in listOf(2, 4, 5, 6)) {
            view.viewTreeObserver.registerFrameCommitCallback(callback)
            view.invalidate()
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= 29 && view.viewTreeObserver.isAlive)
                view.viewTreeObserver.unregisterFrameCommitCallback(callback)
        }
    }
    DisposableEffect(handoff, source) {
        onDispose { handoff?.cancel(completePending = true) }
    }
    val displayed = remember(frame, handoff) { derivedStateOf { handoff?.pinned ?: frame.value } }
    val ready = remember(displayed, enabled, videoExpected) { derivedStateOf { glassContentReady(enabled, videoExpected, displayed.value != null) } }
    CompositionLocalProvider(LocalGlassReady provides ready, LocalGlassVideoFrame provides displayed, LocalGlassEnabled provides enabled, LocalVideoGlassBackdrop provides videoExpected, content = content)
}

/** Main-thread scheduling/publication, one asynchronous copy in flight, no queued stale frames. */
private class GlassCapture(private val source: SurfaceView, private val active: () -> Boolean, private val playing: () -> Boolean,
    private val buffering: () -> Boolean, private val mode: () -> GlassCapturePolicy, private val encodedSize: () -> Pair<Int, Int>,
    private val contentRevision: () -> Long, private val frameToken: (Long) -> Int,
    private val frameRevision: () -> Long,
    private val onInvalidated: () -> Unit,
    private val onEligible: (GlassVideoFrame) -> Unit,
    private val publish: (GlassVideoFrame?) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val worker = HandlerThread("SweeflGlassCopy").apply { start() }
    private val copyHandler = Handler(worker.looper)
    @Volatile private var stopped = false
    private var copying = false
    private var lastFrameToken = -1
    private var rendererRevision = frameRevision()
    private var settlingCopies = 2
    private var front: GlassVideoFrame? = null
    private var back: GlassVideoFrame? = null
    private var wasPlaying = playing()
    private var previousMode = mode()
    private var previousRevision = contentRevision()
    private var burstUntilNs = System.nanoTime() + 500_000_000L
    private var nextFrameNs = 0L
    private val location = IntArray(2)
    private var samples = 0
    private var totalCopyMs = 0.0
    private var maxCopyMs = 0.0
    private var statsStartNs = System.nanoTime()
    private var generation = 0
    private var wasActive = active()
    private var nextTransitionCopyNs = 0L
    private val layoutListener = android.view.View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (l != ol || t != ot || r != or || b != ob) invalidateGeometry()
    }
    private val surfaceCallback = object : android.view.SurfaceHolder.Callback {
        override fun surfaceCreated(holder: android.view.SurfaceHolder) = invalidateGeometry(discard = true)
        override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = invalidateGeometry()
        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = invalidateGeometry(discard = true)
    }
    private val resumeTick: Runnable = Runnable { if (!stopped) choreographer.postFrameCallback(tick) }
    private val tick: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (stopped) return
            if (rendererRevision != frameRevision()) {
                rendererRevision = frameRevision()
                generation++; lastFrameToken = -1; settlingCopies = 2
                nextTransitionCopyNs = 0L
                nextFrameNs = 0L
            }
            // Phase-based deadlines avoid reducing 60fps to 48fps on a 144Hz display.
            if (active() && frameTimeNanos + 1_000_000L >= nextFrameNs && capture()) {
                val interval = mode().intervalNanos(source.display?.refreshRate ?: 60f)
                nextFrameNs = if (nextFrameNs == 0L || frameTimeNanos - nextFrameNs > interval)
                    frameTimeNanos + interval else nextFrameNs + interval
            }
            // Invalidation must be observed before the delayed seek changes Surface output,
            // including the FIRST seek after a long pause. This cheap revision/token check
            // stays display-aligned; capture() still deduplicates paused bitmap copies.
            choreographer.postFrameCallback(this)
        }
    }
    fun start() {
        source.addOnLayoutChangeListener(layoutListener)
        source.holder.addCallback(surfaceCallback)
        choreographer.postFrameCallback(tick)
    }
    private fun invalidateGeometry(discard: Boolean = false) {
        if (stopped) return
        onInvalidated()
        generation++; lastFrameToken = -1; settlingCopies = 2; nextFrameNs = 0L
        if (discard || source.width <= 0 || source.height <= 0) {
            front = null; back = null; publish(null)
        } else {
            source.getLocationOnScreen(location)
            front = front?.let { GlassVideoFrame(it.bitmap, Offset(location[0].toFloat(), location[1].toFloat()),
                source.width.toFloat(), source.height.toFloat()) }
            publish(front)
        }
        nextTransitionCopyNs = 0L
        burstUntilNs = System.nanoTime() + 500_000_000L
        handler.removeCallbacks(resumeTick); choreographer.removeFrameCallback(tick)
        choreographer.postFrameCallback(tick)
    }
    fun onPlaybackChanged() {
        val nowActive = active()
        if (wasActive != nowActive) {
            wasActive = nowActive; nextFrameNs = 0L
            // Hidden playback advances independently. Never reveal its retained old sample.
            if (nowActive && playing()) { generation++; lastFrameToken = -1; publish(null) }
        }
        if (!stopped && previousRevision != contentRevision()) {
            previousRevision = contentRevision()
            // Timeline changes do not invalidate the geometry of the visible Surface.
            // Retain its last observation only until a fresh current-generation copy arrives;
            // independently reject all copies initiated before this revision.
            generation++; lastFrameToken = -1; settlingCopies = 2; nextFrameNs = 0L
            nextTransitionCopyNs = 0L
            burstUntilNs = System.nanoTime() + 500_000_000L
            handler.removeCallbacks(resumeTick); choreographer.removeFrameCallback(tick)
            choreographer.postFrameCallback(tick)
        }
        val nowPlaying = playing()
        if (!stopped && (wasPlaying != nowPlaying || previousMode != mode())) {
            wasPlaying = nowPlaying; previousMode = mode(); nextFrameNs = 0L
            burstUntilNs = System.nanoTime() + 500_000_000L
            handler.removeCallbacks(resumeTick); choreographer.removeFrameCallback(tick)
            choreographer.postFrameCallback(tick)
            report(reset = true)
        }
    }
    fun stop() {
        stopped = true
        source.removeOnLayoutChangeListener(layoutListener)
        source.holder.removeCallback(surfaceCallback)
        choreographer.removeFrameCallback(tick); handler.removeCallbacks(resumeTick)
        worker.quitSafely()
        // Published bitmaps may still be referenced by RenderThread; never recycle them here.
        front = null; back = null
        if (mode().diagnostics) android.util.Log.d("SweeflGlass", "capture stopped")
    }
    private fun capture(): Boolean {
        if (copying || !source.isAttachedToWindow || !source.holder.surface.isValid || source.width < 1 || source.height < 1) return false
        // A scheduled buffer may not be visible yet. Allow one display period after its
        // metadata deadline, then deduplicate actual eligible frames instead of decoder counts.
        val displayHz = source.display?.refreshRate?.takeIf { it.isFinite() && it > 0f } ?: 60f
        val settledAt = System.nanoTime() - (1_000_000_000.0 / displayHz.coerceIn(30f, 240f)).toLong()
        val requestRevision = frameRevision()
        val token = frameToken(settledAt)
        if (requestRevision != rendererRevision || requestRevision != frameRevision()) return false
        // Decoder evidence may be absent for seconds while buffering. The Surface still
        // has a displayed image: sample that image afresh instead of replacing optics with
        // a tinted rectangle. This is display-backdrop sampling, not a claim that the
        // requested seek position has already rendered. Never republish a cached old epoch.
        val nowNs = System.nanoTime()
        // Metadata precedes Surface presentation; briefly sample through that handoff.
        val settlingBurst = nowNs < burstUntilNs
        val transitionCopy = token < 0 || (buffering() && token == lastFrameToken)
        if (transitionCopy && !settlingBurst && nowNs < nextTransitionCopyNs) return false
        if (!transitionCopy && !settlingBurst && token == lastFrameToken && settlingCopies <= 0) return false
        if (transitionCopy) nextTransitionCopyNs = nowNs + 66_666_667L
        source.getLocationOnScreen(location)
        // PixelCopy's source rectangle uses buffer pixels, not SurfaceView layout pixels.
        // Copy the whole buffer, then map it to the whole displayed SurfaceView (also when
        // its bounds extend outside the window in zoom mode). Never crop using view pixels.
        // Match visible pixel detail, without enlarging beyond the decoded video's detail.
        // The bound protects very large zoom layouts; it no longer reduces an entire HD
        // picture to a thumbnail before magnifying it inside every control.
        val (encodedWidth, encodedHeight) = encodedSize()
        val nativeScale = if (encodedWidth > 0 && encodedHeight > 0)
            min(encodedWidth.toFloat() / source.width, encodedHeight.toFloat() / source.height) else 1f
        val scale = min(min(1f, nativeScale), min(2560f / source.width, 1440f / source.height))
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val origin = Offset(location[0].toFloat(), location[1].toFloat())
        val bitmap = try {
            back?.bitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            stop(); publish(null)
            return false
        }
        // Reuse pixel buffers, but use a fresh shader for the mutable bitmap's new contents.
        val target = GlassVideoFrame(bitmap, origin, source.width.toFloat(), source.height.toFloat())
        back = target // Retain the buffer even before the first successful copy.
        val surface = source.holder.surface
        val startedNs = System.nanoTime()
        val requestGeneration = generation
        val requestContentRevision = contentRevision()
        copying = true
        copyHandler.post {
            if (!stopped) {
                try {
                    PixelCopy.request(surface, target.bitmap, { result ->
                        copying = false
                        if (!stopped && result == PixelCopy.SUCCESS) {
                            source.getLocationOnScreen(location)
                            if (!active() || !source.isAttachedToWindow || requestContentRevision != contentRevision() ||
                                requestGeneration != generation || requestRevision != frameRevision() ||
                                !surface.isValid || surface !== source.holder.surface) {
                                // Drop obsolete work; never erase the current observation.
                                handler.removeCallbacks(resumeTick); choreographer.removeFrameCallback(tick)
                                choreographer.postFrameCallback(tick)
                                return@request
                            }
                            if (source.width.toFloat() != target.width ||
                                source.height.toFloat() != target.height || location[0].toFloat() != target.origin.x ||
                                location[1].toFloat() != target.origin.y) {
                                invalidateGeometry()
                                return@request
                            }
                            if (!transitionCopy) {
                                if (lastFrameToken >= 0 && settlingCopies > 0) settlingCopies--
                                lastFrameToken = token
                            }
                            back = front; front = target
                            publish(target)
                            if (token >= 0 && !buffering() && settlingCopies <= 0) onEligible(target)
                            if (mode().diagnostics) {
                                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000.0
                                samples++; totalCopyMs += elapsedMs; maxCopyMs = max(maxCopyMs, elapsedMs)
                                report()
                            }
                        }
                    }, handler)
                } catch (_: IllegalArgumentException) { handler.post { copying = false } }
            }
        }
        return true
    }
    private fun report(reset: Boolean = false) {
        if (!mode().diagnostics) return
        val now = System.nanoTime()
        val elapsed = (now - statsStartNs) / 1_000_000_000.0
        if (!reset && elapsed >= 2.0 && samples > 0) {
            android.util.Log.d("SweeflGlass", "mode=${mode().modeName} displayHz=${source.display?.refreshRate} playing=${playing()} samples=$samples seconds=$elapsed fps=${samples / elapsed} copyMeanMs=${totalCopyMs / samples} copyMaxMs=$maxCopyMs")
        }
        if (reset || elapsed >= 2.0) {
            statsStartNs = now; samples = 0; totalCopyMs = 0.0; maxCopyMs = 0.0
        }
    }
}
