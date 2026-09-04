package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DmmScraperTest {
    private val scraper = DmmScraper()

    @Test
    fun selectDetailUrlPrefersExactCatalogNumberOverLongerPrefix() {
        val html = """
            <a href="/digital/videoa/-/detail/=/cid=1hnamh00022/">HNAMH-022</a>
            <a href="/digital/videoa/-/detail/=/cid=1namh00022/">NAMH-022</a>
        """.trimIndent()

        assertEquals(
            "https://www.dmm.co.jp/digital/videoa/-/detail/=/cid=1namh00022/",
            scraper.selectDetailUrl(html, "NAMH-022")
        )
    }

    @Test
    fun selectDetailUrlRejectsOnlyLongerCatalogPrefix() {
        val html = """
            <a href="/digital/videoa/-/detail/=/cid=1hnamh00022/">HNAMH-022</a>
        """.trimIndent()

        assertNull(scraper.selectDetailUrl(html, "NAMH-022"))
    }
}
