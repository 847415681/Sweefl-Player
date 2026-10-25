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
import androidx.media3.common.Timeline
import com.sweefl.player.core.model.PlaybackTimeline
import com.sweefl.player.core.model.ResolvedOnlineMedia
import com.sweefl.player.domain.LiveReplayRules
import kotlinx.coroutines.*

/** Activity-scoped owner. Never retained in an application singleton or ViewModel. */
class PlaybackSession(
    private val context: Context,
    private val mediaUri: Uri,
    private val mediaMime: String?,
    title: String,
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
    private val persistRenderSettings: (com.sweefl.player.domain.PlaybackRenderSettings) -> Unit = {},
    private val onlineResolver: com.sweefl.player.core.model.OnlineMediaResolver? = null,
    private val readSubtitleOffsetMs: (String) -> Long = { 0L },
    private val readRenderSettings: (String) -> com.sweefl.player.domain.PlaybackRenderSettings = { com.sweefl.player.domain.PlaybackRenderSettings() }
) {
    var title by mutableStateOf(savedState?.getString("onlineTitle") ?: title)
        private set
    var onlineMedia by mutableStateOf<ResolvedOnlineMedia?>(null)
        private set
    var sourceLoading by mutableStateOf(false)
        private set
    var sourceError by mutableStateOf<String?>(null)
        private set
    fun clearSourceError() { sourceError = null; failedSource = null }
    private val sourceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sourceJob: Job? = null
    private var sourceSerial = 0L
    private var active = false
    private var pageUrl = savedState?.getString("onlinePageUrl") ?: mediaUri.toString()
    private var chosenQuality = savedState?.takeIf { it.containsKey("onlineQuality") }?.getInt("onlineQuality")
    private var chosenAudio = savedState?.getString("onlineAudio")
    private var replayTargetMs = savedState?.takeIf { it.containsKey("onlineReplayPosition") }?.getLong("onlineReplayPosition")
    private var replayEdgeAtRequestMs: Long? = null
    private var replayAnchorMs: Long? = null
    private var pausedLivePositionMs: Long? = null
    private var replayWindowOffsetMs = 0L
    private var measuredPlaylistDurationMs = 0L
    private data class SourceRequest(val page: String, val quality: Int?, val keepPosition: Boolean,
        val targetMs: Long? = null, val live: Boolean = false, val playIntent: Boolean? = null)
    private var pendingSource: SourceRequest? = null
    private var failedSource: SourceRequest? = null
    private val online get() = onlineResolver?.supports(pageUrl) == true

    fun playbackTimeline(): PlaybackTimeline {
        val engine = player
        val window = Timeline.Window()
        if (engine != null && !engine.currentTimeline.isEmpty) engine.currentTimeline.getWindow(engine.currentMediaItemIndex, window)
        val duration = engine?.duration?.takeIf { it > 0L } ?: 0L
        val live = onlineMedia?.live
        if (live != null) {
            val now = live.nowMs()
            val position = pausedLivePositionMs ?: replayAnchorMs?.let {
                LiveReplayRules.replayPosition(it, replayWindowOffsetMs,
                    window.positionInFirstPeriodUs / 1000L, engine?.currentPosition ?: 0L)
            } ?: replayTargetMs ?: now
            return PlaybackTimeline(live.earliestMs(now), now, position, true, live.dvrWindowSeconds > 0,
                atLiveEdge = replayTargetMs == null && pausedLivePositionMs == null)
        }
        val isLive = engine?.isCurrentMediaItemLive == true
        val start = if (isLive && window.windowStartTimeMs != C.TIME_UNSET) window.windowStartTimeMs else 0L
        return PlaybackTimeline(start, start + duration, start + (engine?.currentPosition ?: resumeAt),
            isLive, duration > 0 && engine?.isCurrentMediaItemSeekable == true)
    }

    private fun cancelSourceResolution() {
        sourceSerial++
        sourceJob?.cancel(); sourceJob = null
        sourceLoading = false
    }

    private fun resolveSource(request: SourceRequest) {
        val resolver = onlineResolver ?: return
        cancelSourceResolution()
        pendingSource = request
        failedSource = null
        if (!active) return
        sourceLoading = true; sourceError = null
        val serial = sourceSerial
        sourceJob = sourceScope.launch {
            try {
                val resolved = resolver.resolve(request.page, request.quality)
                ensureActive()
                if (serial != sourceSerial || !active) return@launch
                resolvedAtMs = System.currentTimeMillis()
                commitSource(resolved, request)
                pendingSource = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (serial == sourceSerial && active) {
                    pendingSource = null
                    failedSource = request
                    val message = failure.message.orEmpty()
                    sourceError = message.takeIf { it.isNotBlank() && it.length <= 180 &&
                        !Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) && !it.contains("SESSDATA", true) }
                        ?: "暂时无法获取播放地址，请检查网络或登录 B 站后重试。"
                }
            } finally {
                if (serial == sourceSerial) { sourceLoading = false; sourceJob = null }
            }
        }
    }

    private fun commitSource(resolved: ResolvedOnlineMedia, request: SourceRequest) {
        val firstCanonicalResolution = onlineMedia == null && resolved.pageUrl != pageUrl
        val oldLive = onlineMedia?.live != null
        val wasReplaying = oldLive && (replayTargetMs != null || pausedLivePositionMs != null)
        val oldPosition = if (firstCanonicalResolution && resolved.live == null && !hasSavedPosition) {
            if (benchmark) 0L else history.position(Uri.parse(resolved.pageUrl))
        } else if (player != null) seekBasePosition() else if (wasReplaying) pausedLivePositionMs ?: replayTargetMs!! else resumeAt
        val ready = request.playIntent ?: desiredPlayWhenReady
        if (!request.live && (request.targetMs != null || (wasReplaying && request.keepPosition)) &&
            (resolved.live?.dvrWindowSeconds ?: 0) <= 0) {
            throw IllegalArgumentException("该清晰度暂不支持直播回看，已保留当前播放。")
        }
        measuredPlaylistDurationMs = player?.duration?.takeIf { it in 1L..30_000L } ?: measuredPlaylistDurationMs
        saveProgress()
        releasePlayer()
        val audio = chosenAudio?.takeIf { id -> resolved.audios.any { it.id == id } } ?: resolved.selectedAudioId
        onlineMedia = resolved.copy(selectedAudioId = audio)
        pageUrl = resolved.pageUrl; title = resolved.title
        chosenQuality = resolved.video.qualityId; chosenAudio = audio
        replayTargetMs = if (resolved.live == null || request.live) null
            else request.targetMs ?: oldPosition.takeIf { request.keepPosition && wasReplaying }
        pausedLivePositionMs = replayTargetMs.takeIf { !ready }
        resumeAt = if (resolved.live != null || !request.keepPosition) 0L else oldPosition
        if (!request.keepPosition || firstCanonicalResolution) {
            subtitleOffsetMs = com.sweefl.player.domain.SubtitleSyncRules.clampOffsetMs(readSubtitleOffsetMs(pageUrl))
            renderSettings = normalizeRendering(readRenderSettings(pageUrl))
        }
        if (!request.keepPosition) {
            subtitleUri = null; subtitleMime = null; subtitleName = null; pendingSubtitleSelection = false
            selection = null
            advanceSubtitleEpoch()
        } else {
            selection = selection?.buildUpon()?.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                ?.clearOverridesOfType(C.TRACK_TYPE_AUDIO)?.build()
        }
        replayAnchorMs = null
        autoplay = ready
        failedSource = null
        sourceError = null
        initializePlayer()
    }

    fun selectEpisode(url: String) {
        if (onlineMedia?.episodes?.none { it.pageUrl == url } != false || url == pageUrl) return
        resolveSource(SourceRequest(url, chosenQuality, false))
    }
    fun selectQuality(qualityId: Int) {
        if (onlineMedia?.qualities?.none { it.id == qualityId } != false) return
        resolveSource(SourceRequest(pageUrl, qualityId, true))
    }
    fun selectAudio(audioId: String) {
        val source = onlineMedia ?: return
        if (source.audios.none { it.id == audioId } || audioId == source.selectedAudioId) return
        chosenAudio = audioId
        cancelSourceResolution(); pendingSource = null
        commitSource(source.copy(selectedAudioId = audioId), SourceRequest(pageUrl, chosenQuality, true))
    }
    fun goLive() {
        val source = onlineMedia
        if (source?.live != null) {
            cancelSourceResolution(); pendingSource = null
            commitSource(source, SourceRequest(pageUrl, chosenQuality, true, live = true))
        } else {
            val engine = player ?: return
            val ready = desiredPlayWhenReady
            seekSerial++; opticalSeekHandoff.cancel(); handoffPlayIntent = null; pendingSeekPosition = null
            engine.seekToDefaultPosition(); engine.playWhenReady = ready
        }
    }

    private fun preparedOnlineSource(): ResolvedOnlineMedia? {
        val source = onlineMedia ?: return null
        val live = source.live ?: return source
        val target = replayTargetMs ?: pausedLivePositionMs ?: run { replayEdgeAtRequestMs = null; return source }
        val now = live.nowMs()
        val clamped = LiveReplayRules.clampTarget(target, now, live.startedAtMs, live.dvrWindowSeconds)
        if (clamped - target > 2_000L) onlineMedia = source.copy(notice = "目标已超出可回看范围，已定位到最早可回看位置。")
        if (pausedLivePositionMs != null) pausedLivePositionMs = clamped
        val delay = LiveReplayRules.delaySeconds(clamped, now, measuredPlaylistDurationMs)
        if (delay == null) { replayTargetMs = null; pausedLivePositionMs = null; replayEdgeAtRequestMs = null; return source }
        replayTargetMs = clamped
        replayEdgeAtRequestMs = now - delay * 1000L
        val uri = source.video.url
        val url = uri.substringBefore('#') + (if ('?' in uri.substringBefore('#')) "&" else "?") + "tmshift=$delay" +
            (if ('#' in uri) "#" + uri.substringAfter('#') else "")
        return source.copy(videos = source.videos.map { if (it.id == source.selectedVideoId) it.copy(url = url) else it })
    }

    private fun updateReplayAnchor(engine: ExoPlayer) {
        if (player !== engine || onlineMedia?.live == null || replayAnchorMs != null || engine.currentTimeline.isEmpty) return
        val window = engine.currentTimeline.getWindow(engine.currentMediaItemIndex, Timeline.Window())
        if (window.durationMs <= 0L || window.isPlaceholder) return
        measuredPlaylistDurationMs = window.durationMs.coerceAtMost(30_000L)
        replayWindowOffsetMs = window.positionInFirstPeriodUs / 1000L
        replayAnchorMs = (replayEdgeAtRequestMs ?: onlineMedia!!.live!!.nowMs()) - window.durationMs
    }

    var renderSettings by mutableStateOf(normalizeRendering(initialRenderSettings))
        private set
    private var frameTracker: com.sweefl.player.domain.SubmittedFrameTracker? = null
    fun glassFrameRevision(): Long = frameTracker?.revision() ?: -1L
    private var pendingSeekPosition: Long? = null
    private var seekSerial = 0L
    fun seekBasePosition(): Long = pendingSeekPosition ?: playbackTimeline().positionMs
    val opticalSeekHandoff = com.sweefl.player.core.designsystem.OpticalSeekHandoff()
    private var handoffPlayIntent by mutableStateOf<Boolean?>(null)
    val desiredPlayWhenReady: Boolean get() = handoffPlayIntent ?: player?.playWhenReady ?: autoplay
    fun togglePlayback() {
        val engine = player ?: return
        val ended = engine.playbackState == Player.STATE_ENDED
        val next = if (ended) true else !desiredPlayWhenReady
        if ((onlineMedia?.live?.dvrWindowSeconds ?: 0) > 0) {
            if (!next) {
                pausedLivePositionMs = playbackTimeline().positionMs
                engine.pause()
                return
            }
            pausedLivePositionMs?.let { target ->
                val source = onlineMedia ?: return
                cancelSourceResolution(); pendingSource = null
                commitSource(source, SourceRequest(pageUrl, chosenQuality, true, targetMs = target, playIntent = true))
                return
            }
        }
        if (ended) seekTo(0)
        if (opticalSeekHandoff.running) handoffPlayIntent = next
        else engine.playWhenReady = next
    }
    fun seekTo(positionMs: Long) {
        val engine = player ?: return
        val live = onlineMedia?.live
        if (live != null && live.dvrWindowSeconds > 0) {
            val source = onlineMedia ?: return
            cancelSourceResolution(); pendingSource = null
            commitSource(source, SourceRequest(pageUrl, chosenQuality, true,
                targetMs = LiveReplayRules.clampTarget(positionMs, live.nowMs(), live.startedAtMs, live.dvrWindowSeconds)))
            return
        }
        val timeline = playbackTimeline()
        if (!timeline.seekable) return
        val logicalTarget = timeline.clamp(positionMs)
        val nativeTarget = logicalTarget - timeline.startMs
        if (pendingSeekPosition == null && nativeTarget == engine.currentPosition) return
        pendingSeekPosition = logicalTarget
        val serial = ++seekSerial
        val frames = frameTracker
        val request = frames?.requestSeek()
        val performSeek: () -> Unit = {
            if (player === engine && seekSerial == serial) {
                if (request != null) android.os.Handler(engine.playbackLooper).post { frames.armSeek(request) }
                engine.seekTo(nativeTarget)
                if (request != null) android.os.Handler(engine.playbackLooper).post { frames.completeSeek(request) }
            }
        }
        val wasReady = desiredPlayWhenReady
        val finish: () -> Unit = {
            if (player === engine && seekSerial == serial) {
                pendingSeekPosition = null
                val resume = handoffPlayIntent ?: wasReady
                handoffPlayIntent = null
                engine.playWhenReady = resume
            }
        }
        if (opticalSeekHandoff.begin(performSeek, finish)) {
            handoffPlayIntent = wasReady
            engine.pause()
        } else {
            opticalSeekHandoff.cancel()
            handoffPlayIntent = null
            performSeek(); pendingSeekPosition = null
            engine.playWhenReady = wasReady
        }
    }
    fun presentedFrameToken(cutoffNs: Long): Int = frameTracker?.tokenAt(cutoffNs) ?: -1
    private var renderingPipeline by mutableStateOf<com.sweefl.player.playback.rendering.PlaybackRenderingPipeline?>(null)
    val renderingStatus get() = renderingPipeline?.status
    private fun normalizeRendering(value: com.sweefl.player.domain.PlaybackRenderSettings) =
        if (android.os.Build.VERSION.SDK_INT < 29) value.copy(mode = com.sweefl.player.domain.PlaybackRenderMode.NATIVE) else value
    /** Retry owns a new graph: a failed GPU pipeline cannot reuse sticky failure evidence. */
    fun retryPlayback() {
        if (online) {
            resolveSource(failedSource ?: pendingSource ?: SourceRequest(pageUrl, chosenQuality, true,
                targetMs = (replayTargetMs ?: pausedLivePositionMs)?.let { playbackTimeline().positionMs }))
            return
        }
        saveProgress()
        releasePlayer()
        autoplay = true
        initializePlayer()
    }
    fun applyRenderSettings(value: com.sweefl.player.domain.PlaybackRenderSettings) {
        val next = normalizeRendering(value)
        if (next == renderSettings) return
        val ready = desiredPlayWhenReady
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
        val ready = desiredPlayWhenReady
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
    private var engineOnlineMedia: ResolvedOnlineMedia? = null
    private var resolvedAtMs = 0L
    private var lastAutomaticRecoveryMs = 0L
    val previewSource get() = com.sweefl.player.playback.preview.PreviewSource(
        engineOnlineMedia?.video?.url ?: mediaUri.toString(), engineOnlineMedia?.video?.mimeType ?: mediaMime,
        engineOnlineMedia?.userAgent ?: userAgent, requestHeaders = engineOnlineMedia?.requestHeaders ?: emptyMap())
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var decodeMode by mutableStateOf(PlaybackConfig.parseDecodeMode(savedState?.getString("decodeMode") ?: initialMode))
        private set
    var subtitleName by mutableStateOf(savedState?.getString("subtitleName"))
        private set
    var diagnosticsState by mutableStateOf(PlaybackDiagnosticsState())
        private set
    private val hasSavedPosition = savedState?.containsKey("position") == true
    private var resumeAt = if (benchmark) 0L else savedState?.getLong("position") ?: history.position(mediaUri)
    var temporaryPlaybackBaseSpeed: Float? = null
    private var resumeSpeed = savedState?.getFloat("speed", 1f) ?: 1f
    private var autoplay = savedState?.getBoolean("autoplay", true) ?: true
    private var selection = savedState?.getBundle("selection")?.let(TrackSelectionParameters::fromBundle)
    private var subtitleUri = savedState?.getString("subtitleUri")?.let(Uri::parse)
    private var subtitleMime = savedState?.getString("subtitleMime")
    private var pendingSubtitleSelection = false
    private var diagnostics: PlaybackDiagnostics? = null

    private fun onlineDiagnosticState(): Map<String, Any?> {
        val source = onlineMedia ?: return emptyMap()
        val timeline = playbackTimeline()
        val shift = engineOnlineMedia?.video?.url?.let { Uri.parse(it).getQueryParameter("tmshift")?.toLongOrNull() } ?: 0L
        return mapOf("provider" to "bilibili", "kind" to if (source.live != null) "live" else "vod",
            "qualityId" to source.video.qualityId, "episodeCount" to source.episodes.size,
            "episodeIndex" to source.episodes.indexOfFirst { it.pageUrl == source.pageUrl },
            "audioId" to source.selectedAudioId, "audioCount" to source.audios.size,
            "sourceLoading" to sourceLoading, "intendedPlaying" to desiredPlayWhenReady,
            "rangeStartMs" to timeline.startMs, "rangeEndMs" to timeline.endMs,
            "contentPositionMs" to timeline.positionMs, "seekable" to timeline.seekable,
            "shiftSeconds" to shift, "pausedContentMs" to pausedLivePositionMs)
    }

    fun initializePlayer() {
        val wasActive = active
        active = true
        if (player != null) return
        if (online && (onlineMedia == null || (!wasActive && pendingSource != null) ||
                System.currentTimeMillis() - resolvedAtMs > 120_000L)) {
            if (sourceJob?.isActive != true) resolveSource(pendingSource ?: SourceRequest(pageUrl, chosenQuality, true, replayTargetMs))
            return
        }
        engineOnlineMedia = preparedOnlineSource()
        replayAnchorMs = null
        val subtitles = com.sweefl.player.playback.subtitles.ExternalSubtitleSync(subtitleOffsetMs, subtitleEpoch)
        subtitleSync = subtitles
        val rendering = com.sweefl.player.playback.rendering.PlaybackRenderingPipeline(renderSettings)
        frameTracker = rendering.frames
        renderingPipeline = rendering
        val engine = engineFactory.create(userAgent, decodeMode, subtitles, rendering, engineOnlineMedia)
        if (benchmark) { engine.repeatMode = Player.REPEAT_MODE_ONE; engine.volume = 0f }
        engine.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { updateReplayAnchor(engine) }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val recoverable = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                val now = android.os.SystemClock.elapsedRealtime()
                if (online && active && player === engine && pendingSource == null && recoverable && now - lastAutomaticRecoveryMs > 30_000L) {
                    lastAutomaticRecoveryMs = now
                    resolveSource(SourceRequest(pageUrl, chosenQuality, true,
                        (replayTargetMs ?: pausedLivePositionMs)?.let { playbackTimeline().positionMs }))
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && player === engine && engine.playbackState == Player.STATE_READY &&
                    (onlineMedia?.live?.dvrWindowSeconds ?: 0) > 0 && pausedLivePositionMs == null) {
                    pausedLivePositionMs = playbackTimeline().positionMs
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                subtitles.setPlaybackEnded(playbackState == Player.STATE_ENDED && engine.playbackState == Player.STATE_ENDED)
                updateReplayAnchor(engine)
                if (playbackState == Player.STATE_READY && player === engine && !desiredPlayWhenReady &&
                    (onlineMedia?.live?.dvrWindowSeconds ?: 0) > 0 && pausedLivePositionMs == null) {
                    pausedLivePositionMs = playbackTimeline().positionMs
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { onPlaybackChanged() }
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
            onFrameScheduled = rendering::onFrameMetadata, renderingState = { rendering.settings to rendering.status.value },
            sourceState = ::onlineDiagnosticState) { diagnosticsState = it }
        engine.setMediaItem(mediaItem())
        if (engineOnlineMedia?.live == null && resumeAt > 0L) engine.seekTo(resumeAt)
        engine.setPlaybackSpeed(resumeSpeed)
        selection?.let { engine.trackSelectionParameters = it }
        engine.prepare()
        engine.playWhenReady = autoplay
        onPlaybackChanged()
    }

    private fun mediaItem(): MediaItem = MediaItem.Builder()
        .setUri(engineOnlineMedia?.video?.url?.let(Uri::parse) ?: mediaUri)
        .setMimeType(engineOnlineMedia?.video?.mimeType ?: mediaMime).apply {
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
        val ready = desiredPlayWhenReady
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
        onlineMedia?.let {
            cancelSourceResolution(); pendingSource = null
            commitSource(it, SourceRequest(pageUrl, chosenQuality, true))
            return
        }
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
        onlineMedia?.let {
            cancelSourceResolution(); pendingSource = null
            commitSource(it, SourceRequest(pageUrl, chosenQuality, true))
            return
        }
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

    val sourceIdentity: String get() = if (online) pageUrl else mediaUri.toString()

    fun saveProgress() {
        val engine = player ?: return
        val live = onlineMedia?.live != null || engine.isCurrentMediaItemLive
        val nativePosition = if (!live) pendingSeekPosition ?: engine.currentPosition else engine.currentPosition
        resumeAt = com.sweefl.player.domain.rebuildPosition(nativePosition, engine.playbackState == Player.STATE_ENDED)
        if (engine.playbackState == Player.STATE_READY || engine.playbackState == Player.STATE_ENDED) {
            if (!benchmark) history.save(Uri.parse(sourceIdentity), title, if (live) 0L else nativePosition,
                if (live) 0L else engine.duration)
        }
        resumeSpeed = temporaryPlaybackBaseSpeed ?: engine.playbackParameters.speed
        selection = engine.trackSelectionParameters
    }

    fun saveState(outState: Bundle) {
        saveProgress()
        outState.putLong("position", resumeAt)
        outState.putFloat("speed", resumeSpeed)
        outState.putString("decodeMode", decodeMode)
        outState.putBoolean("autoplay", desiredPlayWhenReady)
        selection?.let { outState.putBundle("selection", it.toBundle()) }
        outState.putString("subtitleUri", subtitleUri?.toString())
        outState.putString("subtitleName", subtitleName)
        outState.putString("subtitleMime", subtitleMime)
        if (online) {
            outState.putString("onlinePageUrl", pageUrl)
            outState.putString("onlineTitle", title)
            chosenQuality?.let { outState.putInt("onlineQuality", it) }
            outState.putString("onlineAudio", chosenAudio)
            if (replayTargetMs != null || pausedLivePositionMs != null) outState.putLong("onlineReplayPosition", playbackTimeline().positionMs)
            else outState.remove("onlineReplayPosition")
        }
    }

    fun stop(pickingSubtitle: Boolean, changingConfigurations: Boolean) {
        if (replayTargetMs != null && player != null) replayTargetMs = playbackTimeline().positionMs
        saveProgress()
        if (pickingSubtitle) player?.let { resumeAt = it.currentPosition }
        autoplay = if (pickingSubtitle || changingConfigurations) desiredPlayWhenReady else false
        active = false
        cancelSourceResolution()
        releasePlayer()
    }

    fun close() {
        active = false
        cancelSourceResolution()
        sourceScope.cancel()
        releasePlayer()
    }

    fun releasePlayer() {
        if (player != null && replayTargetMs != null) replayTargetMs = playbackTimeline().positionMs
        seekSerial++
        opticalSeekHandoff.cancel()
        handoffPlayIntent = null
        pendingSeekPosition = null
        diagnostics?.close(); diagnostics = null
        frameTracker?.close(); frameTracker = null
        subtitleSync?.close(); subtitleSync = null
        renderingPipeline?.close(); renderingPipeline = null
        val released = player
        player = null
        released?.pause()
        released?.release()
        replayAnchorMs = null
        replayEdgeAtRequestMs = null
        temporaryPlaybackBaseSpeed = null
    }

}
