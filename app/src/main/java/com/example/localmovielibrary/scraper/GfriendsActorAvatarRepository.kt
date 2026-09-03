package com.example.localmovielibrary.scraper

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Fusion 使用的 gfriends 演员头像库的 Android 端读取器。 */
class GfriendsActorAvatarRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String) -> Unit = {}
) {
    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
    private val loadMutex = Mutex()
    private var imageMap: Map<String, String>? = null

    /*
     * ================================================================================
     * 步骤1：查询 gfriends 演员头像
     * ================================================================================
     * 目标：按演员名返回 Fusion 共用头像库中的图片地址。
     * 数据源：本地 Filetree 缓存或 gfriends CDN。
     * 操作：
     * 1) 首次查询时加载并解析头像索引。
     * 2) 只用统一姓名键匹配日文、英文别名和多余空格。
     */
    suspend fun findAvatar(actorName: String): String? = withContext(ioDispatcher) {
        logger("开始查询 gfriends 演员头像：$actorName")
        val nameKeys = actorNameVariants(actorName)
        if (nameKeys.isEmpty()) {
            logger("gfriends 演员头像查询完成：空演员名")
            return@withContext null
        }
        val index = ensureLoaded()
        val result = nameKeys.firstNotNullOfOrNull { index[it] }
        logger("gfriends 演员头像查询完成：$actorName，命中=${result != null}")
        result
    }

    /*
     * ================================================================================
     * 步骤2：加载头像索引
     * ================================================================================
     * 目标：复用本地缓存，避免每部影片重复下载 6 MB 级别的 Filetree.json。
     * 数据源：应用私有缓存文件和 gfriends CDN。
     * 操作：
     * 1) 并发请求合并为一次加载。
     * 2) 网络失败时优先使用已有缓存。
     */
    private suspend fun ensureLoaded(): Map<String, String> {
        imageMap?.let { return it }
        return loadMutex.withLock {
            imageMap?.let { return@withLock it }
            val cachedText = readCacheIfFresh()
            val text = if (cachedText != null) {
                logger("使用本地 gfriends 头像索引缓存")
                cachedText
            } else {
                runCatching { fetchFiletree() }
                    .onSuccess { body -> writeCache(body) }
                    .onFailure { error -> logger("gfriends 头像索引下载失败：${error.message ?: error::class.java.simpleName}") }
                    .getOrElse {
                        readCacheIfPresent()
                            ?: throw it
                    }
            }
            val parsed = parseFiletree(text)
            imageMap = parsed
            logger("gfriends 头像索引加载完成：${parsed.size} 个姓名")
            parsed
        }
    }

    private fun fetchFiletree(): String {
        val request = Request.Builder()
            .url(FILETREE_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("gfriends Filetree 请求失败 HTTP ${response.code}")
            response.body?.string() ?: error("gfriends Filetree 响应为空")
        }
    }

    private fun parseFiletree(json: String): Map<String, String> = parseGfriendsFiletreeJson(json)

    private fun readCacheIfFresh(): String? {
        if (!cacheFile.isFile || cacheFile.length() == 0L) return null
        val age = System.currentTimeMillis() - cacheFile.lastModified()
        return if (age in 0..CACHE_MAX_AGE_MS) cacheFile.readText(Charsets.UTF_8) else null
    }

    private fun readCacheIfPresent(): String? =
        cacheFile.takeIf { it.isFile && it.length() > 0L }?.readText(Charsets.UTF_8)

    private fun writeCache(text: String) {
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(text, Charsets.UTF_8)
    }

    private companion object {
        const val BASE_URL = "https://fastly.jsdelivr.net/gh/gfriends/gfriends@latest"
        const val FILETREE_URL = "$BASE_URL/Filetree.json"
        const val CACHE_FILE_NAME = "gfriends_filetree.json"
        const val CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}

private fun buildGfriendsAvatarUrl(folder: String, storedPath: String): String {
    val path = storedPath.substringBefore('?').trim()
    val timestamp = storedPath.substringAfter("?t=", "").trim()
    val builder = "https://fastly.jsdelivr.net/gh/gfriends/gfriends@latest".toHttpUrl().newBuilder()
        .addPathSegment("Content")
        .addPathSegment(folder)
        .addPathSegment(path)
    if (timestamp.isNotBlank()) builder.addQueryParameter("t", timestamp)
    return builder.build().toString()
}

internal fun parseGfriendsFiletreeJson(json: String): Map<String, String> {
    val content = JSONObject(json).optJSONObject("Content") ?: return emptyMap()
    val result = LinkedHashMap<String, String>()
    val folders = content.keys()
    while (folders.hasNext()) {
        val folder = folders.next()
        val files = content.optJSONObject(folder) ?: continue
        val names = files.keys()
        while (names.hasNext()) {
            val actorFileName = names.next()
            val actorName = actorFileName
                .replace(Regex("""\.(?:jpe?g|png|webp)$""", RegexOption.IGNORE_CASE), "")
                .trim()
            val storedPath = files.optString(actorFileName).trim()
            if (actorName.isBlank() || storedPath.isBlank()) continue
            val avatarUrl = buildGfriendsAvatarUrl(folder, storedPath)
            actorNameVariants(actorName)
                .filter { it.isNotBlank() }
                .forEach { key ->
                    if (!result.containsKey(key)) result[key] = avatarUrl
                }
        }
    }
    return result
}
