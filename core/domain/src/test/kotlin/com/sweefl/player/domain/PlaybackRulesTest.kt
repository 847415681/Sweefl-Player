package com.sweefl.player.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRulesTest {
    @Test fun completedVideosRestartInsteadOfResumingAtTheEnd() {
        assertEquals(0L, resumePosition(58_000, 60_000))
        assertEquals(30_000L, resumePosition(30_000, 60_000))
        assertEquals(0L, resumePosition(-1, 60_000))
        assertEquals(0L, resumePosition(90_000, 60_000))
    }
    @Test fun unknownDurationDoesNotDiscardValidBookmark() {
        assertEquals(30_000L, resumePosition(30_000, -1))
    }
    @Test fun seekGesturesStayInsideMediaBounds() {
        assertEquals(0L, seekPosition(2_000, -10_000, 60_000))
        assertEquals(60_000L, seekPosition(55_000, 10_000, 60_000))
        assertEquals(30_000L, seekPosition(20_000, 10_000, 60_000))
    }
    @Test fun displayDurationHandlesLongFilmsAndUnknownDuration() {
        assertEquals("0:00", formatDuration(-1))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("2:03:04", formatDuration(7_384_000))
    }
}
