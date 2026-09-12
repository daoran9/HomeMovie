package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.Locale

class JavlibraryCloudflareException(message: String) : IllegalStateException(message)

class JavlibraryScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cookieProvider: () -> String = { "" },
    private val logger: ((String) -> Unit)? = null,
    private val webViewFetcher: JavlibraryWebViewFetcher? = null
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Javlibrary

    suspend fun findActorNames(number: String): List<String> = findActors(number).map { it.name }

    suspend fun findActors(number: String): List<ActorAliasLookup> = withContext(ioDispatcher) {
        val normalized = normalizeNumber(number)
        logger?.invoke("JavLibrary 演员别名查询：$normalized")
        val searchUrl = buildSearchUrl(normalized)
        val detailUrl = findDetailUrl(fetch(searchUrl), normalized)
            ?: return@withContext emptyList()
        logger?.invoke("JavLibrary 演员别名找到详情：$normalized，$detailUrl")
        val actors = parseActors(fetch(detailUrl)).map { actor ->
            ActorAliasLookup(name = actor.name, aliases = actor.aliases)
        }
        logger?.invoke("JavLibrary 演员别名查询完成：$normalized，${actors.size} 人")
        actors
    }

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        /*
         * ================================================================================
         * 步骤1：定位 JavLibrary 影片详情
         * ================================================================================
         * 目标：按番号搜索并只打开匹配的详情页。
         * 数据源：JavLibrary 搜索页、详情页和 WebView 保存的 Cookie。
         * 操作：
         * 1) 用标准化番号请求搜索页。
         * 2) 从搜索结果提取包含完整番号的详情链接。
         * 3) 解析详情页，遇到 Cloudflare 时返回可识别错误。
         */
        val normalized = normalizeNumber(number)
        logger?.invoke("JavLibrary 开始搜索：$normalized")
        val searchUrl = buildSearchUrl(normalized)
        val detailUrl = findDetailUrl(fetch(searchUrl), normalized)
            ?: error("JavLibrary 没有搜索到详情页：$normalized")
        logger?.invoke("JavLibrary 找到详情页：$detailUrl")
        parseDetail(normalized, detailUrl, fetch(detailUrl))
    }

    internal fun findDetailUrl(html: String, number: String): String? {
        val normalized = normalizeNumber(number)

        /*
         * ================================================================================
         * 步骤1：识别搜索页自动跳转后的详情页
         * ================================================================================
         * 目标：WebView 搜索页已跳到详情页时，不再要求 HTML 中存在搜索结果链接。
         * 数据源：详情页 canonical URL 与 video_id 中的识别码。
         * 操作：
         * 1) 校验 video_id 包含当前完整番号。
         * 2) 返回页面声明的 canonical 详情 URL。
         */
        val redirectedDetailUrl = canonicalDetailUrl(html)
        val videoId = sectionText(html, "video_id").filter(Char::isLetterOrDigit)
        if (
            redirectedDetailUrl != null &&
            containsExactCatalogNumber(videoId, normalized)
        ) {
            return redirectedDetailUrl
        }

        val anchorPattern = Regex(
            """<a\b[^>]+href=[\"']([^\"']+)[\"'][^>]*>[\s\S]{0,1600}?</a>""",
            RegexOption.IGNORE_CASE
        )
        return anchorPattern.findAll(html)
            .firstOrNull { match ->
                val href = match.groupValues[1]
                val text = cleanHtml(match.value).filter(Char::isLetterOrDigit)
                (containsExactCatalogNumber(href, normalized) ||
                    containsExactCatalogNumber(text, normalized)) &&
                    !href.contains("vl_searchbyid.php", ignoreCase = true)
            }
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::absoluteUrl)
    }

    private fun canonicalDetailUrl(html: String): String? =
        Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { match -> match.value }
            .firstOrNull { tag ->
                Regex("""\brel=[\"'][^\"']*\bcanonical\b[^\"']*[\"']""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(tag)
            }
            ?.let { tag ->
                Regex("""\bhref=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
                    .find(tag)
                    ?.groupValues
                    ?.getOrNull(1)
            }
            ?.let(::absoluteUrl)
            ?.takeIf { url ->
                url.startsWith(BASE_URL, ignoreCase = true) &&
                    !url.contains("vl_searchbyid.php", ignoreCase = true)
            }

    internal fun parseDetail(number: String, url: String, html: String): ScrapedMovieInfo {
        val detailNumber = sectionText(html, "video_id")
        if (detailNumber.isNotBlank() && !containsExactCatalogNumber(detailNumber, normalizeNumber(number))) {
            error("JavLibrary 详情页番号不匹配：请求 ${normalizeNumber(number)}，页面 $detailNumber")
        }
        /*
         * ================================================================================
         * 步骤2：解析 JavLibrary 影片字段
         * ================================================================================
         * 目标：把旧版页面字段转换成统一影片资料模型。
         * 数据源：video_title、video_id、video_jacket、video_cast、video_genres 等区域。
         * 操作：
         * 1) 读取标题、封面、发行日期和时长。
         * 2) 读取制作商、发行商、导演、演员和类型。
         * 3) 没有演员头像时交给后续 DMM/FANZA、JavDB 和 gfriends 链路补齐。
         */
        val title = sectionHeading(html, "video_title")
            .ifBlank { titleTag(html) }
            .removeSuffix(" - JAVLibrary")
            .cleanText()
        if (title.isBlank() || title.equals("404 Not Found", ignoreCase = true)) {
            error("JavLibrary 详情页不可用：$number")
        }

        val cover = sectionImage(html, "video_jacket")
        val poster = buildPosterUrl(cover)
        val release = sectionValue(html, "video_date")
        val directors = sectionLinks(html, "video_director")
        val cast = parseActors(html)
        val actors = cast.map { actor -> actor.name }
        val genres = sectionLinks(html, "video_genres")
        val score = Regex(
            """<div\b[^>]+id=[\"']video_review[\"'][^>]*>[\s\S]{0,900}?<[^>]+class=[\"'][^\"']*\bscore\b[^\"']*[\"'][^>]*>([\s\S]*?)</""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.let(::cleanHtml).orEmpty()

        return ScrapedMovieInfo(
            number = number,
            title = title,
            originalTitle = title,
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            premiered = release,
            runtime = sectionValue(html, "video_length").digitsOnly(),
            studio = sectionValue(html, "video_maker"),
            publisher = sectionValue(html, "video_label"),
            directors = directors,
            actors = actors,
            actorAliases = cast.associate { actor -> actor.name to actor.aliases }
                .filterValues { aliases -> aliases.isNotEmpty() },
            genres = genres,
            rating = Regex("""\d+(?:\.\d+)?""").find(score)?.value.orEmpty(),
            website = url,
            source = "javlibrary",
            thumbUrl = cover,
            posterUrl = poster
        )
    }

    private fun buildPosterUrl(coverUrl: String): String {
        if (!coverUrl.contains("dmm.co.jp/", ignoreCase = true)) return coverUrl
        return coverUrl.replace(
            Regex("""pl(\.(jpg|jpeg|png|webp))$""", RegexOption.IGNORE_CASE),
            "ps\$1"
        )
    }

    /*
     * ================================================================================
     * 步骤3：解析 JavLibrary 演员别名节点
     * ================================================================================
     * 目标：保留视频演员条目里的主名和 alias 节点，供 NFO 和头像回查共同使用。
     * 数据源：video_cast 内的 cast、star 和 alias span。
     * 操作：
     * 1) 每个 cast 块只取一个主演员链接。
     * 2) 把同一 cast 块的所有 alias 文本拆开后绑定到该主演员。
     */
    private fun parseActors(html: String): List<JavlibraryActor> {
        val castSection = sectionHtml(html, "video_cast")
        val castBlocks = CAST_BLOCK.findAll(castSection).mapNotNull { match ->
            val block = match.groupValues[1]
            val name = STAR_NAME.find(block)?.groupValues?.getOrNull(1)?.let(::cleanHtml).orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val aliases = ALIAS_NAME.findAll(block)
                .flatMap { alias -> actorNameParts(cleanHtml(alias.groupValues[1])).asSequence() }
                .filter { alias -> alias.isNotBlank() && !actorNamesHaveExactVariant(alias, name) }
                .distinctBy { alias -> actorNameVariants(alias).sorted().joinToString("|") }
                .toList()
            JavlibraryActor(name, aliases)
        }.toList()
        if (castBlocks.isNotEmpty()) return castBlocks
        return sectionLinks(html, "video_cast")
            .ifEmpty { sectionText(html, "video_cast").splitNames() }
            .map { actor -> JavlibraryActor(actor) }
    }

    private fun buildSearchUrl(number: String): String =
        "$BASE_URL/cn/vl_searchbyid.php".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", number)
            .build()
            .toString()

    /*
     * ================================================================================
     * 步骤3：选择 WebView 或 OkHttp 请求入口
     * ================================================================================
     * 目标：Cloudflare 页面交给真实 WebView，普通单元测试和无 UI 场景保留 OkHttp。
     * 数据源：应用级 JavlibraryWebViewFetcher、设置页 Cookie 和当前 URL。
     * 操作：
     * 1) 应用运行时优先等待隐藏 WebView 返回 HTML。
     * 2) 未注入 WebView 时沿用原有 Cookie + OkHttp 请求。
     */
    private suspend fun fetch(url: String): String {
        webViewFetcher?.let { fetcher ->
            logger?.invoke("JavLibrary 使用 WebView 请求：$url")
            return fetcher.fetch(url)
        }
        val cookie = cookieProvider().trim()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", "$BASE_URL/cn/")
        if (cookie.isNotBlank()) requestBuilder.header("Cookie", cookie)
        return client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body ?: error("JavLibrary 响应为空：$url")
            val bytes = body.bytes()
            val html = String(bytes, body.contentType()?.charset() ?: detectCharset(bytes))
            if (response.code == 403 || response.header("cf-mitigated").equals("challenge", ignoreCase = true) || html.isCloudflareChallengeHtml()) {
                throw JavlibraryCloudflareException("JavLibrary 需要先在设置页通过 WebView 完成 Cloudflare 验证")
            }
            if (!response.isSuccessful) error("JavLibrary 请求失败 HTTP ${response.code}: $url")
            html
        }
    }

    private fun sectionHeading(html: String, id: String): String =
        sectionHtml(html, id).let { section ->
            Regex("""<h3\b[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)
                .find(section)?.groupValues?.getOrNull(1)?.let(::cleanHtml).orEmpty()
        }

    private fun sectionImage(html: String, id: String): String =
        Regex("""<[^>]+id=[\"']${Regex.escape(id)}[\"'][^>]*>[\s\S]{0,1200}?<img\b[^>]*(?:data-src|src)=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let(::absoluteUrl).orEmpty()

    private fun sectionValue(html: String, id: String): String {
        val section = sectionHtml(html, id)
        return Regex("""class=[\"'][^\"']*\btext\b[^\"']*[\"'][^>]*>([\s\S]*?)</""", RegexOption.IGNORE_CASE)
            .find(section)?.groupValues?.getOrNull(1)?.let(::cleanHtml)
            ?.ifBlank { cleanHtml(section) }
            .orEmpty()
            .removePrefix(id)
            .trim(' ', ':', '：')
    }

    private fun sectionText(html: String, id: String): String = cleanHtml(sectionHtml(html, id))

    private fun sectionLinks(html: String, id: String): List<String> {
        val section = sectionHtml(html, id)
        return Regex("""<a\b[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(section)
            .map { it.groupValues[1].let(::cleanHtml) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun sectionHtml(html: String, id: String): String =
        Regex(
            """<[^>]+id=[\"']${Regex.escape(id)}[\"'][^>]*>([\s\S]*?)(?=<[^>]+id=[\"']video_[^\"']+[\"']|</body>|$)""",
            RegexOption.IGNORE_CASE
        )
            .find(html)?.groupValues?.getOrNull(1).orEmpty()

    private fun titleTag(html: String): String =
        Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let(::cleanHtml).orEmpty()

    private fun absoluteUrl(value: String): String {
        val clean = value.cleanText()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$BASE_URL$clean"
            clean.startsWith("http", ignoreCase = true) -> clean
            else -> "$BASE_URL/$clean"
        }
    }

    private fun normalizeNumber(number: String): String {
        val match = Regex("""(?i)([a-z]{2,12})[-_ ]?(\d{2,6})""").find(number)
            ?: return number.trim().uppercase(Locale.ROOT)
        return "${match.groupValues[1].uppercase(Locale.ROOT)}-${match.groupValues[2]}"
    }

    private fun String.splitNames(): List<String> =
        split(Regex("[,，、|;\\r\\n\\t]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun String.digitsOnly(): String = Regex("""\d+""").find(this)?.value.orEmpty()

    private fun String.cleanText(): String = cleanHtml(this)

    private fun cleanHtml(value: String): String =
        value.replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("\u00A0", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun detectCharset(bytes: ByteArray): Charset {
        val head = bytes.decodeToString(endIndex = minOf(bytes.size, 4096))
        val name = Regex("""charset=[\"']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.getOrNull(1)
        return runCatching { name?.let(Charset::forName) }.getOrNull() ?: Charsets.UTF_8
    }

    private fun String.isCloudflareChallengeHtml(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return "cloudflare" in lower && ("challenge" in lower || "cf-chl" in lower || "just a moment" in lower)
    }

    private data class JavlibraryActor(
        val name: String,
        val aliases: List<String> = emptyList()
    )

    companion object {
        const val BASE_URL = "https://www.javlibrary.com"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.7871.181 Mobile Safari/537.36"
        private val CAST_BLOCK = Regex(
            """<span\b[^>]*\bid=[\"']cast[^\"']*[\"'][^>]*>([\s\S]*?)(?=<span\b[^>]*\bid=[\"']cast[^\"']*[\"']|</td>)""",
            RegexOption.IGNORE_CASE
        )
        private val STAR_NAME = Regex(
            """<span\b[^>]*\bclass=[\"'][^\"']*\bstar\b[^\"']*[\"'][^>]*>[\s\S]*?<a\b[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        private val ALIAS_NAME = Regex(
            """<span\b[^>]*\bid=[\"']alias[^\"']*[\"'][^>]*>([\s\S]*?)</span>""",
            RegexOption.IGNORE_CASE
        )
    }
}
