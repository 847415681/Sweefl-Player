package com.sweefl.player.feature.network

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.util.Locale

/** Cookies remain in this application's private WebView store. No native JavaScript bridge. */
class BilibiliLoginActivity : Activity() {
    private lateinit var browser: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 24, 29))
            fitsSystemWindows = true
        }
        val padding = (12 * resources.displayMetrics.density).toInt()
        root.addView(TextView(this).apply {
            text = "B 站官方登录（可选）\n登录后可使用账号已开通的清晰度与内容。登录状态仅保存在本应用，请在下方官方网页中操作。"
            setTextColor(Color.WHITE)
            setPadding(padding, padding, padding, padding)
        })
        val actions = LinearLayout(this)
        actions.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "退出登录"
            setOnClickListener { confirmLogout() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "完成登录"
            setOnClickListener { CookieManager.getInstance().flush(); finish() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        browser = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                setAllowFileAccessFromFileURLs(false)
                @Suppress("DEPRECATION")
                setAllowUniversalAccessFromFileURLs(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    if (isOfficialLoginNavigation(request.url)) return false
                    if (request.hasGesture() && request.url.scheme?.lowercase(Locale.ROOT) in listOf("https", "http")) {
                        confirmExternalNavigation(request.url)
                    }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    // Also covers top-level loads that do not call shouldOverrideUrlLoading.
                    if (request.isForMainFrame && !isOfficialLoginNavigation(request.url)) {
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                            emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    Toast.makeText(this@BilibiliLoginActivity, "网页安全连接失败，请稍后重试。", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        browser.loadUrl(LOGIN_URL)
    }

    private fun confirmExternalNavigation(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("在系统浏览器打开？")
            .setMessage("此链接将离开 B 站官方登录页面。")
            .setNegativeButton("取消", null)
            .setPositiveButton("打开浏览器") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, "没有可用的浏览器。", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出本应用网页登录？")
            .setMessage("清除 Sweefl Player 内的网页登录会话，不影响系统浏览器或 B 站应用。")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出登录") { _, _ ->
                browser.stopLoading()
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    if (!isFinishing && !isDestroyed) {
                        android.webkit.WebStorage.getInstance().deleteAllData()
                        browser.clearHistory()
                        browser.loadUrl(LOGIN_URL)
                        Toast.makeText(this, "已退出本应用网页登录。", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    override fun onPause() {
        if (::browser.isInitialized) browser.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::browser.isInitialized) browser.onResume()
    }

    override fun onDestroy() {
        if (::browser.isInitialized) {
            browser.stopLoading()
            (browser.parent as? android.view.ViewGroup)?.removeView(browser)
            browser.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val LOGIN_URL = "https://passport.bilibili.com/login"
    }
}

internal fun isOfficialLoginNavigation(uri: Uri): Boolean {
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) &&
        Regex("(?:[a-z0-9-]+\\.)*bilibili\\.com").matches(host) &&
        uri.encodedAuthority?.lowercase(Locale.ROOT) in listOf(host, "$host:443")
}
