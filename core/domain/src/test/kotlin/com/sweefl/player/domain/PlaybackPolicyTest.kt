package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun rebuildingNearTheEndPreservesPausedPositionWhileHistoryRestarts() {
        assertEquals(39_686L, rebuildPosition(39_686, ended = false))
        assertEquals(0L, resumePosition(39_686, 40_000))
        assertEquals(0L, rebuildPosition(40_000, ended = true))
        assertEquals(0L, rebuildPosition(-1, ended = false))
    }

    @Test fun manifestAliasesAreCaseInsensitiveAndOtherTypesKeepAutoInference() {
        assertEquals("application/x-mpegURL", normalizeStreamMime("APPLICATION/X-MPEGURL"))
        assertEquals("application/x-mpegURL", normalizeStreamMime("application/vnd.apple.mpegurl"))
        assertEquals("application/dash+xml", normalizeStreamMime("application/dash+xml"))
        assertNull(normalizeStreamMime(null))
        assertNull(normalizeStreamMime("video/mp4"))
        assertNull(normalizeStreamMime("application/octet-stream"))
    }

    @Test fun networkDialogAcceptsStreamsAndRejectsUnsupportedInput() {
        assertNull(networkInputError(" https://example.org/live ", "Sweefl/1.0"))
        assertNotNull(networkInputError("content://provider/video/1", "Sweefl/1.0"))
        assertNotNull(networkInputError("https://user:secret@example.org/live", "Sweefl/1.0"))
        assertNotNull(networkInputError("https://example.org/live", "Agent\r\nInjected: value"))
    }

    @Test fun dialogLengthBoundsArePreserved() {
        assertNotNull(networkInputError("https://example.org/" + "x".repeat(8192), "Agent"))
        assertNotNull(networkInputError("https://example.org/live", "x".repeat(513)))
        assertNull(networkInputError("https://example.org/live", "x".repeat(512)))
    }
}
