package com.example.localmovielibrary.scraper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Dmm2ScraperTest {
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
