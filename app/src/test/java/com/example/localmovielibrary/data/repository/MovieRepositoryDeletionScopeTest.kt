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

    @Test
    fun deletionOnlyUsesTheStoredUriInsideItsLibraryTree() {
        val root = "primary:HomeMovie/Library"

        assertTrue(isDocumentWithinTree(root, "$root/未知演员/DANDY-00287.strm"))
        assertFalse(isDocumentWithinTree(root, "primary:HomeMovie/LibraryBackup/DANDY-00287.strm"))
    }

    @Test
    fun sharedActorDirectoryDeletesOnlyTheStoredStrm() {
        val root = "primary:HomeMovie/Library"
        val video = "$root/未知演员/DANDY-00287.strm"

        assertFalse(
            dedicatedMovieDirectoryDocumentId(root, video, "未知演员", "DANDY-00287.strm") != null
        )
        assertTrue(
            dedicatedMovieDirectoryDocumentId(
                root,
                "$root/【未知演员】DANDY-00287/DANDY-00287.strm",
                "【未知演员】DANDY-00287",
                "DANDY-00287.strm"
            ) != null
        )
    }
}
