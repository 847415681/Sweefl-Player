package com.sweefl.player.data.online

import com.sweefl.player.core.model.OnlineVideo
import com.sweefl.player.core.model.ResolvedOnlineMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class BilibiliMediaResolverTest {
    private val bv = "BV1xx411c7mD"
    private val page get() = "https://www.bilibili.com/video/$bv"
    private val nav = """{"code":-101,"data":{"wbi_img":{"img_url":"https://i.example/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i.example/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
    private val view get() = """{"code":0,"data":{"bvid":"$bv","title":"Example","pages":[{"cid":10,"page":1,"part":"One"},{"cid":20,"page":2,"part":"Two"}],"ugc_season":{"sections":[{"episodes":[{"bvid":"BV1yy411c7mD","title":"Next"}]}]}}}"""
    private val dash = """{"code":0,"data":{"quality":64,"accept_quality":[120,80,64],"accept_description":["4K","1080P","720P"],"dash":{"video":[{"id":64,"baseUrl":"https://cdn.example/video.m4s?sign=secret","codecs":"avc1.64001f","mimeType":"video/mp4","width":1280,"height":720}],"audio":[{"id":30280,"base_url":"https://cdn.example/audio.m4s","codecs":"mp4a.40.2"}]}}}"""
    private class Fake(val reply: (String, Map<String, String>) -> OnlineHttpResponse) : OnlineHttpTransport {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun get(url: String, headers: Map<String, String>): OnlineHttpResponse {
            calls += url to headers
            return reply(url, headers)
        }
    }
    private fun vodFake(play: String = dash): Fake = Fake { url, _ -> OnlineHttpResponse(200, when (URI(url).path) {
        "/x/web-interface/view" -> view
        "/x/web-interface/nav" -> nav
        "/x/player/wbi/playurl" -> play
        else -> error("Unexpected fixture request")
    }) }
    private fun resolver(fake: Fake) = BilibiliMediaResolver(fake, OnlineCookieProvider { "SESSDATA=test-only" }, { 1702204169000 })

    @Test fun supportsOnlyRecognizedPageHostsAndPaths() {
        val resolver = resolver(vodFake())
        listOf(page, "av123", "ep12", "ss13", "https://b23.tv/abc", "https://live.bilibili.com/blanc/6").forEach { assertTrue(it, resolver.supports(it)) }
        listOf("https://bilibili.com.evil/video/$bv", "https://evil@www.bilibili.com/video/$bv", "https://www.bilibili.com:444/video/$bv", "$page?p=0", "https://www.bilibili.com/").forEach { assertFalse(it, resolver.supports(it)) }
    }
    @Test fun dashUsesActualQualityAndSelectedPageAndNeverReturnsCookies() = runTest {
        val fake = vodFake()
        val result = resolver(fake).resolve("$page?p=2", 120)
        assertEquals(64, result.video.qualityId)
        assertEquals("720P", result.video.label)
        assertEquals(listOf(64), result.qualities.map { it.id })
        assertTrue(result.video.separateAudio)
        assertNotNull(result.audio)
        assertNotNull(result.notice)
        assertEquals(3, result.episodes.size)
        assertEquals("$page?p=2", result.pageUrl)
        assertTrue(fake.calls.last().first.contains("cid=20"))
        assertTrue(fake.calls.last().first.contains("w_rid="))
        assertTrue(fake.calls.all { it.second["Cookie"] == "SESSDATA=test-only" })
        assertFalse(result.requestHeaders.keys.any { it.equals("Cookie", true) })
        assertFalse(fake.calls.any { URI(it.first).host == "cdn.example" })
    }
    @Test fun shortLinkDoesNotReceiveCookieAndRejectsExternalRedirect() = runTest {
        val fake = Fake { _, headers ->
            assertFalse(headers.containsKey("Cookie"))
            OnlineHttpResponse(302, "", mapOf("location" to "https://evil.example/video/$bv"))
        }
        assertFailure(BilibiliResolveException.Reason.INVALID_URL) { resolver(fake).resolve("https://b23.tv/abc") }
        assertEquals(1, fake.calls.size)
    }
    @Test fun shortLinkResolvesValidPageWithoutCookieOnRedirectRequest() = runTest {
        val inner = vodFake()
        val fake = Fake { url, headers -> if (URI(url).host == "b23.tv") {
            assertFalse(headers.containsKey("Cookie")); OnlineHttpResponse(302, "", mapOf("Location" to "$page?p=2"))
        } else inner.reply(url, headers) }
        assertEquals("$page?p=2", resolver(fake).resolve("https://b23.tv/abc").pageUrl)
    }
    @Test fun multipleProgressiveSegmentsAreNeverSilentlyTruncated() = runTest {
        val fake = vodFake("""{"code":0,"data":{"quality":32,"durl":[{"url":"https://cdn.example/a.mp4"},{"url":"https://cdn.example/b.mp4"}]}}}""")
        assertFailure(BilibiliResolveException.Reason.UNSUPPORTED_SEGMENTS) { resolver(fake).resolve(page) }
    }
    @Test fun singleProgressiveStreamUsesReturnedQuality() = runTest {
        val fake = vodFake("""{"code":0,"data":{"quality":32,"accept_quality":[80,32],"accept_description":["1080P","480P"],"durl":[{"url":"https://cdn.example/a.mp4"}]}}}""")
        val result = resolver(fake).resolve(page, 80)
        assertEquals(32, result.video.qualityId)
        assertEquals("480P", result.video.label)
        assertFalse(result.video.separateAudio)
    }
    @Test fun permissionAndLoginCodesRemainDistinct() = runTest {
        for ((code, reason) in listOf(-101 to BilibiliResolveException.Reason.LOGIN_REQUIRED, -403 to BilibiliResolveException.Reason.ENTITLEMENT)) {
            val fake = Fake { _, _ -> OnlineHttpResponse(200, """{"code":$code,"message":"secret must not leak"}""") }
            val error = assertFailure(reason) { resolver(fake).resolve(page) }
            assertEquals(code, error.apiCode)
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }
    @Test fun previewDoesNotClaimFullEntitlement() = runTest {
        assertFailure(BilibiliResolveException.Reason.ENTITLEMENT) { resolver(vodFake("""{"code":0,"data":{"is_preview":1}}""")).resolve(page) }
    }
    @Test fun cancellationIsNotConvertedToNetworkError() = runTest {
        val resolver = BilibiliMediaResolver(OnlineHttpTransport { _, _ -> throw CancellationException("cancel") }, OnlineCookieProvider { null })
        try { resolver.resolve(page); fail("Expected cancellation") } catch (_: CancellationException) { }
    }
    @Test fun wbiMatchesIndependentlyVerifiedDeterministicVector() {
        val key = BilibiliWbi.mixinKey("https://i.example/7cd084941338484aae1ad9425b84077c.png", "https://i.example/4932caff0ff746eab6f01bf08b70ac45.png")
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
        // Independently verified with .NET MD5 over UTF-8 bytes of this literal:
        // bar=514&baz=1919810&foo=114&wts=1702204169ea1db124af3c7062474693fa704f4ff8
        assertEquals("bar=514&baz=1919810&foo=114&wts=1702204169&w_rid=6149fdadf571698ca7e6a567265cd0ee", BilibiliWbi.sign(mapOf("foo" to "114", "bar" to "514", "baz" to "1919810"), key, 1702204169))
        assertTrue(BilibiliWbi.sign(mapOf("text" to "a b!'()*"), key, 1).startsWith("text=a%20b&wts=1&"))
    }
    @Test fun returnedModelRejectsCookieCaseInsensitively() {
        try {
            ResolvedOnlineMedia(page, "title", emptyList(), listOf(OnlineVideo("v", 1, "q", "https://cdn.example/a", "video/mp4")), selectedVideoId = "v", requestHeaders = mapOf("cOoKiE" to "secret"))
            fail("Expected Cookie rejection")
        } catch (_: IllegalArgumentException) { }
    }
    private fun liveFake(format: String = "fmp4", flag: Int = 19, status: String = "ENABLE", start: Long = 0, liveStatus: Int = 1): Fake = Fake { url, _ ->
        val body = when (URI(url).path) {
            "/room/v1/Room/room_init" -> """{"code":0,"data":{"room_id":7734200,"live_status":$liveStatus,"live_time":1702190000}}"""
            "/xlive/web-room/v2/index/getRoomPlayInfo" -> """{"code":0,"data":{"all_special_types":[$flag],"playurl_info":{"playurl":{"g_qn_desc":[{"qn":150,"desc":"480P 高清"},{"qn":10000,"desc":"1080P 原画"}],"stream":[{"protocol_name":"http_hls","format":[{"format_name":"$format","codec":[{"codec_name":"avc","current_qn":150,"accept_qn":[150,10000],"base_url":"/live.m3u8","url_info":[{"host":"https://cdn.example","extra":"?sign=keep&qn=150"}]}]}]}]}}}}"""
            "/xlive/web-room/v1/index/getProgressBarInfo" -> {
                assertTrue(url.contains("roomid=7734200"))
                assertTrue(url.contains("platform=web"))
                """{"code":0,"data":{"bar_status":"$status","bar_special_time":1800,"bar_start_time":$start}}"""
            }
            else -> error("Unexpected live request")
        }
        OnlineHttpResponse(200, body, mapOf("Date" to "Sun, 10 Dec 2023 09:09:34 GMT"))
    }
    @Test fun liveUsesCanonicalRoomActualQualityAndDvrGates() = runTest {
        val fake = liveFake()
        val result = resolver(fake).resolve("https://live.bilibili.com/6", 10000)
        assertEquals("7734200", result.live?.roomId)
        assertEquals(3600, result.live?.dvrWindowSeconds)
        assertEquals(1702190000000, result.live?.startedAtMs)
        assertEquals(150, result.video.qualityId)
        assertEquals(listOf(150), result.qualities.map { it.id })
        assertEquals("480P 高清", result.video.label)
        assertEquals("https://cdn.example/live.m3u8?sign=keep&qn=150", result.video.url)
        assertFalse(result.video.url.contains("tmshift"))
        assertNotEquals(0L, result.live?.serverClockOffsetMs)
    }
    @Test fun tsMissingFlagDisabledAndFutureStartAreLiveOnly() = runTest {
        for (fake in listOf(liveFake(format = "ts"), liveFake(flag = 50), liveFake(status = "DISABLE"), liveFake(start = 1800000000))) {
            assertEquals(0, resolver(fake).resolve("https://live.bilibili.com/6").live?.dvrWindowSeconds)
        }
    }
    @Test fun offlineIsExplicit() = runTest {
        assertFailure(BilibiliResolveException.Reason.OFFLINE) { resolver(liveFake(liveStatus = 0)).resolve("https://live.bilibili.com/6") }
    }
    @Test fun pgcSelectsRequestedEpisode() = runTest {
        val fake = Fake { url, _ -> OnlineHttpResponse(200, if (URI(url).path == "/pgc/view/web/season")
            """{"code":0,"result":{"title":"Series","episodes":[{"id":10,"cid":100,"title":"1"},{"id":20,"cid":200,"title":"2"}]}}"""
        else { assertTrue(url.contains("ep_id=20")); dash.replace("\"data\"", "\"result\"") }) }
        val result = resolver(fake).resolve("https://www.bilibili.com/bangumi/play/ep20")
        assertEquals(2, result.episodes.size)
        assertEquals("https://www.bilibili.com/bangumi/play/ep20", result.pageUrl)
    }
    @Test fun vodHighLowHighKeepsFullActualDashCatalogForVideoAndPgc() = runTest {
        for (pgc in listOf(false, true)) {
            val fake = Fake { url, _ ->
                val path = URI(url).path
                val body = when (path) {
                    "/x/web-interface/view" -> view
                    "/x/web-interface/nav" -> nav
                    "/pgc/view/web/season" -> """{"code":0,"result":{"title":"Series","episodes":[{"id":20,"cid":200,"title":"2"}]}}"""
                    else -> {
                        val root = org.json.JSONObject(dash)
                        // This API deliberately hides high tiers when requested qn is low.
                        if (URI(url).rawQuery.split('&').contains("qn=127")) {
                            root.getJSONObject("data").getJSONObject("dash").getJSONArray("video").put(
                                org.json.JSONObject("""{"id":80,"baseUrl":"https://cdn.example/high.m4s","codecs":"avc1.640028"}"""))
                        }
                        root.toString()
                    }
                }
                OnlineHttpResponse(200, body)
            }
            val source = if (pgc) "https://www.bilibili.com/bangumi/play/ep20" else page
            val resolver = resolver(fake)
            for (quality in listOf(80, 64, 80)) {
                val result = resolver.resolve(source, quality)
                assertEquals(quality, result.video.qualityId)
                assertEquals(setOf(64, 80), result.qualities.map { it.id }.toSet())
                assertFalse(result.qualities.any { it.id == 120 })
            }
        }
    }
    @Test fun liveFirstOpenDiscoversLowTierAndHighLowHighKeepsVerifiedHigh() = runTest {
        val base = liveFake()
        val fake = Fake { url, headers ->
            val response = base.reply(url, headers)
            if (URI(url).path != "/xlive/web-room/v2/index/getRoomPlayInfo") response else {
                val requested = URI(url).rawQuery.split('&').first { it.startsWith("qn=") }.substringAfter('=').toInt()
                val actual = if (requested == 20000) 10000 else requested
                val root = org.json.JSONObject(response.body)
                val play = root.getJSONObject("data").getJSONObject("playurl_info").getJSONObject("playurl")
                play.getJSONArray("g_qn_desc").put(org.json.JSONObject("""{"qn":80,"desc":"流畅"}"""))
                val codec = play.getJSONArray("stream").getJSONObject(0).getJSONArray("format").getJSONObject(0).getJSONArray("codec").getJSONObject(0)
                codec.put("current_qn", actual)
                codec.getJSONArray("url_info").getJSONObject(0).put("extra", "?sign=keep&qn=$actual")
                response.copy(body = root.toString())
            }
        }
        val resolver = resolver(fake)
        val source = "https://live.bilibili.com/6"
        val first = resolver.resolve(source)
        assertEquals(setOf(80, 150, 10000), first.qualities.map { it.id }.toSet())
        assertEquals(10000, first.video.qualityId)
        for (quality in listOf(10000, 80, 10000)) {
            val result = resolver.resolve(source, quality)
            assertEquals(quality, result.video.qualityId)
            assertEquals(setOf(80, 150, 10000), result.qualities.map { it.id }.toSet())
            assertTrue(result.video.url.endsWith("qn=$quality"))
            assertFalse(result.qualities.any { it.id == 20000 })
        }
    }
    @Test fun liveOptionalLowTierFailureDoesNotDiscardHighStream() = runTest {
        val base = liveFake()
        val fake = Fake { url, headers ->
            if (URI(url).path.endsWith("getRoomPlayInfo") && URI(url).rawQuery.split('&').contains("qn=80"))
                OnlineHttpResponse(403, "") else base.reply(url, headers)
        }
        val result = resolver(fake).resolve("https://live.bilibili.com/6")
        assertEquals(listOf(150), result.qualities.map { it.id })
    }
    @Test fun vodUsesDesktopOriginHeadersWhileCanonicalPageRemainsSpecific() = runTest {
        val base = vodFake()
        val fake = Fake { url, headers ->
            assertEquals("api.bilibili.com", URI(url).host)
            assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36", headers["User-Agent"])
            assertEquals("application/json, text/html;q=0.9", headers["Accept"])
            // Reproduce the observed API rejection instead of merely mirroring a helper.
            if (headers["Referer"] != "https://www.bilibili.com/") OnlineHttpResponse(412, "{\"code\":-412}")
            else base.reply(url, headers)
        }
        val result = resolver(fake).resolve("$page?p=2")
        assertEquals(listOf("/x/web-interface/view", "/x/web-interface/nav", "/x/player/wbi/playurl"), fake.calls.map { URI(it.first).path })
        assertEquals("$page?p=2", result.pageUrl)
        assertEquals("https://www.bilibili.com/", result.requestHeaders["Referer"])
        assertEquals(BilibiliMediaResolver.VOD_USER_AGENT, result.userAgent)
        assertEquals("$page?p=1", result.episodes.first().pageUrl)
        assertEquals("$page?p=2", result.episodes[1].pageUrl)
        assertEquals("https://cdn.example/video.m4s?sign=secret", result.video.url)
        assertFalse(result.requestHeaders.keys.any { it.equals("Cookie", true) })
    }
    @Test fun pgcApiUsesOriginRefererWithoutChangingEpisodeIdentity() = runTest {
        val source = "https://www.bilibili.com/bangumi/play/ep20"
        val fake = Fake { url, headers ->
            assertEquals("https://www.bilibili.com/", headers["Referer"])
            assertEquals(BilibiliMediaResolver.VOD_USER_AGENT, headers["User-Agent"])
            OnlineHttpResponse(200, if (URI(url).path == "/pgc/view/web/season")
                """{"code":0,"result":{"title":"Series","episodes":[{"id":20,"cid":200,"title":"2"}]}}"""
            else dash)
        }
        val result = resolver(fake).resolve(source)
        assertEquals(source, result.pageUrl)
        assertEquals(source, result.episodes.single().pageUrl)
        assertEquals("https://www.bilibili.com/", result.requestHeaders["Referer"])
        assertEquals(BilibiliMediaResolver.VOD_USER_AGENT, result.userAgent)
    }
    @Test fun liveApiAndPlaybackReferersRemainRoomSpecific() = runTest {
        val fake = liveFake()
        val result = resolver(fake).resolve("https://live.bilibili.com/6")
        assertEquals("https://live.bilibili.com/6", fake.calls.first().second["Referer"])
        assertTrue(fake.calls.drop(1).all { it.second["Referer"] == "https://live.bilibili.com/7734200" })
        assertTrue(fake.calls.all { URI(it.first).host == "api.live.bilibili.com" })
        assertEquals("https://live.bilibili.com/7734200", result.pageUrl)
        assertEquals(result.pageUrl, result.requestHeaders["Referer"])
        val mobile = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        assertEquals(mobile, result.userAgent)
        assertTrue(fake.calls.all { it.second["User-Agent"] == mobile })
        assertNotEquals(BilibiliMediaResolver.VOD_USER_AGENT, result.userAgent)
    }
    @Test fun dashVideoAndAudioPreferSuppliedNonPeerCandidatesAndKeepAllAlternatives() = runTest {
        val videoPrimary = "https://xy1.mcdn.bilivideo.cn/v?sign=primary"
        val videoHidden = "https://edge.example/v?os=mcdn&sign=hidden"
        val videoOfficial = "https://upos-sz-mirrorhwo1.bilivideo.com/v?sign=exact%2Bvalue"
        val audioPrimary = "https://edge.example/a?os=mcdn&sign=audio"
        val audioOfficial = "https://upos-sz-mirrorcos.bilivideo.com/a?sign=exact%2Fvalue"
        val root = org.json.JSONObject(dash)
        val streams = root.getJSONObject("data").getJSONObject("dash")
        streams.getJSONArray("video").getJSONObject(0).put("baseUrl", videoPrimary)
            .put("backup_url", org.json.JSONArray(listOf(videoHidden, videoOfficial)))
        // API variants sometimes supply a single backup string rather than an array.
        streams.getJSONArray("audio").getJSONObject(0).put("base_url", audioPrimary)
            .put("backupUrl", audioOfficial).put("bandwidth", 128000)
        val result = resolver(vodFake(root.toString())).resolve(page)
        assertEquals(videoOfficial, result.video.url)
        assertEquals(listOf(videoPrimary, videoHidden), result.video.backupUrls)
        assertEquals(setOf(videoPrimary, videoHidden, videoOfficial), (result.video.backupUrls + result.video.url).toSet())
        assertEquals(audioOfficial, result.audio?.url)
        assertEquals("AAC · 128 kbps", result.audio?.label)
        assertEquals("a30280_0", result.selectedAudioId)
        assertEquals(listOf(audioPrimary), result.audio?.backupUrls)
        assertEquals(setOf(audioPrimary, audioOfficial), (result.audio!!.backupUrls + result.audio!!.url).toSet())
    }
    private suspend fun assertFailure(reason: BilibiliResolveException.Reason, block: suspend () -> Unit): BilibiliResolveException {
        try { block() } catch (error: BilibiliResolveException) { assertEquals(reason, error.reason); return error }
        throw AssertionError("Expected $reason")
    }
}
