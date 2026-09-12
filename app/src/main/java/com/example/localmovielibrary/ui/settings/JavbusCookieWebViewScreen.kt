package com.example.localmovielibrary.ui.settings

import android.annotation.SuppressLint
import android.util.Log
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
import com.example.localmovielibrary.scraper.JavbusScraper
import org.json.JSONArray

/*
 * ================================================================================
 * 步骤1：完成并保存 JavBus 年龄确认
 * ================================================================================
 * 目标：由用户在真实 WebView 中确认年龄，再把同站 Cookie 交给 JavBus 刮削请求。
 * 数据源：JavBus 页面、Android CookieManager 和最终页面 HTML。
 * 操作：
 * 1) 年龄确认页保持可操作，确认后检查是否进入普通站点页面。
 * 2) 只在普通页面已加载时保存当前 JavBus Cookie。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JavbusCookieWebViewScreen(
    onBack: () -> Unit,
    onSaveCookie: (String) -> Unit
) {
    var lastCookie by remember { mutableStateOf("") }
    var verifiedPageLoaded by remember { mutableStateOf(false) }
    var statusText by remember {
        mutableStateOf("请在页面中完成 JavBus 年龄确认。进入站点后点击返回，会保存 Cookie 并用于后续刮削。")
    }

    fun saveAndBack() {
        val cookie = collectJavbusCookies().ifBlank { lastCookie }
        if (verifiedPageLoaded && cookie.isNotBlank()) {
            Log.i(TAG, "步骤1结束：已保存 JavBus Cookie")
            onSaveCookie(cookie)
        } else {
            Log.i(TAG, "步骤1结束：未通过 JavBus 年龄确认")
            onBack()
        }
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
                text = "JavBus 年龄确认",
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
                    Log.i(TAG, "步骤1开始：打开 JavBus 年龄确认页")
                    WebView(context).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = JavbusScraper.USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                verifiedPageLoaded = false
                                statusText = "JavBus WebView 渲染进程崩溃，请返回后重试。"
                                view?.stopLoading()
                                view?.destroy()
                                Log.i(TAG, "步骤1结束：JavBus WebView 渲染进程崩溃")
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { encodedHtml ->
                                    val html = runCatching {
                                        JSONArray("[$encodedHtml]").getString(0)
                                    }.getOrDefault("")
                                    lastCookie = collectJavbusCookies()
                                    val ageVerificationPage = url.isJavbusAgeVerificationUrl() ||
                                        html.isJavbusAgeVerificationHtml()
                                    verifiedPageLoaded = !ageVerificationPage && html.isNotBlank()
                                    statusText = if (verifiedPageLoaded) {
                                        "已进入 JavBus。点击返回保存 Cookie。"
                                    } else {
                                        "请勾选已成年并确认，进入站点后再点击返回。"
                                    }
                                }
                            }
                        }
                        loadUrl(JAVBUS_HOME)
                    }
                }
            )
        }
    }
}

private fun collectJavbusCookies(): String {
    CookieManager.getInstance().flush()
    return listOf(
        CookieManager.getInstance().getCookie(JAVBUS_HOME).orEmpty(),
        CookieManager.getInstance().getCookie(JAVBUS_BARE).orEmpty()
    )
        .flatMap { it.split(";") }
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("=") }
        .distinctBy { it.substringBefore("=") }
        .joinToString("; ")
}

private fun String?.isJavbusAgeVerificationUrl(): Boolean =
    this?.contains("/doc/driver-verify", ignoreCase = true) == true

private fun String.isJavbusAgeVerificationHtml(): Boolean {
    val lower = lowercase()
    return "id=\"ageverify\"" in lower ||
        "id='ageverify'" in lower ||
        "age verification javbus" in lower
}

private const val JAVBUS_HOME = "https://www.javbus.com/"
private const val JAVBUS_BARE = "https://javbus.com/"
private const val TAG = "JavBusCookieWebView"
