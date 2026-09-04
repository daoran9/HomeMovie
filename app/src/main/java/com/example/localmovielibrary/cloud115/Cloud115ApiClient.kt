package com.example.localmovielibrary.cloud115

import android.util.Log
import com.example.localmovielibrary.playback.USER_AGENT
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class Cloud115ApiClient(
    private val cookieProvider: Cloud115CookieProvider,
    private val httpClient: OkHttpClient = OkHttpClient()
) : Cloud115Client {
    @Volatile
    private var filesEndpoint = FilesEndpoint.Aps

    override suspend fun listFiles(cid: Long): List<Cloud115FileItem> = withContext(Dispatchers.IO) {
        val cookies = cookieProvider.loadCookies()
            ?: error("115 Cookie 未配置，请先到设置页填写 Cookie")
        val result = mutableListOf<Cloud115FileItem>()
        val seenPageKeys = mutableSetOf<String>()
        var offset = 0
        while (true) {
            Log.i(TAG, "读取115目录分页，cid=$cid，offset=$offset")
            val page = fetchFilesPageWithRetry(cid, offset, cookies)
            result += page.items
            if (page.rawCount < PAGE_SIZE) break

            val pageKey = page.items.joinToString("|") { item ->
                "${item.fid ?: item.cid}:${item.pickcode}:${item.name}"
            }
            if (!seenPageKeys.add(pageKey)) {
                Log.i(TAG, "115目录分页内容重复，停止继续读取，cid=$cid，offset=$offset")
                break
            }
            offset += PAGE_SIZE
        }
        Log.i(TAG, "115目录读取完成，cid=$cid，条数=${result.size}")
        result
    }

    /*
     * ================================================================================
     * 步骤2：退避重试被115拒绝的目录请求
     * ================================================================================
     * 目标：降低连续递归读取触发 405 临时拒绝时的失败概率。
     * 数据源：115 files 接口响应状态。
     * 操作：
     * 1) 只对 HTTP 405 做有限次数重试。
     * 2) 每次重试增加等待时间，避免继续突发请求。
     */
    private suspend fun fetchFilesPageWithRetry(
        cid: Long,
        offset: Int,
        cookies: String
    ): FilesPage {
        var attempt = 0
        while (true) {
            try {
                return fetchFilesPage(cid, offset, cookies)
            } catch (error: FilesRequestException) {
                if (error.code != 405 || attempt >= MAX_405_RETRIES) throw error
                val waitMs = RETRY_DELAYS_MS[attempt]
                attempt += 1
                Log.i(TAG, "115目录请求返回405，准备退避重试，cid=$cid，offset=$offset，等待=${waitMs}ms")
                delay(waitMs)
            }
        }
    }

    /*
     * ================================================================================
     * 步骤1：读取115目录分页
     * ================================================================================
     * 目标：把单页 JSON 转成统一的文件项列表。
     * 数据源：115 files 接口当前 offset 的响应。
     * 操作：
     * 1) 校验 HTTP 状态和响应结构。
     * 2) 转换文件名、目录标记、pickcode 和时间字段。
     */
    private fun fetchFilesPage(cid: Long, offset: Int, cookies: String): FilesPage {
        val endpoint = filesEndpoint
        return try {
            fetchFilesPageFromEndpoint(endpoint, cid, offset, cookies)
        } catch (error: FilesRequestException) {
            if (!error.shouldUseFallback) throw error

            val fallbackEndpoint = when (endpoint) {
                FilesEndpoint.WebApi -> FilesEndpoint.Aps
                FilesEndpoint.Aps -> FilesEndpoint.WebApi
            }

            /*
             * ================================================================================
             * 步骤3：切换115目录备用接口
             * ================================================================================
             * 目标：当前目录接口被 WAF 返回 405 时继续读取目录。
             * 数据源：webapi.115.com/files 和 aps.115.com/natsort/files.php。
             * 操作：
             * 1) 只在当前接口出现 405 或明确的未登录状态时切换。
             * 2) 切换成功后复用可用接口，避免后续递归请求重复撞上拦截。
             */
            Log.i(TAG, "115目录接口不可用，切换备用接口，from=$endpoint，to=$fallbackEndpoint，cid=$cid，offset=$offset，原因=${error.message}")
            runCatching {
                fetchFilesPageFromEndpoint(fallbackEndpoint, cid, offset, cookies)
            }.onSuccess {
                filesEndpoint = fallbackEndpoint
                Log.i(TAG, "115目录接口切换完成，endpoint=$fallbackEndpoint，cid=$cid，offset=$offset")
            }.getOrElse { fallbackError ->
                Log.i(TAG, "115备用目录接口也失败，endpoint=$fallbackEndpoint，cid=$cid，offset=$offset，原因=${fallbackError.message}")
                throw error
            }
        }
    }

    private fun fetchFilesPageFromEndpoint(
        endpoint: FilesEndpoint,
        cid: Long,
        offset: Int,
        cookies: String
    ): FilesPage {
        val request = Request.Builder()
            .url(endpoint.url(cid, offset))
            .get()
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.i(TAG, "115目录请求失败，cid=$cid，offset=$offset，http=${response.code}，body=${raw.take(200)}")
                throw FilesRequestException(
                    code = response.code,
                    message = "115 目录读取失败：HTTP ${response.code}",
                    shouldUseFallback = response.code == 405
                )
            }
            val json = JSONObject(raw)
            if (json.has("state") && !json.optBoolean("state", false)) {
                val message = json.optString("error")
                    .takeIf { it.isNotBlank() }
                    ?: json.optString("message")
                        .takeIf { it.isNotBlank() }
                    ?: "115 目录接口返回失败"
                Log.i(TAG, "115目录接口返回失败，cid=$cid，offset=$offset，message=$message")
                throw FilesRequestException(
                    code = response.code,
                    message = message,
                    shouldUseFallback = true
                )
            }
            val data = json.optJSONArray("data") ?: error("115 目录响应为空")
            val items = buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val name = item.optString("n").takeIf { it.isNotBlank() } ?: continue
                    val fid = item.optString("fid").takeIf { it.isNotBlank() }?.toLongOrNull()
                    val cidValue = item.optString("cid").takeIf { it.isNotBlank() }?.toLongOrNull()
                    add(
                        Cloud115FileItem(
                            name = name,
                            cid = cidValue,
                            fid = fid,
                            pickcode = item.optString("pc").takeIf { it.isNotBlank() },
                            size = item.optString("s").toLongOrNull(),
                            modifiedAt = item.optTimestamp(
                                "t",
                                "user_ptime",
                                "ptime",
                                "pt",
                                "te",
                                "tu",
                                "tp",
                                "mtime",
                                "utime"
                            ),
                            isDirectory = fid == null
                        )
                    )
                }
            }
            FilesPage(items = items, rawCount = data.length())
        }
    }

    override suspend fun fetchDirectUrl(pickcode: String): String = withContext(Dispatchers.IO) {
        val cookies = cookieProvider.loadCookies()
            ?: error("115 Cookie 未配置，请先到设置页填写 Cookie")
        val payload = JSONObject().put("pickcode", pickcode).toString()
        val encryptedPayload = P115Cipher.encrypt(payload)
        val body = FormBody.Builder()
            .add("data", encryptedPayload)
            .build()
        val request = Request.Builder()
            .url(DOWNLOAD_URL)
            .post(body)
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("115 获取直链失败：HTTP ${response.code}")
            }
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            if (!json.optBoolean("state", false)) {
                error(json.optString("message", "115 获取直链失败"))
            }
            val encryptedData = json.optString("data")
            if (encryptedData.isBlank()) error("115 响应缺少直链数据")
            val data = JSONObject(P115Cipher.decrypt(encryptedData))
            extractDirectUrl(data) ?: error("115 响应中没有可播放直链")
        }
    }

    override suspend fun downloadBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("115 图片下载失败：HTTP ${response.code}")
            }
            response.body?.bytes() ?: error("115 图片响应为空")
        }
    }

    private fun extractDirectUrl(data: JSONObject): String? {
        data.optString("url").takeIf { it.startsWith("http", ignoreCase = true) }?.let { return it }
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = data.optJSONObject(key) ?: continue
            val urlObject = item.optJSONObject("url")
            urlObject?.optString("url")?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { return it }
            item.optString("url").takeIf { it.startsWith("http", ignoreCase = true) }?.let { return it }
        }
        return null
    }

    private fun JSONObject.optTimestamp(vararg keys: String): Long? {
        keys.forEach { key ->
            val value = optString(key).takeIf { it.isNotBlank() && it != "0" } ?: return@forEach
            value.toLongOrNull()?.let { raw ->
                return if (raw < 10_000_000_000L) raw * 1000L else raw
            }
        }
        return null
    }

    private data class FilesPage(
        val items: List<Cloud115FileItem>,
        val rawCount: Int
    )

    private enum class FilesEndpoint {
        WebApi,
        Aps;

        fun url(cid: Long, offset: Int): String = when (this) {
            WebApi -> "$FILES_URL?aid=1&cid=$cid&o=user_ptime&asc=0&offset=$offset&show_dir=1&limit=$PAGE_SIZE&format=json"
            Aps -> "$APS_FILES_URL?aid=1&cid=$cid&o=file_name&asc=1&offset=$offset&show_dir=1&limit=$PAGE_SIZE&code=&scid=&snap=0&natsort=1&record_open_time=1&source=&format=json&fc_mix=0&type=&star=&is_share=&suffix=&custom_order="
        }
    }

    /*
     * ================================================================================
     * 步骤1：按 pickcode 查询 115 视频信息
     * ================================================================================
     * 目标：恢复历史 STRM 损坏后丢失的原文件名和文件大小。
     * 数据源：115 files/video 接口返回的 file_name、file_size。
     * 操作：
     * 1) 使用当前 115 Cookie 请求单个视频元信息。
     * 2) 只返回详情页区分播放源所需的名称和大小。
     */
    override suspend fun fetchVideoInfo(pickcode: String): Cloud115VideoInfo = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始读取115视频信息，pickcode=$pickcode")
        val cookies = cookieProvider.loadCookies()
            ?: error("115 Cookie 未配置，请先到设置页填写 Cookie")
        val request = Request.Builder()
            .url("$VIDEO_INFO_URL?pickcode=$pickcode&share_id=0&local=1")
            .get()
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("115 视频信息读取失败：HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.has("state") && !json.optBoolean("state", false)) {
                error(json.optString("error").ifBlank { json.optString("message", "115 视频信息读取失败") })
            }
            val data = json.optJSONObject("data") ?: json
            val name = data.optString("file_name")
                .ifBlank { data.optString("n") }
                .takeIf { it.isNotBlank() }
                ?: error("115 视频信息缺少文件名")
            val size = data.optString("file_size")
                .ifBlank { data.optString("s") }
                .toLongOrNull()
            Log.i(TAG, "115视频信息读取完成，pickcode=$pickcode，size=${size ?: -1}")
            Cloud115VideoInfo(name = name, sizeBytes = size)
        }
    }

    private class FilesRequestException(
        val code: Int,
        message: String,
        val shouldUseFallback: Boolean
    ) : IOException(message)

    private companion object {
        const val TAG = "Cloud115ApiClient"
        const val PAGE_SIZE = 1000
        const val MAX_405_RETRIES = 2
        val RETRY_DELAYS_MS = longArrayOf(3_000L, 10_000L)
        const val FILES_URL = "https://webapi.115.com/files"
        const val APS_FILES_URL = "https://aps.115.com/natsort/files.php"
        const val VIDEO_INFO_URL = "https://webapi.115.com/files/video"
        const val DOWNLOAD_URL = "https://proapi.115.com/app/chrome/downurl"
    }
}
