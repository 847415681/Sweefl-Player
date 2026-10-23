package com.sweefl.player.domain

import java.net.URI
import java.util.Locale

/** Network input rules shared by the launcher and player; software means system MediaCodec. */
object PlaybackConfig {
    const val DECODE_AUTO = "AUTO"
    const val DECODE_SOFTWARE = "SOFTWARE"
    const val DECODE_FFMPEG = "FFMPEG"
    const val EXTRA_USER_AGENT = "userAgent"
    const val EXTRA_DECODE_MODE = "decodeMode"
    const val EXTRA_DIAGNOSTICS = "diagnostics"
    const val DEFAULT_USER_AGENT = "SweeflPlayer/0.2.0"

    fun validateMediaUri(value: String): String {
        require(value.isNotBlank() && value.none { it == '\r' || it == '\n' }) { "媒体地址不能为空或包含换行。" }
        require(!Regex("%0[ad]", RegexOption.IGNORE_CASE).containsMatchIn(value)) { "媒体地址不能包含编码换行。" }
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("媒体地址格式无效。") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme in setOf("http", "https", "content")) { "仅支持 HTTP、HTTPS 和 content 地址。" }
        require(uri.rawUserInfo == null && uri.rawAuthority?.contains('@') != true) { "媒体地址不能包含用户名或密码。" }
        require(!uri.isOpaque && !uri.rawAuthority.isNullOrBlank()) { "媒体地址缺少主机或提供方。" }
        if (scheme != "content") {
            require(!uri.host.isNullOrBlank() && uri.port in -1..65535) { "网络地址主机或端口无效。" }
        }
        return value
    }

    fun validateUserAgent(value: String?): String? {
        require(value == null || value.all { it.code in 32..126 }) { "User-Agent 仅支持不含换行的可打印 ASCII 字符。" }
        return value?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun parseDecodeMode(value: String?): String = when (value?.uppercase(Locale.ROOT)) {
        null, "", DECODE_AUTO -> DECODE_AUTO
        DECODE_SOFTWARE -> DECODE_SOFTWARE
        DECODE_FFMPEG -> DECODE_FFMPEG
        else -> throw IllegalArgumentException("解码模式必须为 AUTO、SOFTWARE 或 FFMPEG。")
    }

    fun subtitleMimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> null
    }

    /** API 26–28 fallback where the platform does not expose isSoftwareOnly. */
    fun isLegacySoftwareCodec(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.startsWith("c2.google.")
    }
}
