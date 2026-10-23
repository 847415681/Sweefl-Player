package com.sweefl.player.feature.player

import com.sweefl.player.playback.diagnostics.PlaybackDiagnosticsState



/** Narrow host contract: player UI never depends on the exported Activity class. */
interface PlayerHost {
    val inPip: Boolean
    val decodeMode: String
    val subtitleName: String?
    fun presentedFrameToken(cutoffNs: Long): Int
    fun glassFrameRevision(): Long
    fun seekTo(positionMs: Long)
    fun seekBasePosition(): Long
    val renderSettings: com.sweefl.player.domain.PlaybackRenderSettings
    val renderingStatus: kotlinx.coroutines.flow.StateFlow<com.sweefl.player.domain.PlaybackRenderStatus>?
    fun applyRenderSettings(value: com.sweefl.player.domain.PlaybackRenderSettings)
    val subtitleOffsetMs: Long
    fun setSubtitleOffsetMs(value: Long)
    val diagnosticsState: PlaybackDiagnosticsState
    val keepControls: Boolean
    val glassOverride: Boolean?
    fun setTemporaryPlaybackBaseSpeed(speed: Float?)
    fun retryPlayback()
    fun changeDecodeMode(mode: String)
    fun pickSubtitle()
    fun removeSubtitle()
    fun enterPip(): Boolean
    fun finish()
}
