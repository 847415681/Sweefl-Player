package com.sweefl.player.playback.engine

import java.net.URI

/** Session-local DVR propagation. Raw signed query bytes are never decoded and rebuilt. */
internal class HlsDvrUriPolicy(selectedUrl: String?) {
    private data class Scope(val scheme: String, val host: String, val port: Int, val directory: String)
    private val scopes = mutableSetOf<Scope>()
    private val shift: String?

    init {
        val root = selectedUrl?.let(::parse)
        shift = root?.takeIf { playlist(it) && scope(it) != null }?.rawQuery
            ?.split('&')?.firstOrNull { it.substringBefore('=') == "tmshift" }
            ?.substringAfter('=', "")?.takeIf {
                it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } &&
                    it.toLongOrNull()?.let { value -> value > 0 } == true
            }
        if (shift != null && root != null) scope(root)?.let(scopes::add)
    }

    @Synchronized fun rewrite(url: String): String {
        val uri = parse(url) ?: return url
        if (shift == null || !inScope(uri) || !resource(uri) || hasShift(uri)) return url
        val fragmentAt = url.indexOf('#').let { if (it < 0) url.length else it }
        val base = url.substring(0, fragmentAt)
        val separator = when { uri.rawQuery == null -> "?"; base.endsWith('?') || base.endsWith('&') -> ""; else -> "&" }
        return base + separator + "tmshift=" + shift + url.substring(fragmentAt)
    }

    /** Called only after upstream.open succeeds; foreign segment redirects cannot widen trust. */
    @Synchronized fun onSuccessfulOpen(originalUrl: String, finalUrl: String?) {
        if (shift == null) return
        val original = parse(originalUrl) ?: return
        val final = finalUrl?.let(::parse) ?: return
        if (inScope(original) && playlist(original)) scope(final)?.let(scopes::add)
    }

    private fun inScope(uri: URI) = scope(uri) in scopes
    private fun hasShift(uri: URI) = uri.rawQuery?.split('&')?.any {
        // Recognize escaped keys too, but retain all original bytes.
        runCatching { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") }.getOrNull() == "tmshift"
    } == true
    private fun playlist(uri: URI) = uri.rawPath.orEmpty().endsWith(".m3u8", true)
    private fun resource(uri: URI) = listOf(".m3u8", ".m4s", ".mp4").any { uri.rawPath.orEmpty().endsWith(it, true) }
    private fun scope(uri: URI): Scope? {
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https" || uri.host == null || uri.rawUserInfo != null) return null
        val path = uri.rawPath.orEmpty()
        if (uri.normalize().rawPath != path || path.contains("%2f", true) || path.contains("%2e", true) || path.contains("%5c", true)) return null
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        return Scope(scheme, uri.host.lowercase(), port, path.substringBeforeLast('/', "") + "/")
    }
    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()
}
