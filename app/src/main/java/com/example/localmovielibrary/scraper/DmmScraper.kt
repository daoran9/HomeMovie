package com.example.localmovielibrary.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class DmmScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: ((String) -> Unit)? = null
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Dmm

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = number.uppercase()
        val detailUrl = search(normalized)
        val html = fetch(detailUrl)
        parseDetail(html, detailUrl, normalized)
    }

    private fun search(number: String): String {
        logger?.invoke("DMM 全部搜索开始：number=$number")
        val searchDestinations = mutableListOf<String>()
        for ((requestName, url) in dmmWebSearchRequests(number)) {
            var destination = "unknown"
            val html = fetch(url) { finalUrl ->
                destination = dmmResponseDestination(finalUrl)
                searchDestinations += "$requestName=$destination"
            }
            val link = selectDetailUrl(html, number)
            if (!link.isNullOrBlank()) {
                logger?.invoke(
                    "DMM 全部搜索命中：number=$number, request=$requestName, destination=$destination"
                )
                return link
            }
        }
        /*
         * ================================================================================
         * 步骤2：按官方商品 CID 直查
         * ================================================================================
         * 目标：DMM 搜索索引不返回旧 DVD 商品时，仍能读取官方详情页剧情。
         * 数据源：DMM video 商品详情 URL 和当前番号的 CID 候选。
         * 操作：
         * 1) 依次尝试数字前缀、原始序号和 re 再版后缀。
         * 2) 只接受页面主体身份字段与当前番号匹配的详情页。
         * 3) 找不到官方页才返回搜索失败，交给外部来源分支。
         */
        logger?.invoke(
            "DMM 全部搜索未命中，开始 CID 直查：number=$number, " +
                "destinations=${searchDestinations.joinToString().ifBlank { "none" }}"
        )
        var successfulResponses = 0
        var identifiedResponses = 0
        val directDestinations = linkedSetOf<String>()
        dmmDirectContentIds(number).forEach { contentId ->
            val url = dmmDirectDetailUrl(contentId)
            val html = runCatching {
                fetch(url) { finalUrl -> directDestinations += dmmResponseDestination(finalUrl) }
            }.getOrNull() ?: return@forEach
            successfulResponses += 1
            val identityIds = dmmDirectDetailIdentityIds(html)
            if (identityIds.isNotEmpty()) identifiedResponses += 1
            val matched = identityIds.any { identityId ->
                dmmContentIdMatchScore(identityId, normalizeDmmSearchKeyword(number)) >= 850
            }
            if (matched) {
                logger?.invoke(
                    "DMM CID 直查命中：number=$number, requestedCid=$contentId, " +
                        "identityIds=${identityIds.joinToString()}"
                )
                return url
            }
        }
        logger?.invoke(
            "DMM CID 直查结束：number=$number, successfulResponses=$successfulResponses, " +
                "identifiedResponses=$identifiedResponses, destinations=${directDestinations.joinToString().ifBlank { "none" }}, " +
                "matched=false"
        )
        error("DMM 没有搜索到详情页：$number")
    }

    /*
     * ================================================================================
     * 步骤1：从 DMM“全部”搜索页选择同番号详情页
     * ================================================================================
     * 目标：多个分类结果同时出现时，只接受同厂牌同序号的普通视频或 DVD 商品。
     * 数据源：DMM“全部”搜索页中的真实商品链接。
     * 操作：
     * 1) 提取普通视频和 DVD 详情链接，明确排除 TV Plus 和非 DMM 主机。
     * 2) 先按完整番号评分，再优先选择当前 video 商品入口。
     */
    internal fun selectDetailUrl(html: String, number: String): String? {
        val keyword = normalizeDmmSearchKeyword(number)
        val cidPattern = Regex("""(?:cid=|[?&]id=)([^/?&"']+)""", RegexOption.IGNORE_CASE)
        return Regex(
            """<a[^>]+href=["']([^"']*(?:/detail/=/cid=|/av/content/\?id=)[^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
            .findAll(html)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)?.takeIf { link -> link.isNotBlank() }
            }
            .map { rawLink ->
                val link = rawLink.replace("&amp;", "&")
                when {
                    link.startsWith("//") -> "https:$link"
                    link.startsWith("/av/content/") -> "https://video.dmm.co.jp$link"
                    link.startsWith("/") -> "https://www.dmm.co.jp$link"
                    else -> link
                }
            }
            .mapNotNull { detailUrl ->
                val contentId = cidPattern.find(detailUrl)?.groupValues?.getOrNull(1).orEmpty()
                val matchScore = dmmContentIdMatchScore(contentId, keyword)
                val routePriority = dmmDetailRoutePriority(detailUrl)
                detailUrl.takeIf { matchScore >= 850 && routePriority > 0 }
                    ?.let { Triple(it, matchScore, routePriority) }
            }
            .maxByOrNull { (_, matchScore, routePriority) -> matchScore * 10_000 + routePriority }
            ?.first
    }

    private fun parseDetail(html: String, detailUrl: String, number: String): ScrapedMovieInfo {
        val title = textByRegex(html, Regex("""<h1[^>]*(?:id=["']title["']|class=["'][^"']*(?:item|fn|bold)[^"']*["'])[^>]*>(.*?)</h1>""", RegexOption.IGNORE_CASE))
            .ifBlank { textByRegex(html, Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)) }
        if (title.isBlank()) error("DMM 详情页没有解析到标题")
        val thumb = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.replace("ps.jpg", "pl.jpg").orEmpty()
        val tags = linksNearLabel(html, "ジャンル")
        val actors = Regex("""<(?:span|td)[^>]+(?:id=["']performer["']|id=["']fn-visibleActor["'])[\s\S]*?</(?:span|td)>""", RegexOption.IGNORE_CASE)
            .find(html)?.value
            ?.let { linksIn(it) }
            .orEmpty()
            .ifEmpty { linksNearLabel(html, "出演者") }

        val release = textNearLabel(html, "発売日")
            .ifBlank { textNearLabel(html, "配信開始日") }
            .replace("/", "-")
        val runtime = textNearLabel(html, "収録時間").digitsOnly()

        return ScrapedMovieInfo(
            number = number,
            title = title,
            originalTitle = title,
            plot = textByRegex(html, Regex("""<div[^>]+class=["'][^"']*(?:mg-b20|clear|wrapper-detailContents)[^"']*["'][^>]*>\s*<p[^>]*>(.*?)</p>""", RegexOption.IGNORE_CASE)),
            premiered = release,
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            runtime = runtime,
            studio = linksNearLabel(html, "メーカー").firstOrNull().orEmpty(),
            publisher = linksNearLabel(html, "レーベル").firstOrNull().orEmpty(),
            series = linksNearLabel(html, "シリーズ").firstOrNull().orEmpty(),
            directors = linksNearLabel(html, "監督"),
            actors = actors,
            genres = tags,
            tags = tags,
            rating = textByRegex(html, Regex("""d-review__average[\s\S]*?<strong[^>]*>(.*?)</strong>""", RegexOption.IGNORE_CASE)),
            trailer = Regex("""video_url["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty(),
            website = detailUrl,
            source = "dmm",
            thumbUrl = thumb,
            posterUrl = thumb
        )
    }

    private fun fetch(url: String, onFinalUrl: ((String) -> Unit)? = null): String {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cookie", "age_check_done=1")
            .build()
        return client.newCall(request).execute().use { response ->
            onFinalUrl?.invoke(response.request.url.toString())
            if (!response.isSuccessful) error("DMM 请求失败 HTTP ${response.code}: $url")
            response.body?.string().orEmpty()
        }
    }

    private fun linksNearLabel(html: String, label: String): List<String> {
        val area = Regex("""$label[\s\S]{0,900}?(?:</tr>|</table>|</div>\s*</div>)""", RegexOption.IGNORE_CASE)
            .find(html)?.value.orEmpty()
        return linksIn(area)
    }

    private fun linksIn(html: String): List<String> =
        Regex("""<a[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun textNearLabel(html: String, label: String): String {
        val area = Regex("""$label[\s\S]{0,260}?(?:</tr>|</td>|</div>)""", RegexOption.IGNORE_CASE).find(html)?.value.orEmpty()
        return cleanHtml(Regex("""</(?:td|th|div)>\s*<[^>]+>\s*([^<]+)""", RegexOption.IGNORE_CASE).find(area)?.groupValues?.getOrNull(1).orEmpty())
    }

    private fun textByRegex(html: String, regex: Regex): String =
        cleanHtml(regex.find(html)?.groupValues?.getOrNull(1).orEmpty())

    private fun String.digitsOnly(): String = Regex("""\d+""").find(this)?.value.orEmpty()

    private fun cleanHtml(value: String): String =
        value.replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")
            .trim()

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

/*
 * ================================================================================
 * 步骤3：生成 DMM 旧站商品 CID 候选
 * ================================================================================
 * 目标：覆盖 DMM 商品常见的数字前缀和 re 再版后缀，不扩大到模糊搜索。
 * 数据源：番号的厂牌与原始序号。
 * 操作：
 * 1) 优先尝试五位序号和 1/18 前缀，兼容 DANDY-414、CEMN-003。
 * 2) 再尝试原始序号、re 再版和其它历史前缀。
 * 3) 去重后交给详情页校验。
 */
internal fun dmmDirectContentIds(number: String): List<String> {
    val input = number.trim().replace("_", "-")
    val match = Regex("""(?i)^([a-z]+)-?(\d+)$""").find(input) ?: return emptyList()
    val prefix = match.groupValues[1].lowercase()
    val serial = match.groupValues[2]
    val bases = listOf(prefix + serial.toInt().toString().padStart(5, '0'), prefix + serial).distinct()
    return buildList {
        bases.forEach { base ->
            add("1$base")
            add("18$base")
            add("1${base}re")
            add(base)
            add("2$base")
        }
    }.distinct()
}

internal fun dmmDirectDetailUrl(contentId: String): String =
    "https://video.dmm.co.jp/av/content/?id=$contentId"

internal fun dmmDetailRoutePriority(url: String): Int = runCatching {
    val parsed = url.toHttpUrl()
    when {
        parsed.host == "video.dmm.co.jp" && parsed.encodedPath == "/av/content/" -> 300
        parsed.host == "www.dmm.co.jp" && "/digital/videoa/" in parsed.encodedPath -> 200
        parsed.host == "www.dmm.co.jp" && "/detail/=/cid=" in url -> 100
        else -> 0
    }
}.getOrDefault(0)

/*
 * ================================================================================
 * 步骤4：生成 DMM 全站搜索词
 * ================================================================================
 * 目标：匹配 DMM“すべて”搜索页使用的厂牌空格序号格式。
 * 数据源：用户输入番号和 DMM 旧站搜索 URL 规则。
 * 操作：
 * 1) 优先发送“厂牌 序号”，例如 CEMN 003。
 * 2) 保留连字符、紧凑和 DMM 五位序号候选。
 * 3) 详情页仍按完整番号评分，拒绝相似前缀结果。
 */
internal fun dmmWebSearchTerms(number: String): List<String> {
    val input = number.trim().replace("_", "-")
    val match = Regex("""(?i)^([a-z]+)-?(\d+)$""").find(input)
        ?: return listOf(number.trim())
    val prefix = match.groupValues[1].uppercase()
    val serial = match.groupValues[2]
    return listOf(
        "$prefix $serial",
        "$prefix-$serial",
        prefix + serial
    ).plus(dmmSearchKeywords(number).map(String::uppercase)).distinct()
}

internal fun dmmWebSearchRequests(number: String): List<Pair<String, String>> = buildList {
    add("upstream-all" to "https://www.dmm.co.jp/search/=/searchstr=$number/")
    add("upstream-dvd" to "https://www.dmm.co.jp/mono/dvd/-/search/=/searchstr=$number/")
    add("upstream-videoa" to "https://www.dmm.co.jp/digital/videoa/-/list/search/=/?searchstr=$number")
    dmmWebSearchTerms(number).forEachIndexed { index, term ->
        val encodedTerm = term.replace(" ", "%20")
        add(
            "all-${index + 1}" to
                "https://www.dmm.co.jp/search/=/searchstr=$encodedTerm/limit=30/sort=rankprofile"
        )
    }
}.distinctBy { (_, url) -> url }

/*
 * ================================================================================
 * 步骤5：校验 DMM 直查详情页身份
 * ================================================================================
 * 目标：拒绝只在推荐区、脚本或回显 URL 中出现候选 CID 的错误页面。
 * 数据源：详情页的 og:url 和 canonical 主体身份字段。
 * 操作：
 * 1) 只从主体身份标签提取 cid 或 id。
 * 2) 复用完整番号评分，允许官方数字前缀和 re 再版后缀。
 */
internal fun matchesDmmDirectDetailIdentity(html: String, number: String): Boolean {
    val keyword = normalizeDmmSearchKeyword(number)
    return dmmDirectDetailIdentityIds(html).any { id ->
        dmmContentIdMatchScore(id, keyword) >= 850
    }
}

internal fun dmmDirectDetailIdentityIds(html: String): List<String> {
    val identityTag = Regex(
        """<(?:meta|link)\b[^>]*(?:property=["']og:url["']|rel=["']canonical["'])[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    val contentId = Regex("""(?:cid=|[?&]id=)([^/?&"']+)""", RegexOption.IGNORE_CASE)
    return identityTag.findAll(html)
        .mapNotNull { tag -> contentId.find(tag.value)?.groupValues?.getOrNull(1) }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

internal fun dmmResponseDestination(url: String): String = runCatching {
    val parsed = url.toHttpUrl()
    val firstSegment = parsed.pathSegments.firstOrNull { it.isNotBlank() }.orEmpty()
    parsed.host + firstSegment.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
}.getOrElse { url.substringBefore('?').take(160) }
