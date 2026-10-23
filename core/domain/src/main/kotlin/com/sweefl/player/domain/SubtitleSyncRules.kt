package com.sweefl.player.domain

import java.util.Collections

object SubtitleSyncRules {
    const val MAX_OFFSET_MS = 60_000L
    fun clampOffsetMs(value: Long) = value.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
    fun subtractSaturated(a: Long, b: Long): Long = try { Math.subtractExact(a, b) }
        catch (_: ArithmeticException) { if (b >= 0L) Long.MIN_VALUE else Long.MAX_VALUE }
    fun addSaturated(a: Long, b: Long): Long = try { Math.addExact(a, b) }
        catch (_: ArithmeticException) { if (b >= 0L) Long.MAX_VALUE else Long.MIN_VALUE }
    /** Positive delays subtitles without moving the audio/video clock. */
    fun timelinePositionUs(rendererPositionUs: Long, streamOffsetUs: Long, offsetMs: Long): Long =
        subtractSaturated(subtractSaturated(rendererPositionUs, streamOffsetUs), clampOffsetMs(offsetMs) * 1_000L)
}

data class TimedSubtitleCues<T>(val startUs: Long, val endUs: Long, val cues: List<T>)

/** Complete seek-independent timeline. REPLACE never resurrects an older replaced group. */
class SubtitleTimeline<T> private constructor(
    private val groups: List<TimedSubtitleCues<T>>, private val replace: Boolean,
) {
    private val prefixEnd = LongArray(groups.size)
    init {
        var end = Long.MIN_VALUE
        for (index in groups.indices) {
            end = maxOf(end, groups[index].endUs)
            prefixEnd[index] = end
        }
    }
    fun cuesAt(timeUs: Long): List<T> {
        var low = 0
        var high = groups.size
        while (low < high) {
            val middle = low + (high-low)/2
            if (groups[middle].startUs <= timeUs) low = middle+1 else high = middle
        }
        val last = low-1
        if (last < 0) return emptyList()
        if (replace) return groups[last].let { if (timeUs < it.endUs) it.cues else emptyList() }
        val active = ArrayList<List<T>>()
        var index = last
        while (index >= 0 && prefixEnd[index] > timeUs) {
            val group = groups[index]
            if (timeUs < group.endUs) active.add(group.cues)
            index--
        }
        if (active.isEmpty()) return emptyList()
        val result = ArrayList<T>()
        for (group in active.asReversed()) result.addAll(group)
        return Collections.unmodifiableList(result)
    }
    companion object {
        fun <T> create(input: List<TimedSubtitleCues<T>>, replace: Boolean): SubtitleTimeline<T> {
            require(input.all { it.endUs >= it.startUs })
            val copied = input.map {
                TimedSubtitleCues(it.startUs, it.endUs, Collections.unmodifiableList(ArrayList(it.cues)))
            }.sortedBy { it.startUs }
            return SubtitleTimeline(Collections.unmodifiableList(copied), replace)
        }
    }
}
