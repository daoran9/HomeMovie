package com.example.localmovielibrary.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterArtworkRuleTest {
    /*
     * ================================================================================
     * 步骤1：验证高清包装图识别规则
     * ================================================================================
     * 目标：仅让同源的低清竖图使用对应的高清横版包装图生成海报。
     * 数据源：DMM/JavLibrary 和 JavBus 的真实 URL 命名规则。
     * 操作：
     * 1) 验证两个来源的已知图片对可命中。
     * 2) 验证没有对应关系的普通海报不会被处理。
     */
    @Test
    fun detectsOnlyMatchingWideCoverPairs() {
        // 1.1 DMM 的 pl.jpg 为高清包装图，ps.jpg 为低清竖图。
        assertTrue(
            shouldBuildPortraitPosterFromWideCover(
                "https://pics.dmm.co.jp/mono/movie/adult/1namh056/1namh056ps.jpg",
                "https://pics.dmm.co.jp/mono/movie/adult/1namh056/1namh056pl.jpg"
            )
        )

        // 1.2 JavBus 的 cover 图与 thumb 图为同一包装图的两个尺寸。
        assertTrue(
            shouldBuildPortraitPosterFromWideCover(
                "https://www.javbus.com/pics/thumb/c5w3.jpg",
                "https://www.javbus.com/pics/cover/c5w3_b.jpg"
            )
        )

        // 1.3 普通竖版海报没有同源包装图关系，保持原文件。
        assertFalse(
            shouldBuildPortraitPosterFromWideCover(
                "https://cdn.example/poster.jpg",
                "https://cdn.example/thumb.jpg"
            )
        )
    }
}
