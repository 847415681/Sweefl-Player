@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.engine

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sweefl.player.domain.PlaybackConfig

/** The single place that configures Media3 network, decoder and audio policies. */
class PlayerEngineFactory(private val context: Context,
    private val networkSettings: () -> com.sweefl.player.domain.NetworkPlaybackSettings = { com.sweefl.player.domain.NetworkPlaybackSettings() }) {
    fun create(userAgent: String?, decodeMode: String, subtitles: com.sweefl.player.playback.subtitles.ExternalSubtitleSync? = null,
        rendering: com.sweefl.player.playback.rendering.PlaybackRenderingPipeline? = null): ExoPlayer {
        val renderers = (when {
            subtitles != null && rendering != null -> com.sweefl.player.playback.rendering.MainRenderersFactory(context, subtitles, rendering, decodeMode)
            subtitles != null -> com.sweefl.player.playback.subtitles.ExternalSubtitleRenderersFactory(context, subtitles)
            else -> DefaultRenderersFactory(context)
        }).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        if (decodeMode == PlaybackConfig.DECODE_SOFTWARE) {
            val softwareNames = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder && if (Build.VERSION.SDK_INT >= 29) it.isSoftwareOnly else PlaybackConfig.isLegacySoftwareCodec(it.name) }
                .map { it.name }.toSet()
            renderers.setMediaCodecSelector { mime, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { it.name in softwareNames }
            }
        }
        val http = DefaultHttpDataSource.Factory().setUserAgent(userAgent ?: PlaybackConfig.DEFAULT_USER_AGENT)
            .setConnectTimeoutMs(8000).setReadTimeoutMs(8000)
        // Immutable snapshot per engine: changing preferences never disrupts active requests.
        val settings = networkSettings().normalized()
        val parallelHttp = com.sweefl.player.playback.network.ParallelHttpDataSource.Factory(http, settings.threads)
        val data = DefaultDataSource.Factory(context, parallelHttp)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(settings.bufferDurationMs, settings.bufferDurationMs, 1000, 2500)
            .setTargetBufferBytes(com.sweefl.player.domain.NetworkPlaybackSettings.TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val sources = if (subtitles == null) DefaultMediaSourceFactory(data)
            else com.sweefl.player.playback.subtitles.SubtitleMediaSourceFactory(data, subtitles)
        return ExoPlayer.Builder(context, renderers).setMediaSourceFactory(sources).setLoadControl(loadControl).build().apply {
            subtitles?.bind(this)
            rendering?.bind(this)
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
        }
    }
}
