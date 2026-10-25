package com.sweefl.player.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect as ScreenRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Composite includes lower controls and this modal's dim. Do not apply video-only tint twice. */
internal val LocalGlassBackdropIsComposite = staticCompositionLocalOf { false }

internal fun View.glassOwnerWindow(): Window? {
    var node: android.view.ViewParent? = parent
    while (node != null) {
        if (node is DialogWindowProvider) return node.window
        node = node.parent
    }
    var candidate: Context = context
    while (candidate is ContextWrapper) {
        if (candidate is Activity) return candidate.window
        val next = candidate.baseContext
        if (next === candidate) break
        candidate = next
    }
    return (candidate as? Activity)?.window
}

/**
 * PixelCopy(Window) copies that Window's buffer, not SurfaceFlinger's final scene: the
 * SurfaceView video is a separate layer. Its transparent hole must be filled UNDER the UI.
 * A separate Dialog Window is deliberately never captured into its own backdrop.
 */
@Composable
internal fun DialogBackdropProvider(sourceWindow: Window?, underlay: State<GlassVideoFrame?>,
    content: @Composable ((ScreenRect?) -> Unit) -> Unit) {
    val dialogView = LocalView.current
    // Grow support in three bounded steps, never shrink/restart while this modal lives.
    // A larger crop replaces the retained valid composite only after its copy completes.
    val support = LocalGlassBlurAmount.current.coerceIn(0f, 1f) * 72f + 16f
    val requestedPadding = when {
        support <= 16f -> 16f
        support <= 40f -> 40f
        support <= 64f -> 64f
        else -> 88f
    }
    var retainedPadding by remember(sourceWindow, dialogView) { mutableFloatStateOf(requestedPadding) }
    val padding = maxOf(retainedPadding, requestedPadding)
    val paddingPx = with(LocalDensity.current) { padding.dp.toPx() }
    val latestPadding = rememberUpdatedState(paddingPx)
    val bounds = remember(sourceWindow, dialogView) { mutableStateOf<ScreenRect?>(null) }
    val captureRef = remember(sourceWindow, dialogView) { arrayOfNulls<DialogBackdropCapture>(1) }
    SideEffect {
        if (retainedPadding < padding) {
            retainedPadding = padding
            captureRef[0]?.invalidateCrop()
        }
    }
    val reportBounds: (ScreenRect?) -> Unit = remember(sourceWindow, dialogView) {
        { value ->
            if (bounds.value != value) {
                bounds.value = value
                captureRef[0]?.invalidateCrop()
            }
        }
    }
    val owner = LocalLifecycleOwner.current
    val enabled = LocalGlassEnabled.current
    val inheritedVideo = LocalVideoGlassBackdrop.current
    val composite = remember(sourceWindow, dialogView) { mutableStateOf<GlassVideoFrame?>(null) }
    val latestUnderlay = rememberUpdatedState(underlay)
    val latestPolicy = rememberUpdatedState(LocalGlassCapturePolicy.current)
    // A two-pixel input footprint is below the Gaussian support here; foreground text
    // and the sharp optical rim still render at full resolution. Zero blur stays 1:1.
    val blurPixels = with(LocalDensity.current) { (LocalGlassBlurAmount.current * 24f).dp.toPx() }
    val latestScale = rememberUpdatedState(glassTransmissionScale(blurPixels))
    DisposableEffect(sourceWindow, dialogView, owner, enabled, inheritedVideo) {
        var capture: DialogBackdropCapture? = null
        fun start() {
            if (capture == null && enabled && Build.VERSION.SDK_INT >= 33 && sourceWindow != null) {
                capture = DialogBackdropCapture(sourceWindow, dialogView,
                    { latestUnderlay.value.value }, inheritedVideo, { bounds.value }, { latestPadding.value },
                    { latestPolicy.value.intervalNanos(dialogView.display?.refreshRate ?: 60f) },
                    { latestScale.value }, { composite.value = it }).also { captureRef[0] = it; it.start() }
            }
        }
        fun stop() { capture?.stop(); capture = null; captureRef[0] = null; composite.value = null }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    val ready = remember(composite, enabled) { derivedStateOf {
        !enabled || Build.VERSION.SDK_INT < 33 || composite.value != null
    } }
    CompositionLocalProvider(LocalGlassReady provides ready, LocalGlassVideoFrame provides composite,
        LocalVideoGlassBackdrop provides true, LocalGlassBackdropIsComposite provides true,
        content = { content(reportBounds) })
}

/** One flight, no queued frames. Start-to-start deadlines include copy/composition work.
 * No bitmap recycling while RenderThread may still hold a shader. */
private class DialogBackdropCapture(private val source: Window, private val dialogView: View,
    private val underlay: () -> GlassVideoFrame?, private val needsVideo: Boolean,
    private val bounds: () -> ScreenRect?, private val paddingPx: () -> Float,
    private val intervalNanos: () -> Long, private val sampleScale: () -> Float,
    private val publish: (GlassVideoFrame?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("SweeflDialogCopy").apply { start() }
    private val worker = Handler(thread.looper)
    @Volatile private var stopped = false
    private var copying = false
    private var ui: Bitmap? = null
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private val choreographer = Choreographer.getInstance()
    private var nextCaptureNanos = 0L
    private val tick = Choreographer.FrameCallback { capture(it) }
    private var geometryRevision = 0L
    private var sourceDrawRevision = 0L
    private var sampledDrawRevision = -1L
    private var sampledGeometryRevision = -1L
    private var sampledBase: GlassVideoFrame? = null
    private var sampledDim = -1f
    private var sampledScale = -1f
    private val sourceDrawListener = android.view.ViewTreeObserver.OnDrawListener { sourceDrawRevision++ }
    fun invalidateGeometry() {
        publish(null)
        invalidateCrop()
    }
    fun invalidateCrop() {
        geometryRevision++
        choreographer.removeFrameCallback(tick)
        if (!stopped && !copying) choreographer.postFrameCallback(tick)
    }
    private var observedDecor: View? = null
    private data class SourceGeometry(val decor: View, val modal: Window,
        val width: Int, val height: Int, val x: Int, val y: Int)
    private var lastSourceGeometry: SourceGeometry? = null
    private val sourceLayoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (l != ol || t != ot || r != or || b != ob) invalidateGeometry()
    }
    private fun observeSource(decor: View?) {
        if (observedDecor === decor) return
        observedDecor?.removeOnLayoutChangeListener(sourceLayoutListener)
        observedDecor?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(sourceDrawListener)
        observedDecor = decor
        decor?.addOnLayoutChangeListener(sourceLayoutListener)
        decor?.viewTreeObserver?.addOnDrawListener(sourceDrawListener)
    }
    fun start() {
        observeSource(source.peekDecorView())
        choreographer.postFrameCallback(tick)
    }
    fun stop() {
        stopped = true; choreographer.removeFrameCallback(tick); thread.quitSafely()
        observeSource(null)
        sampledBase = null
        ui = null; front = null; back = null
    }
    private fun schedule() {
        choreographer.removeFrameCallback(tick)
        if (!stopped) choreographer.postFrameCallback(tick)
    }
    private fun capture(frameTimeNanos: Long) {
        if (stopped || copying) return
        if (frameTimeNanos + 1_000_000L < nextCaptureNanos) { schedule(); return }
        val interval = intervalNanos().coerceAtLeast(1_000_000L)
        nextCaptureNanos = if (nextCaptureNanos == 0L || frameTimeNanos - nextCaptureNanos > interval)
            frameTimeNanos + interval else nextCaptureNanos + interval
        val decor = source.peekDecorView()
        val modal = dialogView.glassOwnerWindow()
        if (decor == null || modal == null || modal === source || !decor.isAttachedToWindow ||
            decor.width < 1 || decor.height < 1) { publish(null); lastSourceGeometry = null; schedule(); return }
        val width = decor.width; val height = decor.height
        val originArray = IntArray(2); decor.getLocationOnScreen(originArray)
        val geometry = SourceGeometry(decor, modal, width, height, originArray[0], originArray[1])
        if (lastSourceGeometry != geometry) {
            lastSourceGeometry = geometry
            geometryRevision++
            publish(null)
            observeSource(decor)
        }
        val base = underlay()
        if (needsVideo && base == null) { schedule(); return }
        val dialogBounds = bounds()
        if (dialogBounds == null || dialogBounds.isEmpty ||
            !listOf(dialogBounds.left, dialogBounds.top, dialogBounds.right, dialogBounds.bottom).all { it.isFinite() }) {
            schedule(); return
        }
        val requestRevision = geometryRevision
        val paddingPx = paddingPx()
        // Window PixelCopy rect is decor-local; scene mapping remains screen-space.
        // Round outward to keep all 72dp (three maximum Gaussian radii) at fractional bounds.
        val crop = Rect(
            floor(dialogBounds.left - paddingPx - originArray[0]).toInt().coerceIn(0, width),
            floor(dialogBounds.top - paddingPx - originArray[1]).toInt().coerceIn(0, height),
            ceil(dialogBounds.right + paddingPx - originArray[0]).toInt().coerceIn(0, width),
            ceil(dialogBounds.bottom + paddingPx - originArray[1]).toInt().coerceIn(0, height))
        if (crop.isEmpty) { schedule(); return }
        val origin = Offset((originArray[0] + crop.left).toFloat(), (originArray[1] + crop.top).toFloat())
        val scale = sampleScale()
        val targetWidth = ceil(crop.width() * scale).toInt().coerceAtLeast(1)
        val targetHeight = ceil(crop.height() * scale).toInt().coerceAtLeast(1)
        val scaleX = targetWidth.toFloat() / crop.width()
        val scaleY = targetHeight.toFloat() / crop.height()
        val dim = if (modal.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
            modal.attributes.dimAmount.coerceIn(0f, 1f) else 0f
        val drawingRevision = sourceDrawRevision
        if (front != null && sampledBase === base && sampledDrawRevision == drawingRevision &&
            sampledGeometryRevision == requestRevision && sampledDim == dim && sampledScale == scale) {
            schedule(); return
        }
        val uiTarget: Bitmap
        val output: Bitmap
        try {
            uiTarget = ui?.takeIf { it.width == targetWidth && it.height == targetHeight }
                ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { ui = it }
            output = back?.takeIf { it.width == targetWidth && it.height == targetHeight }
                ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { back = it }
        } catch (_: OutOfMemoryError) { stop(); return }
        // Snapshot inherited frame on the publication thread before PixelCopy; do not keep
        // reading a reusable video bitmap from the worker while the parent capture rotates it.
        val underlayCanvas = Canvas(output)
        underlayCanvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
        if (base != null) {
            val left = base.origin.x - origin.x
            val top = base.origin.y - origin.y
            underlayCanvas.drawBitmap(base.bitmap, null,
                RectF(left * scaleX, top * scaleY, (left + base.width) * scaleX, (top + base.height) * scaleY),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        copying = true
        try {
            PixelCopy.request(source, crop, uiTarget, { status ->
                if (!stopped && status == PixelCopy.SUCCESS) {
                    // Window UI alpha covers its video hole; premultiplied SRC_OVER preserves
                    // bar/glyph occlusion. Black is the playback letterbox beneath that hole.
                    val canvas = Canvas(output)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(uiTarget, 0f, 0f, paint)
                    canvas.drawColor(Color.argb((dim * 255).roundToInt(), 0, 0, 0))
                    main.post {
                        copying = false
                        if (!stopped) {
                            val currentOrigin = IntArray(2); decor.getLocationOnScreen(currentOrigin)
                            val currentModal = dialogView.glassOwnerWindow()
                            val currentDim = if (currentModal != null &&
                                currentModal.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                                currentModal.attributes.dimAmount.coerceIn(0f, 1f) else 0f
                            if (requestRevision == geometryRevision && bounds() == dialogBounds &&
                                source.peekDecorView() === decor && decor.isAttachedToWindow &&
                                decor.width == width && decor.height == height && currentModal === modal &&
                                currentDim == dim && sampleScale() == scale && currentOrigin.contentEquals(originArray) &&
                                (!needsVideo || underlay() != null)) {
                                sampledBase = base
                                sampledDrawRevision = drawingRevision
                                sampledGeometryRevision = requestRevision
                                sampledDim = dim; sampledScale = scale
                                back = front; front = output
                                publish(GlassVideoFrame(output, origin, crop.width().toFloat(), crop.height().toFloat()))
                            } else if (source.peekDecorView() !== decor || !decor.isAttachedToWindow ||
                                decor.width != width || decor.height != height || currentModal !== modal ||
                                !currentOrigin.contentEquals(originArray)) {
                                // An obsolete crop/dim/underlay result is merely discarded.
                                // Only invalid source mapping makes the displayed sample unsafe.
                                publish(null)
                            }
                            schedule()
                        }
                    }
                } else main.post { copying = false; if (!stopped) schedule() }
            }, worker)
        } catch (_: IllegalArgumentException) { copying = false; schedule() }
    }
}
