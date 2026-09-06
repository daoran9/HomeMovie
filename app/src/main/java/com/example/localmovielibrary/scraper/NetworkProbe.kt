package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NetworkProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: ((String) -> Unit)? = null
) {
    suspend fun canReachGoogle(): Boolean = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url("https://www.google.com/generate_204")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                response.code in 200..399
            }
        }.getOrDefault(false)
    }

    /*
     * ================================================================================
     * 步骤2：测试刮削来源网络能力
     * ================================================================================
     * 目标：区分来源可达、地区限制、出口封禁、CF 验证和普通网络失败，给设置页人话结果。
     * 数据源：DMM/FANZA 与 JavDB 番号搜索响应、最终重定向地址和响应正文。
     * 操作：
     * 1) 使用当前系统网络请求来源首页，不修改代理软件配置。
     * 2) 先识别地区限制、出口封禁和 CF 页面，再把普通 2xx/3xx 响应判为可达。
     */
    suspend fun probeSource(source: ScrapeSource): SourceProbeResult = withContext(ioDispatcher) {
        val url = when (source) {
            ScrapeSource.Dmm, ScrapeSource.Dmm2 -> "https://www.dmm.co.jp/search/=/searchstr=CEMN%20003/"
            ScrapeSource.Javdb -> "https://javdb.com/search?q=CEMN-003"
            else -> error("不支持测试的来源：${source.name}")
        }
        logger?.invoke("来源连通性测试开始：${source.name}，url=$url")
        val result = try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PROBE_USER_AGENT)
                .header("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val finalUrl = response.request.url.toString()
                val markerText = (finalUrl + "\n" + body.take(PROBE_BODY_LIMIT)).lowercase()
                val status = when {
                    isRegionBlocked(markerText) -> SourceProbeStatus.RegionBlocked
                    isAccessBanned(markerText) -> SourceProbeStatus.AccessBanned
                    isCloudflareChallenge(markerText) -> SourceProbeStatus.CloudflareChallenge
                    response.code in 200..399 -> SourceProbeStatus.Reachable
                    else -> SourceProbeStatus.NetworkError
                }
                SourceProbeResult(status = status, httpCode = response.code, finalUrl = finalUrl)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SourceProbeResult(
                status = SourceProbeStatus.NetworkError,
                detail = error.message ?: error::class.java.simpleName
            )
        }
        logger?.invoke("来源连通性测试结束：${source.name}，status=${result.status}")
        result
    }

    private fun isRegionBlocked(value: String): Boolean =
        value.contains("not-available-in-your-region") ||
            value.contains("not available in your region") ||
            value.contains("地域制限")

    private fun isAccessBanned(value: String): Boolean =
        value.contains("has banned your access") ||
            value.contains("禁止了你的访问") ||
            value.contains("禁止了你的訪問")

    private fun isCloudflareChallenge(value: String): Boolean =
        value.contains("cf-chl") ||
            value.contains("cloudflare") && (value.contains("challenge") || value.contains("just a moment")) ||
            value.contains("turnstile")

    private companion object {
        const val PROBE_BODY_LIMIT = 20_000
        const val PROBE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    }
}

enum class SourceProbeStatus {
    Reachable,
    RegionBlocked,
    AccessBanned,
    CloudflareChallenge,
    NetworkError
}

data class SourceProbeResult(
    val status: SourceProbeStatus,
    val httpCode: Int = 0,
    val finalUrl: String = "",
    val detail: String = ""
)
