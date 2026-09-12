package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DmmScraperTest {
    private val scraper = DmmScraper()

    private fun capturedHtml(name: String): String = System.getProperty("dmm.captureDir")?.let {
        java.io.File(it, "$name.html").readText()
    } ?: javaClass.getResource("/dmm/$name.html")!!.readText()

    @Test
    fun capturedDvdAndRentalPagesParseRealFieldShapes() {
        val dvd = capturedHtml("dvd-mobile")
        val rental = capturedHtml("rental-mobile")
        val dvdInfo = scraper.parseDetail(dvd, "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=118onet012/", "ONET-012")
        val rentalInfo = scraper.parseDetail(rental, "https://www.dmm.co.jp/rental/ppr/-/detail/=/cid=118onet012r/", "ONET-012")
        val desktopInfo = scraper.parseDetail(capturedHtml("dvd-desktop"), "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=118onet012/", "ONET-012")

        assertEquals("媚薬快楽依存変態中毒ドエロヤンデレ女子校生生中出し孕ませSEX アイネちゃん 神楽アイネ", dvdInfo.title)
        assertTrue(dvdInfo.plot.length > 150)
        assertEquals("2016-12-16", dvdInfo.premiered)
        assertEquals("130", dvdInfo.runtime)
        assertEquals("4.43", dvdInfo.rating)
        assertEquals("https://pics.dmm.co.jp/mono/movie/adult/118onet012/118onet012ps.jpg", dvdInfo.posterUrl)
        assertEquals("", rentalInfo.premiered)
        assertEquals(dvdInfo.plot, rentalInfo.plot)
        assertEquals("130", rentalInfo.runtime)
        assertEquals("4.43", rentalInfo.rating)
        assertEquals("https://pics.dmm.co.jp/mono/movie/118onet012r/118onet012rps.jpg", rentalInfo.posterUrl)
        assertEquals(dvdInfo.plot, desktopInfo.plot)
        assertEquals(dvdInfo.title, desktopInfo.title)
        assertEquals(dvdInfo.premiered, desktopInfo.premiered)
        assertTrue(dvdInfo.trailer.startsWith("https://cc3001.dmm.co.jp/pv/"))
        assertEquals(dvdInfo.trailer, rentalInfo.trailer)
        for (info in listOf(dvdInfo, rentalInfo, desktopInfo)) {
            assertEquals("130", info.runtime)
            assertEquals("ONE MORE", info.studio)
            assertEquals("TODOManic", info.publisher)
            assertEquals("媚薬快楽依存変態中毒", info.series)
            assertEquals(listOf("TODO"), info.directors)
            assertEquals(listOf("神楽アイネ"), info.actors)
            assertEquals(12, info.genres.size)
            assertEquals("4.43", info.rating)
            val nfo = org.jsoup.Jsoup.parse(NfoWriter.build(info), "", org.jsoup.parser.Parser.xmlParser())
            assertEquals(info.plot, nfo.selectFirst("plot")!!.text())
            assertEquals(info.plot, nfo.selectFirst("outline")!!.text())
            assertEquals("4.43", nfo.selectFirst("rating")!!.text())
        }
    }

    @Test
    fun capturedSearchPrefersDvdOverRental() {
        val html = capturedHtml("search-mobile")
        assertEquals(
            "/mono/dvd/-/detail/=/cid=118onet012/",
            java.net.URI(scraper.selectDetailUrl(html, "ONET-012")!!).path
        )
    }

    @Test
    fun rentalSuffixIsAcceptedOnlyOnRentalRoutes() {
        assertTrue(scraper.selectDetailUrl("<a href='/rental/ppr/-/detail/=/cid=118onet012r/'>Rental</a>", "ONET-012")!!.contains("118onet012r"))
        assertNull(scraper.selectDetailUrl("<a href='/mono/dvd/-/detail/=/cid=118onet012r/'>Other edition</a>", "ONET-012"))
        assertNull(scraper.selectDetailUrl("<a href='/rental/ppr/-/detail/=/cid=118honet012r/'>Other label</a>", "ONET-012"))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsDifferentPrimaryIdentityDespiteValidLookingTitle() {
        scraper.parseDetail("<link rel='canonical' href='https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=118onet013/'><h1>Title</h1>", "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=118onet012/", "ONET-012")
    }

    @Test
    fun desktopPlotIgnoresImageAndDeliveryInstructions() {
        val info = scraper.parseDetail("""
            <h1 id="title">DVD</h1>
            <div class="mg-b20"><p>画像をクリックして拡大</p></div>
            <div class="mg-b20 lh4">
              <p class="mg-b20">
                Story line one.<br>Story line two.
              </p>
              <div>Delivery instructions</div>
            </div>
        """.trimIndent(), "https://www.dmm.co.jp/", "ABC-123")
        assertEquals("Story line one. Story line two.", info.plot)
    }

    @Test
    fun currentDvdDefinitionListsAndProductCommentAreParsed() {
        val html = """
            <meta content="ONET title" property="og:title">
            <meta content="https://pics.dmm.co.jp/mono/movie/adult/118onet012/118onet012pl.jpg" property="og:image">
            <img src="https://pics.dmm.co.jp/mono/movie/adult/118onet012/118onet012ps.jpg">
            <section class="area-productcomment">
              <div class="area-productcomment-inner">
                <h2>商品コメント</h2>
                <p class="box-productcomment">普段は地味そうな彼女。<br>今日は特別な旅行。</p>
              </div>
            </section>
            <section class="area-productinfo">
              <dl class="box-genreinfo"><dt>ジャンル</dt><dd><a>女子校生</a><a>単体作品</a></dd></dl>
              <dl class="box-info"><dt>関連タグ</dt><dd><a>中出し</a><a>孕ませ</a></dd></dl>
              <dl class="box-info"><dt>出演者</dt><dd><a>神楽アイネ</a></dd></dl>
              <dl class="box-info"><dt>シリーズ</dt><dd><a>媚薬快楽依存変態中毒</a></dd></dl>
              <dl class="box-info"><dt>メーカー</dt><dd><a>ONE MORE</a></dd></dl>
              <dl class="box-info"><dt>レーベル</dt><dd><a>TODOMANIC</a></dd></dl>
              <dl class="box-info"><dt>監督</dt><dd><a>TODO</a></dd></dl>
              <dl class="box-info"><dt>発売日</dt><dd>2016/12/16</dd></dl>
              <dl class="box-info"><dt>収録時間</dt><dd>130分</dd></dl>
            </section>
            <div class="dcd-review__average"><strong>4.43点</strong></div>
            <section class="area-overview"><a class="play-btn" href="https://video.example/onet012.mp4"></a></section>
        """.trimIndent()

        val info = scraper.parseDetail(html, "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=118onet012/", "ONET-012")

        assertEquals("ONET title", info.title)
        assertEquals("普段は地味そうな彼女。 今日は特別な旅行。", info.plot)
        assertEquals("2016-12-16", info.premiered)
        assertEquals("2016", info.year)
        assertEquals("130", info.runtime)
        assertEquals("ONE MORE", info.studio)
        assertEquals("TODOMANIC", info.publisher)
        assertEquals("媚薬快楽依存変態中毒", info.series)
        assertEquals(listOf("TODO"), info.directors)
        assertEquals(listOf("神楽アイネ"), info.actors)
        assertEquals(listOf("女子校生", "単体作品"), info.genres)
        assertEquals(listOf("中出し", "孕ませ"), info.tags)
        assertEquals("4.43", info.rating)
        assertEquals("https://video.example/onet012.mp4", info.trailer)
        assertEquals("https://pics.dmm.co.jp/mono/movie/adult/118onet012/118onet012pl.jpg", info.thumbUrl)
        assertEquals("https://pics.dmm.co.jp/mono/movie/adult/118onet012/118onet012ps.jpg", info.posterUrl)
    }

    @Test
    fun capturedKeywordSectionSurvivesNfoNormalization() {
        val info = scraper.parseDetail(
            capturedHtml("dvd-keywords-mobile"),
            "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=h_955kv302/",
            "KV-302"
        )
        val rawTags = setOf("AV", "生中出し", "中出し", "ごっくん", "ノーカット", "フェラ", "竹内夏希", "FS.KnightsVisual")
        assertEquals(rawTags, info.tags.toSet())
        assertEquals(rawTags.size, info.tags.size)
        assertEquals(listOf("単体作品", "中出し", "フェラ", "ごっくん", "サンプル動画"), info.genres)
        assertEquals("2025-10-01", info.premiered)
        assertEquals("96", info.runtime)
        assertEquals("3.5", info.rating)

        val nfo = org.jsoup.Jsoup.parse(NfoWriter.build(info), "", org.jsoup.parser.Parser.xmlParser())
        assertEquals(listOf("单体作品", "中出", "フェラ", "吞精"), nfo.select("genre").map { it.text() })
        assertEquals(
            setOf("AV", "生中出し", "中出", "吞精", "ノーカット", "フェラ", "竹内夏希", "FS.KnightsVisual"),
            nfo.select("tag").map { it.text() }.toSet()
        )
    }

    @Test
    fun keywordGroupsPreserveSpacesAndIgnoreUnrelatedLinks() {
        val info = scraper.parseDetail("""
            <h1>DVD title</h1>
            <nav><ul class="box-taglink"><li><a>#Navigation</a></li></ul></nav>
            <dl><dt>ジャンル</dt><dd><a>Drama</a></dd></dl>
            <dl><dt>関連タグ</dt><dd><a>First Star</a><a>Legacy tag</a></dd></dl>
            <section class="area-keyword">
              <h2 class="ttl-keyword">関連タグ</h2>
              <ul class="box-taglink">
                <li><a> #First Star #AV </a></li>
                <li><a>#AV #Drama</a></li>
                <li><a> #Drama #AV </a></li>
                <li><a>#BIGセール #サンプル動画</a></li>
              </ul>
              <a>#Unrelated footer</a>
            </section>
        """.trimIndent(), "https://www.dmm.co.jp/", "ABC-123")

        assertEquals(listOf("First Star", "Legacy tag", "AV", "Drama", "BIGセール", "サンプル動画"), info.tags)
        assertEquals(listOf("Drama"), info.genres)
        val nfo = org.jsoup.Jsoup.parse(NfoWriter.build(info), "", org.jsoup.parser.Parser.xmlParser())
        assertEquals(listOf("First Star", "Legacy tag", "AV", "Drama"), nfo.select("tag").map { it.text() })
    }

    @Test
    fun dvdFieldsStayWithinTheirOwnCells() {
        val html = """
            <h1 id="title">DVD title</h1>
            <nav>シリーズ <a href="/general">一般作品DVD通販へ</a></nav>
            <meta content="https://pics.dmm.co.jp/118onet012pl.jpg" property="og:image">
            <img src="https://pics.dmm.co.jp/118onet012ps.jpg">
            <table>
            <tr><td>発売日：</td><td><span>2016/12/16</span></td></tr>
            <tr><th>収録時間：</th><td>134分</td></tr>
            <tr><td>メーカー：</td><td><a>ONE MORE</a></td></tr>
            <tr><td>シリーズ：</td><td>----</td></tr>
            <tr><td>ジャンル：</td><td><a>Drama</a></td></tr>
            </table>
        """.trimIndent()
        val info = scraper.parseDetail(html, "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=118onet012/", "ONET-012")
        assertEquals("2016-12-16", info.premiered)
        assertEquals("2016", info.year)
        assertEquals("134", info.runtime)
        assertEquals("ONE MORE", info.studio)
        assertEquals("", info.series)
        assertEquals(listOf("Drama"), info.genres)
        assertEquals("https://pics.dmm.co.jp/118onet012ps.jpg", info.posterUrl)
    }

    @Test
    fun missingDvdLabelsDoNotConsumeNavigationOrInventAPoster() {
        val info = scraper.parseDetail("""
            <h1 id="title">Test</h1>
            <meta property="og:image" content="https://images.example/cover.jpg">
            <nav>メーカー <a>Wrong maker</a> シリーズ <a>Wrong series</a></nav>
            <table><tr><td>配信開始日：</td><td>2020/01/02</td></tr></table>
        """.trimIndent(), "https://www.dmm.co.jp/", "ABC-123")
        assertEquals("", info.studio)
        assertEquals("", info.series)
        assertEquals("", info.runtime)
        assertTrue(info.tags.isEmpty())
        assertEquals("2020-01-02", info.premiered)
        assertEquals("https://images.example/cover.jpg", info.posterUrl)
    }

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
    fun selectDetailUrlAcceptsDvdCatalogPrefix() {
        val html = """
            <a href="/mono/dvd/-/detail/=/cid=h_1681ichk029/">ICHK-029</a>
        """.trimIndent()

        assertEquals(
            "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=h_1681ichk029/",
            scraper.selectDetailUrl(html, "ICHK-029")
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
    fun directDetailIdentityAcceptsDvdCatalogPrefix() {
        val html = """
            <meta property="og:url" content="https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=h_1681skjk024/" />
        """.trimIndent()

        assert(matchesDmmDirectDetailIdentity(html, "SKJK-024"))
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
