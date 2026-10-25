package com.sweefl.player.data.online

import com.sweefl.player.core.model.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class BilibiliResolveException(val reason: Reason, message: String, val apiCode: Int? = null) : Exception(message) {
    enum class Reason { INVALID_URL, NETWORK, LOGIN_REQUIRED, ENTITLEMENT, OFFLINE, UNAVAILABLE, INVALID_RESPONSE, UNSUPPORTED_SEGMENTS }
}

class BilibiliMediaResolver(
    private val transport: OnlineHttpTransport = UrlConnectionOnlineHttpTransport(),
    private val cookieProvider: OnlineCookieProvider = WebViewOnlineCookieProvider(),
    private val clockMs: () -> Long = System::currentTimeMillis
) : OnlineMediaResolver {
    override fun supports(pageUrl: String): Boolean = runCatching { parse(pageUrl); true }.getOrDefault(false)

    override suspend fun resolve(pageUrl: String, qualityId: Int?): ResolvedOnlineMedia {
        try {
            var target = parse(pageUrl)
            var hops = 0
            while (target.kind == Kind.SHORT) {
                if (++hops > 5) fail(BilibiliResolveException.Reason.INVALID_URL, "短链接重定向过多")
                val response = request(target.url, "https://www.bilibili.com/")
                if (response.status !in 300..399) fail(BilibiliResolveException.Reason.INVALID_URL, "短链接未返回有效跳转")
                val location = response.header("Location") ?: fail(BilibiliResolveException.Reason.INVALID_RESPONSE, "短链接缺少跳转地址")
                target = parse(URI(target.url).resolve(location).toString())
            }
            return when (target.kind) {
                Kind.VIDEO -> resolveVideo(target, qualityId)
                Kind.EPISODE, Kind.SEASON -> resolvePgc(target, qualityId)
                Kind.LIVE -> resolveLive(target, qualityId)
                Kind.SHORT -> error("Unreachable")
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (known: BilibiliResolveException) { throw known
        } catch (_: Exception) {
            // Do not expose transport exceptions: they can contain account or signed URL data.
            fail(BilibiliResolveException.Reason.NETWORK, "哔哩哔哩解析失败，请检查网络后重试")
        }
    }

    private suspend fun resolveVideo(target: Target, quality: Int?): ResolvedOnlineMedia {
        val identity = if (target.id.startsWith("BV")) "bvid" else "aid"
        val value = if (identity == "aid") target.id.substring(2) else target.id
        val view = api("https://api.bilibili.com/x/web-interface/view", mapOf(identity to value), target.url).first
        val bvid = view.optString("bvid").ifBlank { target.id }
        val base = "https://www.bilibili.com/video/$bvid"
        val pages = view.optJSONArray("pages").objects()
        val page = pages.firstOrNull { it.optInt("page") == target.page }
            ?: if (target.page == 1 && pages.isEmpty()) view else fail(BilibiliResolveException.Reason.UNAVAILABLE, "视频分P不存在")
        val cid = page.optLong("cid")
        if (cid <= 0) fail(BilibiliResolveException.Reason.INVALID_RESPONSE, "视频缺少播放标识")
        val episodes = pages.map { OnlineEpisode(it.optLong("cid").toString(), it.optString("part"), "$base?p=${it.optInt("page", 1)}") }.toMutableList()
        view.optJSONObject("ugc_season")?.optJSONArray("sections").objects().forEach { section ->
            section.optJSONArray("episodes").objects().forEach { episode ->
                val id = episode.optString("bvid").ifBlank { episode.optJSONObject("arc")?.optString("bvid").orEmpty() }
                if (id.matches(Regex("BV[0-9A-Za-z]{10}"))) episodes += OnlineEpisode(id, episode.optString("title"), "https://www.bilibili.com/video/$id")
            }
        }
        val params = mapOf("bvid" to bvid, "cid" to cid.toString(), "qn" to "127", "fnval" to "4048", "fourk" to "1")
        val play = signedApi("https://api.bilibili.com/x/player/wbi/playurl", params, base)
        return vod(play, "$base?p=${target.page}", view.optString("title", bvid), episodes.distinctBy { it.pageUrl }, quality)
    }

    private suspend fun resolvePgc(target: Target, quality: Int?): ResolvedOnlineMedia {
        val season = api("https://api.bilibili.com/pgc/view/web/season", mapOf((if (target.kind == Kind.EPISODE) "ep_id" else "season_id") to target.id), target.url).first
        val entries = season.optJSONArray("episodes").objects().toMutableList()
        season.optJSONArray("section").objects().forEach { entries += it.optJSONArray("episodes").objects() }
        val episode = if (target.kind == Kind.EPISODE) entries.firstOrNull { it.optLong("id").toString() == target.id } else entries.firstOrNull()
        if (episode == null) fail(BilibiliResolveException.Reason.UNAVAILABLE, "番剧剧集不存在或尚未上线")
        val id = episode.optLong("id").toString()
        val page = "https://www.bilibili.com/bangumi/play/ep$id"
        val play = api("https://api.bilibili.com/pgc/player/web/playurl", mapOf("ep_id" to id, "cid" to episode.optLong("cid").toString(), "qn" to "127", "fnval" to "4048", "fourk" to "1"), page).first
        val actual = play.optJSONObject("video_info") ?: play
        val episodes = entries.map { OnlineEpisode(it.optLong("id").toString(), listOf(it.optString("title"), it.optString("long_title")).filter(String::isNotBlank).joinToString(" · "), "https://www.bilibili.com/bangumi/play/ep${it.optLong("id")}") }
        return vod(actual, page, season.optString("title", "哔哩哔哩番剧"), episodes, quality)
    }

    private fun vod(data: JSONObject, page: String, title: String, episodes: List<OnlineEpisode>, requested: Int?): ResolvedOnlineMedia {
        if (data.optBoolean("is_preview") || data.optInt("is_preview") == 1)
            fail(BilibiliResolveException.Reason.ENTITLEMENT, "当前账号仅有试看权限，请在官方页面确认购买或会员权限")
        val labels = qualityLabels(data)
        val dash = data.optJSONObject("dash")
        val audios = mutableListOf<OnlineAudio>()
        val videos = mutableListOf<OnlineVideo>()
        if (dash != null) {
            val audioItems = dash.optJSONArray("audio").objects().toMutableList()
            dash.optJSONObject("dolby")?.optJSONArray("audio").objects().let { audioItems += it }
            dash.optJSONObject("flac")?.optJSONObject("audio")?.let { audioItems += it }
            audioItems.forEachIndexed { index, item ->
                streamUrl(item)?.let { url -> audios += OnlineAudio("a${item.optInt("id")}_$index", audioLabel(item, index), url, item.text("mimeType", "mime_type").ifBlank { "audio/mp4" }, item.text("codecs"), backupUrls = backups(item)) }
            }
            dash.optJSONArray("video").objects().forEachIndexed { index, item ->
                val qn = item.optInt("id")
                streamUrl(item)?.let { url ->
                    videos += OnlineVideo("v${qn}_${item.optInt("codecid")}_$index", qn, labels[qn] ?: "画质 $qn", url,
                        item.text("mimeType", "mime_type").ifBlank { "video/mp4" }, true, item.text("codecs"), item.optInt("width"), item.optInt("height"), item.optInt("bandwidth"), backups(item))
                }
            }
            if (videos.isNotEmpty() && audios.isEmpty()) fail(BilibiliResolveException.Reason.UNAVAILABLE, "分离视频未返回可用音轨")
        } else {
            val groups = data.optJSONArray("durls").objects().ifEmpty { listOf(data) }
            groups.forEachIndexed { index, group ->
                val segments = group.optJSONArray("durl").objects()
                if (segments.size > 1) fail(BilibiliResolveException.Reason.UNSUPPORTED_SEGMENTS, "该视频包含${segments.size}个连续片段，当前播放器尚不支持拼接播放")
                segments.singleOrNull()?.let { item ->
                    val qn = group.optInt("quality", data.optInt("quality"))
                    streamUrl(item)?.let { url -> videos += OnlineVideo("progressive_${qn}_$index", qn, labels[qn] ?: "画质 $qn", url, if (data.optString("format").contains("flv")) "video/x-flv" else "video/mp4", backupUrls = backups(item)) }
                }
            }
        }
        if (videos.isEmpty()) fail(BilibiliResolveException.Reason.UNAVAILABLE, "未返回可播放视频，可能需要登录或拥有该内容权限")
        val selected = videos.filter { it.qualityId == requested }.ifEmpty { videos }.sortedWith(compareByDescending<OnlineVideo> { it.qualityId }.thenBy { if (it.codec.orEmpty().startsWith("avc")) 0 else 1 }).first()
        return ResolvedOnlineMedia(page, title, episodes, videos, audios, selected.id, audios.firstOrNull()?.id,
            requestHeaders = mapOf("Referer" to VOD_REFERER), userAgent = VOD_USER_AGENT,
            notice = if (requested != null && selected.qualityId != requested) "所选画质未获提供，已使用实际可用画质；更高画质可能需要登录或会员权限" else null)
    }

    private fun audioLabel(item: JSONObject, index: Int): String {
        val codec = item.text("codecs").lowercase()
        val label = when {
            codec.startsWith("flac") -> "FLAC"
            codec.startsWith("ec-3") || codec.startsWith("eac3") || codec.startsWith("ac-3") || codec.startsWith("ac3") -> "杜比"
            codec.startsWith("mp4a.40.5") || codec.startsWith("mp4a.40.29") -> "HE-AAC"
            codec.startsWith("mp4a") || codec.startsWith("aac") -> "AAC"
            else -> "音轨 ${index + 1}"
        }
        val bandwidth = item.optLong("bandwidth")
        return if (bandwidth > 0) "$label · ${(bandwidth + 500) / 1000} kbps" else label
    }

    private fun liveCandidates(data: JSONObject): List<Pair<String, OnlineVideo>> {
        val play = data.optJSONObject("playurl_info")?.optJSONObject("playurl") ?: return emptyList()
        val descriptions = play.optJSONArray("g_qn_desc").objects().associate { it.optInt("qn") to it.optString("desc") }
        val candidates = mutableListOf<Pair<String, OnlineVideo>>()
        play.optJSONArray("stream").objects().filter { it.optString("protocol_name") == "http_hls" }.forEach { stream ->
            stream.optJSONArray("format").objects().filter { it.optString("format_name") in setOf("fmp4", "ts") }.forEach { format ->
                format.optJSONArray("codec").objects().filter { it.optString("codec_name") in setOf("avc", "hevc") && !it.optBoolean("drm") }.forEach { codec ->
                    val qn = codec.optInt("current_qn")
                    val name = codec.optString("codec_name")
                    val urls = codec.optJSONArray("url_info").objects().mapNotNull { info -> mediaUrl(info.optString("host") + codec.optString("base_url") + info.optString("extra")) }.distinct()
                    if (qn > 0 && urls.isNotEmpty()) {
                        val type = format.optString("format_name")
                        candidates += type to OnlineVideo("live_${type}_${qn}_${name}", qn, descriptions[qn]?.takeIf(String::isNotBlank) ?: "画质 $qn", urls.first(), "application/x-mpegURL", codec = name, width = codec.optJSONObject("media_info")?.optInt("width") ?: 0, height = codec.optJSONObject("media_info")?.optInt("height") ?: 0, backupUrls = urls.drop(1))
                    }
                }
            }
        }
        return candidates
    }

    private suspend fun resolveLive(target: Target, requested: Int?): ResolvedOnlineMedia {
        val initial = api("https://api.live.bilibili.com/room/v1/Room/room_init", mapOf("id" to target.id), target.url).first
        if (initial.optInt("live_status") != 1) fail(BilibiliResolveException.Reason.OFFLINE, "直播间尚未开播")
        if (initial.optBoolean("is_locked") || initial.optBoolean("is_hidden") || (initial.optBoolean("encrypted") && !initial.optBoolean("pwd_verified")))
            fail(BilibiliResolveException.Reason.ENTITLEMENT, "直播间当前受访问限制，请在官方页面确认访问权限")
        val room = initial.optLong("room_id").takeIf { it > 0 }?.toString() ?: target.id
        val page = "https://live.bilibili.com/$room"
        suspend fun fetch(qn: Int) = api("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo", mapOf("room_id" to room, "protocol" to "0,1", "format" to "0,1,2", "codec" to "0,1", "qn" to qn.toString(), "platform" to "web", "ptype" to "8"), page)
        // Always refresh the high catalog: requesting only the selected low qn can hide high options.
        val (data, response) = fetch(20000)
        val responseReceivedAtMs = clockMs()
        val candidates = liveCandidates(data).toMutableList()
        if (candidates.isEmpty()) fail(BilibiliResolveException.Reason.ENTITLEMENT, "未返回可用直播流，请确认直播状态及账号观看权限")
        val preferredFormat = if (candidates.any { it.first == "fmp4" }) "fmp4" else "ts"
        // Advertised accept_qn is only a hint, never evidence of a playable quality.
        // Probe two inexpensive tiers so the first open also has verified lower-quality choices.
        for (qn in listOfNotNull(requested, 80, 150).distinct().filter { it != 20000 }) {
            if (candidates.any { it.first == preferredFormat && it.second.qualityId == qn }) continue
            try {
                candidates += liveCandidates(fetch(qn).first)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: BilibiliResolveException) { /* Keep already verified streams if an optional tier fails. */ }
        }
        val format = if (candidates.any { it.first == "fmp4" }) "fmp4" else "ts"
        val videos = candidates.filter { it.first == format }.map { it.second }.distinctBy { it.id }
        if (videos.isEmpty()) fail(BilibiliResolveException.Reason.UNAVAILABLE, "直播间未提供可用的AVC/HEVC HLS流")
        val selected = videos.filter { it.qualityId == requested }.ifEmpty { videos }.sortedWith(compareBy<OnlineVideo> { if (it.codec == "avc") 0 else 1 }.thenByDescending { it.qualityId }).first()
        val offset = response.header("Date")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - responseReceivedAtMs }.getOrNull() } ?: 0L
        var start = initial.optLong("live_time") * 1000L
        var window = 0
        val specials = data.optJSONArray("all_special_types")
        if (format == "fmp4" && specials != null && (0 until specials.length()).any { specials.optInt(it) == 19 }) {
            try {
                val progress = api("https://api.live.bilibili.com/xlive/web-room/v1/index/getProgressBarInfo", mapOf("roomid" to room, "platform" to "web"), page).first
                if (progress.optString("bar_status") == "ENABLE") {
                    val barStart = progress.optLong("bar_start_time") * 1000L
                    if (barStart > 0) start = barStart
                    if (start > 0 && start <= clockMs() + offset) window = maxOf(3600, progress.optInt("bar_special_time"))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: BilibiliResolveException) { /* Optional capability failure leaves live-only playback. */ }
        }
        return ResolvedOnlineMedia(page, "哔哩哔哩直播 $room", emptyList(), videos, selectedVideoId = selected.id,
            requestHeaders = mapOf("Referer" to page), userAgent = LIVE_USER_AGENT,
            live = OnlineLiveInfo(room, start, window, offset),
            notice = if (requested != null && selected.qualityId != requested) "直播接口返回了不同画质，已按实际画质显示" else null)
    }

    private suspend fun signedApi(url: String, params: Map<String, String>, referer: String): JSONObject {
        // nav supplies WBI image keys even for anonymous code -101 responses.
        val nav = request("https://api.bilibili.com/x/web-interface/nav", referer)
        val root = json(nav)
        val keys = root.optJSONObject("data")?.optJSONObject("wbi_img")
            ?: fail(BilibiliResolveException.Reason.INVALID_RESPONSE, "无法获取网页请求签名")
        val mixin = BilibiliWbi.mixinKey(keys.optString("img_url"), keys.optString("sub_url"))
        return apiRaw(url + "?" + BilibiliWbi.sign(params, mixin, clockMs() / 1000), referer).first
    }
    private suspend fun api(url: String, params: Map<String, String>, referer: String): Pair<JSONObject, OnlineHttpResponse> =
        apiRaw(url + "?" + params.entries.joinToString("&") { BilibiliWbi.encode(it.key) + "=" + BilibiliWbi.encode(it.value) }, referer)
    private suspend fun apiRaw(url: String, referer: String): Pair<JSONObject, OnlineHttpResponse> {
        val response = request(url, referer)
        val root = json(response)
        val code = root.optInt("code", Int.MIN_VALUE)
        if (code != 0) {
            val reason = when (code) {
                -101 -> BilibiliResolveException.Reason.LOGIN_REQUIRED
                -104, -403, -105, 6002003, 6002004, 10015002 -> BilibiliResolveException.Reason.ENTITLEMENT
                -404, 62002, 62004 -> BilibiliResolveException.Reason.UNAVAILABLE
                else -> BilibiliResolveException.Reason.UNAVAILABLE
            }
            val message = when (reason) {
                BilibiliResolveException.Reason.LOGIN_REQUIRED -> "此内容需要登录哔哩哔哩账号"
                BilibiliResolveException.Reason.ENTITLEMENT -> "当前账号没有该内容的观看权限，请在官方页面确认会员、购买或地区限制"
                else -> "哔哩哔哩暂未提供该内容（接口代码 $code）"
            }
            throw BilibiliResolveException(reason, message, code)
        }
        return (root.optJSONObject("data") ?: root.optJSONObject("result") ?: fail(BilibiliResolveException.Reason.INVALID_RESPONSE, "接口未返回播放数据")) to response
    }
    private suspend fun request(url: String, referer: String): OnlineHttpResponse {
        // Main-site web API and VOD media use the verified desktop/origin contract.
        // Live APIs keep their independently verified mobile/room contract.
        val mainApi = isBilibiliApi(url) && URI(url).host == "api.bilibili.com"
        val apiReferer = if (mainApi) VOD_REFERER else referer
        val apiUserAgent = if (mainApi) VOD_USER_AGENT else LIVE_USER_AGENT
        val headers = mutableMapOf("User-Agent" to apiUserAgent, "Referer" to apiReferer, "Accept" to "application/json, text/html;q=0.9")
        if (isBilibiliApi(url)) cookieProvider.cookiesFor(url)?.takeIf(String::isNotBlank)?.let { headers["Cookie"] = it }
        val response = try { transport.get(url, headers) } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { fail(BilibiliResolveException.Reason.NETWORK, "连接哔哩哔哩失败，请稍后重试") }
        if (response.status == 401) fail(BilibiliResolveException.Reason.LOGIN_REQUIRED, "此内容需要登录")
        if (response.status == 403) fail(BilibiliResolveException.Reason.ENTITLEMENT, "服务拒绝访问，请确认账号权限或稍后重试")
        if (response.status !in 200..399) fail(BilibiliResolveException.Reason.NETWORK, "哔哩哔哩服务错误（HTTP ${response.status}）")
        return response
    }
    private fun json(response: OnlineHttpResponse): JSONObject = try { JSONObject(response.body) } catch (_: Exception) { fail(BilibiliResolveException.Reason.INVALID_RESPONSE, "哔哩哔哩返回了无效数据") }
    private fun qualityLabels(data: JSONObject): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        val ids = data.optJSONArray("accept_quality")
        val labels = data.optJSONArray("accept_description")
        if (ids != null && labels != null) for (i in 0 until minOf(ids.length(), labels.length())) labels.optString(i).takeIf(String::isNotBlank)?.let { result[ids.optInt(i)] = it }
        data.optJSONArray("support_formats").objects().forEach { format -> format.text("new_description", "display_desc", "description").takeIf(String::isNotBlank)?.let { result[format.optInt("quality")] = it } }
        return result
    }
    private enum class Kind { VIDEO, EPISODE, SEASON, LIVE, SHORT }
    private data class Target(val kind: Kind, val id: String, val page: Int, val url: String)
    private fun parse(input: String): Target {
        val text = input.trim()
        val expanded = when {
            text.matches(Regex("BV[0-9A-Za-z]{10}|av[0-9]+")) -> "https://www.bilibili.com/video/$text"
            text.matches(Regex("(?:ep|ss)[0-9]+")) -> "https://www.bilibili.com/bangumi/play/$text"
            text.startsWith("b23.tv/") || text.startsWith("www.bilibili.com/") || text.startsWith("live.bilibili.com/") -> "https://$text"
            else -> text
        }
        val uri = try { URI(expanded) } catch (_: Exception) { fail(BilibiliResolveException.Reason.INVALID_URL, "无效的哔哩哔哩链接") }
        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null || uri.port !in listOf(-1, 80, 443)) fail(BilibiliResolveException.Reason.INVALID_URL, "无效的哔哩哔哩链接")
        val host = uri.host?.lowercase()
        val path = uri.path.orEmpty().trimEnd('/')
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part -> val pieces = part.split('=', limit = 2); if (pieces.size == 2) URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces[1], "UTF-8") else null }.toMap()
        val page = query["p"]?.toIntOrNull() ?: 1
        if (page < 1) fail(BilibiliResolveException.Reason.INVALID_URL, "分P编号必须大于0")
        if (host == "b23.tv" && path.matches(Regex("/[A-Za-z0-9]+"))) return Target(Kind.SHORT, path.drop(1), page, "https://b23.tv$path")
        if (host == "live.bilibili.com") Regex("/(?:blanc/)?([0-9]+)").matchEntire(path)?.let { return Target(Kind.LIVE, it.groupValues[1], 1, "https://live.bilibili.com/${it.groupValues[1]}") }
        if (host in setOf("www.bilibili.com", "bilibili.com", "m.bilibili.com")) {
            Regex("/video/(BV[0-9A-Za-z]{10}|av[0-9]+)").matchEntire(path)?.let { return Target(Kind.VIDEO, it.groupValues[1], page, "https://www.bilibili.com$path?p=$page") }
            Regex("/bangumi/play/(ep|ss)([0-9]+)").matchEntire(path)?.let { return Target(if (it.groupValues[1] == "ep") Kind.EPISODE else Kind.SEASON, it.groupValues[2], 1, "https://www.bilibili.com$path") }
        }
        fail(BilibiliResolveException.Reason.INVALID_URL, "不支持的哔哩哔哩页面链接")
    }
    private fun streamUrl(item: JSONObject): String? = streamUrls(item).firstOrNull()
    private fun backups(item: JSONObject): List<String> = streamUrls(item).drop(1)
    private fun streamUrls(item: JSONObject): List<String> {
        val urls = mutableListOf<String>()
        mediaUrl(item.text("baseUrl", "base_url", "url"))?.let(urls::add)
        for (field in listOf("backupUrl", "backup_url")) {
            when (val value = item.opt(field)) {
                is JSONArray -> for (index in 0 until value.length()) mediaUrl(value.optString(index))?.let(urls::add)
                is String -> mediaUrl(value)?.let(urls::add)
            }
        }
        return BilibiliCdnCandidates.ordered(urls)
    }
    private fun mediaUrl(value: String): String? {
        val url = if (value.startsWith("//")) "https:$value" else value
        return runCatching { URI(url).takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null }?.toString() }.getOrNull()
    }
    private fun fail(reason: BilibiliResolveException.Reason, message: String): Nothing = throw BilibiliResolveException(reason, message)
    companion object {
        const val VOD_REFERER = "https://www.bilibili.com/"
        const val VOD_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
        const val LIVE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val USER_AGENT = VOD_USER_AGENT
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.text(vararg names: String): String = names.firstNotNullOfOrNull { name -> optString(name).takeIf { it.isNotBlank() && it != "null" } }.orEmpty()
