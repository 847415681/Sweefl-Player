package com.sweefl.player.domain

/** Engine reconstruction preserves the exact position, even inside the history completion threshold. */
fun rebuildPosition(position: Long, ended: Boolean): Long = if (ended) 0L else position.coerceAtLeast(0L)

fun resumePosition(position: Long, duration: Long): Long =
    if (position < 0 || (duration > 0 && position >= (duration - 3000).coerceAtLeast(0))) 0 else position

fun seekPosition(position: Long, delta: Long, duration: Long): Long =
    (position + delta).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)

fun formatDuration(milliseconds: Long): String {
    val total = milliseconds.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
}
