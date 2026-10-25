@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.feature.player.ui

import com.sweefl.player.core.designsystem.LocalGlassEnabled
import com.sweefl.player.core.designsystem.GlassSurface
import com.sweefl.player.core.designsystem.Mint
import com.sweefl.player.core.designsystem.VideoGlassProvider
import com.sweefl.player.core.designsystem.GlassIconButton as IconButton
import com.sweefl.player.core.designsystem.GlassIconButton as FilledIconButton
import com.sweefl.player.core.designsystem.GlassTextButton as TextButton
import com.sweefl.player.core.designsystem.GlassSlider as Slider
import com.sweefl.player.core.designsystem.GlassDialog as AlertDialog
import com.sweefl.player.domain.DoubleTapAction
import com.sweefl.player.feature.settings.LocalPlaybackInteractions
import com.sweefl.player.feature.settings.PlaybackInteractionDialog
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.sweefl.player.core.designsystem.glass
import com.sweefl.player.domain.formatDuration
import com.sweefl.player.feature.player.PlayerHost
import com.sweefl.player.feature.player.platform.PlayerWindowController

import android.view.SurfaceView
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sweefl.player.core.designsystem.glassContentContrast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(player: ExoPlayer, title: String, host: PlayerHost, window: PlayerWindowController, onGlassChanged: (Boolean) -> Unit, preview: @Composable (Long) -> Unit = {}, onSave: () -> Unit) {
    var controls by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(true) }
    var timeline by remember(player, host) { mutableStateOf(host.playbackTimeline()) }
    val position = timeline.positionMs
    val duration = timeline.durationMs
    var scrubOrigin by remember { mutableLongStateOf(0L) }
    var scrubDuration by remember { mutableLongStateOf(0L) }
    var sliderRangeHeld by remember { mutableStateOf(false) }
    val media = host.onlineMedia
    var error by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    val videoChoices = remember(tracks) { supportedTrackChoices(tracks, C.TRACK_TYPE_VIDEO) }
    val audioChoices = remember(tracks) { supportedTrackChoices(tracks, C.TRACK_TYPE_AUDIO) }
    val hasEpisodes = (media?.episodes?.distinctBy { it.pageUrl }?.size ?: 0) > 1
    val hasQuality = (media?.qualities?.distinctBy { it.id }?.size ?: videoChoices.size) > 1
    val hasAudio = (if (usesResolvedAudioSelection(media))
        media?.audios?.distinctBy { it.id }?.size ?: 0 else audioChoices.size) > 1
    var dialog by remember { mutableStateOf<String?>(null) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    var fill by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var gestureScrub by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    var hint by remember { mutableStateOf<String?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val inPip = host.inPip
    var videoView by remember { mutableStateOf<PlayerView?>(null) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    var glassContentRevision by remember(player) { mutableLongStateOf(0L) }
    val keepControls = host.keepControls
    val glass = host.glassOverride ?: LocalGlassEnabled.current
    val interactions = LocalPlaybackInteractions.current.value
    var gestureActive by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    val temporarySpeed = remember(player) { com.sweefl.player.domain.TemporaryPlaybackSpeed() }
    var holdSpeed by remember(player) { mutableStateOf<Float?>(null) }
    fun endHold() {
        val previous = temporarySpeed.end() ?: return
        holdSpeed = null
        gestureActive = false
        try { player.setPlaybackSpeed(previous) } finally { host.setTemporaryPlaybackBaseSpeed(null) }
    }
    fun startHold() {
        if (!foreground || inPip || locked || dialog != null) return
        val original = player.playbackParameters.speed
        val applied = temporarySpeed.begin(original, interactions.longPressSpeed,
            player.isPlaying, locked) ?: return
        host.setTemporaryPlaybackBaseSpeed(original)
        holdSpeed = applied
        gestureActive = true
        hint = null
        player.setPlaybackSpeed(applied)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
                if (!temporarySpeed.active) speed = parameters.speed
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) endHold()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) endHold()
                buffering = state == Player.STATE_BUFFERING
                timeline = host.playbackTimeline()
                if (state == Player.STATE_ENDED) { controls = true; onSave() }
                if (state == Player.STATE_READY) error = null
            }
            override fun onPlayerError(failure: PlaybackException) {
                endHold()
                error = "无法播放此视频（${failure.errorCodeName}）。文件可能已移除、访问权限已失效，或设备不支持此编码。"
                controls = true
                buffering = false
            }
            override fun onRenderedFirstFrame() { glassContentRevision++ }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                glassContentRevision++
            }
            override fun onVideoSizeChanged(value: androidx.media3.common.VideoSize) { videoSize = value }
            override fun onTracksChanged(value: Tracks) { tracks = value }
        }
        player.addListener(listener)
        playing = player.isPlaying
        buffering = player.playbackState == Player.STATE_BUFFERING
        timeline = host.playbackTimeline()
        tracks = player.currentTracks
        player.playerError?.let(listener::onPlayerError)
        onDispose { endHold(); player.removeListener(listener) }
    }
    LaunchedEffect(player, controls, inPip, playing) {
        var savedAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            timeline = host.playbackTimeline()
            val now = android.os.SystemClock.elapsedRealtime()
            if (playing && now - savedAt >= 10_000) { onSave(); savedAt = now }
            delay(if (controls && !inPip && playing) 500 else 2000)
        }
    }
    LaunchedEffect(controls, playing, scrubbing, sliderRangeHeld, dialog, locked, gestureActive) {
        if (controls && playing && !scrubbing && !sliderRangeHeld && dialog == null && !locked && !gestureActive && !keepControls) { delay(4500); controls = false }
    }
    LaunchedEffect(hint, gestureActive) { if (hint != null && !gestureActive) { delay(if (hint == media?.notice) 3500 else 1200); hint = null } }
    LaunchedEffect(controls, locked, inPip) {
        if (!inPip) window.showSystemBars(controls && !locked)
    }
    DisposableEffect(window) { onDispose { window.showSystemBars(true) } }
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) foreground = true
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                foreground = false
                endHold()
                scrubbing = false; gestureActive = false; hint = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { endHold(); lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(locked) { locked = false; controls = true }
    LaunchedEffect(inPip, locked, dialog) {
        if (inPip || locked || dialog != null) endHold()
        if (inPip || locked) { scrubbing = false; gestureActive = false; hint = null }
    }
    fun seek(delta: Long) {
        endHold()
        val range = host.playbackTimeline()
        if (!range.seekable || range.durationMs <= 0) { hint = "当前视频不可调整进度"; return }
        host.seekTo(range.clamp(host.seekBasePosition() + delta))
        timeline = host.playbackTimeline()
        hint = "${if (delta > 0) "+" else "−"}${kotlin.math.abs(delta) / 1000} 秒"
    }
    fun togglePlayback() {
        endHold()
        host.togglePlayback()
    }
    fun finishScrub(commit: Boolean) {
        val range = host.playbackTimeline()
        if (scrubbing && commit && range.seekable && range.durationMs > 0) {
            host.seekTo(range.clamp(scrubOrigin + scrub.toLong()))
            timeline = host.playbackTimeline()
        }
        scrubbing = false
    }
    fun timeLabel(target: Long): String = if (timeline.isLive) {
        val behind = (timeline.endMs - target).coerceAtLeast(0L)
        if ((!scrubbing && timeline.atLiveEdge && target == position) || behind < 2_000L) "直播" else "−${formatDuration(behind)}"
    } else formatDuration((target - timeline.startMs).coerceAtLeast(0L))
    LaunchedEffect(player, media?.pageUrl, media?.selectedVideoId, timeline.isLive) {
        endHold(); scrubbing = false; gestureActive = false
        timeline = host.playbackTimeline()
    }
    LaunchedEffect(media?.notice) { media?.notice?.let { hint = it; controls = true } }
    LaunchedEffect(hasEpisodes, hasQuality, hasAudio) {
        if ((dialog == "episodes" && !hasEpisodes) || (dialog == "quality" && !hasQuality) ||
            (dialog == "audio" && !hasAudio)) dialog = null
    }
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val timeMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val timeTemplate = (if (timeline.isLive) "−" else "") + if (duration > 0) formatDuration(duration.coerceAtLeast(3_599_000L))
        .map { if (it.isDigit()) '8' else it }.joinToString("") else "88:88:88"
    val timeLabelWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
        timeMeasurer.measure(timeTemplate, style = timeStyle, maxLines = 1, softWrap = false).size.width.toDp() + 2.dp
    }
    VideoGlassProvider(videoView?.videoSurfaceView as? SurfaceView, playing,
        active = !inPip && foreground && (controls || scrubbing || holdSpeed != null || dialog != null || hint != null || host.sourceLoading || host.sourceError != null || host.opticalSeekHandoff.running), enabled = glass,
        videoWidth = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt(), videoHeight = videoSize.height, contentRevision = glassContentRevision,
        videoFrameToken = host::presentedFrameToken, videoFrameRevision = host::glassFrameRevision, buffering = buffering, handoff = host.opticalSeekHandoff,
        videoExpected = !(player.playbackState == Player.STATE_READY && !player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))) {
    val opticalReady by com.sweefl.player.core.designsystem.LocalGlassReady.current
    val revealControls = opticalReady
    val loading = buffering || host.opticalSeekHandoff.running || host.sourceLoading
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { context -> PlayerView(context).apply { this.player = player; useController = false; videoView = this } },
            modifier = Modifier.fillMaxSize(),
            update = {
                if (it.player !== player) it.player = player
                it.resizeMode = if (fill && !inPip) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onRelease = { it.player = null; videoView = null })
        com.sweefl.player.core.designsystem.OpticalSeekCover(host.opticalSeekHandoff)
        if (!inPip && foreground && dialog == null) PlayerGestureLayer(player, locked, interactions, window,
            onHoldStart = ::startHold, onHoldEnd = ::endHold,
            onTap = { controls = if (locked) true else !controls }, onDoubleTap = { action ->
                when {
                    action.seekMs != 0L -> seek(action.seekMs)
                    action == DoubleTapAction.PLAY_PAUSE -> {
                        togglePlayback()
                        hint = if (player.playWhenReady) "继续播放" else "已暂停"
                    }
                    action == DoubleTapAction.CONTROLS -> controls = !controls
                    action == DoubleTapAction.LOCK -> { locked = true; endHold(); controls = false }
                }
            }, onActive = { gestureActive = it }, onHint = { hint = it },
            onSeekStart = { target, range ->
                gestureScrub = true; scrubbing = true
                scrubOrigin = range.startMs; scrubDuration = range.durationMs
                scrub = (target - scrubOrigin).toFloat(); hint = null
            },
            onSeekChange = { scrub = (it - scrubOrigin).toFloat(); hint = if (interactions.showSeekPreview) null else "定位 ${timeLabel(it)}" },
            onSeekEnd = ::finishScrub, timeline = host::playbackTimeline, seekBasePosition = host::seekBasePosition,
            sourceKey = Triple(media?.pageUrl, media?.selectedVideoId, timeline.isLive))
        if (loading && !inPip && (!controls || !revealControls || scrubbing || locked || holdSpeed != null))
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Mint)
        if (locked && controls && !inPip) IconButton(onClick = { locked = false; controls = true },
            modifier = Modifier.align(Alignment.CenterEnd).navigationBarsPadding().padding(20.dp)) { Icon(Icons.Rounded.LockOpen, "解锁控制") }
        if (((controls && revealControls) || scrubbing) && !locked && !inPip) {
            if (controls && !(scrubbing && gestureScrub)) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { host.finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回媒体库") }
                Text(title, Modifier.weight(1f).glassContentContrast(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                GlassSurface(radius = 28) { Row {
                IconButton(onClick = { dialog = "details" }) { Icon(Icons.Rounded.Info, "播放信息与解码") }
                IconButton(onClick = { endHold(); if (!host.enterPip()) hint = "当前设备或设置不允许画中画" }) { Icon(Icons.Rounded.PictureInPictureAlt, "画中画") }
                IconButton(onClick = { locked = true; endHold(); controls = false }) { Icon(Icons.Rounded.Lock, "锁定触控，轻触屏幕后可解锁") }
                } }
            }
            }
            if (controls && error == null && !scrubbing && holdSpeed == null) Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { seek(-10_000) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Replay10, "后退 10 秒") }
                FilledIconButton(onClick = ::togglePlayback, modifier = Modifier.size(70.dp).semantics {
                    if (loading) contentDescription = if (host.desiredPlayWhenReady) "正在加载，暂停" else "正在加载，继续播放"
                }) {
                    if (loading) CircularProgressIndicator(Modifier.size(32.dp), color = Mint, strokeWidth = 3.dp)
                    else Icon(if (host.desiredPlayWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (host.desiredPlayWhenReady) "暂停" else "播放", Modifier.size(36.dp))
                }
                IconButton(onClick = { seek(10_000) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Forward10, "快进 10 秒") }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                if (scrubbing && interactions.showSeekPreview && !timeline.isLive && media?.live == null) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)) { preview(scrubOrigin + scrub.toLong()) }
                }
                GlassSurface(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeLabel(if (scrubbing) scrubOrigin + scrub.toLong() else position), Modifier.width(timeLabelWidth),
                        style = timeStyle, textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
                    Slider(value = if (scrubbing) scrub.coerceIn(0f, scrubDuration.toFloat().coerceAtLeast(1f))
                        else if (sliderRangeHeld) (position - scrubOrigin).coerceIn(0L, scrubDuration).toFloat()
                        else (timeline.clamp(position) - timeline.startMs).toFloat(),
                        onValueChange = {
                            endHold()
                            if (!scrubbing) {
                                gestureScrub = false
                                if (!sliderRangeHeld) {
                                    scrubOrigin = timeline.startMs; scrubDuration = timeline.durationMs
                                }
                            }
                            scrubbing = true; scrub = it
                        },
                        onValueChangeFinished = { finishScrub(true) },
                        valueRange = 0f..(if (scrubbing || sliderRangeHeld) scrubDuration else duration).toFloat().coerceAtLeast(1f),
                        enabled = timeline.seekable && duration > 0,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            .pointerInput(player, media?.pageUrl, media?.selectedVideoId, timeline.isLive) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val range = host.playbackTimeline()
                                    scrubOrigin = range.startMs; scrubDuration = range.durationMs
                                    sliderRangeHeld = true
                                    var released = false
                                    try {
                                        do { val event = awaitPointerEvent(PointerEventPass.Final) }
                                        while (event.changes.any { it.pressed })
                                        released = true
                                    } finally {
                                        sliderRangeHeld = false
                                        if (!released && !gestureScrub) scrubbing = false
                                    }
                                }
                            }.semantics { contentDescription = "播放进度" })
                    Text(if (timeline.isLive) "直播" else formatDuration(duration), Modifier.width(timeLabelWidth),
                        style = timeStyle, textAlign = androidx.compose.ui.text.style.TextAlign.Start, maxLines = 1)
                }
                if (controls && !(scrubbing && gestureScrub)) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    if (hasEpisodes) TextButton(onClick = { dialog = "episodes" }, enabled = !host.sourceLoading) { Text("选集") }
                    if (hasQuality) TextButton(onClick = { dialog = "quality" }, enabled = !host.sourceLoading) { Text("清晰度") }
                    if (hasAudio) TextButton(onClick = { dialog = "audio" }, enabled = !host.sourceLoading) { Text("音轨") }
                    if (timeline.isLive) TextButton(onClick = { endHold(); host.goLive() }, enabled = !host.sourceLoading) { Text("返回直播") }
                    TextButton(onClick = { dialog = "speed" }) { Text("${speed}×") }
                    IconButton(onClick = { dialog = "gestures" }) { Icon(Icons.Rounded.TouchApp, "手势与进度预览") }
                    IconButton(onClick = { dialog = "tracks" }) { Icon(Icons.Rounded.Subtitles, "字幕与音轨") }
                    IconButton(onClick = { fill = !fill }) { Icon(if (fill) Icons.Rounded.FitScreen else Icons.Rounded.AspectRatio, if (fill) "恢复完整画面" else "填满画面，裁切边缘") }
                    IconButton(onClick = {
                        endHold()
                        window.rotate(landscape)
                    }) { Icon(Icons.Rounded.ScreenRotation, "切换横竖屏") }
                }
                }
                }
            }
        }
        if (!inPip && !locked && !scrubbing) {
            val feedback = holdSpeed?.let { "长按 ${it}× · 松手恢复" } ?: hint
            feedback?.let {
                Text(it, Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = if (controls) 76.dp else 24.dp).glass(18)
                    .glassContentContrast().padding(horizontal = 14.dp, vertical = 9.dp), color = Mint,
                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        if (!inPip && host.sourceError != null) {
            GlassSurface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 440.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("来源暂时不可用", style = MaterialTheme.typography.titleLarge)
                    Text("未能加载所选内容，请检查网络或稍后重新选择。已有播放内容会保留。")
                    TextButton(onClick = host::clearSourceError) { Text("关闭提示") }
                }
            }
        }
        if (!inPip && host.sourceError == null) error?.let { message ->
            GlassSurface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 440.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("播放遇到问题", style = MaterialTheme.typography.titleLarge)
                Text(message)
                Row {
                    TextButton(onClick = { error = null; buffering = true; host.retryPlayback() }) { Text("重试") }
                    if (host.renderSettings.mode == com.sweefl.player.domain.PlaybackRenderMode.GPU_SDR) TextButton(onClick = {
                        host.applyRenderSettings(host.renderSettings.copy(mode = com.sweefl.player.domain.PlaybackRenderMode.NATIVE))
                    }) { Text("切回原生渲染") }
                    else if (host.decodeMode == "SOFTWARE") TextButton(onClick = { host.changeDecodeMode("AUTO") }) { Text("改用自动解码") }
                    TextButton(onClick = { host.finish() }) { Text("返回") }
                }
            }
            }
        }
    }
    if (dialog == "speed" && !inPip) AlertDialog(onDismissRequest = { dialog = null }, title = { Text("播放速度") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { value ->
            TextButton(onClick = { endHold(); player.setPlaybackSpeed(value); speed = value; dialog = null }, modifier = Modifier.fillMaxWidth()) {
                Text(if (speed == value) "✓  ${value}×" else "${value}×")
            }
        } }
    }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("完成") } })
    if (!inPip && dialog in listOf("episodes", "quality", "audio"))
        OnlineChoiceDialog(dialog!!, host, player, tracks) { dialog = null }
    if (dialog == "tracks" && !inPip) TrackDialog(player, tracks, host) { dialog = null }
    if (dialog == "details" && !inPip) PlaybackDetailsDialog(host, onGlassChanged) { dialog = null }
    if (dialog == "gestures" && !inPip) PlaybackInteractionDialog { dialog = null }
    }
}
