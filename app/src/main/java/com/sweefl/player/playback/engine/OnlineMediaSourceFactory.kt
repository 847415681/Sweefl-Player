@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.engine

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.text.SubtitleParser
import com.sweefl.player.core.model.ResolvedOnlineMedia

/** Outer wrapper: the subtitle factory still receives each original caller item. */
internal class OnlineMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    online: ResolvedOnlineMedia,
) : MediaSource.Factory by delegate {
    private val selected = SelectedOnlineSourceSnapshot(online)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        return composeSelectedSources(
            caller = mediaItem,
            matchesSelected = { it.localConfiguration?.uri.toString() == selected.videoUrl },
            createPrimary = delegate::createMediaSource,
            createAudioItem = selected.audio?.let { audio ->
                { MediaItem.Builder().setUri(audio.url).setMimeType(audio.mimeType).build() }
            },
            // DefaultMediaSourceFactory retains the default Progressive MP4 extractors.
            createAudio = delegate::createMediaSource,
            merge = { video, sound -> MergingMediaSource(video, sound) },
        )
    }

    // Keep fluent configuration on this wrapper, including future createMediaSource calls.
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply { delegate.setDrmSessionManagerProvider(provider) }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply { delegate.setLoadErrorHandlingPolicy(policy) }
    override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory) = apply { delegate.setCmcdConfigurationFactory(factory) }
    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory) = apply { delegate.setSubtitleParserFactory(factory) }
    override fun experimentalParseSubtitlesDuringExtraction(enabled: Boolean) = apply { delegate.experimentalParseSubtitlesDuringExtraction(enabled) }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int) = apply { delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs) }
}
