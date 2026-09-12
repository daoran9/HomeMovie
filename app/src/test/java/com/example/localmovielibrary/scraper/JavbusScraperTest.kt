package com.example.localmovielibrary.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class JavbusScraperTest {
    @Test
    fun genresAreNotCopiedToTags() = runBlocking {
        val html = """<h3>ABC-123 Test</h3><div class="info"></div><span class="genre"><label><a>分類</a></label></span>"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(html.toResponseBody()).build()
        }.build()
        val info = JavbusScraper(client, Dispatchers.Unconfined).scrape("ABC-123")
        assertEquals(listOf("分類"), info.genres)
        assertEquals(emptyList<String>(), info.tags)
    }

    @Test
    fun savedCookieIsSentWithDetailRequest() = runBlocking {
        val requestCookie = AtomicReference<String>()
        val html = """<h3>ABC-123 Test</h3><div class="info"></div>"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestCookie.set(chain.request().header("Cookie"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(html.toResponseBody()).build()
        }.build()

        JavbusScraper(client, Dispatchers.Unconfined) { "age=verified; PHPSESSID=session" }
            .scrape("ABC-123")

        assertEquals("age=verified; PHPSESSID=session", requestCookie.get())
    }

    @Test
    fun ageVerificationPageIsRejectedForAwtn003() {
        val html = """
            <title>Age Verification JavBus - JavBus</title>
            <div class="modal show" id="ageVerify"><h3>所在地區年齡檢測</h3></div>
        """.trimIndent()

        val error = assertThrows(IllegalStateException::class.java) {
            scrapeHtml("AWTN-003", html)
        }

        assertTrue(error.message.orEmpty().contains("年龄确认"))
    }

    @Test
    fun searchPageIsRejectedForCemn003() {
        val html = """
            <title>CEMN-003 搜尋結果 - JavBus</title>
            <h3>CEMN-003 搜尋結果</h3>
            <div class="movie-box"></div>
        """.trimIndent()

        val error = assertThrows(IllegalStateException::class.java) {
            scrapeHtml("CEMN-003", html)
        }

        assertTrue(error.message.orEmpty().contains("不是当前影片详情页"))
    }

    @Test
    fun notFoundPageIsRejectedForAbc123() {
        val error = assertThrows(IllegalStateException::class.java) {
            scrapeHtml("ABC-123", "<title>404 Not Found - JavBus</title>")
        }

        assertTrue(error.message.orEmpty().contains("不是当前影片详情页"))
    }

    @Test
    fun differentCatalogNumberIsRejected() {
        val html = """<h3>ABC-124 Other Movie</h3><div class="info"></div>"""

        val error = assertThrows(IllegalStateException::class.java) {
            scrapeHtml("ABC-123", html)
        }

        assertTrue(error.message.orEmpty().contains("不是当前影片详情页"))
    }

    @Test
    fun parseActorImageUrlsDoesNotBorrowImageFromNextActorBlock() {
        val html = """
            <div class="star-box"><a title="演员甲"></a></div>
            <div class="star-box"><a><img src="/pics/actress/b.jpg" title="演员乙"></a></div>
        """.trimIndent()

        val result = JavbusScraper().parseActorImageUrls(html)

        assertFalse(result.containsKey("演员甲"))
        assertEquals("https://www.javbus.com/pics/actress/b.jpg", result["演员乙"])
    }

    @Test
    fun parseActorImageUrlsAcceptsImageAttributesInEitherOrder() {
        val html = """
            <div class="star-box"><img title="演员甲" src="https://cdn.example/a.jpg"></div>
        """.trimIndent()

        val result = JavbusScraper().parseActorImageUrls(html)

        assertEquals("https://cdn.example/a.jpg", result["演员甲"])
    }

    @Test
    fun genericMetadataDescriptionIsNotUsableAsMoviePlot() {
        val description = "【發行日期】2015-03-05,【長度】189分鐘,(DANDY-414) 官方标题"

        assert(isGenericMovieDescription(description, "DANDY-414"))
        assertFalse(isGenericMovieDescription("真正的剧情正文", "DANDY-414"))
    }

    private fun scrapeHtml(number: String, html: String) = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(html.toResponseBody()).build()
        }.build()
        JavbusScraper(client, Dispatchers.Unconfined).scrape(number)
    }
}
