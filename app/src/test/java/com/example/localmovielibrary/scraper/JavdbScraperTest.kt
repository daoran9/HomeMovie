package com.example.localmovielibrary.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JavdbScraperTest {
    private val scraper = JavdbScraper()

    @Test
    fun genresAreNotCopiedToTags() {
        val html = """<title>Test - JavDB</title><div>類別:<a>分類</a></div>"""
        val info = scraper.parseDetail("ABC-123", "https://javdb.com/v/abc", html)
        assertEquals(listOf("分類"), info.genres)
        assertEquals(emptyList<String>(), info.tags)
    }

    @Test
    fun findDetailUrlMatchesExactNumberWithSeparators() {
        val html = """
            <a href="https://www.javlibrary.com/v/not-javdb"><span>ABC-123</span></a>
            <a href="/v/wrong"><span>ABC-1234</span></a>
            <a class="item" href="/v/right"><strong>ABC-123</strong></a>
        """.trimIndent()

        assertEquals("https://javdb.com/v/right", scraper.findDetailUrl(html, "abc123"))
    }

    @Test
    fun findDetailUrlDoesNotCrossMatchLongerCatalogPrefix() {
        val html = """
            <a class="item" href="/v/wrong"><strong>HNAMH-022</strong></a>
            <a class="item" href="/v/right"><strong>NAMH-022</strong></a>
        """.trimIndent()

        assertEquals("https://javdb.com/v/right", scraper.findDetailUrl(html, "NAMH-022"))
    }

    @Test
    fun parseActorsUsesActorIdForJdbStaticAvatarPath() {
        val html = """
            <a href="/actors/Ab123" title="演员甲"><img alt="演员甲"></a>
            <a href="/actors/xy987">演员乙</a>
        """.trimIndent()

        val actors = scraper.parseActors(html)

        assertEquals(
            listOf("演员甲", "演员乙"),
            actors.map { it.name }
        )
        assertEquals("https://c0.jdbstatic.com/avatars/ab/Ab123.jpg", actors.first().imageUrl)
        assertEquals("https://c0.jdbstatic.com/avatars/xy/xy987.jpg", actors[1].imageUrl)
    }

    @Test
    fun parseActorsIgnoresCategoryLinks() {
        val html = """
            <a href="/actors/censored">有碼</a>
            <a href="/actors/uncensored">無碼</a>
            <a href="/actors/western">歐美</a>
            <a href="/actors/ab123">演员甲</a>
        """.trimIndent()

        val actors = scraper.parseActors(html)

        assertEquals(listOf("演员甲"), actors.map { it.name })
    }

    @Test
    fun parseActorsOnlyReadsTheMovieActorField() {
        val html = """
            <div class="panel-block">
              <strong>演員:</strong>
              <span class="value"><a href="/actors/a1">演员甲</a></span>
            </div>
            <div class="panel-block">
              <strong>片商:</strong>
              <a href="/actors/unrelated">不相关演员</a>
            </div>
        """.trimIndent()

        val actors = scraper.parseActors(html)

        assertEquals(listOf("演员甲"), actors.map { it.name })
    }

    @Test
    fun parseActorsKeepsFemalePerformersAndSkipsMaleMarkers() {
        val html = """
            <div class="panel-block">
              <strong>演員:</strong>
              <span class="value">
                <a href="/actors/male1">男演员甲</a><strong class="symbol male">♂</strong>&nbsp;
                <a href="/actors/female1">女演员甲</a><strong class="symbol female">♀</strong>&nbsp;
                <a href="/actors/male2">男演员乙</a><strong class="symbol male">♂</strong>&nbsp;
                <a class="actor-female" href="/actors/female2">女演员乙</a>&nbsp;
                <a href="/actors/unknown">性别未标记演员</a>
              </span>
            </div>
            <div class="panel-block">
              <strong>片商:</strong>
              <span class="value"><a href="/makers/m1">制作商</a></span>
            </div>
        """.trimIndent()

        val actors = scraper.parseActors(html)

        assertEquals(listOf("女演员甲", "女演员乙", "性别未标记演员"), actors.map { it.name })
        assertEquals(
            listOf(
                JavdbScraper.JavdbActorGender.Female,
                JavdbScraper.JavdbActorGender.Female,
                JavdbScraper.JavdbActorGender.Unknown
            ),
            actors.map { it.gender }
        )
    }

    /*
     * ================================================================================
     * 步骤3：验证 JavDB 当前演员性别结构
     * ================================================================================
     * 目标：演员链接自身标记女演员、演员主页标记男演员时，只保留女演员。
     * 数据源：CEMN-003 当前详情页和演员主页的最小 HTML 结构。
     * 操作：
     * 1) 用 OkHttp 拦截器回放搜索页、详情页和两个演员主页。
     * 2) 确认主页的“男優”是性别证据，不进入演员别名。
     * 3) 无番号、姓名或片商分支，所有影片使用同一解析路径。
     */
    @Test
    fun scrapeExcludesMaleActorFromCurrentLinkAndProfileMarkup() = runBlocking {
        val pages = mapOf(
            "/search" to """<a href="/v/pkXZm"><strong>CEMN-003</strong></a>""",
            "/v/pkXZm" to """
                <html><head><title>覚醒注意 悔しがり目線。 佐々木あき - JavDB</title></head><body>
                  <div class="panel-block">
                    <strong>演員:</strong>
                    <span class="value">
                      <a class="actor-female" href="/actors/ZOM6">佐々木あき</a>,
                      <a href="/actors/d4EaB">市川哲也</a>
                    </span>
                  </div>
                </body></html>
            """.trimIndent(),
            "/actors/ZOM6" to """
                <span class="actor-section-name">佐々木あき</span>
                <span class="section-meta">180 部影片</span>
            """.trimIndent(),
            "/actors/d4EaB" to """
                <span class="actor-section-name">市川哲也</span>
                <span class="section-meta">男優, 1904 部影片</span>
            """.trimIndent()
        )
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val html = pages[chain.request().url.encodedPath]
                    ?: error("Unexpected JavDB test URL: ${chain.request().url}")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        val info = JavdbScraper(client, Dispatchers.Unconfined).scrape("CEMN-003")

        assertEquals(listOf("佐々木あき"), info.actors)
        assertEquals(listOf("市川哲也"), info.excludedActorNames)
        assertEquals(emptyMap<String, List<String>>(), info.actorAliases)
    }

    @Test
    fun parseDetailKeepsExplicitMaleNamesAsExclusionEvidence() {
        val html = """
            <html><head><title>示例标题 - JavDB</title></head><body>
              <strong>演員:</strong>
              <span class="value">
                <a href="/actors/male1">男演员甲</a><strong class="symbol male">♂</strong>
                <a href="/actors/female1">女演员甲</a><strong class="symbol female">♀</strong>
              </span>
            </body></html>
        """.trimIndent()

        val info = scraper.parseDetail("ABC-123", "https://javdb.com/v/abc", html)

        assertEquals(listOf("女演员甲"), info.actors)
        assertEquals(listOf("男演员甲"), info.excludedActorNames)
    }

    @Test
    fun parseDetailMapsActorNamesAndImages() {
        val html = """
            <html><head><title>示例标题 - JavDB</title></head>
            <body>
              <img src="/covers/abc.jpg" class="video-cover">
              <a href="/actors/a1">演员甲</a>
            </body></html>
        """.trimIndent()

        val info = scraper.parseDetail("ABC-123", "https://javdb.com/v/abc", html)

        assertEquals("示例标题", info.title)
        assertEquals(listOf("演员甲"), info.actors)
        assertEquals(
            "https://c0.jdbstatic.com/avatars/a1/a1.jpg",
            info.actorImageUrls["演员甲"]
        )
        assertNotNull(info.posterUrl)
        assertEquals("https://javdb.com/covers/abc.jpg", info.posterUrl)
    }

    @Test
    fun parseReviewActorNamesAcceptsOnlyPureMultilineNameLists() {
        val html = """
            <div class="content">
              <p>五十嵐清華<br />芦名ほのか<br />Nia<br />白姫かんな</p>
            </div>
            <div class="content">
              <p>非常喜欢这个姐姐<br />漂亮又温柔</p>
            </div>
        """.trimIndent()

        assertEquals(
            listOf("五十嵐清華", "芦名ほのか", "Nia", "白姫かんな"),
            scraper.parseReviewActorNames(html)
        )
    }

    /*
     * ================================================================================
     * 步骤5：验证短评演员候选边界
     * ================================================================================
     * 目标：普通双行评论不能成为候选，候选姓名必须由演员搜索卡片精确确认。
     * 数据源：普通评论、明确演员标签和 JavDB 演员搜索结果片段。
     * 操作：
     * 1) 拒绝无标签的双行普通评论。
     * 2) 接受明确标签中的单个姓名。
     * 3) 拒绝影片文本和相似演员名，只接受精确演员卡片。
     */
    @Test
    fun reviewActorFallbackRejectsOrdinaryCommentsAndRequiresExactActorSearchResult() {
        val ordinaryReview = """
            <div class="content"><p>演技自然<br />值得推荐</p></div>
        """.trimIndent()
        val labeledReview = """
            <div class="content"><p>演员：五十嵐清華</p></div>
        """.trimIndent()
        val searchHtml = """
            <a href="/v/not-an-actor"><strong>五十嵐清華</strong></a>
            <a href="/actors/wrong" title="五十嵐清華子"><strong>五十嵐清華子</strong></a>
            <a href="/actors/right" title="五十嵐清華"><strong>五十嵐清華</strong></a>
        """.trimIndent()

        assertEquals(emptyList<String>(), scraper.parseReviewActorNames(ordinaryReview))
        assertEquals(listOf("五十嵐清華"), scraper.parseReviewActorNames(labeledReview))
        assertEquals(true, scraper.hasExactActorSearchResult(searchHtml, "五十嵐清華"))
        assertEquals(false, scraper.hasExactActorSearchResult(searchHtml, "芦名ほのか"))
    }

    @Test
    fun parseDetailUsesReviewActorsWhenTheStructuredActorFieldIsEmpty() {
        val html = """
            <html><head><title>示例标题 - JavDB</title></head><body>
              <strong>演員:</strong><span class="value">N/A</span>
            </body></html>
        """.trimIndent()
        val reviewActors = listOf(
            JavdbScraper.JavdbActor(name = "演员甲", imageUrl = ""),
            JavdbScraper.JavdbActor(name = "演员乙", imageUrl = "")
        )

        val info = scraper.parseDetail(
            number = "ABC-123",
            url = "https://javdb.com/v/abc",
            html = html,
            resolvedActors = reviewActors,
            verifiedActorNames = listOf("演员甲")
        )

        assertEquals(listOf("演员甲", "演员乙"), info.actors)
        assertEquals(listOf("演员甲"), info.verifiedActorNames)
        assertEquals(emptyMap<String, String>(), info.actorImageUrls)
    }

    @Test
    fun parseActorProfileNamesReadsPrimaryNamesAndAliasesFromOneIdentity() {
        val html = """
            <div class="column actor-avatar">
              <span class="avatar" style="background-image: url(https://c0.jdbstatic.com/avatars/j2/J26Dq.jpg)"></span>
            </div>
            <h2 class="title is-4 has-text-justified">
              <span class="actor-section-name">星川舞, 安西天</span>
              <br />
              <span class="section-meta">星川まい, 椎名あかり, 中谷真白</span>
              <span class="section-meta">255 部影片</span>
            </h2>
        """.trimIndent()

        assertEquals(
            listOf("星川舞", "安西天", "星川まい", "椎名あかり", "中谷真白"),
            scraper.parseActorProfileNames(html)
        )
        assertEquals("https://c0.jdbstatic.com/avatars/j2/J26Dq.jpg", scraper.parseActorProfileImageUrl(html))
        assertEquals(
            "",
            scraper.parseActorProfileImageUrl(
                """<div class="actor-avatar"><span style="background-image: url(https://c0.jdbstatic.com/images/actor_unknow.jpg)"></span></div>"""
            )
        )
        assertEquals(
            JavdbScraper.JavdbActorGender.Male,
            scraper.parseActorProfileGender(
                """<span class="section-meta">男優, 1904 部影片</span>"""
            )
        )
    }

    @Test
    fun parseDetailRejectsADifferentCatalogNumber() {
        val html = """
            <html><body>
              <h2 class="title is-4"><strong>HNAMH-022 </strong><strong class="current-title">错误详情</strong></h2>
            </body></html>
        """.trimIndent()

        assertThrows(IllegalStateException::class.java) {
            scraper.parseDetail("NAMH-022", "https://javdb.com/v/wrong", html)
        }
    }
}
