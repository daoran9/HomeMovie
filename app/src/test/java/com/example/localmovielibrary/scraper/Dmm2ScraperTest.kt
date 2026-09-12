package com.example.localmovielibrary.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Dmm2ScraperTest {
    private fun capturedDetail() = JSONObject(
        javaClass.getResource("/dmm2/anav00002-detail.json")!!.readText()
    )

    private fun scrapeCapturedDetail(detail: JSONObject, direct: Boolean = false): ScrapedMovieInfo = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            val response = if (payload.getString("operationName") == "AvSearch") {
                if (direct) {
                    """{"data":{"legacySearchPPV":{"result":{"contents":[]}}}}"""
                } else {
                    """{"data":{"legacySearchPPV":{"result":{"contents":[{"id":"anav00002","title":"ANAV-002","sampleMovie":{"mp4Url":"https://example.com/stale.mp4"}}]}}}}"""
                }
            } else {
                val query = payload.getString("query")
                assertTrue(query.contains("sample2DMovie { highestMovieUrl hlsMovieUrl }"))
                assertTrue(query.contains("sampleVRMovie { highestMovieUrl }"))
                if (payload.getJSONObject("variables").getString("id") == "anav00002") {
                    detail.toString()
                } else {
                    """{"data":{"ppvContent":null}}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        Dmm2Scraper(client, Dispatchers.Unconfined, emptySearchRetryDelayMs = 0).scrape("ANAV-002")
    }

    @Test
    fun realDetailTrailerSurvivesSearchScrapeAndNfoWriting() {
        val detail = capturedDetail()
        val expected = detail.getJSONObject("data").getJSONObject("ppvContent")
            .getJSONObject("sample2DMovie").getString("highestMovieUrl")
        val info = scrapeCapturedDetail(detail)
        assertEquals(expected, info.trailer)
        assertEquals("ANAV-002", info.number)
        assertTrue(info.plot.isNotBlank())
        val nfo = org.jsoup.Jsoup.parse(NfoWriter.build(info), "", org.jsoup.parser.Parser.xmlParser())
        assertEquals(expected, nfo.selectFirst("trailer")!!.text())
    }

    @Test
    fun realDetailTrailerSurvivesEmptySearchAndDirectCidLookup() {
        val detail = capturedDetail()
        val expected = detail.getJSONObject("data").getJSONObject("ppvContent")
            .getJSONObject("sample2DMovie").getString("highestMovieUrl")
        assertEquals(expected, scrapeCapturedDetail(detail, direct = true).trailer)
    }

    @Test
    fun hlsTrailerIsUsedWhenHighestUrlIsNullOrEmpty() {
        for (highest in listOf(JSONObject.NULL, "")) {
            val detail = capturedDetail()
            val sample = detail.getJSONObject("data").getJSONObject("ppvContent").getJSONObject("sample2DMovie")
            sample.put("highestMovieUrl", highest)
            assertEquals(sample.getString("hlsMovieUrl"), scrapeCapturedDetail(detail).trailer)
        }
    }

    @Test
    fun missingOrNullSamplesLeaveTrailerEmpty() {
        for (missing in listOf(false, true)) {
            val detail = capturedDetail()
            val content = detail.getJSONObject("data").getJSONObject("ppvContent")
            if (missing) content.remove("sample2DMovie") else content.put("sample2DMovie", JSONObject.NULL)
            val info = scrapeCapturedDetail(detail)
            assertEquals("", info.trailer)
            assertTrue(info.title.isNotBlank())
        }
    }

    @Test
    fun vrOnlySampleUsesItsReturnedHighestUrl() {
        val detail = capturedDetail()
        val content = detail.getJSONObject("data").getJSONObject("ppvContent")
        content.put("sample2DMovie", JSONObject.NULL)
        content.put("sampleVRMovie", JSONObject().put("highestMovieUrl", "https://example.com/sample-vr.mp4"))
        assertEquals("https://example.com/sample-vr.mp4", scrapeCapturedDetail(detail).trailer)
    }

    /*
     * ================================================================================
     * 步骤1：验证 DMM2 相关标签展开
     * ================================================================================
     * 目标：确保详情页 relatedTags 的标签组和顶层标签都进入 tags。
     * 数据源：PPVContent.relatedTags 返回的 ContentTagGroup 与 ContentTag。
     * 操作：
     * 1) 展开标签组内的 tags。
     * 2) 合并顶层标签并去重，genres 保持独立。
     */
    @Test
    fun relatedTagsAreReadFromGroupsAndTopLevelItems() {
        val detail = capturedDetail()
        val content = detail.getJSONObject("data").getJSONObject("ppvContent")
        content.put(
            "genres",
            JSONArray().put(JSONObject().put("name", "単体作品"))
        )
        content.put(
            "relatedTags",
            JSONArray()
                .put(
                    JSONObject()
                        .put("__typename", "ContentTagGroup")
                        .put(
                            "tags",
                            JSONArray()
                                .put(JSONObject().put("__typename", "ContentTag").put("name", "ソープ"))
                                .put(JSONObject().put("__typename", "ContentTag").put("name", "中出し"))
                        )
                )
                .put(JSONObject().put("__typename", "ContentTag").put("name", "中出し"))
                .put(JSONObject().put("__typename", "ContentTag").put("name", "美女"))
        )

        val info = scrapeCapturedDetail(detail)

        assertEquals(listOf("単体作品"), info.genres)
        assertEquals(listOf("ソープ", "中出し", "美女"), info.tags)
    }

    @Test
    fun missingOrEmptyRelatedTagsDoNotCopyGenres() {
        for (value in listOf(null, JSONObject.NULL, JSONArray())) {
            val detail = capturedDetail()
            val content = detail.getJSONObject("data").getJSONObject("ppvContent")
            content.put("relatedTags", value)
            val info = scrapeCapturedDetail(detail)
            assertTrue(info.genres.isNotEmpty())
            assertTrue(info.tags.isEmpty())
        }
    }

    /*
     * ================================================================================
     * 步骤1：验证 DMM/FANZA 片长换算
     * ================================================================================
     * 目标：确保 DMM2 返回的 duration 能转换为 NFO 使用的分钟数。
     * 数据源：DMM2 PPVContent.duration 秒数。
     * 操作：
     * 1) 按分钟四舍五入，避免详情页显示秒数。
     * 2) 空值和非正数保持空字符串。
     */
    @Test
    fun dmmDurationSecondsAreRoundedToMinutes() {
        assertEquals("125", dmmDurationToRuntimeMinutes(7477))
        assertEquals("", dmmDurationToRuntimeMinutes(0))
        assertEquals("", dmmDurationToRuntimeMinutes(-1))
    }

    @Test
    fun dmmFanzaActorImageCandidatesSwitchBetweenOfficialCdnHosts() {
        val awsUrl = "https://awsimgsrc.dmm.co.jp/pics_dig/mono/actjpgs/actor.jpg"
        val picsUrl = "https://pics.dmm.co.jp/mono/actjpgs/actor.jpg"

        assertEquals(listOf(awsUrl, picsUrl), dmmFanzaActorImageCandidates(awsUrl))
        assertEquals(listOf(picsUrl, awsUrl), dmmFanzaActorImageCandidates(picsUrl))
    }

    @Test
    fun dmmFanzaActorImageCandidatesKeepNonOfficialSourceUntouched() {
        val url = "https://www.javbus.com/pics/actress/actor.jpg"

        assertEquals(listOf(url), dmmFanzaActorImageCandidates(url))
    }

    @Test
    fun dmmContentIdMatchScorePrefersExactCatalogNumberOverLongerPrefix() {
        val exact = dmmContentIdMatchScore("1namh00022", "namh00022")
        val extendedPrefix = dmmContentIdMatchScore("1hnamh00022", "namh00022")

        assertTrue(exact > extendedPrefix)
    }

    @Test
    fun dmmContentIdMatchScoreAcceptsLeadingZeroVariantsOnlyWithinTheSameLabel() {
        assertTrue(dmmContentIdMatchScore("dvmm344", "dvmm00344") >= 850)
        assertTrue(dmmContentIdMatchScore("dvmm344", "dvmm00344") > dmmContentIdMatchScore("edvmm344", "dvmm00344"))
        assertTrue(dmmContentIdMatchScore("dvmm344x", "dvmm00344") < 850)
    }

    @Test
    fun dmmSearchKeywordsKeepOriginalNumberWidthForOfficialContentIds() {
        assertEquals(listOf("cemn 003", "cemn-003", "cemn00003", "cemn003", "cemn3"), dmmSearchKeywords("CEMN-003"))
        assertEquals(listOf("dandy 414", "dandy-414", "dandy00414", "dandy414"), dmmSearchKeywords("DANDY-414"))
    }

    @Test
    fun dmmContentIdMatchScoreAcceptsOfficialPrefixAndReissueSuffix() {
        assertTrue(dmmContentIdMatchScore("18cemn003", "cemn00003") >= 850)
        assertTrue(dmmContentIdMatchScore("1dandy414re", "dandy00414") >= 850)
        assertTrue(dmmContentIdMatchScore("1hnamh00022", "namh00022") < 850)
    }

    @Test
    fun dmmContentIdMatchScoreAcceptsUnknownMixedCatalogPrefix() {
        assertTrue(dmmContentIdMatchScore("x_999abc029", "abc00029") >= 850)
    }

    /*
     * ================================================================================
     * 步骤1：验证 DMM/FANZA 影片空结果重试
     * ================================================================================
     * 目标：HTTP 成功但 GraphQL contents 暂时为空时，必须有界重试，不能无限请求。
     * 数据源：DMM2 影片搜索的空结果重试计数。
     * 操作：
     * 1) 前两次空结果允许重新请求。
     * 2) 第三次空结果停止并交给调用方判定正式未命中。
     */
    @Test
    fun dmmEmptySearchRetriesExactlyTwiceBeforeStopping() {
        // 1.1 三次搜索窗口只允许前两次短暂空结果继续请求。
        assertTrue(shouldRetryDmmEmptySearchResult(0))
        assertTrue(shouldRetryDmmEmptySearchResult(1))
        assertFalse(shouldRetryDmmEmptySearchResult(2))
    }

    /*
     * ================================================================================
     * 步骤2：验证 DMM/FANZA 直查内容 ID的严格校验边界
     * ================================================================================
     * 目标：搜索为空时只接受完全对应的标准内容 ID，拒绝相似前缀内容。
     * 数据源：DMM 内容 ID 与标准番号的匹配评分。
     * 操作：
     * 1) 完整内容 ID 必须达到严格匹配分数。
     * 2) 仅带额外前缀的内容 ID 不得进入直查结果。
     */
    @Test
    fun directContentIdLookupRequiresExactContentId() {
        // 2.1 仅完全相同的标准内容 ID 才允许作为直查结果。
        assertTrue(isExactDmmContentId("msaj00002", "msaj00002"))
        assertFalse(isExactDmmContentId("1msaj00002", "msaj00002"))
    }

}
