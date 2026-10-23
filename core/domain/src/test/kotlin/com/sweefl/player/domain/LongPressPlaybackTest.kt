package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class LongPressPlaybackTest {
    @Test fun invalidSpeedsFallBackAndFiniteValuesAreBounded() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f, 1f)
            .forEach { assertEquals(2f, normalizeLongPressSpeed(it), 0f) }
        assertEquals(3f, normalizeLongPressSpeed(Float.MAX_VALUE), 0f)
        assertEquals(1.25f, normalizeLongPressSpeed(1.1f), 0f)
        LONG_PRESS_SPEED_CHOICES.forEach { assertEquals(it, normalizeLongPressSpeed(it), 0f) }
    }

    @Test fun dragClaimPreventsLongPress() {
        val claim = PressGestureClaim()
        claim.begin()
        assertTrue(claim.claimDrag())
        assertFalse(claim.claimHold(playing = true, locked = false))
    }

    @Test fun longPressPreventsDragEvenAfterReleaseUntilNextDown() {
        val claim = PressGestureClaim()
        claim.begin()
        assertTrue(claim.claimHold(playing = true, locked = false))
        assertFalse(claim.claimDrag())
        claim.finishHold()
        assertFalse(claim.claimDrag())
        assertFalse(claim.claimHold(playing = true, locked = false))
        claim.begin()
        assertTrue(claim.claimDrag())
    }

    @Test fun pausedOrLockedCannotClaimLongPress() {
        val claim = PressGestureClaim()
        claim.begin()
        assertFalse(claim.claimHold(playing = false, locked = false))
        assertFalse(claim.claimHold(playing = true, locked = true))
        assertTrue(claim.claimHold(playing = true, locked = false))
    }

    @Test fun overrideCapturesOriginalOnlyOnceAndRestoresExactlyOnce() {
        val session = TemporaryPlaybackSpeed()
        assertEquals(2f, session.begin(1.75f, 2f, playing = true, locked = false)!!, 0f)
        assertTrue(session.active)
        assertNull(session.begin(2f, 3f, playing = true, locked = false))
        assertEquals(1.75f, session.end()!!, 0f)
        assertFalse(session.active)
        assertNull(session.end())
    }

    @Test fun cancellationWithoutAnOverrideLeavesNormalSpeedUntouched() {
        val session = TemporaryPlaybackSpeed()
        assertNull(session.end())
        assertNull(session.begin(.75f, 2f, playing = false, locked = false))
        assertNull(session.begin(.75f, 2f, playing = true, locked = true))
        assertNull(session.begin(Float.NaN, 2f, playing = true, locked = false))
        assertNull(session.end())
    }

    @Test fun subsequentHoldsCaptureNewNormalSpeedRatherThanPreviousOverride() {
        val session = TemporaryPlaybackSpeed()
        session.begin(.75f, 3f, playing = true, locked = false)
        assertEquals(.75f, session.end()!!, 0f)
        session.begin(2.5f, Float.POSITIVE_INFINITY, playing = true, locked = false)
        assertEquals(2.5f, session.end()!!, 0f)
    }

    @Test fun existingNormalSpeedAboveLimitIsNotClampedWhenRestoring() {
        val session = TemporaryPlaybackSpeed()
        assertEquals(3f, session.begin(4f, 99f, playing = true, locked = false)!!, 0f)
        assertEquals(4f, session.end()!!, 0f)
    }

    @Test fun defaultGesturesResetLongPressButPreservePreviewChoice() {
        val custom = PlaybackInteractionSettings(showSeekPreview = false,
            leftDoubleTap = DoubleTapAction.NONE, centerDoubleTap = DoubleTapAction.LOCK,
            rightDoubleTap = DoubleTapAction.BACK_30, longPressSpeed = 3f)
        val reset = custom.withDefaultGestures()
        assertFalse(reset.showSeekPreview)
        assertEquals(2f, reset.longPressSpeed, 0f)
        assertEquals(DoubleTapAction.BACK_10, reset.leftDoubleTap)
        assertEquals(DoubleTapAction.PLAY_PAUSE, reset.centerDoubleTap)
        assertEquals(DoubleTapAction.FORWARD_10, reset.rightDoubleTap)
    }
}
