@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player

import com.sweefl.player.core.designsystem.LocalGlassEnabled
import com.sweefl.player.core.designsystem.SweeflTheme
import com.sweefl.player.core.platform.DocumentAccess
import com.sweefl.player.domain.PlaybackConfig
import com.sweefl.player.feature.player.PlaybackSession
import com.sweefl.player.feature.player.PlayerHost
import com.sweefl.player.feature.player.platform.PlayerWindowController
import com.sweefl.player.feature.player.ui.PlayerScreen
import com.sweefl.player.feature.settings.rememberGlassEnabled
import com.sweefl.player.playback.diagnostics.DeviceCapabilities

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class PlayerActivity : ComponentActivity(), PlayerHost {
    private lateinit var session: PlaybackSession
    private val container by lazy { (application as SweeflApplication).container }
    private val documents by lazy { DocumentAccess(this) }
    private val windowController by lazy { PlayerWindowController(this) }
    private var pickingSubtitle = false
    private var openingBilibiliLogin = false
    private val bilibiliLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        openingBilibiliLogin = false
        updatePipParams()
        if (::session.isInitialized) session.retryPlayback()
    }
    private fun openBilibiliLogin() {
        openingBilibiliLogin = true
        updatePipParams()
        bilibiliLogin.launch(Intent(this, com.sweefl.player.feature.network.BilibiliLoginActivity::class.java))
    }
    override var inPip by mutableStateOf(false)
        private set
    override val decodeMode get() = session.decodeMode
    override val subtitleName get() = session.subtitleName
    override val onlineMedia get() = session.onlineMedia
    override val sourceLoading get() = session.sourceLoading
    override val sourceError get() = session.sourceError
    override fun clearSourceError() = session.clearSourceError()
    override fun playbackTimeline() = session.playbackTimeline()
    override fun selectEpisode(pageUrl: String) = session.selectEpisode(pageUrl)
    override fun selectQuality(qualityId: Int) = session.selectQuality(qualityId)
    override fun selectAudio(audioId: String) = session.selectAudio(audioId)
    override fun goLive() = session.goLive()
    override fun glassFrameRevision() = session.glassFrameRevision()
    override fun seekTo(positionMs: Long) = session.seekTo(positionMs)
    override fun seekBasePosition() = session.seekBasePosition()
    override val opticalSeekHandoff get() = session.opticalSeekHandoff
    override val desiredPlayWhenReady get() = session.desiredPlayWhenReady
    override fun togglePlayback() = session.togglePlayback()
    override fun presentedFrameToken(cutoffNs: Long) = session.presentedFrameToken(cutoffNs)
    override val renderSettings get() = session.renderSettings
    override val renderingStatus get() = session.renderingStatus
    override fun applyRenderSettings(value: com.sweefl.player.domain.PlaybackRenderSettings) = session.applyRenderSettings(value)
    override val subtitleOffsetMs get() = session.subtitleOffsetMs
    override fun setSubtitleOffsetMs(value: Long) = session.updateSubtitleOffsetMs(value)
    override val diagnosticsState get() = session.diagnosticsState
    private val debug get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    override val keepControls get() = debug && intent.getBooleanExtra("keepControls", false)
    override val glassOverride get() = if (debug && intent.hasExtra("glass")) intent.getBooleanExtra("glass", true) else null
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickingSubtitle = false
        updatePipParams()
        if (uri != null) attachSubtitle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val uri = intent.data ?: run { finish(); return }
        try {
            PlaybackConfig.validateMediaUri(uri.toString())
            val store = container.playbackConfig
            val settingsIdentity = savedInstanceState?.getString("onlinePageUrl") ?: uri.toString()
            val mediaMime = com.sweefl.player.domain.normalizeStreamMime(intent.type ?: store.mimeType(uri.toString()))
            if (intent.type != null) store.saveMimeType(uri.toString(), mediaMime)
            val userAgent = PlaybackConfig.validateUserAgent(if (intent.hasExtra(PlaybackConfig.EXTRA_USER_AGENT))
                intent.getStringExtra(PlaybackConfig.EXTRA_USER_AGENT) else store.userAgent(uri.toString()))
            val mode = PlaybackConfig.parseDecodeMode(savedInstanceState?.getString("decodeMode") ?: intent.getStringExtra(PlaybackConfig.EXTRA_DECODE_MODE))
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) store.saveUserAgent(uri.toString(), userAgent)
            val benchmark = debug && intent.getBooleanExtra("benchmark", false)
            if (benchmark) {
                windowController.setBrightness(.4f)
                if (intent.getBooleanExtra("landscape", false)) windowController.rotate(false)
            }
            session = PlaybackSession(applicationContext, uri, mediaMime, intent.getStringExtra("title") ?: documents.displayName(uri),
                userAgent, mode, container.history, container.playerEngines, savedInstanceState,
                debug && intent.getBooleanExtra(PlaybackConfig.EXTRA_DIAGNOSTICS, false), benchmark, ::onPlaybackChanged,
                initialSubtitleOffsetMs = store.subtitleOffsetMs(settingsIdentity),
                persistSubtitleOffsetMs = { store.saveSubtitleOffsetMs(session.sourceIdentity, it) },
                initialRenderSettings = store.renderSettings(settingsIdentity),
                persistRenderSettings = { store.saveRenderSettings(session.sourceIdentity, it) },
                onlineResolver = container.onlineMediaResolver,
                readSubtitleOffsetMs = store::subtitleOffsetMs, readRenderSettings = store::renderSettings)
        } catch (failure: IllegalArgumentException) {
            Toast.makeText(this, failure.message, Toast.LENGTH_LONG).show()
            finish(); return
        }
        pickingSubtitle = savedInstanceState?.getBoolean("pickingSubtitle", false) ?: false
        openingBilibiliLogin = savedInstanceState?.getBoolean("openingBilibiliLogin", false) ?: false
        DeviceCapabilities.collectOnce(this)
        setContent {
            val glass = rememberGlassEnabled(container.settings)
            val interactions = com.sweefl.player.feature.settings.rememberPlaybackInteractions(container.settings)
            CompositionLocalProvider(LocalGlassEnabled provides glass,
                com.sweefl.player.feature.settings.LocalPlaybackInteractions provides
                    com.sweefl.player.feature.settings.InteractionPreferences(interactions) { container.settings.interactions = it }) {
                com.sweefl.player.feature.settings.NetworkPlaybackPreferencesProvider(container.settings) {
                com.sweefl.player.feature.settings.GlassRefreshPreferencesProvider(container.settings) {
                SweeflTheme {
                    val engine = session.player
                    if (engine == null) {
                        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding(), contentAlignment = Alignment.Center) {
                            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (session.sourceLoading) CircularProgressIndicator()
                                Text(session.sourceError ?: "正在获取播放地址…", color = Color.White)
                                if (session.sourceError != null) {
                                    Button(onClick = { session.retryPlayback() }) { Text("重试") }
                                    TextButton(onClick = { openBilibiliLogin() }) { Text("B站官方登录") }
                                }
                                TextButton(onClick = { finish() }) { Text("返回") }
                            }
                        }
                    } else key(engine) {
                        val previewHost = com.sweefl.player.feature.player.ui.rememberSeekPreviewHost(
                            session.previewSource, enabled = interactions.showSeekPreview && !inPip && session.onlineMedia?.live == null)
                        PlayerScreen(engine, session.title, this, windowController,
                            onGlassChanged = { container.settings.glassEnabled = it },
                            preview = { target -> com.sweefl.player.feature.player.ui.SeekPreview(previewHost, target) },
                            onSave = session::saveProgress)
                    }
                }
                }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); if (::session.isInitialized) session.initializePlayer() }
    private fun onPlaybackChanged() {
        if (::session.isInitialized && session.player?.isPlaying == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updatePipParams()
    }
    override fun setTemporaryPlaybackBaseSpeed(speed: Float?) { session.temporaryPlaybackBaseSpeed = speed }
    override fun retryPlayback() = session.retryPlayback()
    override fun changeDecodeMode(mode: String) = session.changeDecodeMode(mode)
    override fun removeSubtitle() = session.removeSubtitle()
    override fun pickSubtitle() {
        if (inPip || pickingSubtitle) return
        pickingSubtitle = true
        updatePipParams()
        // Providers frequently label ASS/SSA/SRT as octet-stream, so validate the chosen name/type.
        subtitlePicker.launch(arrayOf("*/*"))
    }

    private fun attachSubtitle(uri: Uri) {
        if (uri.scheme != "content") {
            Toast.makeText(this, "请选择文档提供方中的字幕文件。", Toast.LENGTH_LONG).show(); return
        }
        val name = documents.displayName(uri)
        val mime = documents.subtitleMime(uri, name) ?: run {
            Toast.makeText(this, "支持 SRT、VTT、ASS 和 SSA 字幕。", Toast.LENGTH_LONG).show(); return
        }
        if (!documents.retainReadPermission(uri)) Toast.makeText(this, "提供方未允许永久访问，字幕可能仅本次可用。", Toast.LENGTH_LONG).show()
        session.attachSubtitle(uri, name, mime)
    }

    private fun pipParams(): PictureInPictureParams {
        val size = session.player?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0)
            (size.width.toFloat() * size.pixelWidthHeightRatio / size.height).coerceIn(1f / 2.39f, 2.39f) else 16f / 9f
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 10_000).toInt().coerceIn(4_185, 23_900), 10_000)).apply {
            if (Build.VERSION.SDK_INT >= 31) {
                setAutoEnterEnabled(session.player?.isPlaying == true && !pickingSubtitle && !openingBilibiliLogin)
                setSeamlessResizeEnabled(true)
            }
        }.build()
    }

    private fun updatePipParams() {
        if (::session.isInitialized && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun enterPip(): Boolean {
        if (isFinishing || !::session.isInitialized || session.player == null || pickingSubtitle || openingBilibiliLogin || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        if (isInPictureInPictureMode) return true
        return runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::session.isInitialized && session.player?.isPlaying == true && !pickingSubtitle && !openingBilibiliLogin && Build.VERSION.SDK_INT < 31) enterPip()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::session.isInitialized) session.onOutputConfigurationChanged()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::session.isInitialized) session.saveState(outState)
        outState.putBoolean("pickingSubtitle", pickingSubtitle)
        outState.putBoolean("openingBilibiliLogin", openingBilibiliLogin)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        if (::session.isInitialized) session.stop(pickingSubtitle || openingBilibiliLogin, isChangingConfigurations)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() {
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, uri: Uri, title: String?, userAgent: String? = null, decodeMode: String? = null): Intent {
            PlaybackConfig.validateMediaUri(uri.toString())
            PlaybackConfig.validateUserAgent(userAgent)
            PlaybackConfig.parseDecodeMode(decodeMode)
            return Intent(context, PlayerActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("title", title).apply {
                    if (userAgent != null) putExtra(PlaybackConfig.EXTRA_USER_AGENT, userAgent)
                    if (decodeMode != null) putExtra(PlaybackConfig.EXTRA_DECODE_MODE, decodeMode)
                }
        }
    }
}
