package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.net.URLDecoder
import java.util.Locale

class JavdbScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cookieProvider: () -> String = { "" },
    private val logger: ((String) -> Unit)? = null,
    private val webViewFetcher: JavlibraryWebViewFetcher? = null
) : MovieScraper, ReviewActorFallback {
    override val source: ScrapeSource = ScrapeSource.Javdb

    /** 只读取对应 JavDB 影片的演员名，用于跨源回查 DMM/FANZA 官方头像。 */
    suspend fun findActorNames(number: String): List<String> = findActors(number).map { it.name }

    suspend fun findActors(number: String): List<ActorAliasLookup> = withContext(ioDispatcher) {
        val normalized = normalizeNumber(number)
        logger?.invoke("JavDB 演员别名查询：$normalized")
        val searchHtml = fetch(buildSearchUrl(normalized))
        val detailUrl = findDetailUrl(searchHtml, normalized)
        if (detailUrl == null) {
            logger?.invoke("JavDB 演员别名查询未找到详情：$normalized")
            return@withContext emptyList()
        }
        val detailHtml = fetch(detailUrl)
        val (resolvedActors, aliases) = resolveActorProfiles(parseActorEntries(detailHtml))
        val actors = resolvedActors
            .filterNot { actor -> actor.gender == JavdbActorGender.Male }
            .map { actor -> ActorAliasLookup(name = actor.name, aliases = aliases[actor.name].orEmpty()) }
            .distinctBy { actor -> actor.name }
        logger?.invoke("JavDB 演员别名查询完成：$normalized，${actors.size} 人")
        actors
    }

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        /*
         * ================================================================================
         * 步骤1：定位 JavDB 番号详情
         * ================================================================================
         * 目标：只打开与当前番号完全一致的 JavDB 详情页。
         * 数据源：JavDB 搜索页和详情页。
         * 操作：
         * 1) 用标准化番号发起搜索。
         * 2) 从搜索卡片中筛选精确番号，避免相似番号误匹配。
         * 3) 解析详情页演员页链接和头像地址。
         */
        val normalized = normalizeNumber(number)
        logger?.invoke("JavDB 开始搜索：$normalized")
        val searchUrl = buildSearchUrl(normalized)
        val searchHtml = fetch(searchUrl)
        val detailUrl = findDetailUrl(searchHtml, normalized)
            ?: error("JavDB 没有搜索到详情页：$normalized")
        logger?.invoke("JavDB 找到详情页：$detailUrl")
        val detailHtml = fetch(detailUrl)
        val resolved = resolveActorProfiles(parseActorEntries(detailHtml))
        parseDetail(normalized, detailUrl, detailHtml, resolved.actors, resolved.aliases, resolved.verifiedNames)
    }

    private fun buildSearchUrl(number: String): String =
        "https://javdb.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", number)
            .build()
            .toString()

    private fun buildActorSearchUrl(actorName: String): String =
        "https://javdb.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("f", "actor")
            .addQueryParameter("q", actorName)
            .build()
            .toString()

    internal fun findDetailUrl(html: String, number: String): String? {
        val normalizedNumber = normalizeNumber(number)
        val numberParts = Regex("""(?i)^([a-z]{2,12})-(\d{2,6})$""").matchEntire(normalizedNumber)
        val numberPattern = numberParts?.let {
            """(?<![A-Z0-9])${Regex.escape(it.groupValues[1])}[-_\s]?${Regex.escape(it.groupValues[2])}(?![A-Z0-9])"""
        } ?: Regex.escape(normalizedNumber)
        val anchorPattern = Regex(
            """<a\b[^>]+href=[\"']([^\"']*/v/[^\"']+)[\"'][^>]*>[\s\S]{0,1500}?</a>""",
            RegexOption.IGNORE_CASE
        )
        val numberRegex = Regex(numberPattern, RegexOption.IGNORE_CASE)
        return anchorPattern.findAll(html)
            .mapNotNull { match ->
                val detailUrl = absoluteUrl(match.groupValues[1])
                if (detailUrl.isJavdbDetailUrl()) match to detailUrl else null
            }
            .firstOrNull { (match, _) -> numberRegex.containsMatchIn(match.value) }
            ?.second
    }

    internal fun parseDetail(
        number: String,
        url: String,
        html: String,
        resolvedActors: List<JavdbActor>? = null,
        resolvedActorAliases: Map<String, List<String>> = emptyMap(),
        verifiedActorNames: List<String> = emptyList()
    ): ScrapedMovieInfo {
        val detailNumber = parseDetailNumber(html)
        if (detailNumber.isNotBlank() && !containsExactCatalogNumber(detailNumber, normalizeNumber(number))) {
            error("JavDB 详情页番号不匹配：请求 ${normalizeNumber(number)}，页面 $detailNumber")
        }
        val title = textByRegex(html, Regex("""<div[^>]+class=[\"'][^\"']*current-title[^\"']*[\"'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE))
            .ifBlank { textByRegex(html, Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)) }
            .removeSuffix(" - JavDB")
            .cleanText()
        if (title.isBlank()) error("JavDB 详情页没有解析到标题：$number")

        val cover = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .firstOrNull { tag ->
                attributeValue(tag.value, "class")
                    .split(Regex("\\s+"))
                    .any { it.equals("video-cover", ignoreCase = true) }
            }
            ?.value
            ?.let { tag -> attributeValue(tag, "data-src").ifBlank { attributeValue(tag, "src") } }
            ?.let(::absoluteUrl)
            .orEmpty()
        val parsedActors = parseActorEntries(html)
        val actorEvidence = resolvedActors ?: parsedActors
        val actors = actorEvidence
            .filterNot { actor -> actor.gender == JavdbActorGender.Male }
        val excludedActorNames = actorEvidence
            .filter { actor -> actor.gender == JavdbActorGender.Male }
            .map { actor -> actor.name }
        val actorImageUrls = actors.associate { actor -> actor.name to actor.imageUrl }
            .filterValues { it.isNotBlank() }
        val genres = parseLinksNearLabel(html, "類別", "类别")
        val release = textNearLabel(html, "日期")
            .ifBlank { textNearLabel(html, "发行日期") }
            .replace("/", "-")

        return ScrapedMovieInfo(
            number = number,
            title = title,
            originalTitle = title,
            plot = metaContent(html, "description"),
            outline = metaContent(html, "description"),
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            premiered = release,
            runtime = textNearLabel(html, "时长", "時長").digitsOnly(),
            studio = parseLinksNearLabel(html, "片商").firstOrNull().orEmpty(),
            directors = parseLinksNearLabel(html, "导演", "導演"),
            actors = actors.map { it.name },
            actorAliases = resolvedActorAliases,
            verifiedActorNames = verifiedActorNames,
            excludedActorNames = excludedActorNames,
            actorImageUrls = actorImageUrls,
            genres = genres,
            website = url,
            source = "javdb",
            thumbUrl = cover,
            posterUrl = cover
        )
    }

    internal fun parseActors(html: String): List<JavdbActor> = parseActorEntries(html)
        .filterNot { actor -> actor.gender == JavdbActorGender.Male }

    /*
     * ================================================================================
     * 步骤4：用 JavDB 短评中的姓名清单补演员
     * ================================================================================
     * 目标：供多源融合层在所有结构化来源都缺演员时读取用户整理的姓名清单。
     * 数据源：详情页短评接口；只接受带换行的纯姓名列表或明确“演员：”标记的列表。
     * 操作：
     * 1) 重新定位严格匹配的详情页，再读取对应短评接口。
     * 2) 调用方负责确认 DMM/FANZA、JavLibrary、JavBus、JavDB 均无演员。
     * 3) 评论正文必须整体符合姓名列表格式，普通评价文字不会进入演员字段。
     */
    override suspend fun findReviewActorNames(number: String): List<String> = withContext(ioDispatcher) {
        val normalized = normalizeNumber(number)
        val detailUrl = findDetailUrl(fetch(buildSearchUrl(normalized)), normalized)
            ?: return@withContext emptyList()
        logger?.invoke("JavDB 开始读取短评姓名清单：$detailUrl")
        val reviewUrl = detailUrl.substringBefore('?').trimEnd('/') + "/reviews/lastest"
        val names = try {
            val candidates = parseReviewActorNames(fetch(reviewUrl))
            verifyReviewActorNames(candidates)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger?.invoke("JavDB 短评演员补充失败：$detailUrl，${error.message ?: error::class.java.simpleName}")
            emptyList()
        }
        logger?.invoke("JavDB 短评演员补充结束：$detailUrl，${names.size} 人")
        names
    }

    /*
     * ================================================================================
     * 步骤5：核对短评演员候选
     * ================================================================================
     * 目标：只保留 JavDB 演员搜索可确认的姓名，避免普通评论短句写入演员字段。
     * 数据源：短评姓名候选和 JavDB 演员搜索结果。
     * 操作：
     * 1) 每个候选使用演员筛选搜索，不接受影片标题或普通文本命中。
     * 2) 只保留演员卡片标题与候选姓名完全对应的结果。
     * 3) 单个候选查询失败时丢弃该候选，不影响其余姓名。
     */
    private suspend fun verifyReviewActorNames(candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        logger?.invoke("JavDB 短评演员候选核对开始：${candidates.size} 人")
        val verified = candidates.filter { actorName ->
            val searchHtml = try {
                fetch(buildActorSearchUrl(actorName))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger?.invoke(
                    "JavDB 短评演员候选查询失败：$actorName，" +
                        (error.message ?: error::class.java.simpleName)
                )
                null
            }
            searchHtml?.let { hasExactActorSearchResult(it, actorName) } == true
        }
        logger?.invoke("JavDB 短评演员候选核对结束：${verified.size}/${candidates.size} 人")
        return verified
    }

    internal fun hasExactActorSearchResult(html: String, actorName: String): Boolean {
        val actorCard = Regex(
            """<a\b[^>]+href=[\"'][^\"']*/actors/[^\"']+[\"'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        return actorCard.findAll(html).any { match ->
            val resultName = attributeValue(match.value, "title")
            resultName.isNotBlank() && actorNamesHaveExactVariant(resultName, actorName)
        }
    }

    /*
     * ================================================================================
     * 步骤6：读取 JavDB 演员主页别名
     * ================================================================================
     * 目标：按演员主页归并艺名，并只保留主页真实展示的头像地址。
     * 数据源：影片演员链接和对应演员主页的主名、别名区。
     * 操作：
     * 1) 只处理人数较少的影片，限制批量修复的网络请求量。
     * 2) 主页请求失败时保留影片页姓名和原候选，不影响当前影片刮削。
     * 3) 主页显示未知头像或没有头像时清空猜测地址，交给其它头像源继续补齐。
     * 4) 去掉当前显示名后，把其余名字作为显式 alias 交给多源融合。
     */
    private suspend fun resolveActorProfiles(actors: List<JavdbActor>): ResolvedActorProfiles {
        if (actors.isEmpty() || actors.size > ACTOR_PROFILE_ALIAS_LIMIT) return ResolvedActorProfiles(actors)
        logger?.invoke("JavDB 演员主页证据查询开始：${actors.size} 人")
        val aliases = mutableMapOf<String, List<String>>()
        val verifiedNames = mutableListOf<String>()
        val resolvedActors = actors.map { actor ->
            if (actor.profileUrl.isBlank() || actor.gender == JavdbActorGender.Male) return@map actor
            val profileHtml = try {
                fetch(actor.profileUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger?.invoke("JavDB 演员主页别名读取失败：${actor.name}，${error.message ?: error::class.java.simpleName}")
                null
            }
            if (profileHtml == null) return@map actor
            val profileNames = parseActorProfileNames(profileHtml)
            val profileGender = parseActorProfileGender(profileHtml)
            val resolvedGender = when {
                actor.gender == JavdbActorGender.Male || profileGender == JavdbActorGender.Male ->
                    JavdbActorGender.Male
                actor.gender == JavdbActorGender.Female || profileGender == JavdbActorGender.Female ->
                    JavdbActorGender.Female
                else -> JavdbActorGender.Unknown
            }
            if (
                resolvedGender != JavdbActorGender.Male &&
                profileNames.any { name -> actorNamesHaveExactVariant(name, actor.name) }
            ) {
                verifiedNames += actor.name
            }
            val actorAliases = profileNames
                .filter { name -> !actorNamesHaveExactVariant(name, actor.name) }
                .distinctBy { name -> name.lowercase(Locale.ROOT) }
            if (resolvedGender != JavdbActorGender.Male && actorAliases.isNotEmpty()) {
                aliases[actor.name] = actorAliases
            }
            actor.copy(
                imageUrl = parseActorProfileImageUrl(profileHtml),
                gender = resolvedGender
            )
        }
        logger?.invoke("JavDB 演员主页证据查询结束：${resolvedActors.size} 人")
        return ResolvedActorProfiles(resolvedActors, aliases, verifiedNames.distinct())
    }

    internal fun parseActorProfileNames(html: String): List<String> {
        val titleNames = Regex(
            """<span\b[^>]+class=[\"'][^\"']*\bactor-section-name\b[^\"']*[\"'][^>]*>([\s\S]*?)</span>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1).orEmpty()
        val aliasNames = Regex(
            """<span\b[^>]+class=[\"'][^\"']*\bsection-meta\b[^\"']*[\"'][^>]*>([\s\S]*?)</span>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1).orEmpty()
        return listOf(titleNames, aliasNames)
            .flatMap { value -> cleanHtml(value).split(Regex("""[,，、/／|;；]+""")) }
            .map { name -> name.trim() }
            .filter { name ->
                name.isNotBlank() &&
                    REVIEW_ACTOR_NAME.matches(name) &&
                    name.lowercase(Locale.ROOT) !in ACTOR_PROFILE_ROLE_MARKERS
            }
            .distinctBy { name -> name.lowercase(Locale.ROOT) }
    }

    internal fun parseActorProfileGender(html: String): JavdbActorGender {
        val markers = ACTOR_PROFILE_METADATA.findAll(html)
            .flatMap { match ->
                cleanHtml(match.groupValues[1])
                    .split(Regex("""[,，、/／|;；]+"""))
                    .asSequence()
            }
            .map { value -> value.trim().lowercase(Locale.ROOT) }
            .toSet()
        return when {
            markers.any { marker -> marker in MALE_ACTOR_PROFILE_MARKERS } -> JavdbActorGender.Male
            markers.any { marker -> marker in FEMALE_ACTOR_PROFILE_MARKERS } -> JavdbActorGender.Female
            else -> JavdbActorGender.Unknown
        }
    }

    internal fun parseActorProfileImageUrl(html: String): String {
        val rawUrl = Regex(
            """\bactor-avatar\b[\s\S]{0,500}?background-image\s*:\s*url\(([^)]+)\)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.trim('\'', '"')
            .orEmpty()
        if (rawUrl.isBlank() || rawUrl.contains("actor_unknow", ignoreCase = true)) return ""
        return absoluteUrl(rawUrl)
    }

    internal fun parseReviewActorNames(html: String): List<String> {
        val contentPattern = Regex(
            """<div\b[^>]+class=[\"'][^\"']*\bcontent\b[^\"']*[\"'][^>]*>([\s\S]*?)</div>""",
            RegexOption.IGNORE_CASE
        )
        return contentPattern.findAll(html)
            .flatMap { match -> actorNameListFromReview(match.groupValues[1]).asSequence() }
            .distinctBy { name -> name.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun actorNameListFromReview(contentHtml: String): List<String> {
        val hasLineBreaks = REVIEW_LINE_BREAK.containsMatchIn(contentHtml)
        val plainText = cleanReviewText(contentHtml)
        val labelMatch = REVIEW_ACTOR_LABEL.find(plainText)
        if (!hasLineBreaks && labelMatch == null) return emptyList()

        val listText = labelMatch?.groupValues?.getOrNull(1).orEmpty().ifBlank { plainText }
        val names = listText
            .split(REVIEW_ACTOR_SEPARATOR)
            .map { name -> name.trim() }
            .filter { name -> name.isNotBlank() }
        val acceptedSize = if (labelMatch == null) names.size in 3..12 else names.size in 1..12
        if (!acceptedSize || names.any { name -> !name.looksLikeReviewActorName() }) {
            return emptyList()
        }
        return names
    }

    private fun cleanReviewText(value: String): String = value
        .replace(REVIEW_LINE_BREAK, "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace("\u00A0", " ")
        .trim()

    private fun String.looksLikeReviewActorName(): Boolean =
        length in 2..20 &&
            REVIEW_ACTOR_NAME.matches(this) &&
            REVIEW_PROSE_MARKERS.none { marker -> contains(marker, ignoreCase = true) }

    /*
     * ================================================================================
     * 步骤3：保留 JavDB 演员性别证据
     * ================================================================================
     * 目标：下游多源融合时，不因 JavLibrary/JavBus 未提供性别而重新加入男演员。
     * 数据源：JavDB 演员链接后的 female/male 标记。
     * 操作：
     * 1) 先解析完整演员条目，保留姓名、头像和性别。
     * 2) 由调用方分别取女演员列表和明确排除的男演员列表。
     */
    private fun parseActorEntries(html: String): List<JavdbActor> {
        val pattern = Regex(
            """<a[^>]+href=[\"']([^\"']*/actors/[^\"']+)[\"'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        val actorSection = actorSection(html)
        return pattern.findAll(actorSection ?: html)
            .mapNotNull { match ->
                val href = absoluteUrl(match.groupValues[1])
                val slug = URLDecoder.decode(
                    href.substringBefore('?').trimEnd('/').substringAfterLast('/'),
                    Charsets.UTF_8.name()
                ).trim()
                val anchorHtml = match.value
                val name = match.groupValues[2].cleanText()
                    .ifBlank { attributeValue(anchorHtml, "title") }
                    .ifBlank { attributeValue(anchorHtml, "data-title") }
                    .ifBlank { attributeValue(anchorHtml, "alt") }
                if (slug.isBlank() || name.isBlank() || isNonActorCategory(slug, name)) return@mapNotNull null
                val gender = actorGender(anchorHtml, (actorSection ?: html), match.range.last + 1)
                val group = slug.take(2).lowercase(Locale.ROOT)
                JavdbActor(
                    name = name,
                    imageUrl = "https://c0.jdbstatic.com/avatars/$group/$slug.jpg",
                    gender = gender,
                    profileUrl = href
                )
            }
            .distinctBy { it.name }
            .toList()
    }

    /*
     * ================================================================================
     * 步骤1：读取 JavDB 演员性别标记
     * ================================================================================
     * 目标：避免把男演员误当成女演员别名，保持演员列表和头像回查的一致性。
     * 数据源：演员链接后紧邻的 female/male 标记节点。
     * 操作：
     * 1) 只检查当前演员链接之后的短区间，避免串到下一个演员。
     * 2) 明确标记为 male 时丢弃；未知标记仍保留。
     */
    private fun actorGender(anchorHtml: String, section: String, afterAnchorIndex: Int): JavdbActorGender {
        val anchorClasses = attributeValue(anchorHtml, "class")
            .split(Regex("""\s+"""))
            .map { value -> value.lowercase(Locale.ROOT) }
        if (anchorClasses.any { value -> value == "actor-male" || value == "male" }) {
            return JavdbActorGender.Male
        }
        if (anchorClasses.any { value -> value == "actor-female" || value == "female" }) {
            return JavdbActorGender.Female
        }
        val tail = section.substring(afterAnchorIndex.coerceIn(0, section.length))
        val marker = GENDER_MARKER.find(tail.take(GENDER_LOOKAHEAD_CHARS))
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
        return when (marker) {
            "male" -> JavdbActorGender.Male
            "female" -> JavdbActorGender.Female
            else -> JavdbActorGender.Unknown
        }
    }

    private fun actorSection(html: String): String? {
        val label = Regex(
            """<strong\b[^>]*>\s*(?:演員|演员)\s*[:：]?\s*</strong>""",
            RegexOption.IGNORE_CASE
        ).find(html) ?: return null
        val tail = html.substring(label.range.last + 1)
        val nextField = Regex(
            """<div\b[^>]*class=[\"'][^\"']*\bpanel-block\b""",
            RegexOption.IGNORE_CASE
        ).find(tail)?.range?.first ?: tail.length
        return tail.substring(0, nextField)
    }

    private fun isNonActorCategory(slug: String, name: String): Boolean {
        val normalizedSlug = slug.trim().lowercase(Locale.ROOT)
        return normalizedSlug in NON_ACTOR_CATEGORY_SLUGS || isNonActorCategoryName(name)
    }

    /*
     * ================================================================================
     * 步骤2：选择 WebView 或 OkHttp 请求入口
     * ================================================================================
     * 目标：JavDB 普通请求被 403 时，复用真实 WebView 的浏览器状态。
     * 数据源：应用级 WebView 队列、设置页 Cookie 和当前 URL。
     * 操作：
     * 1) 应用运行时优先等待隐藏 WebView 返回 HTML。
     * 2) 未注入 WebView 时沿用原有 Cookie + OkHttp 请求。
     */
    private suspend fun fetch(url: String): String {
        webViewFetcher?.let { fetcher ->
            logger?.invoke("JavDB 使用 WebView 请求：$url")
            return fetcher.fetch(url)
        }
        val cookie = cookieProvider().trim()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", "https://javdb.com/")
        if (cookie.isNotBlank()) requestBuilder.header("Cookie", cookie)
        val request = requestBuilder.build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("JavDB 请求失败 HTTP ${response.code}: $url")
            val body = response.body ?: error("JavDB 响应为空：$url")
            val bytes = body.bytes()
            val html = String(bytes, body.contentType()?.charset() ?: detectCharset(bytes))
            if (html.isJavdbGeoBlockedHtml()) {
                error("JavDB 当前网络出口所在地区被禁止访问")
            }
            html
        }
    }

    private fun String.isJavdbGeoBlockedHtml(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return "copyright restrictions" in lower &&
            "prohibited in the country" in lower
    }

    private fun textByRegex(html: String, regex: Regex): String =
        regex.find(html)?.groupValues?.getOrNull(1)?.cleanText().orEmpty()

    /*
     * ================================================================================
     * 步骤2：校验 JavDB 详情页番号
     * ================================================================================
     * 目标：WebView 搜索被重定向时，不把相邻番号的详情误写入当前影片。
     * 数据源：详情页“番號”字段、复制番号属性和标题首个 strong 节点。
     * 操作：
     * 1) 优先读取页面明确标注的复制番号。
     * 2) 页面没有该字段时再读取标题中的番号提示。
     * 3) 字段缺失时交给已有搜索链接校验，兼容精简测试页和旧页面。
     */
    private fun parseDetailNumber(html: String): String {
        val clipboardNumber = Regex(
            """(?is)<strong\b[^>]*>\s*(?:番號|番号)\s*[:：]?\s*</strong>[\s\S]{0,500}?data-clipboard-text=[\"']([^\"']+)[\"']"""
        ).find(html)?.groupValues?.getOrNull(1).orEmpty()
        if (clipboardNumber.isNotBlank()) return clipboardNumber.cleanText()
        return Regex(
            """(?is)<h2\b[^>]*class=[\"'][^\"']*title[^\"']*[\"'][^>]*>\s*<strong\b[^>]*>\s*([^<]+)"""
        ).find(html)?.groupValues?.getOrNull(1)?.cleanText().orEmpty()
    }

    private fun textNearLabel(html: String, vararg labels: String): String {
        labels.forEach { label ->
            val value = Regex(
                """${Regex.escape(label)}[\s\S]{0,260}?(?:</span>|</div>|</p>)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.value?.let(::cleanHtml).orEmpty()
            if (value.isNotBlank()) return value.removePrefix(label).trim(' ', ':', '：')
        }
        return ""
    }

    private fun parseLinksNearLabel(html: String, vararg labels: String): List<String> {
        labels.forEach { label ->
            val area = Regex(
                """${Regex.escape(label)}[\s\S]{0,900}?(?:</div>|</p>)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.value.orEmpty()
            val links = Regex("""<a[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                .findAll(area)
                .map { it.groupValues[1].cleanText() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            if (links.isNotEmpty()) return links
        }
        return emptyList()
    }

    private fun metaContent(html: String, name: String): String =
        Regex(
            """<meta[^>]+(?:property|name)=[\"']${Regex.escape(name)}[\"'][^>]+content=[\"']([^\"']*)[\"']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.cleanText().orEmpty()

    private fun attributeValue(html: String, name: String): String =
        Regex("""\b${Regex.escape(name)}\s*=\s*[\"']([^\"']*)[\"']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.cleanText().orEmpty()

    private fun absoluteUrl(value: String): String {
        val clean = value.cleanText()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "https://javdb.com$clean"
            clean.startsWith("http", ignoreCase = true) -> clean
            else -> "https://javdb.com/$clean"
        }
    }

    private fun String.isJavdbDetailUrl(): Boolean = runCatching {
        toHttpUrl().host.equals("javdb.com", ignoreCase = true)
    }.getOrDefault(false)

    private fun normalizeNumber(number: String): String {
        val match = Regex("""(?i)([a-z]{2,12})[-_ ]?(\d{2,6})""").find(number)
            ?: return number.trim().uppercase(Locale.ROOT)
        return "${match.groupValues[1].uppercase(Locale.ROOT)}-${match.groupValues[2]}"
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        val head = bytes.decodeToString(endIndex = minOf(bytes.size, 4096))
        val name = Regex("""charset=[\"']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.getOrNull(1)
        return runCatching { name?.let(Charset::forName) }.getOrNull() ?: Charsets.UTF_8
    }

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

    internal data class JavdbActor(
        val name: String,
        val imageUrl: String,
        val gender: JavdbActorGender = JavdbActorGender.Unknown,
        val profileUrl: String = ""
    )

    private data class ResolvedActorProfiles(
        val actors: List<JavdbActor>,
        val aliases: Map<String, List<String>> = emptyMap(),
        val verifiedNames: List<String> = emptyList()
    )

    internal enum class JavdbActorGender {
        Female,
        Male,
        Unknown
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        val NON_ACTOR_CATEGORY_SLUGS = setOf("censored", "uncensored", "western")
        private const val ACTOR_PROFILE_ALIAS_LIMIT = 12
        private const val GENDER_LOOKAHEAD_CHARS = 180
        private val GENDER_MARKER = Regex(
            """^\s*(?:<strong|<span)\b[^>]*class=[\"'][^\"']*\b(female|male)\b[^\"']*[\"'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        private val ACTOR_PROFILE_METADATA = Regex(
            """<span\b[^>]+class=[\"'][^\"']*\bsection-meta\b[^\"']*[\"'][^>]*>([\s\S]*?)</span>""",
            RegexOption.IGNORE_CASE
        )
        private val MALE_ACTOR_PROFILE_MARKERS = setOf("男優", "男优", "男演員", "男演员", "male")
        private val FEMALE_ACTOR_PROFILE_MARKERS = setOf("女優", "女优", "女演員", "女演员", "female")
        private val ACTOR_PROFILE_ROLE_MARKERS =
            MALE_ACTOR_PROFILE_MARKERS + FEMALE_ACTOR_PROFILE_MARKERS
        private val REVIEW_LINE_BREAK = Regex("""<br\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val REVIEW_ACTOR_LABEL = Regex(
            """^(?:演員|演员|女優|女优|出演者)\s*[:：]\s*(.+)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val REVIEW_ACTOR_SEPARATOR = Regex("""[\r\n、，,/／|;；]+""")
        private val REVIEW_ACTOR_NAME = Regex("""^[\p{L}\p{M}・ー々〆ヵヶ.]+$""")
        private val REVIEW_PROSE_MARKERS = listOf(
            "期待", "可以", "好看", "漂亮", "姐姐", "老師", "老师", "影片", "作品", "名字",
            "沒有", "没有", "這個", "这个", "那個", "那个", "女優", "女优", "演員", "演员"
        )
    }
}
