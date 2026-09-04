package com.example.localmovielibrary.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
