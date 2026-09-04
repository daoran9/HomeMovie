package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JavbusScraperTest {
    @Test
    fun parseActorImageUrlsDoesNotBorrowImageFromNextActorBlock() {
        val html = """
            <div class="star-box"><a title="演员甲"></a></div>
            <div class="star-box"><a><img src="/pics/actress/b.jpg" title="演员乙"></a></div>
        """.trimIndent()

        val result = JavbusScraper().parseActorImageUrls(html)

        assertFalse(result.containsKey("演员甲"))
        assertEquals("https://www.javbus.com/pics/actress/b.jpg", result["演员乙"])
    }

    @Test
    fun parseActorImageUrlsAcceptsImageAttributesInEitherOrder() {
        val html = """
            <div class="star-box"><img title="演员甲" src="https://cdn.example/a.jpg"></div>
        """.trimIndent()

        val result = JavbusScraper().parseActorImageUrls(html)

        assertEquals("https://cdn.example/a.jpg", result["演员甲"])
    }
}
