package com.sweefl.player.domain

import kotlin.math.roundToLong

enum class DoubleTapAction(val label: String, val seekMs: Long = 0) {
    NONE("不执行操作"), PLAY_PAUSE("播放 / 暂停"),
    BACK_10("后退 10 秒", -10_000), FORWARD_10("快进 10 秒", 10_000),
    BACK_30("后退 30 秒", -30_000), FORWARD_30("快进 30 秒", 30_000),
    CONTROLS("显示 / 隐藏控件"), LOCK("锁定触控");

    companion object {
        fun decode(value: String?, fallback: DoubleTapAction): DoubleTapAction =
            entries.firstOrNull { it.name == value } ?: fallback
    }
}

data class PlaybackInteractionSettings(
    val showSeekPreview: Boolean = true,
    val leftDoubleTap: DoubleTapAction = DoubleTapAction.BACK_10,
    val centerDoubleTap: DoubleTapAction = DoubleTapAction.PLAY_PAUSE,
    val rightDoubleTap: DoubleTapAction = DoubleTapAction.FORWARD_10,
    val longPressSpeed: Float = DEFAULT_LONG_PRESS_SPEED,
) {
    fun withDefaultGestures() = PlaybackInteractionSettings(showSeekPreview = showSeekPreview)

    fun actionAt(x: Float, width: Float): DoubleTapAction = when (doubleTapRegion(x, width)) {
        0 -> leftDoubleTap
        1 -> centerDoubleTap
        else -> rightDoubleTap
    }
}

fun doubleTapRegion(x: Float, width: Float): Int {
    if (!x.isFinite() || !width.isFinite() || width <= 0) return 1
    return (x / width * 3).toInt().coerceIn(0, 2)
}

/** A full-width swipe covers at most two minutes; short videos use their duration.
 * The target is staged until release, so cancellation never moves the main player. */
fun horizontalSeekTarget(startMs: Long, deltaX: Float, width: Float, durationMs: Long): Long {
    if (durationMs <= 0 || !deltaX.isFinite() || !width.isFinite() || width <= 0) return startMs.coerceAtLeast(0)
    val fraction = (deltaX.toDouble() / width).coerceIn(-1.0, 1.0)
    val delta = (fraction * durationMs.coerceAtMost(120_000)).roundToLong()
    val start = startMs.coerceIn(0, durationMs)
    return if (delta > 0 && delta > durationMs - start) durationMs else (start + delta).coerceAtLeast(0)
}
