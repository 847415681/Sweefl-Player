package com.sweefl.player.domain

import java.net.URI

/** A browser page isn't automatically a media URL; manifests/direct streams are accepted. */
fun networkInputError(url: String, userAgent: String): String? {
    if (url.length > 8192) return "链接过长"
    val parsed = try { URI(url.trim()) } catch (_: Exception) { return "请输入有效的 HTTP 或 HTTPS 链接" }
    if (parsed.scheme?.lowercase() !in listOf("http", "https") || parsed.host.isNullOrBlank()) return "仅支持 HTTP / HTTPS 媒体链接"
    if (parsed.userInfo != null) return "请不要在链接中包含用户名和密码"
    if (userAgent.length > 512) return "User-Agent 不能超过 512 字符"
    return try {
        PlaybackConfig.validateMediaUri(url.trim())
        PlaybackConfig.validateUserAgent(userAgent)
        null
    } catch (failure: IllegalArgumentException) { failure.message }
}
