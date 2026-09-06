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

    @Test
    fun selectDetailUrlAcceptsOnlyLeadingZeroDifferencesInTheSameCatalogCode() {
        val html = """
            <a href="/mono/dvd/-/detail/=/cid=2dvmm344/">DVMM-344</a>
            <a href="/mono/dvd/-/detail/=/cid=dvmm344/">DVMM-344</a>
        """.trimIndent()

        assertEquals(
            "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=dvmm344/",
            scraper.selectDetailUrl(html, "DVMM-344")
        )
    }

    @Test
    fun selectDetailUrlAcceptsDmmReissueContentId() {
        val html = """
            <a href="/digital/videoa/-/detail/=/cid=1dandy414re/">DANDY-414</a>
        """.trimIndent()

        assertEquals(
            "https://www.dmm.co.jp/digital/videoa/-/detail/=/cid=1dandy414re/",
            scraper.selectDetailUrl(html, "DANDY-414")
        )
    }

    @Test
    fun selectDetailUrlAcceptsCurrentVideoContentUrl() {
        val html = """
            <a href="https://video.dmm.co.jp/av/content/?id=1dandy00414&amp;i3_ref=search">DANDY-414</a>
        """.trimIndent()

        assertEquals(
            "https://video.dmm.co.jp/av/content/?id=1dandy00414&i3_ref=search",
            scraper.selectDetailUrl(html, "DANDY-414")
        )
    }

    @Test
    fun selectDetailUrlPrefersCurrentVideoOverDvdForSameCatalogNumber() {
        val html = """
            <a href="/mono/dvd/-/detail/=/cid=1dandy00414/">DVD</a>
            <a href="https://video.dmm.co.jp/av/content/?id=1dandy00414">普通视频</a>
        """.trimIndent()

        assertEquals(
            "https://video.dmm.co.jp/av/content/?id=1dandy00414",
            scraper.selectDetailUrl(html, "DANDY-414")
        )
    }

    @Test
    fun selectDetailUrlRejectsTvPlusAndNonDmmHosts() {
        val html = """
            <a href="https://tv.dmm.co.jp/av/content/?id=1dandy00414">TV Plus</a>
            <a href="https://example.com/av/content/?id=1dandy00414">外部站点</a>
        """.trimIndent()

        assertNull(scraper.selectDetailUrl(html, "DANDY-414"))
    }

    @Test
    fun dmmDirectContentIdsCoverDvdPrefixAndReissueVariants() {
        val ids = dmmDirectContentIds("DANDY-414")

        assertEquals("1dandy00414", ids.first())
        assert(ids.contains("18dandy414"))
    }

    @Test
    fun dmmDirectContentIdsCoverThreeDigitCemnVariant() {
        val ids = dmmDirectContentIds("CEMN-003")

        assertEquals("1cemn00003", ids.first())
        assert(ids.contains("18cemn00003"))
    }

    @Test
    fun directDetailUsesCurrentVideoProductRoute() {
        assertEquals(
            "https://video.dmm.co.jp/av/content/?id=1dandy00414",
            dmmDirectDetailUrl("1dandy00414")
        )
    }

    @Test
    fun dmmWebSearchTermsStartWithAllCategorySpaceFormat() {
        val terms = dmmWebSearchTerms("CEMN-003")

        assertEquals("CEMN 003", terms.first())
        assert(terms.contains("CEMN-003"))
        assert(terms.contains("CEMN00003"))
    }

    @Test
    fun dmmWebSearchRequestsTryUnmodifiedUpstreamAllSearchFirst() {
        val requests = dmmWebSearchRequests("DANDY-414")

        assertEquals("upstream-all", requests.first().first)
        assertEquals(
            "https://www.dmm.co.jp/search/=/searchstr=DANDY-414/",
            requests.first().second
        )
        assertEquals(
            "https://www.dmm.co.jp/mono/dvd/-/search/=/searchstr=DANDY-414/",
            requests[1].second
        )
        assertEquals(
            "https://www.dmm.co.jp/digital/videoa/-/list/search/=/?searchstr=DANDY-414",
            requests[2].second
        )
        assert(requests.any { (_, url) -> url.contains("searchstr=DANDY%20414/limit=30") })
    }

    @Test
    fun directDetailIdentityAcceptsOfficialReissueAndCanonicalIds() {
        val html = """
            <meta property="og:url" content="https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=1dandy414re/" />
            <link href="https://video.dmm.co.jp/av/content/?id=1dandy00414" rel="canonical" />
        """.trimIndent()

        assert(matchesDmmDirectDetailIdentity(html, "DANDY-414"))
        assertEquals(listOf("1dandy414re", "1dandy00414"), dmmDirectDetailIdentityIds(html))
    }

    @Test
    fun directDetailIdentityAcceptsDvdNumericPrefix() {
        val html = """
            <meta property="og:url" content="https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=18cemn003/" />
        """.trimIndent()

        assert(matchesDmmDirectDetailIdentity(html, "CEMN-003"))
    }

    @Test
    fun directDetailIdentityRejectsCidOutsidePrimaryIdentityFields() {
        val html = """
            <meta property="og:url" content="https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=1other999/" />
            <a href="https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=1dandy414re/">推荐商品</a>
            <script>var requestedUrl = "/detail/=/cid=1dandy414re/";</script>
        """.trimIndent()

        assert(!matchesDmmDirectDetailIdentity(html, "DANDY-414"))
    }

    @Test
    fun responseDestinationKeepsHostAndTopLevelPath() {
        assertEquals("www.dmm.co.jp/search", dmmResponseDestination("https://www.dmm.co.jp/search/=/searchstr=dandy%20414/"))
        assertEquals("www.dmm.co.jp/age_check", dmmResponseDestination("https://www.dmm.co.jp/age_check/=/?rurl=test"))
        assertEquals("tv.dmm.co.jp/list", dmmResponseDestination("https://tv.dmm.co.jp/list/?keyword=dandy%20414"))
        assertEquals("video.dmm.co.jp/av", dmmResponseDestination("https://video.dmm.co.jp/av/content/?id=1dandy00414"))
        assertEquals("special.fanza.jp/not-available-in-your-region", dmmResponseDestination("https://special.fanza.jp/not-available-in-your-region/"))
    }
}
