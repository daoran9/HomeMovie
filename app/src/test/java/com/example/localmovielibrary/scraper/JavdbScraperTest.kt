package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JavdbScraperTest {
    private val scraper = JavdbScraper()

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
                <a href="/actors/female2">女演员乙</a><strong class="symbol female">♀</strong>&nbsp;
              </span>
            </div>
            <div class="panel-block">
              <strong>片商:</strong>
              <span class="value"><a href="/makers/m1">制作商</a></span>
            </div>
        """.trimIndent()

        val actors = scraper.parseActors(html)

        assertEquals(listOf("女演员甲", "女演员乙"), actors.map { it.name })
        assertEquals(
            listOf(JavdbScraper.JavdbActorGender.Female, JavdbScraper.JavdbActorGender.Female),
            actors.map { it.gender }
        )
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
