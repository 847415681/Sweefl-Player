package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class PresentedFrameTrackerTest {
    @Test fun futureFramesNeverBecomeEligibleEarly() {
        val frames=PresentedFrameTracker()
        frames.scheduled(100);frames.scheduled(200)
        assertEquals(-1,frames.tokenAt(99))
        val first=frames.tokenAt(100);assertTrue(first>=0)
        assertEquals(first,frames.tokenAt(199))
        assertNotEquals(first,frames.tokenAt(200))
    }
    @Test fun finalScheduledFrameCanArriveAfterDecoderStops() {
        val frames=PresentedFrameTracker()
        frames.scheduled(100);val before=frames.tokenAt(100)
        frames.scheduled(150);assertEquals(before,frames.tokenAt(149))
        val after=frames.tokenAt(150);assertNotEquals(before,after)
        assertEquals(after,frames.tokenAt(1000))
    }
    @Test fun hiddenControlsKeepOnlyBoundedRecentHistory() {
        val frames=PresentedFrameTracker(2)
        for (i in 1L..100L) frames.scheduled(i)
        assertEquals(-1,frames.tokenAt(98))
        assertTrue(frames.tokenAt(99)>=0)
        assertNotEquals(frames.tokenAt(99),frames.tokenAt(100))
    }
    @Test fun resetUnknownTimingAndReleaseDoNotReuseOldTokens() {
        val frames=PresentedFrameTracker()
        frames.scheduled(100);frames.tokenAt(100);frames.reset()
        assertEquals(-1,frames.tokenAt(200))
        frames.scheduled(250);frames.tokenAt(250);frames.scheduled(-1)
        assertEquals(-1,frames.tokenAt(500))
        frames.close();frames.scheduled(600);assertEquals(-1,frames.tokenAt(1000))
    }
}
