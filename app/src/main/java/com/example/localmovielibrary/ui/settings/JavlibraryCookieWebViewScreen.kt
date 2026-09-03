package com.example.localmovielibrary.ui.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.localmovielibrary.scraper.JavlibraryScraper
import org.json.JSONArray

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JavlibraryCookieWebViewScreen(
    onBack: () -> Unit,
    onSaveCookie: (String) -> Unit
) {
    var lastCookie by remember { mutableStateOf("") }
    var statusText by remember {
        mutableStateOf("请在页面中完成 JavLibrary 的 Cloudflare 验证。完成后点击返回，会保存 Cookie 并用于后续刮削。")
    }

    fun saveAndBack() {
        val cookie = collectJavlibraryCookies().ifBlank { lastCookie }
        if (cookie.hasJavlibraryClearance()) onSaveCookie(cookie) else onBack()
    }

    BackHandler { saveAndBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070A0E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101923))
                .padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = ::saveAndBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Text(
                text = "JavLibrary 验证",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Rounded.Public, contentDescription = null, tint = Color.White.copy(alpha = 0.72f))
        }
        Text(
            text = statusText,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101923))
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
        )

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = JavlibraryScraper.USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                statusText = "JavLibrary WebView 渲染进程崩溃，请返回后重试。"
                                view?.stopLoading()
                                view?.destroy()
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { encodedHtml ->
                                    val html = runCatching { JSONArray("[$encodedHtml]").getString(0) }.getOrDefault("")
                                    lastCookie = collectJavlibraryCookies()
                                    statusText = when {
                                        html.isCloudflareChallengeHtml() -> {
                                            "仍在 Cloudflare 验证页，请完成验证，确认页面内容出现后再返回。"
                                        }
                                        lastCookie.hasJavlibraryClearance() -> {
                                            "已通过 Cloudflare 验证。请确认页面能正常显示，然后点击返回。"
                                        }
                                        else -> {
                                            "页面正在加载，请完成 Cloudflare 验证后再返回。"
                                        }
                                    }
                                }
                            }
                        }
                        loadUrl(JAVLIBRARY_HOME)
                    }
                }
            )
        }
    }
}

private fun String.hasJavlibraryClearance(): Boolean =
    contains("cf_clearance=", ignoreCase = true)

private fun String.isCloudflareChallengeHtml(): Boolean {
    val lower = lowercase()
    return "cloudflare" in lower && ("challenge" in lower || "cf-chl" in lower || "请验证您是真人" in this)
}

private fun collectJavlibraryCookies(): String {
    CookieManager.getInstance().flush()
    return listOf(
        CookieManager.getInstance().getCookie(JAVLIBRARY_HOME).orEmpty(),
        CookieManager.getInstance().getCookie(JAVLIBRARY_WWW).orEmpty()
    )
        .flatMap { it.split(";") }
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("=") }
        .distinctBy { it.substringBefore("=") }
        .joinToString("; ")
}

private const val JAVLIBRARY_HOME = "https://www.javlibrary.com"
private const val JAVLIBRARY_WWW = "https://javlibrary.com"
