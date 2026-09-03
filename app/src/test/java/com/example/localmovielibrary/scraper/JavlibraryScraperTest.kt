package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JavlibraryScraperTest {
    private val scraper = JavlibraryScraper()

    @Test
    fun findDetailUrlRequiresMatchingNumber() {
        val html = """
            <a href="/cn/vl_searchbyid.php?keyword=ABC-123">ABC-123</a>
            <div id="vid_javli123"><a href="/cn/?v=javli123"><span>ABC-123</span></a></div>
            <div id="vid_javli999"><a href="/cn/?v=javli999"><span>ABC-999</span></a></div>
        """.trimIndent()

        assertEquals(
            "https://www.javlibrary.com/cn/?v=javli123",
            scraper.findDetailUrl(html, "abc123")
        )
    }

    @Test
    fun findDetailUrlUsesCanonicalUrlAfterWebViewSearchRedirect() {
        val html = """
            <html>
              <head>
                <link rel="canonical" href="https://www.javlibrary.com/cn/javmeyipru.html">
              </head>
              <body>
                <div id="video_id"><span class="text">识别码: MSAJ-018</span></div>
                <div id="video_title"><h3>MSAJ-018</h3></div>
              </body>
            </html>
        """.trimIndent()

        assertEquals(
            "https://www.javlibrary.com/cn/javmeyipru.html",
            scraper.findDetailUrl(html, "MSAJ-018")
        )
    }

    @Test
    fun findDetailUrlDoesNotCrossMatchLongerCatalogPrefix() {
        val html = """
            <a href="/cn/?v=wrong"><span>HNAMH-022</span></a>
            <a href="/cn/?v=right"><span>NAMH-022</span></a>
        """.trimIndent()

        assertEquals(
            "https://www.javlibrary.com/cn/?v=right",
            scraper.findDetailUrl(html, "NAMH-022")
        )
    }

    @Test
    fun parseDetailMapsVideoFields() {
        val html = """
            <html><head><title>ABC-123 - JAVLibrary</title></head>
            <body>
              <div id="video_title"><h3>示例影片</h3></div>
              <div id="video_id"><span class="text">ABC-123</span></div>
              <div id="video_jacket"><img src="//pics.example/abc123.jpg"></div>
              <div id="video_date"><span class="text">2024-03-18</span></div>
              <div id="video_length"><span class="text">120 分钟</span></div>
              <div id="video_maker"><span class="text"><a>示例制作商</a></span></div>
              <div id="video_label"><span class="text"><a>示例发行商</a></span></div>
              <div id="video_director"><span class="text"><a>导演甲</a></span></div>
              <div id="video_cast"><span class="text"><a>演员甲</a><a>演员乙</a></span></div>
              <div id="video_genres"><span class="genre"><a>剧情</a></span><span class="genre"><a>办公室</a></span></div>
              <div id="video_review"><span class="score">评分 7.8</span></div>
            </body></html>
        """.trimIndent()

        val info = scraper.parseDetail("ABC-123", "https://www.javlibrary.com/cn/?v=javli123", html)

        assertEquals("示例影片", info.title)
        assertEquals("https://pics.example/abc123.jpg", info.posterUrl)
        assertEquals("2024", info.year)
        assertEquals("120", info.runtime)
        assertEquals(listOf("演员甲", "演员乙"), info.actors)
        assertEquals(listOf("剧情", "办公室"), info.genres)
        assertEquals("7.8", info.rating)
        assertTrue(info.actorImageUrls.isEmpty())
    }

    @Test
    fun parseDetailReadsActorAliasFromJavlibraryCast() {
        val html = """
            <html><head><title>MSAJ-004 - JAVLibrary</title></head>
            <body>
              <div id="video_title"><h3>MSAJ-004</h3></div>
              <div id="video_cast"><span class="text"><a>前澤あきな</a></span></div>
            </body></html>
        """.trimIndent()

        val info = scraper.parseDetail("MSAJ-004", "https://www.javlibrary.com/cn/javlioz6t4.html", html)

        assertEquals(listOf("前澤あきな"), info.actors)
    }

    @Test
    fun parseDetailKeepsAliasNodeInSameCastEntry() {
        val html = """
            <html><head><title>MSAJ-018 - JAVLibrary</title></head>
            <body>
              <div id="video_title"><h3>MSAJ-018</h3></div>
              <div id="video_cast"><table><tr>
                <td class="header">演员:</td>
                <td class="text"><span id="cast50137" class="cast"><span class="star"><a href="vl_star.php?s=aasu4">百合良</a></span> <span id="alias62262">(白都四季)</span></span></td>
                <td class="icon"></td>
              </tr></table></div>
            </body></html>
        """.trimIndent()

        val info = scraper.parseDetail("MSAJ-018", "https://www.javlibrary.com/cn/javmeyipru.html", html)

        assertEquals(listOf("百合良"), info.actors)
        assertEquals(listOf("白都四季"), info.actorAliases["百合良"])
    }

    @Test
    fun parseDetailKeepsSuffixAliasWhenThePageMarksItExplicitly() {
        val html = """
            <html><head><title>ABC-123 - JAVLibrary</title></head>
            <body>
              <div id="video_title"><h3>ABC-123</h3></div>
              <div id="video_cast"><table><tr>
                <td class="header">演员:</td>
                <td class="text"><span id="cast50137" class="cast"><span class="star"><a>宇流木さらら</a></span> <span id="alias62262">(宇流木さら)</span></span></td>
              </tr></table></div>
            </body></html>
        """.trimIndent()

        val info = scraper.parseDetail("ABC-123", "https://www.javlibrary.com/cn/?v=javli123", html)

        assertEquals(listOf("宇流木さらら"), info.actors)
        assertEquals(listOf("宇流木さら"), info.actorAliases["宇流木さらら"])
    }

    @Test
    fun parseDetailRejectsADifferentCatalogNumber() {
        val html = """
            <html><body>
              <div id="video_id"><span class="text">HNAMH-022</span></div>
              <div id="video_title"><h3>错误详情</h3></div>
            </body></html>
        """.trimIndent()

        assertThrows(IllegalStateException::class.java) {
            scraper.parseDetail("NAMH-022", "https://www.javlibrary.com/cn/wrong.html", html)
        }
    }
}
