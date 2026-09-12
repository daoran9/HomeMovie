package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

class MissavClassificationTest {
    @Test fun onlyIndependentTagFieldBecomesTags() {
        for (tag in listOf("", "分類", "Other tag")) {
            val info = MissavScraper().scrapeFromHtml("ABC-123", """
                <h1 class="text-nord6">ABC-123 Test</h1>
                <div class="space-y-2">
                    <div class="text-secondary"><span>类型:</span><a>分類</a></div>
                    <div class="text-secondary"><span>标签:</span><a>$tag</a></div>
                </div>
            """.trimIndent())
            assertEquals(listOf("分類"), info.genres)
            assertEquals(if (tag.isEmpty()) emptyList<String>() else listOf(tag), info.tags)
        }
    }
}
