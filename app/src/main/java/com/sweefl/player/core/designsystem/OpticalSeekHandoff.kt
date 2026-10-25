package com.sweefl.player.core.designsystem

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/** UI commit handshake only; no player/domain dependency. All calls are on the main thread. */
class OpticalSeekHandoff {
    internal var phase by mutableIntStateOf(0)
        private set
    internal var epoch by mutableLongStateOf(0)
        private set
    internal var pinned by mutableStateOf<GlassVideoFrame?>(null)
        private set
    internal var supported = false
    internal var visible = false
    private var seek: (() -> Unit)? = null
    private var finish: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    val running: Boolean get() = phase != 0

    /** false means caller must use its ordinary seek path (no sample/hardware UI available). */
    fun begin(performSeek: () -> Unit, onFinished: () -> Unit): Boolean {
        if (!supported || !visible) return false
        timeout?.let(handler::removeCallbacks)
        epoch++; seek = performSeek; finish = onFinished
        phase = 1
        val request = epoch
        timeout = Runnable {
            if (epoch == request && running) {
                // Bounded failure recovery: never freeze video indefinitely on missing output.
                // Timeout deliberately returns to ordinary native presentation, not fake PTS.
                phase = 6
            }
        }.also { handler.postDelayed(it, 15_000) }
        return true
    }

    internal fun pin(frame: GlassVideoFrame?) {
        if (phase != 1) return
        val copy = pinned ?: frame?.immutableCopy()
        if (copy == null) { phase = 6; return }
        pinned = copy
        phase = 2
    }
    internal fun accept(frame: GlassVideoFrame, eligible: Boolean) {
        if (phase != 3 || !eligible) return
        val copy = frame.immutableCopy() ?: return
        pinned = copy
        phase = 4
    }
    internal fun committed(request: Long, committedPhase: Int) {
        if (epoch != request || phase != committedPhase) return
        when (phase) {
            2 -> { phase = 3; val action = seek; seek = null; action?.invoke() }
            4 -> phase = 5 // Remove the cover while the engine is still paused.
            5, 6 -> {
                val pending = seek; seek = null
                val done = finish; finish = null
                phase = 0; pinned = null
                timeout?.let(handler::removeCallbacks); timeout = null
                pending?.invoke(); done?.invoke()
            }
        }
    }
    /** Lifecycle/source loss cannot wait for a frame from a detached Window. */
    fun cancel(completePending: Boolean = false) {
        epoch++; timeout?.let(handler::removeCallbacks); timeout = null
        val pending = seek; val done = finish
        seek = null; finish = null; pinned = null; phase = 0
        if (completePending) { pending?.invoke(); done?.invoke() }
    }
}

@Composable
fun OpticalSeekCover(handoff: OpticalSeekHandoff) {
    val frame = handoff.pinned
    if (frame == null || handoff.phase !in 1..4) return
    val view = androidx.compose.ui.platform.LocalView.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned {
        val window = IntArray(2)
        view.getLocationOnScreen(window)
        val root = IntArray(2)
        view.getLocationInWindow(root)
        origin = it.positionInWindow() + Offset((window[0] - root[0]).toFloat(), (window[1] - root[1]).toFloat())
    }) {
        val x = frame.origin.x - origin.x
        val y = frame.origin.y - origin.y
        drawIntoCanvas { it.nativeCanvas.drawBitmap(frame.bitmap, null,
            android.graphics.RectF(x, y, x + frame.width, y + frame.height), paint) }
    }
}

private fun GlassVideoFrame.immutableCopy(): GlassVideoFrame? = try {
    bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)?.let {
        GlassVideoFrame(it, origin, width, height)
    }
} catch (_: OutOfMemoryError) { null }
