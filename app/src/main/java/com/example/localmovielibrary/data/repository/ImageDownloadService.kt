package com.example.localmovielibrary.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random

class ImageDownloadService(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val retryCountProvider: () -> Int,
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun downloadImageBytes(url: String, referer: String? = null): ByteArray = withContext(ioDispatcher) {
        var lastError: Throwable? = null
        val retryCount = retryCountProvider().coerceAtLeast(1)
        repeat(retryCount) { attempt ->
            val request = buildRequest(url, referer)
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("图片下载失败 HTTP ${response.code}: $url")
                    val bytes = response.body?.bytes() ?: error("图片响应为空：$url")
                    validateImageBytes(url, bytes)
                    return@withContext bytes
                }
            }.onFailure { error ->
                lastError = error
                logger("图片下载第 ${attempt + 1} 次失败：${error.message ?: error::class.java.simpleName}")
                if (attempt < retryCount - 1) {
                    val delayMillis = Random.nextLong(1_000L, 2_001L)
                    logger("等待 ${delayMillis}ms 后重试图片下载")
                    delay(delayMillis)
                }
            }
        }
        throw lastError ?: IllegalStateException("图片下载失败：$url")
    }

    /*
     * ================================================================================
     * 步骤1：过滤 DMM/FANZA 占位图
     * ================================================================================
     * 目标：HTTP 200 但内容为 now_printing 的响应不能写入演员头像缓存。
     * 数据源：官方 CDN 返回的图片字节和请求地址。
     * 操作：
     * 1) 仅对 DMM/FANZA 官方 CDN 施加最小体积门槛。
     * 2) 让调用方继续尝试备用 CDN 或其它头像源。
     */
    private fun validateImageBytes(url: String, bytes: ByteArray) {
        if (bytes.isEmpty()) error("图片响应为空：$url")
        if (isDmmImageUrl(url) && bytes.size < MIN_DMM_IMAGE_BYTES) {
            error("DMM/FANZA 返回占位图：${bytes.size}B")
        }
    }

    private fun isDmmImageUrl(url: String): Boolean =
        url.contains("pics.dmm.co.jp", ignoreCase = true) ||
            url.contains("awsimgsrc.dmm.co.jp", ignoreCase = true)

    private fun buildRequest(url: String, referer: String?): Request {
        val builder = Request.Builder().url(url)
        if ("awsimgsrc.dmm.co.jp" !in url) {
            builder
                .header("User-Agent", IMAGE_USER_AGENT)
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        }
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        return builder.build()
    }

    private companion object {
        const val IMAGE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val MIN_DMM_IMAGE_BYTES = 3_000
    }
}
