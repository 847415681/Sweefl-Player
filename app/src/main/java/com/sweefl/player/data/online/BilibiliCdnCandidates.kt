package com.sweefl.player.data.online

import java.net.URI
import java.net.URLDecoder

/** Reorders only URLs supplied by the API; signed hosts, paths and queries are never rewritten. */
internal object BilibiliCdnCandidates {
    fun ordered(urls: List<String>): List<String> = urls.distinct().sortedBy { isPeer(it) }

    fun isPeer(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        val peerMarker = uri.rawQuery.orEmpty().split('&').any { entry ->
            val pair = entry.split('=', limit = 2)
            pair.size == 2 && URLDecoder.decode(pair[0], "UTF-8") == "os" &&
                URLDecoder.decode(pair[1], "UTF-8") == "mcdn"
        }
        peerMarker || "302" in host.substringBefore('.') ||
            listOf("302ppio", "302kodo", ".mcdn.bilivideo", "szbdyd.com", ".nexusedgeio.com",
                ".ahdohpiechei.com", "upos-sz-mirror14b.bilivideo.com").any { it in host }
    }.getOrDefault(false)
}
