package com.sweefl.player.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SelectedSourceCompositionTest {
    private data class Item(val uri: String, val subtitles: List<String> = emptyList(), val metadata: String = "")
    private data class Source(val items: List<Item>)

    @Test fun retainsCallerIdentityAndCreatesIndependentAudioOnEachCall() {
        val seen = mutableListOf<Item>()
        val audios = mutableListOf<Item>()
        fun create(caller: Item) = composeSelectedSources(
            caller, { it.uri == "video" },
            { seen += it; Source(listOf(it)) },
            { Item("audio").also(audios::add) },
            { Source(listOf(it)) },
            { video, audio -> Source(video.items + audio.items) },
        )
        val first = Item("video", listOf("subtitle-one"), "caller metadata")
        val second = Item("video", listOf("subtitle-two"), "updated metadata")
        val result = create(first)
        val later = create(second)
        assertSame(first, seen[0]); assertSame(first, result.items[0])
        assertSame(second, seen[1]); assertSame(second, later.items[0])
        assertEquals(listOf(Item("audio")), result.items.drop(1))
        assertEquals(listOf(Item("audio")), later.items.drop(1))
        org.junit.Assert.assertNotSame(audios[0], audios[1])
        val unrelated = Item("other", listOf("other-subtitle"))
        assertSame(unrelated, create(unrelated).items.single())
        assertEquals(2, audios.size)
    }

    @Test fun noAudioKeepsPrimarySourceIdentity() {
        val item = Item("video")
        val primary = Source(listOf(item))
        val result = composeSelectedSources<Item, Source>(item, { true }, { primary }, null,
            { error("No audio expected") }, { _, _ -> error("No merge expected") })
        assertSame(primary, result)
    }

    @Test fun publicCdnHeadersExcludeCookiesAndOtherCredentials() {
        assertEquals(mapOf("User-Agent" to "agent", "Referer" to "https://page.example"),
            playbackRequestHeaders(mapOf("uSeR-aGeNt" to "agent", "referer" to "https://page.example",
                "cOoKiE" to "secret", "Authorization" to "secret", "X-Token" to "secret")))
    }
}
