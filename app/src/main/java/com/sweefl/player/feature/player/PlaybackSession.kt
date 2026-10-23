@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.feature.player

import com.sweefl.player.data.history.PlaybackHistoryRepository
import com.sweefl.player.domain.PlaybackConfig
import com.sweefl.player.playback.diagnostics.PlaybackDiagnostics
import com.sweefl.player.playback.diagnostics.PlaybackDiagnosticsState
import com.sweefl.player.playback.engine.PlayerEngineFactory

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/** Activity-scoped owner. Never retained in an application singleton or ViewModel. */
class PlaybackSession(
    private val context: Context,
    private val mediaUri: Uri,
    private val mediaMime: String?,
    val title: String,
    private val userAgent: String?,
    initialMode: String,
    private val history: PlaybackHistoryRepository,
    private val engineFactory: PlayerEngineFactory,
    savedState: Bundle?,
    private val snapshotEnabled: Boolean,
    private val benchmark: Boolean,
    private val onPlaybackChanged: () -> Unit,
    initialSubtitleOffsetMs: Long = 0L,
    private val persistSubtitleOffsetMs: (Long) -> Unit = {},
    initialRenderSettings: com.sweefl.player.domain.PlaybackRenderSettings = com.sweefl.player.domain.PlaybackRenderSettings(),
    private val persistRenderSettings: (com.sweefl.player.domain.PlaybackRenderSettings) -> Unit = {}
) {
    var renderSettings by mutableStateOf(normalizeRendering(initialRenderSettings))
        private set
    private var frameTracker: com.sweefl.player.domain.SubmittedFrameTracker? = null
    fun glassFrameRevision(): Long = frameTracker?.revision() ?: -1L
    private var pendingSeekPosition: Long? = null
    fun seekBasePosition(): Long = pendingSeekPosition ?: player?.currentPosition ?: 0L
    fun seekTo(positionMs: Long) {
        val engine = player ?: return
        if (pendingSeekPosition == null && positionMs == engine.currentPosition) return
        pendingSeekPosition = positionMs
        val frames = frameTracker
        val request = frames?.requestSeek()
        // Surface buffers can latch before Compose has cleared its previous optical layer.
        // Invalidate first, then give the UI several display opportunities before changing
        // the video output. Newer requests replace this one; released players are untouched.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (player === engine && (request == null || frames.revision() == request)) {
                if (request != null) android.os.Handler(engine.playbackLooper).post { frames.armSeek(request) }
                pendingSeekPosition = null
                engine.seekTo(positionMs)
                if (request != null) android.os.Handler(engine.playbackLooper).post { frames.completeSeek(request) }
            }
        }, 80L)
    }
    fun presentedFrameToken(cutoffNs: Long): Int = frameTracker?.tokenAt(cutoffNs) ?: -1
    private var renderingPipeline by mutableStateOf<com.sweefl.player.playback.rendering.PlaybackRenderingPipeline?>(null)
    val renderingStatus get() = renderingPipeline?.status
    private fun normalizeRendering(value: com.sweefl.player.domain.PlaybackRenderSettings) =
        if (android.os.Build.VERSION.SDK_INT < 29) value.copy(mode = com.sweefl.player.domain.PlaybackRenderMode.NATIVE) else value
    /** Retry owns a new graph: a failed GPU pipeline cannot reuse sticky failure evidence. */
    fun retryPlayback() {
        saveProgress()
        releasePlayer()
        autoplay = true
        initializePlayer()
    }
    fun applyRenderSettings(value: com.sweefl.player.domain.PlaybackRenderSettings) {
        val next = normalizeRendering(value)
        if (next == renderSettings) return
        val ready = player?.playWhenReady ?: autoplay
        saveProgress()
        releasePlayer()
        renderSettings = next
        persistRenderSettings(next)
        autoplay = ready
        initializePlayer()
    }
    /** A paused GPU graph may retain a framebuffer sized for the old output Surface. */
    fun onOutputConfigurationChanged() {
        if (renderSettings.mode != com.sweefl.player.domain.PlaybackRenderMode.GPU_SDR || player == null) return
        val ready = player?.playWhenReady ?: autoplay
        saveProgress()
        releasePlayer()
        autoplay = ready
        initializePlayer()
    }
    var subtitleOffsetMs by mutableLongStateOf(com.sweefl.player.domain.SubtitleSyncRules.clampOffsetMs(initialSubtitleOffsetMs))
        private set
    private var subtitleEpoch = 1L
    private var subtitleSync: com.sweefl.player.playback.subtitles.ExternalSubtitleSync? = null
    fun updateSubtitleOffsetMs(value: Long) {
        val next = com.sweefl.player.domain.SubtitleSyncRules.clampOffsetMs(value)
        if (next == subtitleOffsetMs) return
        subtitleOffsetMs = next
        persistSubtitleOffsetMs(next)
        subtitleSync?.setOffsetMs(next)
    }
    private fun advanceSubtitleEpoch() {
        subtitleEpoch = if (subtitleEpoch == Long.MAX_VALUE) 1L else subtitleEpoch + 1L
        subtitleSync?.beginEpoch(subtitleEpoch)
    }
    val previewSource = com.sweefl.player.playback.preview.PreviewSource(mediaUri.toString(), mediaMime, userAgent)
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var decodeMode by mutableStateOf(PlaybackConfig.parseDecodeMode(savedState?.getString("decodeMode") ?: initialMode))
        private set
    var subtitleName by mutableStateOf(savedState?.getString("subtitleName"))
        private set
    var diagnosticsState by mutableStateOf(PlaybackDiagnosticsState())
        private set
    private var resumeAt = if (benchmark) 0L else savedState?.getLong("position") ?: history.position(mediaUri)
    var temporaryPlaybackBaseSpeed: Float? = null
    private var resumeSpeed = savedState?.getFloat("speed", 1f) ?: 1f
    private var autoplay = savedState?.getBoolean("autoplay", true) ?: true
    private var selection = savedState?.getBundle("selection")?.let(TrackSelectionParameters::fromBundle)
    private var subtitleUri = savedState?.getString("subtitleUri")?.let(Uri::parse)
    private var subtitleMime = savedState?.getString("subtitleMime")
    private var pendingSubtitleSelection = false
    private var diagnostics: PlaybackDiagnostics? = null

    fun initializePlayer() {
        if (player != null) return
        val subtitles = com.sweefl.player.playback.subtitles.ExternalSubtitleSync(subtitleOffsetMs, subtitleEpoch)
        subtitleSync = subtitles
        val rendering = com.sweefl.player.playback.rendering.PlaybackRenderingPipeline(renderSettings)
        frameTracker = rendering.frames
        renderingPipeline = rendering
        val engine = engineFactory.create(userAgent, decodeMode, subtitles, rendering)
        if (benchmark) { engine.repeatMode = Player.REPEAT_MODE_ONE; engine.volume = 0f }
        engine.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                subtitles.setPlaybackEnded(playbackState == Player.STATE_ENDED && engine.playbackState == Player.STATE_ENDED)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackChanged()
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) { onPlaybackChanged() }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!pendingSubtitleSelection) return
                for (group in tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }) {
                    for (index in 0 until group.length) {
                        if (group.getTrackFormat(index).id?.endsWith("sweefl-external") == true && group.isTrackSupported(index)) {
                            pendingSubtitleSelection = false
                            engine.trackSelectionParameters = engine.trackSelectionParameters.buildUpon()
                                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index))
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                            return
                        }
                    }
                }
            }
        })
        player = engine
        diagnostics = PlaybackDiagnostics(context, engine, decodeMode, snapshotEnabled,
            onFrameScheduled = rendering::onFrameMetadata, renderingState = { rendering.settings to rendering.status.value }) { diagnosticsState = it }
        engine.setMediaItem(mediaItem())
        engine.seekTo(resumeAt)
        engine.setPlaybackSpeed(resumeSpeed)
        selection?.let { engine.trackSelectionParameters = it }
        engine.prepare()
        engine.playWhenReady = autoplay
        onPlaybackChanged()
    }

    private fun mediaItem(): MediaItem = MediaItem.Builder().setUri(mediaUri).setMimeType(mediaMime).apply {
        val uri = subtitleUri
        val mime = subtitleMime
        if (uri != null && mime != null) setSubtitleConfigurations(listOf(
            MediaItem.SubtitleConfiguration.Builder(uri).setId("sweefl-external").setMimeType(mime).setLabel(subtitleName)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        ))
    }.build()

    fun changeDecodeMode(mode: String) {
        val next = PlaybackConfig.parseDecodeMode(mode)
        if (next == decodeMode) return
        val ready = player?.playWhenReady ?: autoplay
        saveProgress()
        releasePlayer()
        decodeMode = next
        autoplay = ready
        initializePlayer()
    }

    fun removeSubtitle() {
        val engine = player
        val position = engine?.currentPosition ?: resumeAt
        val ready = engine?.playWhenReady ?: autoplay
        advanceSubtitleEpoch()
        subtitleUri = null; subtitleMime = null; subtitleName = null
        pendingSubtitleSelection = false
        engine?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).build() }
        engine?.setMediaItem(mediaItem(), position)
        engine?.prepare()
        engine?.playWhenReady = ready
    }

    fun attachSubtitle(uri: Uri, name: String, mime: String) {
        advanceSubtitleEpoch()
        subtitleUri = uri; subtitleName = name; subtitleMime = mime
        pendingSubtitleSelection = true
        selection = (player?.trackSelectionParameters ?: selection ?: TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT)
            .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        player?.let { engine ->
            val position = engine.currentPosition
            val ready = engine.playWhenReady
            val speed = engine.playbackParameters
            engine.setMediaItem(mediaItem(), position)
            selection?.let { engine.trackSelectionParameters = it }
            engine.playbackParameters = speed
            engine.prepare()
            engine.playWhenReady = ready
        }
    }

    fun saveProgress() {
        val engine = player ?: return
        val uri = mediaUri
        resumeAt = com.sweefl.player.domain.rebuildPosition(engine.currentPosition, engine.playbackState == Player.STATE_ENDED)
        if (engine.playbackState == Player.STATE_READY || engine.playbackState == Player.STATE_ENDED) {
            if (!benchmark) history.save(uri, title, engine.currentPosition, engine.duration)
        }
        resumeSpeed = temporaryPlaybackBaseSpeed ?: engine.playbackParameters.speed
        selection = engine.trackSelectionParameters
    }

    fun saveState(outState: Bundle) {
        saveProgress()
        outState.putLong("position", resumeAt)
        outState.putFloat("speed", resumeSpeed)
        outState.putString("decodeMode", decodeMode)
        outState.putBoolean("autoplay", player?.playWhenReady ?: autoplay)
        selection?.let { outState.putBundle("selection", it.toBundle()) }
        outState.putString("subtitleUri", subtitleUri?.toString())
        outState.putString("subtitleName", subtitleName)
        outState.putString("subtitleMime", subtitleMime)
    }

    fun stop(pickingSubtitle: Boolean, changingConfigurations: Boolean) {
        saveProgress()
        if (pickingSubtitle) player?.let { resumeAt = it.currentPosition }
        autoplay = if (pickingSubtitle || changingConfigurations) player?.playWhenReady ?: autoplay else false
        releasePlayer()
    }

    fun releasePlayer() {
        pendingSeekPosition = null
        diagnostics?.close(); diagnostics = null
        frameTracker?.close(); frameTracker = null
        subtitleSync?.close(); subtitleSync = null
        renderingPipeline?.close(); renderingPipeline = null
        player?.pause()
        player?.release(); player = null
        temporaryPlaybackBaseSpeed = null
    }

}
