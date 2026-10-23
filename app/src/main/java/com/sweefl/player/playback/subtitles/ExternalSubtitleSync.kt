@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.subtitles

import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.SpannedString
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Consumer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.sweefl.player.domain.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One engine lifetime. Each media source must capture its own immutable parser epoch. */
class ExternalSubtitleSync(initialOffsetMs: Long, initialEpoch: Long = 1L) {
    internal data class Snapshot(val epoch: Long, val timeline: SubtitleTimeline<Cue>?)
    internal data class Refresh(val epoch: Long, val periodUid: Any?, val periodPositionUs: Long?)
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val state = AtomicReference(Snapshot(initialEpoch, null))
    private val offset = AtomicLong(SubtitleSyncRules.clampOffsetMs(initialOffsetMs))
    internal val playbackEnded = AtomicBoolean(false)
    private var player: ExoPlayer? = null
    private var renderer: ExternalSubtitleRenderer? = null
    private var output: TextOutput? = null
    internal val epoch get() = state.get().epoch
    internal val offsetMs get() = offset.get()
    fun ownsFormat(id: String?) = !closed.get() && id?.endsWith(MARKER) == true
    internal fun snapshotFor(id: String?, rendererEpoch: Long): Snapshot? = state.get().takeIf {
        ownsFormat(id) && it.epoch == rendererEpoch
    }
    fun bind(player: ExoPlayer) { this.player = player; requestRefresh() }
    internal fun attachOutput(output: TextOutput, renderer: ExternalSubtitleRenderer) {
        this.output = output; this.renderer = renderer
    }
    fun setOffsetMs(value: Long) {
        offset.set(SubtitleSyncRules.clampOffsetMs(value)); invalidateOutput(); requestRefresh()
    }
    fun beginEpoch(epoch: Long) {
        if (closed.get()) return
        state.set(Snapshot(epoch, null)); playbackEnded.set(false); invalidateOutput()
        if (renderer?.active?.get() == true) emit(emptyList(), 0L, epoch)
        requestRefresh()
    }
    fun setPlaybackEnded(ended: Boolean) {
        if (playbackEnded.getAndSet(ended) != ended) { invalidateOutput(); requestRefresh() }
    }
    internal fun invalidateOutput() { generation.incrementAndGet() }
    @Suppress("DEPRECATION")
    internal fun emit(cues: List<Cue>, presentationTimeUs: Long, epoch: Long) {
        if (closed.get() || state.get().epoch != epoch) return
        val ticket = generation.incrementAndGet()
        val group = CueGroup(cues, presentationTimeUs.coerceAtLeast(0L))
        main.post {
            if (!closed.get() && generation.get() == ticket && state.get().epoch == epoch) {
                output?.onCues(group.cues); output?.onCues(group)
            }
        }
    }
    private fun requestRefresh() {
        if (closed.get()) return
        main.post {
            if (closed.get()) return@post
            val engine = player ?: return@post
            val target = renderer ?: return@post
            val timeline = engine.currentTimeline
            val index = engine.currentPeriodIndex
            val milliseconds = engine.currentPosition
            var uid: Any? = null
            var position: Long? = null
            if (!timeline.isEmpty && index in 0 until timeline.periodCount && milliseconds in 0L..Long.MAX_VALUE/1000L) {
                val period = timeline.getPeriod(index, Timeline.Period(), true)
                uid = period.uid
                position = SubtitleSyncRules.subtractSaturated(milliseconds*1000L, period.positionInWindowUs)
            }
            engine.createMessage(target).setType(ExternalSubtitleRenderer.MSG_REFRESH)
                .setPayload(Refresh(epoch, uid, position)).send()
        }
    }
    private fun publish(parserEpoch: Long, timeline: SubtitleTimeline<Cue>) {
        while (!closed.get()) {
            val before = state.get()
            if (before.epoch != parserEpoch) return
            if (state.compareAndSet(before, Snapshot(parserEpoch, timeline))) { requestRefresh(); return }
        }
    }

    /** Fresh per-source factory. Never mutate an extractor factory already held by an old loader. */
    internal fun parserFactoryFor(parserEpoch: Long, baseFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()): SubtitleParser.Factory = object : SubtitleParser.Factory {
        private val defaults = baseFactory
        override fun supportsFormat(format: Format) = defaults.supportsFormat(format)
        override fun getCueReplacementBehavior(format: Format) = defaults.getCueReplacementBehavior(format)
        override fun create(format: Format): SubtitleParser {
            val normal = defaults.create(format)
            if (!isExternal(format)) return normal
            return object : SubtitleParser {
                override fun getCueReplacementBehavior() = normal.cueReplacementBehavior
                override fun reset() { normal.reset() } // Complete timeline survives seeks.
                override fun parse(data: ByteArray, offset: Int, length: Int,
                    outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
                    if (!closed.get() && epoch == parserEpoch) {
                        val fullInput = data.copyOfRange(offset, offset+length)
                        val groups = ArrayList<TimedSubtitleCues<Cue>>()
                        val collector = defaults.create(format)
                        collector.parse(fullInput, 0, fullInput.size, SubtitleParser.OutputOptions.allCues(), Consumer { timed ->
                            val start = if (timed.startTimeUs == C.TIME_UNSET) 0L else timed.startTimeUs
                            val end = if (timed.endTimeUs == C.TIME_UNSET) Long.MAX_VALUE else timed.endTimeUs
                            if (end >= start) {
                                val frozen = timed.cues.map { cue ->
                                    val text = cue.text
                                    if (text == null) cue else cue.buildUpon().setText(
                                        if (text is Spanned) SpannedString(text) else text.toString()).build()
                                }
                                groups.add(TimedSubtitleCues(start, end, frozen))
                            }
                        })
                        publish(parserEpoch, SubtitleTimeline.create(groups,
                            collector.cueReplacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE))
                    }
                    // Preserve the normal parser's original input, options and callback contract.
                    normal.parse(data, offset, length, outputOptions, output)
                }
            }
        }
    }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidateOutput(); main.removeCallbacksAndMessages(null)
        state.set(Snapshot(epoch, null)); player = null; renderer = null; output = null
    }
    companion object {
        const val MARKER = "sweefl-external"
        internal fun isExternal(format: Format) = format.id?.endsWith(MARKER) == true
    }
}
