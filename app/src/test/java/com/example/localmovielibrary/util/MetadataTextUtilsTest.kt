package com.example.localmovielibrary.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTextUtilsTest {
    @Test
    fun cleanMetadataTextDecodesBreakEntitiesAndPreservesLineBreaks() {
        assertEquals("第一行\n第二行", "第一行&lt;br&gt;第二行".cleanMetadataText())
        assertEquals("第一行\n第二行", "第一行&amp;lt;br&amp;gt;第二行".cleanMetadataText())
    }

    @Test
    fun cleanMetadataTextRemovesOtherHtmlTags() {
        assertEquals("第一段\n第二段", "<p>第一段</p><br/><span>第二段</span>".cleanMetadataText())
    }

    @Test
    fun exactMetadataMatchRequiresWholeValue() {
        val actors = listOf("FNS-150", "女神ジュン")

        assertTrue(actors.containsMetadataValue("女神ジュン", exact = true))
        assertFalse(actors.containsMetadataValue("FNS", exact = true))
    }

    @Test
    fun fuzzyMetadataMatchAllowsPartialValue() {
        val genres = listOf("Drama Idol", "VR")

        assertTrue(genres.containsMetadataValue("idol", exact = false))
        assertTrue(genres.containsMetadataValue(" drama   idol ", exact = false))
    }
}
