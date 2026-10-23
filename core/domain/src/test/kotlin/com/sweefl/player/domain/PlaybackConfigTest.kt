package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class PlaybackConfigTest {
    @Test fun acceptsNetworkAndDocumentUris() {
        listOf("https://example.org/video.m3u8?token=abc", "http://127.0.0.1:8080/a.mp4", "content://media/external/video/media/12", "https://[::1]:443/a.mpd")
            .forEach { assertEquals(it, PlaybackConfig.validateMediaUri(it)) }
    }

    @Test fun rejectsUnsafeOrUnsupportedUris() {
        listOf("", "file:///tmp/a.mp4", "ftp://host/a", "javascript:alert(1)", "https://user:pass@example.org/a", "https://user%40host@example.org/a",
            "https://host/a\r\nX-Test: injected", "https://host/a%0d%0AX-Test", "https:///a", "https://host:99999/a", "content:opaque")
            .forEach { bad -> assertThrows("Should reject $bad", IllegalArgumentException::class.java) { PlaybackConfig.validateMediaUri(bad) } }
    }

    @Test fun validatesUserAgentWithoutHeaderInjection() {
        assertNull(PlaybackConfig.validateUserAgent(null))
        assertNull(PlaybackConfig.validateUserAgent(" "))
        assertEquals("Player/1.0", PlaybackConfig.validateUserAgent(" Player/1.0 "))
        listOf("Agent\r\nCookie: secret", "Agent\n", "Agent\r", "Agent\u0000", "Agent\u007f")
            .forEach { assertThrows(IllegalArgumentException::class.java) { PlaybackConfig.validateUserAgent(it) } }
    }

    @Test fun decodeModesAreExplicitAndSoftwareNamesConservative() {
        assertEquals("AUTO", PlaybackConfig.parseDecodeMode(null))
        assertEquals("SOFTWARE", PlaybackConfig.parseDecodeMode("software"))
        assertEquals("FFMPEG", PlaybackConfig.parseDecodeMode("ffmpeg"))
        assertThrows(IllegalArgumentException::class.java) { PlaybackConfig.parseDecodeMode("unknown") }
        assertTrue(PlaybackConfig.isLegacySoftwareCodec("OMX.google.h264.decoder"))
        assertTrue(PlaybackConfig.isLegacySoftwareCodec("c2.android.av1.decoder"))
        assertFalse(PlaybackConfig.isLegacySoftwareCodec("OMX.qcom.video.decoder.avc"))
        assertFalse(PlaybackConfig.isLegacySoftwareCodec("vendor.software.decoder"))
    }

    @Test fun recognizesOnlyRequestedSubtitleExtensions() {
        assertEquals("application/x-subrip", PlaybackConfig.subtitleMimeType("movie.SRT"))
        assertEquals("text/vtt", PlaybackConfig.subtitleMimeType("movie.vtt"))
        assertEquals("text/x-ssa", PlaybackConfig.subtitleMimeType("movie.ass"))
        assertEquals("text/x-ssa", PlaybackConfig.subtitleMimeType("movie.ssa"))
        assertNull(PlaybackConfig.subtitleMimeType("movie.txt"))
    }
}
