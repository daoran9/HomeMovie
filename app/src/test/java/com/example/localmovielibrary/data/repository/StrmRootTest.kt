package com.example.localmovielibrary.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrmRootTest {
    @Test
    fun oldRootAndSimilarPrefixesCannotBeReused() {
        assertFalse(isDocumentWithinRoot("primary:HomeMovie/STRM2", "primary:HomeMovie/STRM/DANDY-201.strm"))
        assertFalse(isDocumentWithinRoot("primary:HomeMovie/STRM", "primary:HomeMovie/STRM2/DANDY-201.strm"))
        assertFalse(isDocumentWithinRoot("primary:HomeMovie/STRM", "secondary:HomeMovie/STRM/DANDY-201.strm"))
        assertTrue(isDocumentWithinRoot("primary:HomeMovie/STRM2", "primary:HomeMovie/STRM2/DANDY-201.strm"))
        assertTrue(isDocumentWithinRoot("primary:HomeMovie/STRM2", "primary:HomeMovie/STRM2/sub/DANDY-201.strm"))
    }
}
