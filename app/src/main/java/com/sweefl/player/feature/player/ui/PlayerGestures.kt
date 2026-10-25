@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.feature.player.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import com.sweefl.player.domain.PressGestureClaim
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.media3.exoplayer.ExoPlayer
import com.sweefl.player.core.model.PlaybackTimeline
import com.sweefl.player.domain.DoubleTapAction
import com.sweefl.player.domain.PlaybackInteractionSettings
import com.sweefl.player.domain.horizontalSeekTarget
import com.sweefl.player.feature.player.platform.PlayerWindowController
import kotlin.math.abs

/** Dominant-axis lock keeps seeking separate from brightness/volume. */
@Composable
internal fun PlayerGestureLayer(player: ExoPlayer, locked: Boolean, settings: PlaybackInteractionSettings,
    window: PlayerWindowController, onTap: () -> Unit, onDoubleTap: (DoubleTapAction) -> Unit,
    onActive: (Boolean) -> Unit, onHint: (String) -> Unit,
    onHoldStart: () -> Unit, onHoldEnd: () -> Unit,
    onSeekStart: (Long, PlaybackTimeline) -> Unit, onSeekChange: (Long) -> Unit, onSeekEnd: (Boolean) -> Unit,
    timeline: () -> PlaybackTimeline = { PlaybackTimeline(endMs = player.duration.coerceAtLeast(0L),
        positionMs = player.currentPosition, isLive = player.isCurrentMediaItemLive, seekable = player.isCurrentMediaItemSeekable) },
    seekBasePosition: () -> Long = { player.currentPosition }, sourceKey: Any? = null) {
    val claim = remember(player, locked, settings, sourceKey) { PressGestureClaim() }
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val active by rememberUpdatedState(onActive)
    val hint by rememberUpdatedState(onHint)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val seekStart by rememberUpdatedState(onSeekStart)
    val seekChange by rememberUpdatedState(onSeekChange)
    val seekEnd by rememberUpdatedState(onSeekEnd)
    val currentTimeline by rememberUpdatedState(timeline)
    val currentBase by rememberUpdatedState(seekBasePosition)
    Box(Modifier.fillMaxSize().pointerInput(player, locked, settings, sourceKey) {
        try {
            detectTapGestures(
                onPress = {
                    try { tryAwaitRelease() } finally { claim.finishHold(); holdEnd() }
                },
                onLongPress = if (locked) null else { _ ->
                    if (claim.claimHold(player.isPlaying, locked)) holdStart()
                },
                onTap = { tap() }, onDoubleTap = if (locked) null else { point ->
                    doubleTap(settings.actionAt(point.x, size.width.toFloat()))
                })
        } finally { claim.finishHold(); holdEnd() }
    }.pointerInput(player, locked, settings, sourceKey) {
        if (locked) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            claim.begin()
            // Freeze both origin and span at DOWN, even as a live window advances.
            val range = currentTimeline()
            val startPosition = range.clamp(currentBase())
            val canSeek = range.durationMs > 0 && range.seekable
            val left = down.position.x < size.width / 2
            val startLevel = if (left) window.brightness() else window.volume()
            var movement = Offset.Zero
            var horizontal = false
            val slop = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (claim.claimDrag()) {
                    horizontal = abs(overSlop.x) > abs(overSlop.y)
                    movement = overSlop
                    change.consume()
                }
            } ?: return@awaitEachGesture
            active(true)
            if (horizontal && canSeek) seekStart(startPosition, range)
            fun update() {
                if (horizontal) {
                    if (canSeek) seekChange(range.startMs + horizontalSeekTarget(
                        startPosition - range.startMs, movement.x, size.width.toFloat(), range.durationMs))
                    else hint("当前视频不可调整进度")
                } else {
                    val level = (startLevel - movement.y / size.height.coerceAtLeast(1) * 1.7f)
                        .coerceIn(if (left) .02f else 0f, 1f)
                    if (left) window.setBrightness(level) else window.setVolume(level)
                    hint("${if (left) "亮度" else "音量"} ${(level * 100).toInt()}%")
                }
            }
            var committed = false
            try {
                update()
                val released = drag(slop.id) { change ->
                    movement += change.positionChange()
                    change.consume()
                    update()
                }
                if (horizontal && canSeek && released) { seekEnd(true); committed = true }
            } finally {
                if (horizontal && canSeek && !committed) seekEnd(false)
                active(false)
            }
        }
    })
}
