package com.example.localmovielibrary.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrmScrapeRepositoryTest {
    @Test
    fun imageRefererUsesJavbusPageForJavbusImageFromJavlibraryMetadata() {
        val referer = StrmScrapeRepository.imageRefererFor(
            imageUrl = "https://www.javbus.com/pics/cover/abc_b.jpg",
            number = "ENKI-002",
            sourcePageUrl = "https://www.javlibrary.com/cn/?v=javli123"
        )

        assertEquals("https://www.javbus.com/ENKI-002", referer)
    }

    @Test
    fun imageRefererDoesNotSendCrossSitePageForUnknownImageHost() {
        val referer = StrmScrapeRepository.imageRefererFor(
            imageUrl = "https://images.example/cover.jpg",
            number = "ABC-123",
            sourcePageUrl = "https://www.javlibrary.com/cn/?v=javli123"
        )

        assertNull(referer)
    }
}
