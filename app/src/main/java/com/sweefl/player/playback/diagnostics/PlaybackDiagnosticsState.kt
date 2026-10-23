package com.sweefl.player.playback.diagnostics

import com.sweefl.player.domain.PlaybackConfig

data class PlaybackDiagnosticsState(
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    val videoFormat: String = "",
    val audioFormat: String = "",
    val outputFormat: String = "",
    val renderedFrames: Int = 0,
    val droppedFrames: Int = 0,
    val buffering: Boolean = false,
    val bufferedDurationMs: Long = 0,
    val decodeMode: String = PlaybackConfig.DECODE_AUTO,
    val positionMs: Long = 0,
    val playbackState: Int = 1,
    val isPlaying: Boolean = false,
    val error: String? = null
)

