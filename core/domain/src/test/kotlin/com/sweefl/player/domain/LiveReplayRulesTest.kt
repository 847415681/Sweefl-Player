package com.sweefl.player.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveReplayRulesTest {
    private val now = 1_800_000_000_000L

    @Test fun `declared server window bounds all seeks even if CDN retains older media`() {
        assertEquals(now - 3_600_000L, LiveReplayRules.clampTarget(now - 7_200_000L, now, now - 86_400_000L, 3600))
        assertEquals(now - 600_000L, LiveReplayRules.clampTarget(now - 1_800_000L, now, now - 600_000L, 3600))
        assertEquals(now, LiveReplayRules.clampTarget(now + 10_000L, now, 0L, 3600))
        assertEquals(now, LiveReplayRules.clampTarget(now - 10_000L, now, now + 30_000L, 3600))
    }

    @Test fun `return to live omits shift and replay shifts stay positive`() {
        assertNull(LiveReplayRules.delaySeconds(now, now))
        assertNull(LiveReplayRules.delaySeconds(now - 2_000L, now))
        assertEquals(1794L, LiveReplayRules.delaySeconds(now - 1_800_000L, now, 6_000L))
        assertEquals(1L, LiveReplayRules.delaySeconds(now - 4_000L, now, 8_000L))
    }

    @Test fun `resolver latency and resumed pause recompute delay from content time`() {
        val pausedContent = now - 1_800_000L
        assertEquals(1794L, LiveReplayRules.delaySeconds(pausedContent, now, 6_000L))
        assertEquals(2094L, LiveReplayRules.delaySeconds(pausedContent, now + 300_000L, 6_000L))
    }

    @Test fun `sliding HLS window does not advance paused content time`() {
        val before = LiveReplayRules.replayPosition(now, 0, 0, 5_000)
        val afterWindowSlides = LiveReplayRules.replayPosition(now, 0, 5_000, 0)
        assertEquals(now + 5_000L, before)
        assertEquals(before, afterWindowSlides)
        assertEquals(now + 7_000L, LiveReplayRules.replayPosition(now, 0, 6_000, 1_000))
    }
}
