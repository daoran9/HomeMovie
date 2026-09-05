package com.example.localmovielibrary.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieRepositoryDeletionScopeTest {
    @Test
    fun dedicatedMovieDirectoryMustContainTheSameCatalogNumber() {
        assertTrue(isDedicatedMovieDirectoryName("【未知演员】DANDY-00287", "DANDY-00287.strm"))
        assertFalse(isDedicatedMovieDirectoryName("未知演员", "DANDY-00287.strm"))
    }
}
