package com.sweefl.player.feature.network

import android.net.Uri
import java.util.Locale

/** Only Bilibili share input is rewritten; direct stream URLs retain their exact query/signature. */
internal fun normalizeNetworkAddress(input: String): String {
    val text = input.trim()
    if (text.startsWith("https://", true) || text.startsWith("http://", true)) {
        // A single direct URL must pass through the existing validation unchanged.
        if (text.none { it.isWhitespace() }) return text
    }
    val urls = Regex("https?://[^\\s<>\\\"‘’“”]+", RegexOption.IGNORE_CASE)
    for (match in urls.findAll(text)) {
        val candidate = match.value.trimEnd('。', '，', '！', '？', '、', ')', '）', ']', '】', ',', ';', '；')
        val uri = Uri.parse(candidate)
        val host = uri.host?.lowercase(Locale.ROOT) ?: continue
        if (uri.userInfo == null && (host == "b23.tv" || host == "bilibili.com" || host.endsWith(".bilibili.com"))) {
            return candidate
        }
    }
    val bare = Regex("^(?:(?:www|m|live)\\.)?bilibili\\.com/[^\\s]+$|^b23\\.tv/[^\\s]+$", RegexOption.IGNORE_CASE)
    if (bare.matches(text)) return "https://$text"
    val bv = Regex("(?<![A-Za-z0-9])BV([A-Za-z0-9]{10})(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).find(text)
    if (bv != null) return "https://www.bilibili.com/video/BV${bv.groupValues[1]}"
    val av = Regex("(?<![A-Za-z0-9])av([0-9]+)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).find(text)
    if (av != null) return "https://www.bilibili.com/video/av${av.groupValues[1]}"
    return text
}
