package com.sweefl.player.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsDvrUriPolicyTest {
    private val root = "https://cdn.example/live/playlist.m3u8?sig=a%2Bb&tmshift=90#live"
    private val policy = HlsDvrUriPolicy(root)

    @Test fun appendsOnlyToExactOriginDirectoryAndSupportedResources() {
        listOf("part.m4s", "init.mp4", "level.m3u8").forEach {
            assertEquals("https://cdn.example/live/$it?tmshift=90", policy.rewrite("https://cdn.example/live/$it"))
        }
        listOf("https://other.example/live/a.m4s", "http://cdn.example/live/a.m4s",
            "https://cdn.example:444/live/a.m4s", "https://cdn.example/live/sub/a.m4s",
            "https://cdn.example/other/a.m4s", "https://cdn.example/live/a.ts",
            "https://cdn.example/live/key.bin", "https://cdn.example/live/../a.m4s",
            "https://cdn.example/live/%2e%2e/a.m4s", "https://cdn.example/live/a%2fb.m4s",
            "https://user@cdn.example/live/a.m4s", "not a URI").forEach {
            assertEquals(it, policy.rewrite(it))
        }
        assertEquals("https://CDN.EXAMPLE:443/live/a.mp4?tmshift=90", policy.rewrite("https://CDN.EXAMPLE:443/live/a.mp4"))
    }

    @Test fun retainsSignedQueriesFragmentsAndExistingShiftIncludingZero() {
        val signed = "https://cdn.example/live/a.m4s?token=a%2Fb+%3D&empty=&token=x#part%20one"
        assertEquals("https://cdn.example/live/a.m4s?token=a%2Fb+%3D&empty=&token=x&tmshift=90#part%20one", policy.rewrite(signed))
        listOf("tmshift=0", "tmshift=-5", "tmshift=", "tmshift", "tm%73hift=0", "tmshift=12&tmshift=0").forEach {
            val url = "https://cdn.example/live/a.mp4?sig=%2f&$it#fragment"
            assertEquals(url, policy.rewrite(url))
        }
        assertEquals(root, policy.rewrite(root))
        assertEquals("https://cdn.example/live/a.mp4?tmshift=90#f", policy.rewrite("https://cdn.example/live/a.mp4?#f"))
    }

    @Test fun requiresPositiveRootShift() {
        listOf(null, "https://cdn.example/live/list.m3u8", "https://cdn.example/live/list.m3u8?tmshift=0",
            "https://cdn.example/live/list.m3u8?tmshift=-1", "https://cdn.example/live/list.m3u8?tmshift=no",
            "https://cdn.example/live/list.m3u8?tmshift=+90", "https://cdn.example/live/list.m3u8?tmshift=%2090",
            "https://cdn.example/live/list.m3u8?tmshift=999999999999999999999", "https://cdn.example/live/movie.mp4?tmshift=90").forEach {
            val url = "https://cdn.example/live/a.m4s"
            assertEquals(url, HlsDvrUriPolicy(it).rewrite(url))
        }
    }

    @Test fun learnsSuccessfulPlaylistRedirectButNotForeignOrSegmentRedirects() {
        val redirected = "https://edge.example/room/list.m3u8?signature=a%2Fb"
        val chunk = "https://edge.example/room/a.m4s?sig=%2f#f"
        policy.onSuccessfulOpen("https://foreign.example/live/list.m3u8", redirected)
        assertEquals(chunk, policy.rewrite(chunk))
        policy.onSuccessfulOpen("https://cdn.example/live/a.m4s", redirected)
        assertEquals(chunk, policy.rewrite(chunk))
        policy.onSuccessfulOpen(root, null)
        assertEquals(chunk, policy.rewrite(chunk))
        policy.onSuccessfulOpen(root, redirected)
        assertEquals("https://edge.example/room/a.m4s?sig=%2f&tmshift=90#f", policy.rewrite(chunk))
        val unrelated = "https://edge.example/other/a.m4s"
        assertEquals(unrelated, policy.rewrite(unrelated))
        // The original CDN remains scoped, and an in-scope child playlist can redirect again.
        policy.onSuccessfulOpen("https://edge.example/room/child.m3u8", "https://edge2.example/room/signed-playlist?token=%2f")
        assertEquals("https://edge2.example/room/init.mp4?tmshift=90", policy.rewrite("https://edge2.example/room/init.mp4"))
    }
}
