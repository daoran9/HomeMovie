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
}
