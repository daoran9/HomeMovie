package com.example.localmovielibrary.data.repository

import org.junit.Assert.assertEquals
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

    /*
     * ================================================================================
     * 步骤2：验证 STRM 播放源名称解析
     * ================================================================================
     * 目标：多播放源菜单必须展示 115 原始文件名，并以实际 pickcode 识别同一视频。
     * 数据源：Cloud115StrmRepository 写入的 download_m3u 地址。
     * 操作：
     * 1) 提取真实 pickcode。
     * 2) 解码原始文件名，同时保留文件名中的加号。
     */
    @Test
    fun parsesOriginalPlaybackFileNameFromStrmAddress() {
        // 2.1 使用实际 STRM 写入格式，文件名包含百分号编码和字面加号。
        val source = parseStrmPlaybackSource(
            "http://127.0.0.1/download_m3u/bd5h3bkwkk35g83ez/NAMH-056_restored%20iris+2.mp4"
        )

        // 2.2 pickcode 用于去重，文件名直接供详情页菜单显示。
        assertEquals("bd5h3bkwkk35g83ez", source?.pickcode)
        assertEquals("NAMH-056_restored iris+2.mp4", source?.fileName)
    }

    /*
     * ================================================================================
     * 步骤3：验证错误播放源地址修复
     * ================================================================================
     * 目标：历史 STRM 指向另一条 pickcode 时，恢复数据库记录对应的视频。
     * 数据源：错误的 download_m3u 地址和已保留的原始 pickcode。
     * 操作：
     * 1) 只替换播放路由中的 pickcode 与文件名。
     * 2) 保留 STRM 地址的协议、主机和其他路径结构。
     */
    @Test
    fun restoresRecordedPickcodeInCorruptStrmAddress() {
        // 3.1 模拟旧版追加播放源时错误复用了第二条视频地址。
        val repaired = replaceStrmPlaybackSource(
            "http://127.0.0.1/download_m3u/bd5h3bkwkk35g83ez/NAMH-056_restored.mp4",
            pickcode = "akwj9647lk7tnhtnz",
            fileName = "NAMH-056_restored iris2.mp4"
        )

        // 3.2 修复后播放器从 STRM 解析到的是记录自身的 pickcode。
        assertEquals(
            "http://127.0.0.1/download_m3u/akwj9647lk7tnhtnz/NAMH-056_restored%20iris2.mp4",
            repaired
        )
    }
}
