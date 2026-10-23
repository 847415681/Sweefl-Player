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
    // Capture only the filter's actual support plus the maximum optical displacement.
    // Zero blur needs no 72dp Gaussian halo; sharp foreground remains full resolution.
    val blurAmount = LocalGlassBlurAmount.current.coerceIn(0f, 1f)
    val paddingPx = with(LocalDensity.current) { (blurAmount * 72f + 16f).dp.toPx() }
    val bounds = remember(sourceWindow, dialogView) { mutableStateOf<ScreenRect?>(null) }
    val captureRef = remember(sourceWindow, dialogView) { arrayOfNulls<DialogBackdropCapture>(1) }
    val reportBounds: (ScreenRect?) -> Unit = remember(sourceWindow, dialogView) {
        { value ->
            if (bounds.value != value) {
                bounds.value = value
                captureRef[0]?.invalidateGeometry()
            }
        }
    }
    val owner = LocalLifecycleOwner.current
    val enabled = LocalGlassEnabled.current
    val inheritedVideo = LocalVideoGlassBackdrop.current
    val composite = remember(sourceWindow, dialogView) { mutableStateOf<GlassVideoFrame?>(null) }
    val latestUnderlay = rememberUpdatedState(underlay)
    DisposableEffect(sourceWindow, dialogView, owner, enabled, inheritedVideo, paddingPx) {
        var capture: DialogBackdropCapture? = null
        fun start() {
            if (capture == null && enabled && Build.VERSION.SDK_INT >= 33 && sourceWindow != null) {
                capture = DialogBackdropCapture(sourceWindow, dialogView,
                    { latestUnderlay.value.value }, inheritedVideo, { bounds.value }, paddingPx,
                    { composite.value = it }).also { captureRef[0] = it; it.start() }
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
    CompositionLocalProvider(LocalGlassVideoFrame provides composite,
        LocalVideoGlassBackdrop provides true, LocalGlassBackdropIsComposite provides true,
        content = { content(reportBounds) })
}

/** One flight, no queued frames, at most 15Hz while modal is visible. No bitmap recycling
 * while RenderThread may still hold a shader; two output buffers plus one private UI buffer. */
private class DialogBackdropCapture(private val source: Window, private val dialogView: View,
    private val underlay: () -> GlassVideoFrame?, private val needsVideo: Boolean,
    private val bounds: () -> ScreenRect?, private val paddingPx: Float,
    private val publish: (GlassVideoFrame?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("SweeflDialogCopy").apply { start() }
    private val worker = Handler(thread.looper)
    @Volatile private var stopped = false
    private var copying = false
    private var ui: Bitmap? = null
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private val tick = Runnable { capture() }
    private var geometryRevision = 0L
    fun invalidateGeometry() {
        geometryRevision++
        publish(null)
        main.removeCallbacks(tick)
        if (!stopped && !copying) main.post(tick)
    }
    private var observedDecor: View? = null
    private val sourceLayoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (l != ol || t != ot || r != or || b != ob) invalidateGeometry()
    }
    fun start() {
        observedDecor = source.peekDecorView()
        observedDecor?.addOnLayoutChangeListener(sourceLayoutListener)
        main.post(tick)
    }
    fun stop() {
        stopped = true; main.removeCallbacks(tick); thread.quitSafely()
        observedDecor?.removeOnLayoutChangeListener(sourceLayoutListener); observedDecor = null
        ui = null; front = null; back = null
    }
    private fun schedule() { if (!stopped) main.postDelayed(tick, 67L) }
    private fun capture() {
        if (stopped || copying) return
        val decor = source.peekDecorView()
        val modal = dialogView.glassOwnerWindow()
        if (decor == null || modal == null || modal === source || !decor.isAttachedToWindow ||
            decor.width < 1 || decor.height < 1) { schedule(); return }
        val base = underlay()
        if (needsVideo && base == null) { publish(null); schedule(); return }
        val dialogBounds = bounds()
        if (dialogBounds == null || dialogBounds.isEmpty ||
            !listOf(dialogBounds.left, dialogBounds.top, dialogBounds.right, dialogBounds.bottom).all { it.isFinite() }) {
            publish(null); schedule(); return
        }
        val requestRevision = geometryRevision
        val width = decor.width; val height = decor.height
        val originArray = IntArray(2); decor.getLocationOnScreen(originArray)
        // Window PixelCopy rect is decor-local; scene mapping remains screen-space.
        // Round outward to keep all 72dp (three maximum Gaussian radii) at fractional bounds.
        val crop = Rect(
            floor(dialogBounds.left - paddingPx - originArray[0]).toInt().coerceIn(0, width),
            floor(dialogBounds.top - paddingPx - originArray[1]).toInt().coerceIn(0, height),
            ceil(dialogBounds.right + paddingPx - originArray[0]).toInt().coerceIn(0, width),
            ceil(dialogBounds.bottom + paddingPx - originArray[1]).toInt().coerceIn(0, height))
        if (crop.isEmpty) { publish(null); schedule(); return }
        val origin = Offset((originArray[0] + crop.left).toFloat(), (originArray[1] + crop.top).toFloat())
        // One bitmap pixel per window pixel: zero blur keeps the original detail.
        val targetWidth = crop.width()
        val targetHeight = crop.height()
        val dim = if (modal.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
            modal.attributes.dimAmount.coerceIn(0f, 1f) else 0f
        val uiTarget: Bitmap
        val output: Bitmap
        try {
            uiTarget = ui?.takeIf { it.width == targetWidth && it.height == targetHeight }
                ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { ui = it }
            output = back?.takeIf { it.width == targetWidth && it.height == targetHeight }
                ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { back = it }
        } catch (_: OutOfMemoryError) { publish(null); stop(); return }
        // Snapshot inherited frame on the publication thread before PixelCopy; do not keep
        // reading a reusable video bitmap from the worker while the parent capture rotates it.
        val underlayCanvas = Canvas(output)
        underlayCanvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
        if (base != null) {
            val left = base.origin.x - origin.x
            val top = base.origin.y - origin.y
            underlayCanvas.drawBitmap(base.bitmap, null,
                RectF(left, top, left + base.width, top + base.height),
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
                                currentDim == dim && currentOrigin.contentEquals(originArray) &&
                                (!needsVideo || underlay() != null)) {
                                back = front; front = output
                                publish(GlassVideoFrame(output, origin, targetWidth.toFloat(), targetHeight.toFloat()))
                            } else publish(null)
                            schedule()
                        }
                    }
                } else main.post { copying = false; if (!stopped) { publish(null); schedule() } }
            }, worker)
        } catch (_: IllegalArgumentException) { copying = false; publish(null); schedule() }
    }
}
