package com.sweefl.player.data.online

import org.junit.Assert.*
import org.junit.Test

class BilibiliCdnCandidatesTest {
    @Test fun prefersOfficialCandidateOverPeerHostAndHiddenOsMarkerWithoutRewriting() {
        val primary = "https://xy1x2x3x4xy.mcdn.bilivideo.cn/video.m4s?sign=a%2Bb&deadline=123"
        val hiddenPeer = "https://example.edge.example/video.m4s?os=mcdn&sign=keep%2Fexact"
        val official = "https://upos-sz-mirrorhwo1.bilivideo.com/video.m4s?sign=x%2By&deadline=123"
        val secondOfficial = "https://upos-sz-mirrorcos.bilivideo.com/video.m4s?sign=other"
        val original = listOf(primary, hiddenPeer, official, secondOfficial)
        val ordered = BilibiliCdnCandidates.ordered(original)
        assertEquals(listOf(official, secondOfficial, primary, hiddenPeer), ordered)
        assertEquals(original.toSet(), ordered.toSet())
        assertEquals(original.size, ordered.size)
    }

    @Test fun onlyPeerCandidatesRemainAvailableInApiOrder() {
        val original = listOf(
            "https://xy1.mcdn.bilivideo.cn/video?sign=original",
            "https://edge.example/video?os=mcdn&sign=backup"
        )
        assertEquals(original, BilibiliCdnCandidates.ordered(original))
    }

    @Test fun recognizesPeerQueryAndKnownHostsWithoutClassifyingEveryEdgeAsPeer() {
        listOf(
            "https://edge.example/video?os=%6dcdn",
            "https://a302.example/video",
            "https://edge.szbdyd.com/video",
            "https://edge.nexusedgeio.com/video",
            "https://edge.ahdohpiechei.com/video",
            "https://upos-sz-mirror14b.bilivideo.com/video"
        ).forEach { assertTrue(it, BilibiliCdnCandidates.isPeer(it)) }
        assertFalse(BilibiliCdnCandidates.isPeer("https://upos-sz-mirrorhwo1.bilivideo.com/video?sign=mcdn"))
        assertFalse(BilibiliCdnCandidates.isPeer("https://edge.example/video?os=upos"))
        assertFalse(BilibiliCdnCandidates.isPeer("https://upos.example/video?notes=os%3Dmcdn"))
    }
}
