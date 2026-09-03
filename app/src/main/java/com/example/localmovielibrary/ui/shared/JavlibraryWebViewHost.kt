package com.example.localmovielibrary.ui.shared

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrary.scraper.JavdbScraper
import com.example.localmovielibrary.scraper.JavlibraryScraper
import com.example.localmovielibrary.scraper.JavlibraryWebViewFetcher
import kotlinx.coroutines.delay
import org.json.JSONArray

/*
 * ================================================================================
 * 步骤1：使用隐藏 WebView 加载 JavLibrary 页面
 * ================================================================================
 * 目标：复用设置页完成的 Cloudflare 浏览器状态，获取 OkHttp 无法取得的 HTML。
 * 数据源：JavDB/JavLibrary 当前请求、Android CookieManager。
 * 操作：
 * 1) 只创建一个当前请求对应的 WebView。
 * 2) 使用与验证页一致的 UA、JavaScript 和 DOM Storage 配置。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScraperWebViewHost(fetcher: JavlibraryWebViewFetcher) {
    val request by fetcher.currentRequest.collectAsStateWithLifecycle()
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var completedRequestId by remember { mutableStateOf<Long?>(null) }
    var visibleChallengeRequestId by remember { mutableStateOf<Long?>(null) }
    var loadedRequestId by remember { mutableStateOf<Long?>(null) }

    /*
     * ================================================================================
     * 步骤2：限制页面等待时间并保留工作页
     * ================================================================================
     * 目标：同一 WebView 保持站点会话；遇到 Turnstile 时切成可操作页面。
     * 数据源：当前请求、WebView 的页面完成回调和 Cloudflare 挑战页面。
     * 操作：
     * 1) 未挑战时隐藏工作页，不干扰应用界面。
     * 2) 检测挑战或页面长时间未返回时展示当前 WebView，人工完成后继续原请求。
     */
    LaunchedEffect(request?.id) {
        val current = request ?: return@LaunchedEffect
        Log.i(TAG, "步骤2开始：等待 ${current.url.webViewSourceName()} WebView 页面")
        completedRequestId = null
        visibleChallengeRequestId = null
        delay(WEBVIEW_INTERACTION_DELAY_MS)
        if (fetcher.currentRequest.value?.id == current.id && completedRequestId != current.id) {
            visibleChallengeRequestId = current.id
            Log.i(TAG, "步骤2：${current.url.webViewSourceName()} 页面未及时返回，显示页面供继续验证")
        }
        delay(WEBVIEW_TIMEOUT_MS - WEBVIEW_INTERACTION_DELAY_MS)
        if (fetcher.currentRequest.value?.id == current.id && completedRequestId != current.id) {
            completedRequestId = current.id
            visibleChallengeRequestId = null
            fetcher.fail(current.id, IllegalStateException("${current.url.webViewSourceName()} WebView 抓取超时，请完成页面验证后重试"))
            Log.i(TAG, "步骤2结束：${current.url.webViewSourceName()} WebView 请求超时")
        }
    }

    val activeRequest = request
    val showingChallenge = activeRequest != null && visibleChallengeRequestId == activeRequest.id
    AndroidView(
        modifier = if (showingChallenge) {
            Modifier
                .fillMaxSize()
                .padding(top = 102.dp)
        } else {
            Modifier
                .size(1.dp)
                .zIndex(-1f)
        },
        factory = { context ->
            WebView(context).apply {
                activeWebView = this
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                webViewClient = object : WebViewClient() {
                    private var pageLoadRequestId: Long? = null

                    private fun capturePageHtml(view: WebView?, trigger: String) {
                        val current = fetcher.currentRequest.value ?: return
                        if (pageLoadRequestId != current.id) {
                            Log.i(TAG, "步骤2：忽略过期 ${trigger} 回调")
                            return
                        }
                        if (completedRequestId == current.id || view == null) return
                        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { encodedHtml ->
                            if (fetcher.currentRequest.value?.id != current.id || completedRequestId == current.id) {
                                return@evaluateJavascript
                            }
                            val html = runCatching {
                                JSONArray("[$encodedHtml]").getString(0)
                            }.getOrDefault("")
                            if (html.isBlank()) return@evaluateJavascript
                            if (html.isJavlibraryCloudflareChallenge()) {
                                visibleChallengeRequestId = current.id
                                Log.i(TAG, "步骤2：${current.url.webViewSourceName()} 需要人工验证")
                                return@evaluateJavascript
                            }
                            completedRequestId = current.id
                            visibleChallengeRequestId = null
                            fetcher.complete(current.id, html)
                            Log.i(TAG, "步骤2结束：${current.url.webViewSourceName()} 页面已返回，trigger=$trigger")
                        }
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val current = fetcher.currentRequest.value ?: return
                        if (url?.isSameWebViewSource(current.url) == true) {
                            pageLoadRequestId = current.id
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        val current = fetcher.currentRequest.value
                        if (current != null && completedRequestId != current.id) {
                            completedRequestId = current.id
                            visibleChallengeRequestId = null
                            fetcher.fail(current.id, IllegalStateException("${current.url.webViewSourceName()} WebView 渲染进程崩溃"))
                        }
                        view?.stopLoading()
                        view?.destroy()
                        Log.i(TAG, "步骤2结束：WebView 渲染进程崩溃")
                        return true
                    }

                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        super.onPageCommitVisible(view, url)
                        capturePageHtml(view, "commit-visible")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        capturePageHtml(view, "page-finished")
                    }
                }
            }
        },
        update = { webView ->
            activeWebView = webView
            val current = activeRequest ?: return@AndroidView
            webView.settings.userAgentString = current.url.webViewUserAgent()
            /*
             * ================================================================================
             * 步骤3：按请求编号重新加载页面
             * ================================================================================
             * 目标：同一 URL 的后续请求也获得新的页面回调，避免搜索页被误当详情页后一直等待。
             * 数据源：WebView 队列当前请求编号和请求 URL。
             * 操作：
             * 1) 每个请求编号只加载一次。
             * 2) 新请求先停止旧页面，再重新加载目标 URL。
             */
            if (loadedRequestId != current.id) {
                Log.i(TAG, "步骤3开始：加载 ${current.url.webViewSourceName()} 请求 id=${current.id}")
                loadedRequestId = current.id
                webView.stopLoading()
                webView.loadUrl(current.url)
                Log.i(TAG, "步骤3结束：已提交 ${current.url.webViewSourceName()} 请求 id=${current.id}")
            }
        }
    )
    if (showingChallenge) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF070A0E))
        ) {
            Column(
                modifier = Modifier.padding(top = 28.dp, start = 16.dp, end = 70.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "${activeRequest.url.webViewSourceName()} 验证",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "完成页面验证后会自动继续当前刮削。",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            IconButton(
                onClick = { activeWebView?.reload() },
                modifier = Modifier.padding(top = 20.dp, end = 12.dp).align(androidx.compose.ui.Alignment.TopEnd)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新验证页面", tint = Color.White)
            }
        }
    }
}

private fun String.isJavlibraryCloudflareChallenge(): Boolean {
    val lower = lowercase()
    return "cloudflare" in lower && ("challenge" in lower || "cf-chl" in lower || "just a moment" in lower)
}

private fun String.webViewUserAgent(): String =
    if (contains("javdb.com", ignoreCase = true)) JavdbScraper.USER_AGENT else JavlibraryScraper.USER_AGENT

private fun String.webViewSourceName(): String =
    if (contains("javdb.com", ignoreCase = true)) "JavDB" else "JavLibrary"

private fun String.isSameWebViewSource(other: String): Boolean =
    substringAfter("://", this)
        .substringBefore('/')
        .substringBefore(':')
        .equals(
            other.substringAfter("://", other).substringBefore('/').substringBefore(':'),
            ignoreCase = true
        )

private const val WEBVIEW_TIMEOUT_MS = 70_000L
private const val WEBVIEW_INTERACTION_DELAY_MS = 6_000L
private const val TAG = "ScraperWebView"
