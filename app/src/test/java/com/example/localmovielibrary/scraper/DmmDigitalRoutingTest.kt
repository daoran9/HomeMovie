package com.example.localmovielibrary.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DmmDigitalRoutingTest {
    private fun fixture(name: String) = javaClass.getResource(name)!!.readText()

    @Test fun realSearchUsesStructuredDetailInsteadOfFetchingClientShell() = runBlocking {
        var detailRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.method == "POST") {
                detailRequests++
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val payload = JSONObject(buffer.readUtf8())
                assertEquals("anav00002", payload.getJSONObject("variables").getString("id"))
                assertNotEquals("AvSearch", payload.getString("operationName"))
                fixture("/dmm2/anav00002-detail.json")
            } else {
                assertEquals("www.dmm.co.jp", request.url.host)
                fixture("/dmm/anav-search.html")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val info = DmmScraper(client, Dispatchers.Unconfined).scrape("ANAV-002")
        assertEquals(1, detailRequests)
        assertEquals("ANAV-002", info.number)
        assertEquals("221", info.runtime)
        assertTrue(info.plot.isNotBlank())
        assertTrue(info.trailer.isNotBlank())
        assertEquals("dmm2", info.source)
    }

    @Test fun alreadyFetchedSameCidIsReusedWithoutAnotherDetailRequest() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals("www.dmm.co.jp", request.url.host)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(fixture("/dmm/anav-search.html").toResponseBody()).build()
        }.build()
        val digital = ScrapedMovieInfo("ANAV-002", "Digital", source = "dmm2",
            website = "https://video.dmm.co.jp/av/content/?id=anav00002")
        assertSame(digital, DmmScraper(client, Dispatchers.Unconfined).scrape("ANAV-002", digital))
    }

    @Test fun incorrectReturnedCidIsRejected() = runBlocking {
        val json = JSONObject(fixture("/dmm2/anav00002-detail.json"))
        json.getJSONObject("data").getJSONObject("ppvContent").put("id", "anav00003")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toString().toResponseBody()).build()
        }.build()
        var rejected = false
        try { Dmm2Scraper(client, Dispatchers.Unconfined).scrapeContentId("ANAV-002", "anav00002") }
        catch (error: IllegalStateException) { rejected = error.message!!.contains("不匹配") }
        assertTrue(rejected)
    }

    @Test fun realShellIsNotMistakenForAProductDescription() {
        val html = fixture("/dmm/anav-client-shell.html")
        assertTrue(html.contains("BAILOUT_TO_CLIENT_SIDE_RENDERING"))
        assertThrows(IllegalStateException::class.java) {
            DmmScraper().parseDetail(html, "https://video.dmm.co.jp/av/content/?id=anav00002", "ANAV-002")
        }
        assertNull(dmmDigitalContentId("https://example.com/av/content/?id=anav00002"))
    }

    @Test fun disabledDigitalSourceStillAllowsDvdSelection() {
        val html = """
            <a href="https://video.dmm.co.jp/av/content/?id=anav00002">Digital</a>
            <a href="https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=anav002/">DVD</a>
        """.trimIndent()
        val url = DmmScraper().selectDetailUrl(html, "ANAV-002", allowDigital = false)
        assertTrue(url!!.contains("/mono/dvd/"))
        assertNull(DmmScraper().selectDetailUrl(fixture("/dmm/anav-search.html"), "ANAV-002", allowDigital = false))
    }
}
