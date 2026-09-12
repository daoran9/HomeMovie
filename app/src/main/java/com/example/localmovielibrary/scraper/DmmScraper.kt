package com.example.localmovielibrary.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException

class DmmScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: ((String) -> Unit)? = null
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Dmm

    override suspend fun scrape(number: String): ScrapedMovieInfo = scrape(number, null)

    internal suspend fun scrape(
        number: String,
        digitalInfo: ScrapedMovieInfo?,
        allowDigital: Boolean = true
    ): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = number.uppercase()
        val candidates = search(normalized, allowDigital)
        val detailUrl = candidates.first()
        val contentId = dmmDigitalContentId(detailUrl)
        var primaryIdentityIds = emptyList<String>()
        val primary = if (contentId != null) {
            // New video pages are client-rendered; their metadata comes from the existing detail API.
            if (digitalInfo != null && dmmDigitalContentId(digitalInfo.website) == contentId &&
                digitalInfo.number.equals(normalized, ignoreCase = true)
            ) {
                logger?.invoke("DMM 网页命中同一数字商品，复用 DMM2 详情：number=$normalized, contentId=$contentId")
                digitalInfo
            } else {
                logger?.invoke("DMM 网页数字商品转结构化详情：number=$normalized, contentId=$contentId")
                Dmm2Scraper(client, ioDispatcher, logger).scrapeContentId(normalized, contentId)
            }
        } else {
            val html = fetch(detailUrl)
            primaryIdentityIds = dmmDirectDetailIdentityIds(html)
            parseDetail(html, detailUrl, normalized)
        }
        supplementCandidateTags(primary, contentId?.let(::listOf) ?: primaryIdentityIds, candidates.drop(1))
    }

    private fun supplementCandidateTags(
        primary: ScrapedMovieInfo,
        primaryIdentityIds: List<String>,
        candidates: List<String>
    ): ScrapedMovieInfo {
        // Candidate metadata must never replace the selected product's fields.
        val tags = primary.tags.toMutableSet()
        val dvdCandidates = candidates.filter { it.toHttpUrl().encodedPath.startsWith("/mono/dvd/") }
        logger?.invoke("DMM 同作品标签补证开始：number=${primary.number}, candidates=${dvdCandidates.size}")
        if (primaryIdentityIds.isNotEmpty()) dvdCandidates.forEach { url ->
            try {
                val html = fetch(url)
                if (dmmDirectDetailIdentityIds(html).none { it in primaryIdentityIds }) {
                    logger?.invoke("DMM 标签候选未提供共同作品身份，跳过：number=${primary.number}, url=$url")
                    return@forEach
                }
                val candidate = parseDetail(html, url, primary.number)
                val additions = candidate.tags.filterNot {
                    isSiteClassification(it) || (it == candidate.publisher && candidate.publisher != primary.publisher)
                }
                val previousSize = tags.size
                tags += additions
                logger?.invoke("DMM 同作品标签补证完成：number=${primary.number}, url=$url, added=${tags.size - previousSize}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                logger?.invoke("DMM 标签候选请求失败，保留主商品：number=${primary.number}, url=$url, error=${error.message}")
            } catch (error: IllegalStateException) {
                logger?.invoke("DMM 标签候选无有效详情，保留主商品：number=${primary.number}, url=$url, error=${error.message}")
            }
        }
        logger?.invoke("DMM 同作品标签补证结束：number=${primary.number}, added=${tags.size - primary.tags.toSet().size}")
        return if (tags == primary.tags.toSet()) primary else primary.copy(tags = tags.toList())
    }

    private fun search(number: String, allowDigital: Boolean): List<String> {
        logger?.invoke("DMM 全部搜索开始：number=$number")
        val searchDestinations = mutableListOf<String>()
        for ((requestName, url) in dmmWebSearchRequests(number)) {
            var destination = "unknown"
            val html = fetch(url) { finalUrl ->
                destination = dmmResponseDestination(finalUrl)
                searchDestinations += "$requestName=$destination"
            }
            val links = selectDetailUrls(html, number, allowDigital)
            if (links.isNotEmpty()) {
                logger?.invoke(
                    "DMM 全部搜索命中：number=$number, request=$requestName, destination=$destination, " +
                        "candidates=${links.size}, selected=${links.first()}"
                )
                return links
            }
        }
        if (!allowDigital) error("DMM 数字来源已停用，未找到可用 DVD/租赁详情：$number")
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
                return listOf(url)
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
    internal fun selectDetailUrl(html: String, number: String, allowDigital: Boolean = true): String? =
        selectDetailUrls(html, number, allowDigital).firstOrNull()

    private fun selectDetailUrls(html: String, number: String, allowDigital: Boolean): List<String> {
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
                val matchScore = dmmWebContentIdMatchScore(contentId, keyword, detailUrl)
                val routePriority = dmmDetailRoutePriority(detailUrl)
                detailUrl.takeIf { matchScore >= 850 && routePriority > 0 && (allowDigital || routePriority < 200) }
                    ?.let { Triple(it, matchScore, routePriority) }
            }
            .sortedByDescending { (_, matchScore, routePriority) -> matchScore * 10_000 + routePriority }
            .map { it.first }
            .distinctBy { link ->
                val url = link.toHttpUrl()
                if (dmmDigitalContentId(link) == null) url.newBuilder().query(null).build() else url
            }
            .toList()
    }

    internal fun parseDetail(html: String, detailUrl: String, number: String): ScrapedMovieInfo {
        /*
         * ================================================================================
         * 步骤1：按详情字段标签读取对应值和商品简介
         * ================================================================================
         * 目标：兼容旧表格与当前 DVD 详情页，避免漏掉商品简介。
         * 数据源：DMM 详情 HTML。
         * 操作：1) 精确匹配表格或 dt/dd 标签；2) 从商品评论节点读取简介。
         */
        logger?.invoke("开始解析 DMM 详情字段：$number")
        val document = Jsoup.parse(html, detailUrl)
        val identityIds = dmmDirectDetailIdentityIds(html)
        if (identityIds.isNotEmpty() && identityIds.none {
                dmmWebContentIdMatchScore(it, normalizeDmmSearchKeyword(number), detailUrl) >= 850
            }) error("DMM 详情身份不匹配：$number")
        // 1.1 按精确标签读取表格或定义列表中的值节点
        fun field(label: String): Element? {
            val tableField = document.select("tr").firstNotNullOfOrNull { row ->
                val cells = row.children().filter { it.tagName() in setOf("td", "th") }
                cells.firstOrNull()?.takeIf { it.text().trim().trimEnd(':', '：') == label }
                    ?.let { cells.getOrNull(1) }
            }
            return tableField ?: document.select("dl").firstNotNullOfOrNull { definitionList ->
                definitionList.children().firstOrNull {
                    it.tagName() == "dt" && it.text().trim().trimEnd(':', '：') == label
                }?.nextElementSibling()?.takeIf { it.tagName() == "dd" }
            }
        }
        fun text(label: String): String = field(label)?.text().orEmpty().trim().takeUnless { it == "----" }.orEmpty()
        fun links(label: String): List<String> = field(label)?.select("a")
            ?.map { it.text().trim() }?.filter { it.isNotBlank() }?.distinct().orEmpty()
        // 1.1 读取任意 h1，避免依赖属性顺序和站点模板 class
        val title = document.selectFirst("h1")?.clone()?.apply { select(".status-used").remove() }?.text()?.trim().orEmpty()
            .ifBlank { document.selectFirst("meta[property=og:title], meta[name=og:title]")?.attr("content").orEmpty().trim() }
        if (title.isBlank()) error("DMM 详情页没有解析到标题")
        val packageImage = document.selectFirst(".area-overview .box-package a.package-large img")?.absUrl("src").orEmpty()
        val thumb = document.selectFirst("meta[property=og:image], meta[name=og:image]")?.absUrl("content").orEmpty()
            .ifBlank { packageImage }
            .replace("ps.jpg", "pl.jpg")
        val poster = document.select("img[src]").firstOrNull {
            it.absUrl("src") == thumb.replace("pl.jpg", "ps.jpg")
        }?.absUrl("src") ?: packageImage.ifBlank { thumb }
        /*
         * ==============================================================================
         * 步骤2：分离官方类型与相关标签
         * ==============================================================================
         * 目标：保留 DMM 页面两个独立字段，避免把ジャンル复制成関連タグ。
         * 数据源：详情页的“ジャンル”、“関連タグ”字段及 area-keyword 标签区。
         * 操作：
         * 1) 类型只写入 genres。
         * 2) 相关标签组按 # 边界展开，只写入 tags，保留标签内部空格。
         */
        logger?.invoke("开始解析 DMM 分类与相关标签：number=$number")
        val genres = links("ジャンル")
        val relatedTags = (links("関連タグ") + document.select("section.area-keyword .box-taglink a").map { it.text() })
            .flatMap { group -> group.split(Regex("\\s+#")) }
            .map { it.trim().removePrefix("#").trim() }
            .filter { it.isNotBlank() }
            .distinct()
        logger?.invoke("DMM 分类与相关标签解析完成：number=$number, genres=${genres.size}, relatedTags=${relatedTags.size}")
        val actors = Regex("""<(?:span|td)[^>]+(?:id=["']performer["']|id=["']fn-visibleActor["'])[\s\S]*?</(?:span|td)>""", RegexOption.IGNORE_CASE)
            .find(html)?.value
            ?.let { linksIn(it) }
            .orEmpty()
            .ifEmpty { links("出演者") }

        val release = Regex("""\d{4}[/\-]\d{2}[/\-]\d{2}""")
            .find(text("発売日").ifBlank { text("配信開始日") })?.value.orEmpty().replace("/", "-")
        val runtime = text("収録時間").digitsOnly()
        // 1.2 优先读取当前 DVD 页的商品评论，保留旧页选择器作兼容
        val plot = document.selectFirst("section.area-productcomment p.box-productcomment, section.area-comment p.box-comment")?.text().orEmpty()
            .ifBlank { document.selectFirst("div.mg-b20.lh4 > p.mg-b20")?.text().orEmpty() }
        val studio = text("メーカー")
        val publisher = text("レーベル")
        val series = links("シリーズ").firstOrNull().orEmpty()
        val directors = links("監督")
        val ratingText = document.selectFirst(".dcd-review__average strong, .d-review__average strong")?.text().orEmpty()
        val rating = Regex("""^(\d+(?:\.\d+)?)\s*点?$""").matchEntire(ratingText.trim())?.groupValues?.get(1).orEmpty()
        val trailer = document.selectFirst(".area-overview a.play-btn[href], .area-overview a.openSamplePlayer[href]")?.absUrl("href").orEmpty()
            .ifBlank {
                Regex("""video_url["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty()
            }
        // 1.3 记录真实响应的字段覆盖，供全库回归定位缺失链路
        logger?.invoke(
            "DMM 详情字段解析完成：$number, plot=${plot.length}, date=$release, runtime=$runtime, " +
                "studio=${studio.isNotBlank()}, publisher=${publisher.isNotBlank()}, series=${series.isNotBlank()}, " +
                "directors=${directors.size}, actors=${actors.size}, genres=${genres.size}, relatedTags=${relatedTags.size}, rating=${rating.isNotBlank()}, " +
                "trailer=${trailer.isNotBlank()}, thumb=${thumb.isNotBlank()}, poster=${poster.isNotBlank()}"
        )

        return ScrapedMovieInfo(
            number = number,
            title = title,
            originalTitle = title,
            plot = plot,
            premiered = release,
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            runtime = runtime,
            studio = studio,
            publisher = publisher,
            series = series,
            directors = directors,
            actors = actors,
            genres = genres,
            tags = relatedTags,
            rating = rating,
            trailer = trailer,
            website = detailUrl,
            source = "dmm",
            thumbUrl = thumb,
            posterUrl = poster
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

    private fun linksIn(html: String): List<String> =
        Regex("""<a[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

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
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
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

internal fun dmmDigitalContentId(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    return parsed.queryParameter("id")?.takeIf {
        parsed.host == "video.dmm.co.jp" && parsed.encodedPath == "/av/content/" && it.isNotBlank()
    }
}

internal fun dmmDetailRoutePriority(url: String): Int = runCatching {
    val parsed = url.toHttpUrl()
    when {
        parsed.host == "video.dmm.co.jp" && parsed.encodedPath == "/av/content/" -> 300
        parsed.host == "www.dmm.co.jp" && "/digital/videoa/" in parsed.encodedPath -> 200
        parsed.host == "www.dmm.co.jp" && parsed.encodedPath.startsWith("/rental/") && "/detail/=/cid=" in url -> 90
        parsed.host == "www.dmm.co.jp" && "/detail/=/cid=" in url -> 100
        else -> 0
    }
}.getOrDefault(0)

// 租赁详情的 r 是发行版本后缀，仅在该路由下参与番号匹配。
internal fun dmmWebContentIdMatchScore(contentId: String, keyword: String, url: String): Int =
    dmmContentIdMatchScore(
        if (url.toHttpUrl().encodedPath.startsWith("/rental/")) contentId.removeSuffix("r") else contentId,
        keyword
    )

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
