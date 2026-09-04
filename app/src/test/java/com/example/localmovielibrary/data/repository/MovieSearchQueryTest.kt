package com.example.localmovielibrary.data.repository

import com.example.localmovielibrary.data.local.MovieActorMetadataList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieSearchQueryTest {
    /*
     * ================================================================================
     * 步骤1：验证番号搜索标准化
     * ================================================================================
     * 目标：用户输入空格、连字符或不加分隔符时，都能定位同一番号。
     * 数据源：搜索框中的番号文本。
     * 操作：
     * 1) 验证 NAMH 022 会提取为 NAMH-022。
     * 2) 验证普通文本不会进入番号候选查询。
     */
    @Test
    fun normalizesSpacedMovieNumberForCandidateSearch() {
        // 1.1 SQL 候选条件不依赖文件名中使用的具体分隔符。
        val query = movieNumberSearchQuery("namh 022")

        // 1.2 完整番号仍用于后续精确过滤，排除近似番号。
        assertEquals("NAMH-022", query?.number)
        assertEquals("%NAMH%022%", query?.candidateLikePattern)
    }

    @Test
    fun keepsPlainTextSearchOutOfMovieNumberLookup() {
        // 1.3 非番号输入继续只走原有全文搜索。
        assertNull(movieNumberSearchQuery("泉りおん"))
    }

    @Test
    fun actorSummaryUsesOneIdentityAcrossNameVariantsAndCountsMoviesOnce() {
        val actors = listOf(
            MovieActorMetadataList(1, listOf("上原亚衣（原田麻衣、秋元凛）")),
            MovieActorMetadataList(2, listOf("上原亜衣")),
            MovieActorMetadataList(3, listOf("波多野结衣", "波多野結衣")),
            MovieActorMetadataList(4, listOf("演员:（櫻井美優）")),
            MovieActorMetadataList(5, listOf("日向あいり（橘優花）")),
            MovieActorMetadataList(6, listOf("橘優花"))
        )
        val summaries = summarizeActors(actors).associateBy { it.value }

        assertEquals(2, summaries["上原亚衣"]?.count)
        assertEquals(1, summaries["波多野结衣"]?.count)
        assertEquals(1, summaries["櫻井美優"]?.count)
        assertEquals(2, summaries["日向あいり"]?.count)
        assertEquals(setOf(5L, 6L), actorIdentityMovieIds(actors, "日向あいり", exact = true))
        assertEquals(setOf(5L, 6L), actorIdentityMovieIds(actors, "橘優花", exact = true))
        assertTrue(actorIdentityMovieIds(actors, "日向", exact = false).containsAll(setOf(5L, 6L)))
    }
}
