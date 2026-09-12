package com.example.localmovielibrary.scraper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Fc2ImportPolicyTest {
    @Test
    fun batchRecognizesFc2BeforeGenericCatalogExtraction() {
        assertTrue(MovieNumberExtractor.isUnsupportedFc2("FC2-PPV-4397842.restored.mp4"))
        assertTrue(MovieNumberExtractor.isUnsupportedFc2("FC2-PPV-4785442_1.MR.restored.mp4"))
        assertTrue(MovieNumberExtractor.isUnsupportedFc2("fc2_1234567.mp4"))
        assertFalse(MovieNumberExtractor.isUnsupportedFc2("DANDY-201.restored(1).mp4"))
        assertFalse(MovieNumberExtractor.isUnsupportedFc2("ABC-123.mp4"))
    }
}
