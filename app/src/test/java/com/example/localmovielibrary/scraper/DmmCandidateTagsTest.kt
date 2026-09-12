package com.example.localmovielibrary.scraper

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class DmmCandidateTagsTest {
    private fun fixture(name: String): String = System.getProperty("dmm.candidateCaptureDir")?.let {
        File(it, "$name.html").readText()
    } ?: javaClass.getResource("/dmm/candidates/$name.html")!!.readText()

    private fun client(body: (String) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body(request.url.encodedPath).toResponseBody()).build()
    }.build()

    @Test fun capturedSearchSupplementsBothReissuesWithoutChangingPrimaryFields() = runBlocking {
        for (serial in listOf("062", "073")) {
            val number = "BONY-$serial"
            val originalUrl = "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=bony$serial/?i3_ref=search&i3_ord=4"
            val calls = mutableListOf<String>()
            val scraper = DmmScraper(client { path ->
                calls += path
                when (path) {
                    "/search/=/searchstr=$number/" -> fixture("$number-search-current")
                    "/mono/dvd/-/detail/=/cid=bony$serial/" -> fixture("bony$serial-proxy")
                    "/mono/dvd/-/detail/=/cid=7bony$serial/" -> fixture("7bony$serial-user-evidence")
                    else -> error("Unexpected request: $path")
                }
            }, Dispatchers.Unconfined)
            val original = scraper.parseDetail(fixture("bony$serial-proxy"), originalUrl, number)
            val result = scraper.scrape(number)
            assertEquals(original, result.copy(tags = original.tags))
            val expected = if (serial == "062") {
                setOf("AV", "ぶっかけ", "妄想族", "種付け", "鬼畜", "顔射", "中出し", "孕ませ", "妊娠")
            } else {
                setOf("AV", "妄想族", "ごっくん", "フェラ", "痴女", "中出し", "フェラチオ", "お姉さん", "ビッチ")
            }
            assertEquals(expected, result.tags.toSet())
            assertEquals(3, calls.size)
            assertEquals(calls.distinct(), calls)
            val nfo = Jsoup.parse(NfoWriter.build(result), "", Parser.xmlParser())
            assertEquals(expected.map { when (it) { "中出し" -> "中出"; "ごっくん" -> "吞精"; else -> it } }.toSet(),
                nfo.select("tag").eachText().toSet())
            assertEquals(original.premiered, nfo.selectFirst("premiered")!!.text())
            assertEquals(original.publisher, nfo.selectFirst("publisher")!!.text())
            assertFalse(nfo.select("genre,tag").eachText().any { it in setOf("アウトレット", "ベストヒッツ", "ベスト") })
        }
    }

    private val originalUrl = "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=abc012/"
    private val candidatePath = "/mono/dvd/-/detail/=/cid=9abc012/"
    private fun page(id: String = "abc00012", tags: String = "Primary tag", publisher: String = "Original label") = """
        <link rel="canonical" href="https://video.dmm.co.jp/av/content/?id=$id">
        <h1>Product title</h1>
        <dl><dt>発売日</dt><dd>2020/01/02</dd></dl>
        <dl><dt>レーベル</dt><dd>$publisher</dd></dl>
        <dl><dt>ジャンル</dt><dd><a>Primary genre</a></dd></dl>
        <section class="area-keyword"><ul class="box-taglink"><li><a>#$tags</a></li></ul></section>
    """.trimIndent()

    private fun search(extra: String = "") = """
        <a href="$candidatePath?i3_ord=1">Reissue</a>
        <a href="$candidatePath?i3_ord=2">Duplicate</a>
        <a href="$originalUrl">Original</a>
        <a href="/rental/ppr/-/detail/=/cid=2abc012/">Rental</a>
        <a href="/mono/dvd/-/detail/=/cid=9xabc012/">Different work</a>
        <a href="https://example.com/mono/dvd/-/detail/=/cid=abc012/">Foreign host</a>
        $extra
    """.trimIndent()

    @Test fun existingPrimaryTagsAreKeptAndSupplementPublisherDoesNotBecomeATag() = runBlocking {
        val calls = mutableListOf<String>()
        val scraper = DmmScraper(client { path ->
            calls += path
            when {
                path.startsWith("/search/") -> search()
                path == candidatePath -> page(tags = "New tag #Primary tag #Reissue label #ベスト #アウトレット", publisher = "Reissue label")
                path == "/mono/dvd/-/detail/=/cid=abc012/" -> page()
                else -> error("Unexpected request: $path")
            }
        }, Dispatchers.Unconfined)
        val result = scraper.scrape("ABC-012")
        assertEquals(setOf("Primary tag", "New tag"), result.tags.toSet())
        assertEquals(scraper.parseDetail(page(), originalUrl, "ABC-012"), result.copy(tags = listOf("Primary tag")))
        assertEquals(3, calls.size)
    }

    @Test fun onlySharedPageIdentityCanSupplyTags() = runBlocking {
        for (candidate in listOf(page("other00012", "Wrong"), "<h1>ABC-012</h1><a>#No identity</a>")) {
            val messages = mutableListOf<String>()
            val scraper = DmmScraper(client { path ->
                when {
                    path.startsWith("/search/") -> search()
                    path == candidatePath -> candidate
                    else -> page()
                }
            }, Dispatchers.Unconfined, messages::add)
            assertEquals(listOf("Primary tag"), scraper.scrape("ABC-012").tags)
            assertTrue(messages.any { "未提供共同作品身份" in it })
        }
    }

    @Test fun missingPrimaryIdentityDoesNotCauseSpeculativeRequests() = runBlocking {
        val calls = mutableListOf<String>()
        val scraper = DmmScraper(client { path ->
            calls += path
            if (path.startsWith("/search/")) search() else "<h1>Original</h1>"
        }, Dispatchers.Unconfined)
        assertTrue(scraper.scrape("ABC-012").tags.isEmpty())
        assertEquals(2, calls.size)
    }

    @Test fun optionalCandidateFailuresKeepPrimaryAndAreLogged() = runBlocking {
        for (failure in listOf(IOException("network unavailable"), IllegalStateException("invalid page"))) {
            val messages = mutableListOf<String>()
            val scraper = DmmScraper(client { path ->
                when {
                    path.startsWith("/search/") -> search()
                    path == candidatePath -> throw failure
                    else -> page()
                }
            }, Dispatchers.Unconfined, messages::add)
            assertEquals(listOf("Primary tag"), scraper.scrape("ABC-012").tags)
            assertTrue(messages.any { "保留主商品" in it && failure.message!! in it })
        }
    }

    @Test fun candidateCancellationIsNotSwallowed() {
        val scraper = DmmScraper(client { path ->
            when {
                path.startsWith("/search/") -> search()
                path == candidatePath -> throw CancellationException("cancelled")
                else -> page()
            }
        }, Dispatchers.Unconfined)
        assertThrows(CancellationException::class.java) { runBlocking { scraper.scrape("ABC-012") } }
    }

    @Test fun digitalReuseAndDisabledDigitalKeepTheirExistingBoundaries() = runBlocking {
        for (allowDigital in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val scraper = DmmScraper(client { path ->
                calls += path
                when {
                    path.startsWith("/search/") -> search("<a href='https://video.dmm.co.jp/av/content/?id=abc00012'>Digital</a>")
                    path == candidatePath -> page(tags = "Extra")
                    path == "/mono/dvd/-/detail/=/cid=abc012/" -> page()
                    else -> error("Unexpected request: $path")
                }
            }, Dispatchers.Unconfined)
            val digital = ScrapedMovieInfo("ABC-012", "Digital", source = "dmm2", tags = listOf("Digital tag"),
                website = "https://video.dmm.co.jp/av/content/?id=abc00012")
            val result = scraper.scrape("ABC-012", digital, allowDigital)
            val expected = if (allowDigital) digital else scraper.parseDetail(page(), originalUrl, "ABC-012")
            assertEquals(expected, result.copy(tags = expected.tags))
            assertTrue("Extra" in result.tags)
            assertEquals(3, calls.size)
        }
    }

    @Test fun supplementalTagsReachPriorityFusionWithoutMovingOtherFields() = runBlocking {
        val scraper = DmmScraper(client { path ->
            when {
                path.startsWith("/search/") -> search()
                path == candidatePath -> page(tags = "Extra #ベストヒッツ #アウトレット", publisher = "Reissue label")
                else -> page()
            }
        }, Dispatchers.Unconfined)
        val digital = object : MovieScraper {
            override val source = ScrapeSource.Dmm2
            override suspend fun scrape(number: String) = ScrapedMovieInfo(number, "Digital title", source = "dmm2",
                premiered = "2019-01-02", year = "2019", runtime = "90", publisher = "Digital label",
                genres = listOf("ハイビジョン"), tags = listOf("Digital tag"),
                website = "https://video.dmm.co.jp/av/content/?id=abc00012")
        }
        val baseline = object : MovieScraper {
            override val source = ScrapeSource.Dmm
            override suspend fun scrape(number: String) = scraper.parseDetail(page(), originalUrl, number)
        }
        val expected = MovieScraperRegistry(listOf(digital, baseline)).scrapeWithDmmPriority("ABC-012")
        val result = MovieScraperRegistry(listOf(digital, scraper)).scrapeWithDmmPriority("ABC-012")
        assertEquals(expected, result.copy(tags = expected.tags))
        assertEquals(expected.tags.toSet() + "Extra", result.tags.toSet())
        val nfo = Jsoup.parse(NfoWriter.build(result), "", Parser.xmlParser())
        assertEquals(result.tags.toSet(), nfo.select("tag").eachText().toSet())
        assertEquals("2019-01-02", nfo.selectFirst("premiered")!!.text())
        assertEquals("Digital label", nfo.selectFirst("publisher")!!.text())
    }
}
