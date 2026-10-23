package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class SubmittedFrameTrackerTest {
    @Test fun unknownAndFutureSubmissionsCannotBeCaptured() {
        val frames = SubmittedFrameTracker()
        assertEquals(-1, frames.tokenAt(Long.MAX_VALUE))
        val epoch = frames.resetRenderer()
        frames.submitted(epoch, 100)
        assertEquals(-1, frames.tokenAt(99))
        assertTrue(frames.tokenAt(100) >= 0)
    }
    @Test fun synchronousSeekRejectsLateOldRendererOutput() {
        val frames = SubmittedFrameTracker()
        val old = frames.resetRenderer()
        frames.submitted(old, 1)
        assertTrue(frames.tokenAt(1) >= 0)
        frames.invalidate()
        frames.submitted(old, 2)
        assertEquals(-1, frames.tokenAt(Long.MAX_VALUE))
        val next = frames.resetRenderer()
        frames.submitted(old, 3)
        frames.submitted(next, 4)
        assertEquals(-1, frames.tokenAt(3))
        assertTrue(frames.tokenAt(4) >= 0)
    }
    @Test fun lonePausedOutputRemainsAvailableWithoutAnotherMetadataEvent() {
        val frames = SubmittedFrameTracker()
        val epoch = frames.resetRenderer()
        frames.submitted(epoch, 10)
        val token = frames.tokenAt(11)
        assertTrue(token >= 0)
        repeat(100) { assertEquals(token, frames.tokenAt(1000)) }
    }
    @Test fun sessionsAndEveryResetHaveDifferentRevision() {
        val first = SubmittedFrameTracker()
        val second = SubmittedFrameTracker()
        assertNotEquals(first.revision(), second.revision())
        val epoch = first.resetRenderer()
        first.invalidate()
        assertNotEquals(epoch, first.revision())
        assertNotEquals(epoch, first.resetRenderer())
    }
    @Test fun rapidSeeksCannotArmEarlierQueuedReset() {
        val frames = SubmittedFrameTracker()
        val first = frames.requestSeek()
        val second = frames.requestSeek()
        frames.armSeek(first)
        val obsolete = frames.resetRenderer()
        frames.submitted(obsolete, 1)
        assertEquals(-1, frames.tokenAt(1))
        frames.armSeek(second)
        val current = frames.resetRenderer()
        frames.submitted(current, 2)
        assertTrue(frames.tokenAt(2) >= 0)
    }
    @Test fun acknowledgedNoOpSeekRestoresPausedOutputAndFutureSubmissions() {
        val frames = SubmittedFrameTracker()
        val epoch = frames.resetRenderer()
        frames.submitted(epoch, 1)
        val token = frames.tokenAt(1)
        val request = frames.requestSeek()
        assertEquals(-1, frames.tokenAt(2))
        frames.armSeek(request)
        frames.completeSeek(request)
        assertEquals(token, frames.tokenAt(2))
        frames.submitted(epoch, 3)
        assertTrue(frames.tokenAt(3) > token)
    }
    @Test fun acknowledgementNeverRestoresOutputAfterActualReset() {
        val frames = SubmittedFrameTracker()
        val epoch = frames.resetRenderer()
        frames.submitted(epoch, 1)
        val request = frames.requestSeek()
        frames.armSeek(request)
        frames.resetRenderer()
        frames.completeSeek(request)
        assertEquals(-1, frames.tokenAt(100))
    }
    @Test fun closeRejectsCallbacksAndResetCannotReopen() {
        val frames = SubmittedFrameTracker()
        val old = frames.resetRenderer()
        frames.close()
        frames.submitted(old, 1)
        frames.submitted(frames.resetRenderer(), 2)
        assertEquals(-1, frames.tokenAt(Long.MAX_VALUE))
    }
    @Test fun outOfOrderReleaseDoesNotRegressToken() {
        val frames = SubmittedFrameTracker()
        val epoch = frames.resetRenderer()
        frames.submitted(epoch, 100)
        frames.submitted(epoch, 50)
        val newer = frames.tokenAt(50)
        assertEquals(newer, frames.tokenAt(100))
    }
}
