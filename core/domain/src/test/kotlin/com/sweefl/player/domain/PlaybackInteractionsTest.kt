package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class PlaybackInteractionsTest {
    @Test fun regionsHaveIndependentBoundaryActions() {
        val settings = PlaybackInteractionSettings()
        assertEquals(DoubleTapAction.BACK_10, settings.actionAt(99f, 300f))
        assertEquals(DoubleTapAction.PLAY_PAUSE, settings.actionAt(100f, 300f))
        assertEquals(DoubleTapAction.FORWARD_10, settings.actionAt(200f, 300f))
        assertEquals(DoubleTapAction.FORWARD_10, settings.actionAt(300f, 300f))
    }
    @Test fun invalidRegionGeometryIsSafe() {
        assertEquals(1, doubleTapRegion(Float.NaN, 300f))
        assertEquals(1, doubleTapRegion(10f, 0f))
        assertEquals(0, doubleTapRegion(-50f, 300f))
    }
    @Test fun allRegionsCanBeReassignedOrDisabled() {
        val settings = PlaybackInteractionSettings(leftDoubleTap = DoubleTapAction.LOCK,
            centerDoubleTap = DoubleTapAction.NONE, rightDoubleTap = DoubleTapAction.BACK_30)
        assertEquals(DoubleTapAction.LOCK, settings.actionAt(0f, 300f))
        assertEquals(DoubleTapAction.NONE, settings.actionAt(150f, 300f))
        assertEquals(DoubleTapAction.BACK_30, settings.actionAt(299f, 300f))
    }
    @Test fun storedIdsHaveSafeFallbacks() {
        assertEquals(DoubleTapAction.PLAY_PAUSE, DoubleTapAction.decode("removed", DoubleTapAction.PLAY_PAUSE))
        assertEquals(DoubleTapAction.NONE, DoubleTapAction.decode("NONE", DoubleTapAction.PLAY_PAUSE))
    }
    @Test fun swipesClampAtBothEnds() {
        assertEquals(0L, horizontalSeekTarget(10_000, -100f, 100f, 60_000))
        assertEquals(60_000L, horizontalSeekTarget(50_000, 100f, 100f, 60_000))
    }
    @Test fun longVideosUseBoundedSensitivity() {
        assertEquals(90_000L, horizontalSeekTarget(30_000, 50f, 100f, 7_200_000))
        assertEquals(150_000L, horizontalSeekTarget(30_000, 500f, 100f, 7_200_000))
    }
    @Test fun extremeDurationsDoNotOverflow() {
        assertEquals(Long.MAX_VALUE, horizontalSeekTarget(Long.MAX_VALUE - 1, 100f, 100f, Long.MAX_VALUE))
    }
    @Test fun unknownDurationAndInvalidMotionDoNotSeek() {
        assertEquals(5_000L, horizontalSeekTarget(5_000, 50f, 100f, -1))
        assertEquals(5_000L, horizontalSeekTarget(5_000, Float.NaN, 100f, 100_000))
        assertEquals(5_000L, horizontalSeekTarget(5_000, 50f, 0f, 100_000))
    }
}
