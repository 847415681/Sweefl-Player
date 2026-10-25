package com.sweefl.player.data.online

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface OnlineCookieProvider { fun cookiesFor(apiUrl: String): String? }
class WebViewOnlineCookieProvider : OnlineCookieProvider {
    override fun cookiesFor(apiUrl: String): String? =
        if (isBilibiliApi(apiUrl)) CookieManager.getInstance().getCookie(apiUrl) else null
}
fun interface OnlineHttpTransport {
    /** One GET only; implementations must not follow redirects or persist cookies. */
    suspend fun get(url: String, headers: Map<String, String>): OnlineHttpResponse
}
data class OnlineHttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    override fun toString(): String = "OnlineHttpResponse(status=$status)"
}
internal fun isBilibiliApi(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.port in listOf(-1, 443) && uri.userInfo == null &&
        uri.host in setOf("api.bilibili.com", "api.live.bilibili.com")
}.getOrDefault(false)

class UrlConnectionOnlineHttpTransport : OnlineHttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): OnlineHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpURLConnection?>()
            continuation.invokeOnCancellation { connection.getAndSet(null)?.disconnect() }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                try {
                    if (!continuation.isActive) return@Runnable
                    val conn = URI(url).toURL().openConnection() as HttpURLConnection
                    connection.set(conn)
                    if (!continuation.isActive) { conn.disconnect(); return@Runnable }
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 20_000
                    conn.requestMethod = "GET"
                    conn.useCaches = false
                    headers.forEach { (name, value) ->
                        require(!name.equals("Cookie", true) || isBilibiliApi(url))
                        conn.setRequestProperty(name, value)
                    }
                    val status = conn.responseCode
                    val responseHeaders = conn.headerFields.entries.filter { it.key != null }
                        .associate { it.key!! to it.value.joinToString(", ") }
                    val body = (if (status >= 400) conn.errorStream else conn.inputStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            val result = StringBuilder()
                            val buffer = CharArray(8192)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                require(result.length + count <= 8 * 1024 * 1024) { "API response too large" }
                                result.append(buffer, 0, count)
                            }
                            result.toString()
                        }.orEmpty()
                    if (continuation.isActive) continuation.resume(OnlineHttpResponse(status, body, responseHeaders))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally { connection.getAndSet(null)?.disconnect() }
            })
        }
}
