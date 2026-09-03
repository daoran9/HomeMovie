package com.example.localmovielibrary.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UriImageTest {
    @Test
    fun cacheIdentityChangesWhenArtworkVersionChanges() {
        val uri = "content://example/movie-poster.jpg"

        assertEquals(uri, uriImageCacheIdentity(uri, 0L))
        assertNotEquals(uriImageCacheIdentity(uri, 1L), uriImageCacheIdentity(uri, 2L))
    }
}
