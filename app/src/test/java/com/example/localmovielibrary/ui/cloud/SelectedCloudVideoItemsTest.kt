package com.example.localmovielibrary.ui.cloud

import com.example.localmovielibrary.cloud115.Cloud115FileItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedCloudVideoItemsTest {
    /*
     * ================================================================================
     * 步骤1：过滤已选文件导入候选
     * ================================================================================
     * 目标：批量导入只接收可播放视频，并以 pickcode 识别同一115文件。
     * 数据源：选择模式收集到的文件、目录和无效项。
     * 操作：
     * 1) 丢弃目录、非视频和缺少 pickcode 的项。
     * 2) 同一 pickcode 只保留第一次选择的文件。
     */
    @Test
    fun keepsDistinctPickedVideoFilesOnly() {
        val items = listOf(
            cloudFile(name = "NAMH-001.mp4", pickcode = "first"),
            cloudFile(name = "NAMH-001-copy.mp4", pickcode = "first"),
            cloudFile(name = "NAMH-002.MKV", pickcode = "second"),
            cloudFile(name = "NAMH-001.srt", pickcode = "subtitle"),
            cloudFile(name = "folder", pickcode = "folder", isDirectory = true),
            cloudFile(name = "missing.mp4", pickcode = null)
        )

        // 1.1 只保留两个不同 pickcode 的视频文件。
        val result = distinctSelectedCloudVideoItemsByPickcode(items)

        assertEquals(listOf("first", "second"), result.map { it.pickcode })
    }

    private fun cloudFile(
        name: String,
        pickcode: String?,
        isDirectory: Boolean = false
    ) = Cloud115FileItem(
        name = name,
        cid = null,
        fid = null,
        pickcode = pickcode,
        size = null,
        modifiedAt = null,
        isDirectory = isDirectory
    )
}
