package com.example.localmovielibrary.scraper

import com.example.localmovielibrary.util.cleanMetadataText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal const val DMM_EMPTY_SEARCH_RETRY_COUNT = 3
internal const val DMM_EMPTY_SEARCH_RETRY_DELAY_MS = 750L

internal fun shouldRetryDmmEmptySearchResult(attempt: Int): Boolean =
    attempt < DMM_EMPTY_SEARCH_RETRY_COUNT - 1

/*
 * ================================================================================
 * 步骤1：转换 DMM/FANZA 片长
 * ================================================================================
 * 目标：把 DMM2 详情返回的秒数转换为 NFO 使用的分钟数。
 * 数据源：PPVContent.duration。
 * 操作：
 * 1) 正数按分钟四舍五入。
 * 2) 缺失或非正数保持空值，交给官方旧 DMM 字段补缺。
 */
internal fun dmmDurationToRuntimeMinutes(durationSeconds: Int): String =
    durationSeconds.takeIf { it > 0 }
        ?.let { ((it + 30) / 60).toString() }
        .orEmpty()

class Dmm2Scraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: ((String) -> Unit)? = null,
    private val emptySearchRetryDelayMs: Long = DMM_EMPTY_SEARCH_RETRY_DELAY_MS
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Dmm2

    /** DMM/FANZA 演员资料，供头像补齐流程按姓名回查官方头像。 */
    suspend fun findActorImageByName(actorName: String): DmmActorImage? = withContext(ioDispatcher) {
        val query = actorName.trim()
        if (query.isBlank()) return@withContext null

        /*
         * ================================================================================
         * 步骤1：按演员姓名定位 DMM/FANZA 演员 ID
         * ================================================================================
         * 目标：不扫描 1.5 万条演员总表，直接复用番号搜索接口的演员索引。
         * 数据源：DMM/FANZA legacySearchPPV，queryWord 为当前演员名。
         * 操作：
         * 1) 搜索演员姓名关联的影片。
         * 2) 从影片演员列表中保留姓名完全匹配的演员 ID。
         */
        logger?.invoke("DMM/FANZA 演员名回查：$query")
        val contents = searchContents(fetchSearch(query))
        val ids = (0 until contents.length())
            .flatMap { index ->
                val actresses = contents.optJSONObject(index)?.optJSONArray("actresses") ?: return@flatMap emptyList()
                (0 until actresses.length()).mapNotNull { actressIndex ->
                    val actress = actresses.optJSONObject(actressIndex) ?: return@mapNotNull null
                    val id = actress.optString("id").trim()
                    val name = actress.optString("name").cleanText()
                    if (id.isNotBlank() && actorNamesHaveExactVariant(name, query)) id else null
                }
            }
            .distinct()
        if (ids.isEmpty()) {
            logger?.invoke("DMM/FANZA 演员名未命中：$query")
            return@withContext null
        }

        /*
         * ================================================================================
         * 步骤2：读取官方头像地址
         * ================================================================================
         * 目标：只返回 DMM/FANZA 的真实头像，不把 null 或占位地址交给下载层。
         * 数据源：DMM/FANZA actressesByIds GraphQL。
         * 操作：
         * 1) 批量查询候选演员 ID。
         * 2) 按姓名匹配并过滤空头像。
         */
        val details = fetchActressesByIds(ids)
        val result = details.firstOrNull { actress ->
            actorNamesHaveExactVariant(actress.name, query) && actress.imageUrl.isUsableDmmActorImage()
        }?.let { actress ->
            DmmActorImage(name = actress.name, imageUrl = actress.imageUrl)
        }
        logger?.invoke("DMM/FANZA 演员名回查完成：$query，命中=${result != null}")
        result
    }

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = normalizeNumber(number)
        val keyword = normalizeNumberForSearch(normalized)
        val searchJson = fetchSearchWithContent(keyword)
        val contents = searchContents(searchJson)
        logger?.invoke("DMM2 搜索返回：$keyword，结果 ${contents.length()} 条")
        if (contents.length() == 0) {
            /*
             * ================================================================================
             * 步骤2：搜索为空时直查标准内容 ID
             * ================================================================================
             * 目标：DMM/FANZA 搜索索引偶发返回空数组时，仍能读取标准内容 ID 对应的官方详情。
             * 数据源：normalizeDmmSearchKeyword 生成的标准内容 ID 和 ppvContent 详情接口。
             * 操作：
             * 1) 只使用完整厂牌和五位序号组成的内容 ID，不改用模糊搜索结果。
             * 2) 校验详情返回的 ID 与目标番号完全对应，避免把相似内容写入影片库。
             * 3) 直查失败后仍按原流程报告 DMM/FANZA 未命中。
             */
            logger?.invoke("DMM2 搜索为空，尝试标准内容 ID 直查：$keyword")
            val directInfo = try {
                // 2.1 直查标准内容 ID，并复用详情解析逻辑保留官方演员、简介和图片。
                val detailJson = fetchDetail(keyword)
                val searchItem = buildDirectSearchItem(keyword, detailJson)
                parseMovieInfo(normalized, searchItem, detailJson)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger?.invoke(
                    "DMM2 标准内容 ID 直查失败：$keyword，" +
                        (error.message ?: error::class.java.simpleName)
                )
                null
            }
            if (directInfo != null && directInfo.title.isNotBlank()) {
                logger?.invoke("DMM2 标准内容 ID 直查命中：$keyword")
                return@withContext directInfo
            }
            logger?.invoke("DMM2 标准内容 ID 直查未命中：$keyword")
            error("DMM2 没有搜索到结果：$normalized / $keyword")
        }
        logSearchContents(keyword, contents)

        val selected = selectBestSearchResult(contents, keyword)
        val contentId = selected.optString("id").trim()
        val matchScore = scoreSearchItem(selected, keyword)
        logger?.invoke(
            "DMM2 选中结果：keyword=$keyword, contentId=$contentId, " +
                "matchScore=$matchScore, title=${selected.optString("title").cleanText()}"
        )
        if (matchScore < EXACT_CATALOG_MATCH_SCORE) {
            error("DMM2 没有找到与番号完全一致的详情：$normalized")
        }
        if (contentId.isBlank()) error("DMM2 搜索结果没有 content id")

        val detailJson = fetchDetail(contentId)
        val info = parseMovieInfo(normalized, selected, detailJson)
        if (info.title.isBlank()) error("DMM2 没有解析到标题：$normalized")
        info
    }

    private fun buildDirectSearchItem(contentId: String, detailJson: JSONObject): JSONObject {
        val data = detailJson.optJSONObject("data") ?: error("DMM2 直查详情没有 data")
        val ppv = data.optJSONObject("ppvContent") ?: error("DMM2 直查详情没有 ppvContent")
        val returnedId = ppv.optString("id").trim()
        if (!isExactDmmContentId(returnedId, contentId)) {
            error("DMM2 直查详情番号不匹配：$contentId / $returnedId")
        }
        return JSONObject()
            .put("id", returnedId)
            .put("title", ppv.optString("title"))
            .put("deliveryStartAt", ppv.optString("deliveryStartDate"))
            .put("sampleMovie", ppv.optJSONObject("sampleMovie") ?: JSONObject())
            .put("review", data.optJSONObject("reviewSummary") ?: JSONObject())
    }

    private suspend fun fetchSearch(keyword: String): JSONObject {
        val variables = JSONObject()
            .put("limit", 20)
            .put("offset", 0)
            .put("floor", "AV")
            .put("sort", "SALES_RANK_SCORE")
            .put("queryWord", keyword)
            .put("filter", JSONObject())
            .put("facetLimit", 100)
            .put("excludeUndelivered", false)
        val payload = JSONObject()
            .put("operationName", "AvSearch")
            .put("query", SEARCH_QUERY)
            .put("variables", variables)
        val referer = "https://video.dmm.co.jp/av/list/".toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", keyword)
            .build()
            .toString()
        return postGraphql(payload, referer)
    }

    /*
     * ================================================================================
     * 步骤1：重试 DMM/FANZA 的空影片搜索结果
     * ================================================================================
     * 目标：GraphQL 已成功返回但 contents 短暂为空时，不误判为该番号未上架。
     * 数据源：DMM/FANZA legacySearchPPV 的同一严格番号查询。
     * 操作：
     * 1) 只重试影片番号搜索；演员姓名回查仍保持单次查询。
     * 2) 任一次获得非空 contents 立即返回，不重试详情或改用模糊番号。
     * 3) 最终仍为空才交给调用方报真实的未命中。
     */
    private suspend fun fetchSearchWithContent(keyword: String): JSONObject {
        var lastSearch: JSONObject? = null
        repeat(DMM_EMPTY_SEARCH_RETRY_COUNT) { attempt ->
            // 1.1 保留本次成功 HTTP 响应，最终空结果仍需按原逻辑报未命中。
            val search = fetchSearch(keyword)
            lastSearch = search
            if (searchContents(search).length() > 0) {
                logger?.invoke("DMM2 空结果重试结束：$keyword，attempt=${attempt + 1}")
                return search
            }
            logger?.invoke("DMM2 搜索暂时为空：$keyword，attempt=${attempt + 1}/$DMM_EMPTY_SEARCH_RETRY_COUNT")
            if (shouldRetryDmmEmptySearchResult(attempt) && emptySearchRetryDelayMs > 0) {
                // 1.2 不改变请求参数，只等待 DMM/FANZA 的短暂索引波动恢复。
                delay(emptySearchRetryDelayMs)
            }
        }
        return checkNotNull(lastSearch)
    }

    private fun searchContents(searchJson: JSONObject): JSONArray = searchJson
        .optJSONObject("data")
        ?.optJSONObject("legacySearchPPV")
        ?.optJSONObject("result")
        ?.optJSONArray("contents")
        ?: JSONArray()

    private suspend fun fetchDetail(contentId: String): JSONObject {
        val payload = JSONObject()
            .put("operationName", "Test")
            .put("query", DETAIL_QUERY)
            .put("variables", JSONObject().put("id", contentId))
        return postGraphql(payload, "https://video.dmm.co.jp/av/content/?id=$contentId")
    }

    private fun logSearchContents(keyword: String, contents: JSONArray) {
        val count = minOf(contents.length(), SEARCH_LOG_LIMIT)
        logger?.invoke("DMM2 搜索结果预览：$keyword，显示 $count / ${contents.length()} 条")
        repeat(count) { index ->
            val item = contents.optJSONObject(index) ?: return@repeat
            val id = item.optString("id").trim()
            val title = item.optString("title").cleanText()
            logger?.invoke("DMM2 搜索结果 #${index + 1}: id=$id, title=$title")
        }
    }

    private suspend fun postGraphql(payload: JSONObject, referer: String): JSONObject {
        var lastError: Throwable? = null
        repeat(GRAPHQL_RETRY_COUNT) { attempt ->
            runCatching {
                val request = Request.Builder()
                    .url(GRAPHQL_URL)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://video.dmm.co.jp")
                    .header("Referer", referer)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("DMM2 GraphQL 请求失败 HTTP ${response.code}: ${body.take(300)}")
                    }
                    return JSONObject(body)
                }
            }.onFailure { error ->
                lastError = error
                if (attempt < GRAPHQL_RETRY_COUNT - 1) delay(1500)
            }
        }
        throw RuntimeException("DMM2 GraphQL 请求连续失败：${lastError?.message ?: lastError?.javaClass?.simpleName}")
    }

    private fun selectBestSearchResult(contents: JSONArray, keyword: String): JSONObject {
        val items = (0 until contents.length()).mapNotNull { contents.optJSONObject(it) }
        return items.maxByOrNull { scoreSearchItem(it, keyword.lowercase(Locale.ROOT)) }
            ?: error("DMM2 搜索接口没有返回内容")
    }

    private fun scoreSearchItem(item: JSONObject, keyword: String): Int {
        val contentId = item.optString("id").lowercase(Locale.ROOT)
        val title = item.optString("title").lowercase(Locale.ROOT)
        var score = dmmContentIdMatchScore(contentId, keyword)
        val relaxed = keyword.replace(Regex("""0+(\d+)$"""), "$1")
        if (relaxed.isNotBlank() && relaxed in contentId) score += 30
        if (keyword in title) score += 20
        return score
    }

    private fun parseMovieInfo(number: String, searchItem: JSONObject, detailJson: JSONObject): ScrapedMovieInfo {
        val data = detailJson.optJSONObject("data") ?: JSONObject()
        val ppv = data.optJSONObject("ppvContent") ?: error("DMM2 详情接口没有返回 ppvContent")
        val review = data.optJSONObject("reviewSummary")

        val contentId = ppv.optString("id").ifBlank { searchItem.optString("id") }
        val title = ppv.optString("title").ifBlank { searchItem.optString("title") }.cleanText()
        val packageImage = ppv.optJSONObject("packageImage") ?: JSONObject()
        val thumb = packageImage.optString("largeUrl").ifBlank { packageImage.optString("mediumUrl") }.cleanText()
        val poster = packageImage.optString("mediumUrl")
            .ifBlank { buildPosterUrl(thumb) }
            .let { if (it == thumb) buildPosterUrl(thumb) else it }
            .cleanText()

        val release = parseChinaDate(
            ppv.optString("deliveryStartDate")
                .ifBlank { searchItem.optString("deliveryStartAt") }
        )
        val runtime = dmmDurationToRuntimeMinutes(ppv.optInt("duration", 0))
        val tags = ppv.optJSONArray("genres").namesFromObjects()
        val actors = ppv.optJSONArray("actresses").namesFromObjects()
        val actorImageUrls = ppv.optJSONArray("actresses").imageUrlsByName()
        val directors = ppv.optJSONArray("directors").namesFromObjects()
        val maker = ppv.optJSONObject("maker")?.optString("name").orEmpty().cleanText()
        val label = ppv.optJSONObject("label")?.optString("name").orEmpty().cleanText()
        val series = ppv.optJSONObject("series")?.optString("name").orEmpty().cleanText()
        val rating = review?.optString("average").orEmpty().cleanText()
            .ifBlank { searchItem.optJSONObject("review")?.optString("average").orEmpty().cleanText() }
        val sampleMovie = searchItem.optJSONObject("sampleMovie")
            ?: ppv.optJSONObject("sampleMovie")
            ?: JSONObject()
        val trailer = sampleMovie.optString("mp4Url").ifBlank { sampleMovie.optString("hlsUrl") }.cleanText()
        val plot = ppv.optString("description").cleanText()
            .ifBlank {
                ppv.optJSONArray("announcements")
                    ?.let { announcements ->
                        (0 until announcements.length())
                            .mapNotNull { announcements.optJSONObject(it)?.optString("body")?.cleanText() }
                            .firstOrNull { it.isNotBlank() }
                    }
                    .orEmpty()
            }

        return ScrapedMovieInfo(
            number = number.uppercase(Locale.ROOT),
            title = title.ifBlank { number.uppercase(Locale.ROOT) },
            originalTitle = title.ifBlank { number.uppercase(Locale.ROOT) },
            plot = plot,
            outline = plot,
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            premiered = release,
            runtime = runtime,
            studio = maker,
            publisher = label,
            series = series,
            directors = directors,
            actors = actors,
            actorImageUrls = actorImageUrls,
            genres = tags,
            tags = tags,
            rating = rating,
            trailer = trailer,
            website = buildVideoContentUrl(contentId),
            source = "dmm2",
            thumbUrl = thumb,
            posterUrl = poster
        )
    }

    private fun JSONArray?.namesFromObjects(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { optJSONObject(it)?.optString("name")?.cleanText() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun JSONArray?.imageUrlsByName(): Map<String, String> {
        if (this == null) return emptyMap()
        return (0 until length())
            .mapNotNull { index ->
                val actor = optJSONObject(index) ?: return@mapNotNull null
                val name = actor.optString("name").cleanText()
                val imageUrl = actor.optString("imageUrl", "")
                    .cleanText()
                    .takeUnless { it.equals("null", ignoreCase = true) }
                    .orEmpty()
                if (name.isBlank() || imageUrl.isBlank()) null else name to imageUrl
            }
            .distinctBy { it.first }
            .toMap()
    }

    private suspend fun fetchActressesByIds(ids: List<String>): List<DmmActorRecord> {
        val payload = JSONObject()
            .put("operationName", "ActressByIds")
            .put("query", ACTRESS_BY_IDS_QUERY)
            .put("variables", JSONObject().put("ids", JSONArray(ids)))
        val response = postGraphql(payload, "https://video.dmm.co.jp/av/")
        val actresses = response
            .optJSONObject("data")
            ?.optJSONArray("actressesByIds")
            ?: JSONArray()
        return (0 until actresses.length()).mapNotNull { index ->
            val actress = actresses.optJSONObject(index) ?: return@mapNotNull null
            DmmActorRecord(
                id = actress.optString("id").trim(),
                name = actress.optString("name").cleanText(),
                imageUrl = actress.optString("imageUrl", "").cleanText()
                    .takeUnless { it.equals("null", ignoreCase = true) }
                    .orEmpty()
            )
        }
    }

    private fun normalizeNumber(number: String): String {
        val match = Regex("""(?i)([a-z]{2,10})[-_ ]?(\d{2,6})""").find(number)
            ?: return number.trim().uppercase(Locale.ROOT)
        return "${match.groupValues[1].uppercase(Locale.ROOT)}-${match.groupValues[2]}"
    }

    private fun normalizeNumberForSearch(number: String): String {
        return normalizeDmmSearchKeyword(number)
    }

    private fun buildPosterUrl(thumbUrl: String): String =
        thumbUrl.replace(Regex("""pl(\.(jpg|jpeg|png|webp))$""", RegexOption.IGNORE_CASE), "ps\$1")

    private fun buildVideoContentUrl(contentId: String): String =
        "https://video.dmm.co.jp/av/content/?id=$contentId&i3_ref=search&i3_ord=1&i3_pst=1&dmmref=video_search"

    private fun parseChinaDate(value: String): String {
        val source = value.cleanText()
        if (source.isBlank()) return ""
        val date = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(source)?.value ?: return ""
        if (!source.contains("T00:00:00+09:00", ignoreCase = true)) return date
        return runCatching {
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            calendar.time = formatter.parse(date) ?: return@runCatching date
            calendar.add(Calendar.DAY_OF_MONTH, -1)
            formatter.format(calendar.time)
        }.getOrDefault(date)
    }

    private fun String.cleanText(): String = cleanMetadataText()

    private fun String.isUsableDmmActorImage(): Boolean =
        isNotBlank() && !contains("now-printing", ignoreCase = true) &&
            !contains("no-image", ignoreCase = true) &&
            !contains("placeholder", ignoreCase = true)

    private companion object {
        const val GRAPHQL_URL = "https://api.video.dmm.co.jp/graphql"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val GRAPHQL_RETRY_COUNT = 3
        const val SEARCH_LOG_LIMIT = 10
        const val EXACT_CATALOG_MATCH_SCORE = 900

        const val ACTRESS_BY_IDS_QUERY = """
query ActressByIds(${'$'}ids: [ID!]!) {
  actressesByIds(ids: ${'$'}ids) {
    id
    name
    imageUrl
  }
}
"""

        const val SEARCH_QUERY = """
query AvSearch(${'$'}limit: Int!, ${'$'}offset: Int, ${'$'}floor: PPVFloor, ${'$'}sort: ContentSearchPPVSort!, ${'$'}queryWord: String, ${'$'}filter: ContentSearchPPVFilterInput, ${'$'}facetLimit: Int!, ${'$'}excludeUndelivered: Boolean!) {
  legacySearchPPV(limit: ${'$'}limit, offset: ${'$'}offset, floor: ${'$'}floor, sort: ${'$'}sort, queryWord: ${'$'}queryWord, filter: ${'$'}filter, facetLimit: ${'$'}facetLimit, includeExplicit: true, excludeUndelivered: ${'$'}excludeUndelivered) {
    result {
      contents {
        id
        title
        floor
        contentType
        packageImage { mediumUrl largeUrl }
        sampleImages { number largeUrl }
        releaseStatus
        review { average count }
        deliveryStartAt
        actresses { id name }
        maker { id name }
      }
      pageInfo { totalCount limit offset hasNext }
    }
  }
}

"""

        const val DETAIL_QUERY = """
query Test(${'$'}id: ID!) {
  ppvContent(id: ${'$'}id) {
    id
    title
    description
    duration
    deliveryStartDate
    notices
    announcements { body }
    floor
    contentType
    releaseStatus
    isAllowForeign
    packageImage { mediumUrl largeUrl }
    sampleImages { number imageUrl largeImageUrl }
    maker { id name }
    label { id name }
    series { id name }
    directors { id name }
    genres { id name }
    actresses { id name imageUrl }
  }
  reviewSummary(contentId: ${'$'}id) {
    average
    total
    withCommentTotal
  }
}
"""
    }
}

data class DmmActorImage(
    val name: String,
    val imageUrl: String
)

private data class DmmActorRecord(
    val id: String,
    val name: String,
    val imageUrl: String
)

/*
 * ================================================================================
 * 步骤1：标准化 DMM/FANZA 内容检索码
 * ================================================================================
 * 目标：把用户输入的厂牌番号转换成 DMM 内容 ID 中使用的五位数字编号。
 * 数据源：影片文件名或媒体库记录中的标准番号。
 * 操作：
 * 1) 保留厂牌前缀并统一为小写。
 * 2) 把数字部分补齐为五位，供 DMM2 和旧 DMM 搜索共用。
 */
internal fun normalizeDmmSearchKeyword(number: String): String {
    val input = number.trim().replace("_", "-")
    val match = Regex("""(?i)^([a-z]+)-?(\d+)$""").find(input)
        ?: return input.lowercase(Locale.ROOT).replace("-", "")
    return match.groupValues[1].lowercase(Locale.ROOT) + match.groupValues[2].toInt().toString().padStart(5, '0')
}

/*
 * ================================================================================
 * 步骤2：按完整番号给 DMM 内容 ID 排序
 * ================================================================================
 * 目标：优先选择相同厂牌和相同序号，避免 NAMH-022 命中 HNAMH-022。
 * 数据源：DMM/FANZA 搜索接口返回的 content id。
 * 操作：
 * 1) 识别内容 ID 中有非字母边界的完整番号。
 * 2) 没有完整匹配时保留旧的后缀和子串回退，兼容历史厂牌别名。
 */
internal fun dmmContentIdMatchScore(contentId: String, keyword: String): Int {
    val normalizedContentId = contentId.trim().lowercase(Locale.ROOT)
    val normalizedKeyword = keyword.trim().lowercase(Locale.ROOT)
    if (normalizedContentId.isBlank() || normalizedKeyword.isBlank()) return 0

    val exactCatalogCode = Regex(
        """(?:^|[^a-z])${Regex.escape(normalizedKeyword)}(?:$|[^a-z])""",
        RegexOption.IGNORE_CASE
    )
    var score = when {
        normalizedContentId == normalizedKeyword -> 1_000
        exactCatalogCode.containsMatchIn(normalizedContentId) -> 900
        normalizedContentId.endsWith(normalizedKeyword) -> 200
        normalizedKeyword in normalizedContentId -> 150
        else -> 0
    }
    listOf("tp", "tapestry", "tokuten", "goods", "set", "limited").forEach { bad ->
        if (bad in normalizedContentId) score -= 30
    }
    return score
}

internal fun isExactDmmContentId(contentId: String, expectedId: String): Boolean =
    contentId.trim().equals(expectedId.trim(), ignoreCase = true)

/*
 * ================================================================================
 * 步骤1：准备 DMM/FANZA 演员头像候选地址
 * ================================================================================
 * 目标：同一张官方头像同时覆盖 DMM 旧 CDN 和 FANZA 新 CDN。
 * 数据源：DMM2 GraphQL 返回的 imageUrl。
 * 操作：
 * 1) 保留 GraphQL 原始地址作为首选。
 * 2) 在两个已知官方 CDN 之间生成一个备用地址。
 * 3) 去重并忽略空地址，交给下载层做内容校验。
 */
internal fun dmmFanzaActorImageCandidates(url: String): List<String> {
    val original = url.trim()
    if (original.isBlank()) return emptyList()
    val alternate = when {
        original.contains("https://awsimgsrc.dmm.co.jp/pics_dig/mono/actjpgs/", ignoreCase = true) ->
            original.replace(
                "https://awsimgsrc.dmm.co.jp/pics_dig/mono/actjpgs/",
                "https://pics.dmm.co.jp/mono/actjpgs/",
                ignoreCase = true
            )
        original.contains("https://pics.dmm.co.jp/mono/actjpgs/", ignoreCase = true) ->
            original.replace(
                "https://pics.dmm.co.jp/mono/actjpgs/",
                "https://awsimgsrc.dmm.co.jp/pics_dig/mono/actjpgs/",
                ignoreCase = true
            )
        else -> null
    }
    return listOfNotNull(original, alternate).distinct()
}
