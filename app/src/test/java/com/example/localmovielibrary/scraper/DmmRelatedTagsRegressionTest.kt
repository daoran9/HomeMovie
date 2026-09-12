package com.example.localmovielibrary.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class DmmRelatedTagsRegressionTest {
    private val rawGenres = listOf("パンスト・タイツ", "キャバ嬢・風俗嬢", "中出し", "ハイビジョン", "単体作品")
    // D:/Desktop/1.docx, confirmed by the user to describe AWTN-003 only.
    private val documentTags = listOf("ソープ", "中出し", "淫語", "風俗", "美女", "ムチムチ", "風俗嬢",
        "ソープ嬢", "勃起", "ザーメン", "プレイ", "ボディ", "中だし")
    private val allTags = documentTags + listOf("快楽", "スケベ", "単体", "パンストタイツ", "中に出して",
        "刺激", "中だしソープ", "金玉", "なかだし")

    private fun capturedInfo(route: String): ScrapedMovieInfo = runBlocking {
        val detail = javaClass.getResource("/dmm2/awtn00003-detail.json")!!.readText()
        var detailRequests = 0
        var webRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = if (request.method == "POST") {
                assertEquals("api.video.dmm.co.jp", request.url.host)
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val payload = JSONObject(buffer.readUtf8())
                if (payload.getString("operationName") == "AvSearch") {
                    if (route == "direct") """{"data":{"legacySearchPPV":{"result":{"contents":[]}}}}"""
                    else """{"data":{"legacySearchPPV":{"result":{"contents":[{"id":"awtn00003","title":"AWTN-003"}]}}}}"""
                } else {
                    assertTrue(payload.getString("query").contains("relatedTags(limit: 50)"))
                    assertTrue(payload.getString("query").contains("... on ContentTagGroup"))
                    if (payload.getJSONObject("variables").getString("id") == "awtn00003") {
                        detailRequests++
                        detail
                    } else """{"data":{"ppvContent":null}}"""
                }
            } else {
                assertTrue(route == "web" || route == "reuse")
                assertEquals("www.dmm.co.jp", request.url.host)
                webRequests++
                """<a href="https://video.dmm.co.jp/av/content/?id=awtn00003">AWTN-003</a>
                    <script>{"content_id":"awtn00003","keywords":["Search-only keyword"]}</script>"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody()).build()
        }.build()
        val digital = Dmm2Scraper(client, Dispatchers.Unconfined, emptySearchRetryDelayMs = 0)
        val info = when (route) {
            "web" -> DmmScraper(client, Dispatchers.Unconfined).scrape("AWTN-003")
            "reuse" -> {
                val existing = digital.scrapeContentId("AWTN-003", "awtn00003")
                DmmScraper(client, Dispatchers.Unconfined).scrape("AWTN-003", existing).also {
                    assertSame(existing, it)
                }
            }
            else -> digital.scrape("AWTN-003")
        }
        assertEquals(1, detailRequests)
        assertEquals(if (route == "web" || route == "reuse") 1 else 0, webRequests)
        info
    }

    private fun assertClassifications(info: ScrapedMovieInfo) {
        assertEquals(rawGenres, info.genres)
        assertEquals(allTags.toSet(), info.tags.toSet())
        assertEquals(22, info.tags.size)
        assertTrue(info.tags.containsAll(documentTags))
        assertFalse(info.tags.contains("Search-only keyword"))
    }

    @Test fun capturedTagsSurviveSearchAndEmptySearchDirectLookup() {
        for (route in listOf("search", "direct")) assertClassifications(capturedInfo(route))
    }

    @Test fun capturedTagsSurviveWebRoutingAndSameCidReuse() {
        for (route in listOf("web", "reuse")) assertClassifications(capturedInfo(route))
    }

    @Test fun capturedTagsSurviveFusionAndNfoNormalization() = runBlocking {
        val official = capturedInfo("search")
        val external = ScrapedMovieInfo("AWTN-003", "External title", source = "javlibrary",
            genres = listOf("外部类型", "中出"))
        val registry = MovieScraperRegistry(listOf(
            object : MovieScraper {
                override val source = ScrapeSource.Dmm2
                override suspend fun scrape(number: String) = official
            },
            object : MovieScraper {
                override val source = ScrapeSource.Javlibrary
                override suspend fun scrape(number: String) = external
            }
        ))
        val merged = registry.scrapeWithDmmPriority("AWTN-003")
        val nfo = Jsoup.parse(NfoWriter.build(merged), "", Parser.xmlParser())
        val genres = nfo.select("genre").map { it.text() }
        val tags = nfo.select("tag").map { it.text() }
        assertEquals(listOf("パンスト・タイツ", "キャバ嬢・風俗嬢", "中出", "单体作品", "外部类型"), genres)
        assertEquals((allTags.map { if (it == "中出し") "中出" else it } + "高清").toSet(), tags.toSet())
        assertEquals(23, tags.size)
        assertFalse(tags.contains("外部类型"))
        assertTrue("中出" in genres && "中出" in tags)
    }
}
