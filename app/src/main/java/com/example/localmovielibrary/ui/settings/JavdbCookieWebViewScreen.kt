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
import com.example.localmovielibrary.playback.DEFAULT_USER_AGENT
import org.json.JSONArray

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JavdbCookieWebViewScreen(
    onBack: () -> Unit,
    onSaveCookie: (String) -> Unit
) {
    var lastCookie by remember { mutableStateOf("") }
    var statusText by remember {
        mutableStateOf("请在页面中完成 JavDB 验证。完成后点击返回，会保存 Cookie 并用于后续刮削。")
    }

    fun saveAndBack() {
        val cookie = collectJavdbCookies().ifBlank { lastCookie }
        if (cookie.isNotBlank()) onSaveCookie(cookie) else onBack()
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
                text = "JavDB 验证",
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
                        settings.userAgentString = DEFAULT_USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                statusText = "JavDB WebView 渲染进程崩溃，请返回后重试。"
                                view?.stopLoading()
                                view?.destroy()
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { encodedHtml ->
                                    val html = runCatching { JSONArray("[$encodedHtml]").getString(0) }.getOrDefault("")
                                    lastCookie = collectJavdbCookies()
                                    statusText = when {
                                        html.isJavdbGeoBlockedHtml() -> {
                                            "当前网络出口所在地区被 JavDB 禁止访问，请切换 Clash 节点后刷新。"
                                        }
                                        lastCookie.isNotBlank() -> {
                                            "已获取 JavDB Cookie。若验证已经完成，请点击返回。"
                                        }
                                        else -> {
                                            "请完成页面中的验证，然后点击返回。"
                                        }
                                    }
                                }
                            }
                        }
                        loadUrl(JAVDB_HOME)
                    }
                }
            )
        }
    }
}

private fun collectJavdbCookies(): String {
    CookieManager.getInstance().flush()
    return listOf(
        CookieManager.getInstance().getCookie(JAVDB_HOME).orEmpty(),
        CookieManager.getInstance().getCookie(JAVDB_WWW).orEmpty()
    )
        .flatMap { it.split(";") }
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("=") }
        .distinctBy { it.substringBefore("=") }
        .joinToString("; ")
}

private fun String.isJavdbGeoBlockedHtml(): Boolean {
    val lower = lowercase()
    return "copyright restrictions" in lower &&
        "prohibited in the country" in lower
}

private const val JAVDB_HOME = "https://javdb.com"
private const val JAVDB_WWW = "https://www.javdb.com"
