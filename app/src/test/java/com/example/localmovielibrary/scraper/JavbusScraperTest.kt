package com.example.localmovielibrary.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JavbusScraperTest {
    @Test
    fun genresAreNotCopiedToTags() = runBlocking {
        val html = """<h3>ABC-123 Test</h3><span class="genre"><label><a>分類</a></label></span>"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(html.toResponseBody()).build()
        }.build()
        val info = JavbusScraper(client, Dispatchers.Unconfined).scrape("ABC-123")
        assertEquals(listOf("分類"), info.genres)
        assertEquals(emptyList<String>(), info.tags)
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
}
