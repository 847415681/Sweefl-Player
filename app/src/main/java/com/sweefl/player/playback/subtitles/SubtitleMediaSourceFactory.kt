@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.subtitles

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/** Snapshot each loader's epoch without changing the public sweefl-external configuration ID. */
internal class SubtitleMediaSourceFactory(
    private val data: DataSource.Factory, private val sync: ExternalSubtitleSync,
) : MediaSource.Factory {
    private var drm: DrmSessionManagerProvider? = null
    private var loadPolicy: LoadErrorHandlingPolicy? = null
    private var cmcd: CmcdConfiguration.Factory? = null
    private var codecsToParse: Int? = null
    private var parsers: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply { drm = provider }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply { loadPolicy = policy }
    override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory) = apply { cmcd = factory }
    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory) = apply { parsers = factory }
    override fun experimentalParseSubtitlesDuringExtraction(enabled: Boolean) = apply {
        require(enabled) { "External subtitle synchronization requires extraction-time parsing" }
    }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int) = apply { codecsToParse = codecs }
    override fun getSupportedTypes(): IntArray = DefaultMediaSourceFactory(data).supportedTypes
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val capturedEpoch = sync.epoch
        // A new extractors factory is essential: mutating a shared one could retag an old loader.
        return DefaultMediaSourceFactory(data).apply {
            drm?.let { setDrmSessionManagerProvider(it) }
            loadPolicy?.let { setLoadErrorHandlingPolicy(it) }
            cmcd?.let { setCmcdConfigurationFactory(it) }
            codecsToParse?.let { experimentalSetCodecsToParseWithinGopSampleDependencies(it) }
            setSubtitleParserFactory(sync.parserFactoryFor(capturedEpoch, parsers))
            experimentalParseSubtitlesDuringExtraction(true)
        }.createMediaSource(mediaItem)
    }
}
