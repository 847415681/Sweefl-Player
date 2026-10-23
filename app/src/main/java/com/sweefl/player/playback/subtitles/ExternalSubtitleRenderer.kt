@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.subtitles

import android.content.Context
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.text.Cue
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.sweefl.player.domain.SubtitleSyncRules
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal class ExternalSubtitleRenderer(private val sync: ExternalSubtitleSync) : BaseRenderer(C.TRACK_TYPE_TEXT) {
    internal val active = AtomicBoolean(false)
    private val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val period = Timeline.Period()
    private var inputEnded = false
    private var formatId: String? = null
    private var rendererEpoch = sync.epoch
    private var lastPositionUs = 0L
    private var lastCues: List<Cue>? = null
    override fun getName() = "SweeflExternalSubtitleRenderer"
    override fun supportsFormat(format: Format): Int {
        if (!ExternalSubtitleSync.isExternal(format)) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        val supported = format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES || DefaultSubtitleParserFactory().supportsFormat(format)
        return RendererCapabilities.create(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE)
    }
    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        active.set(true); sync.invalidateOutput()
    }
    override fun onStreamChanged(formats: Array<Format>, startPositionUs: Long, offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId) {
        rendererEpoch = sync.epoch
        formatId = formats.firstOrNull()?.id
        inputEnded = false; lastPositionUs = startPositionUs; clearOutput()
    }
    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        lastPositionUs = positionUs; inputEnded = false; sync.playbackEnded.set(false)
        clearOutput(); refresh()
    }
    private fun clearOutput() {
        lastCues = null; sync.invalidateOutput(); sync.emit(emptyList(), 0L, sync.epoch)
    }
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        lastPositionUs = positionUs
        if (!inputEnded) {
            for (ignored in 0 until 128) {
                input.clear()
                val holder = formatHolder
                when (readSource(holder, input, SampleStream.FLAG_OMIT_SAMPLE_DATA)) {
                    C.RESULT_NOTHING_READ -> { inputEnded = hasReadStreamToEnd(); break }
                    C.RESULT_FORMAT_READ -> holder.format?.id?.let { formatId = it }
                    C.RESULT_BUFFER_READ -> if (input.isEndOfStream) { inputEnded = true; break }
                    else -> break
                }
            }
        }
        // Media3 1.8 keeps rendering enabled renderers after input EOS.
        refresh()
    }
    private fun atPeriodEnd(): Boolean {
        if (state == Renderer.STATE_DISABLED) return false
        val id = mediaPeriodId ?: return false
        val index = timeline.getIndexOfPeriod(id.periodUid)
        if (index == C.INDEX_UNSET) return false
        val duration = timeline.getPeriod(index, period).durationUs
        return duration != C.TIME_UNSET && SubtitleSyncRules.subtractSaturated(lastPositionUs, streamOffsetUs) >= duration
    }
    private fun refresh(force: Boolean = false) {
        if (state == Renderer.STATE_DISABLED) return
        if (force) lastCues = null
        val snapshot = sync.snapshotFor(formatId, rendererEpoch)
        val position = SubtitleSyncRules.subtractSaturated(lastPositionUs, streamOffsetUs)
        val cues = if (snapshot == null || sync.playbackEnded.get() || atPeriodEnd()) emptyList()
            else snapshot.timeline?.cuesAt(SubtitleSyncRules.timelinePositionUs(lastPositionUs, streamOffsetUs, sync.offsetMs)) ?: emptyList()
        if (cues != lastCues) {
            lastCues = cues; sync.emit(cues, position, rendererEpoch)
        }
    }
    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType != MSG_REFRESH) { super.handleMessage(messageType, message); return }
        val update = message as? ExternalSubtitleSync.Refresh ?: return
        if (state == Renderer.STATE_DISABLED || update.epoch != sync.epoch || update.epoch != rendererEpoch) return
        if (update.periodUid == mediaPeriodId?.periodUid && update.periodPositionUs != null)
            lastPositionUs = SubtitleSyncRules.addSaturated(update.periodPositionUs, streamOffsetUs)
        refresh(force = true)
    }
    override fun isReady() = true
    // Positive subtitle delay must never extend video EOF.
    override fun isEnded() = inputEnded || atPeriodEnd()
    override fun onDisabled() {
        active.set(false); formatId = null; inputEnded = false; clearOutput()
    }
    companion object { const val MSG_REFRESH = Renderer.MSG_CUSTOM_BASE + 77 }
}

/** Keep stock TextRenderer untouched for embedded tracks and its internal final-stream hook. */
internal open class ExternalSubtitleRenderersFactory(context: Context, private val sync: ExternalSubtitleSync) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper,
        extensionRendererMode: Int, out: ArrayList<Renderer>) {
        val external = ExternalSubtitleRenderer(sync)
        sync.attachOutput(output, external)
        out.add(external)
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
    }
}
