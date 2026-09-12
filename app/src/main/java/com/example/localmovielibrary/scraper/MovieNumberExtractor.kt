package com.example.localmovielibrary.scraper

object MovieNumberExtractor {
    fun isUnsupportedFc2(fileName: String): Boolean =
        Regex("""(?i)(?<![a-z0-9])FC2[-_ ]*(?:PPV[-_ ]*)?\d+""").containsMatchIn(fileName)

    private val numberPattern = Regex("""(?i)([a-z]{2,8})[-_\s]?(\d{2,6})""")
    private val trailingPickcodePattern = Regex("""(?i)_[a-z0-9]{17}$""")

    fun extract(fileName: String): String? {
        /*
         * ================================================================================
         * 步骤1：排除 115 pickcode 后再提取番号
         * ================================================================================
         * 目标：避免 pickcode 内部的字母数字片段被误认成影片番号。
         * 数据源：STRM 文件名和云端入库追加的 17 位 pickcode 后缀。
         * 操作：
         * 1) 只删除文件名末尾固定格式的 pickcode，不改变普通标题文本。
         * 2) 在剩余文本中沿用最后一个番号候选，兼容演员名和版本前缀。
         */
        val baseName = fileName.substringBeforeLast('.', fileName)
            .replace(trailingPickcodePattern, "")
        val matches = numberPattern.findAll(baseName).toList()
        val match = matches.lastOrNull() ?: return null
        val prefix = match.groupValues[1].uppercase()
        val number = match.groupValues[2]
        return "$prefix-$number"
    }
}
