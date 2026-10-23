@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.feature.player.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sweefl.player.domain.PressGestureClaim
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.media3.exoplayer.ExoPlayer
import com.sweefl.player.domain.DoubleTapAction
import com.sweefl.player.domain.PlaybackInteractionSettings
import com.sweefl.player.domain.horizontalSeekTarget
import com.sweefl.player.feature.player.platform.PlayerWindowController
import kotlin.math.abs

/** Dominant-axis lock keeps horizontal seeking separate from brightness/volume.
 * This layer sits behind controls: sliders and buttons keep their own pointer handling. */
@Composable
internal fun PlayerGestureLayer(player: ExoPlayer, locked: Boolean, settings: PlaybackInteractionSettings,
    window: PlayerWindowController, onTap: () -> Unit, onDoubleTap: (DoubleTapAction) -> Unit,
    onActive: (Boolean) -> Unit, onHint: (String) -> Unit,
    onHoldStart: () -> Unit, onHoldEnd: () -> Unit,
    onSeekStart: (Long) -> Unit, onSeekChange: (Long) -> Unit, onSeekEnd: (Boolean) -> Unit) {
    val claim = remember(player, locked, settings) { PressGestureClaim() }
    Box(Modifier.fillMaxSize().pointerInput(player, locked, settings) {
        try {
            detectTapGestures(
                onPress = {
                    // onPress begins before the long-press timeout. Keep cleanup here, not
                    // immediately after starting the press; cancellation also resumes this finally.
                    try { tryAwaitRelease() } finally {
                        claim.finishHold()
                        onHoldEnd()
                    }
                },
                onLongPress = if (locked) null else { _ ->
                    if (claim.claimHold(player.isPlaying, locked)) onHoldStart()
                },
                onTap = { onTap() }, onDoubleTap = if (locked) null else { point ->
                    onDoubleTap(settings.actionAt(point.x, size.width.toFloat()))
                })
        } finally {
            claim.finishHold()
            onHoldEnd()
        }
    }.pointerInput(player, locked, settings) {
        if (locked) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            claim.begin()
            val startPosition = player.currentPosition
            val duration = player.duration
            val canSeek = duration > 0 && player.isCurrentMediaItemSeekable
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
            onActive(true)
            if (horizontal && canSeek) onSeekStart(startPosition)
            fun update() {
                if (horizontal) {
                    if (canSeek) onSeekChange(horizontalSeekTarget(startPosition, movement.x, size.width.toFloat(), duration))
                    else onHint("当前视频不可调整进度")
                } else {
                    val level = (startLevel - movement.y / size.height.coerceAtLeast(1) * 1.7f)
                        .coerceIn(if (left) .02f else 0f, 1f)
                    if (left) window.setBrightness(level) else window.setVolume(level)
                    onHint("${if (left) "亮度" else "音量"} ${(level * 100).toInt()}%")
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
                if (horizontal && canSeek && released) { onSeekEnd(true); committed = true }
            } finally {
                if (horizontal && canSeek && !committed) onSeekEnd(false)
                onActive(false)
            }
        }
    })
}
