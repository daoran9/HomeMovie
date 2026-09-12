package com.example.localmovielibrary.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieVariantTest {
    @Test
    fun detectsCommonFourKMarkers() {
        assertEquals(MovieVariant.FourK, detectMovieVariant("FNS-118_4K.mp4"))
        assertEquals(MovieVariant.FourK60Fps, detectMovieVariant("FNS-118 4K60FPS.mp4"))
        assertEquals(MovieVariant.FourK, detectMovieVariant("FNS-118-2160p.mkv"))
        assertEquals(MovieVariant.FourK, detectMovieVariant("FNS-118 UHD.mp4"))
        assertEquals(MovieVariant.FourK, detectMovieVariant("START-155_4Ks.mp4"))
    }

    @Test
    fun doesNotDetectEmbeddedTextAsFourK() {
        assertEquals(MovieVariant.Standard, detectMovieVariant("FNS-118_A4KCODE.mp4"))
        assertEquals(MovieVariant.Standard, detectMovieVariant("4k2.me@fns-128.mp4"))
        assertEquals(MovieVariant.Standard, detectMovieVariant("FNS-118.mp4"))
    }

    @Test
    fun detectsCommonEightKMarkers() {
        assertEquals(MovieVariant.EightK, detectMovieVariant("EBVR-00104.part4_8K.mp4"))
        assertEquals(MovieVariant.EightK60Fps, detectMovieVariant("EBVR-00104 8K60FPS.mp4"))
        assertEquals(MovieVariant.EightK, detectMovieVariant("EBVR-00104-4320p.mkv"))
    }

    @Test
    fun buildsPlaybackSourceSuffixWithPartAndVariant() {
        assertEquals("", playbackSourceSuffix(null, MovieVariant.Standard))
        assertEquals("-4K", playbackSourceSuffix(null, MovieVariant.FourK))
        assertEquals("-P4", playbackSourceSuffix("P4", MovieVariant.Standard))
        assertEquals("-P4-8K", playbackSourceSuffix("P4", MovieVariant.EightK))
    }

    @Test
    fun preservesShortPlaybackSourceMarkers() {
        assertEquals("-U", playbackSourceSuffixFromText("BF-287-U.mp4"))
        assertEquals("-C", playbackSourceSuffixFromText("BF-287-C.mp4"))
        assertEquals("-WM", playbackSourceSuffixFromText("BF-287-WM.mp4"))
        assertEquals("-U-4K", playbackSourceSuffixFromText("BF-287-U-4K.mp4"))
        assertEquals("-P3-8K", playbackSourceSuffixFromText("BF-287.part3_8K.mp4"))
        assertEquals("-4K", playbackSourceSuffixFromText("BF-287-UHD.mp4"))
        assertEquals(
            "-WM",
            playbackSourceSuffixFromText("HNDS-005美麗的雙重癡女狂搖不停的騎乘位中出愛咲玲羅竹內紗里奈_002^WM.mp4")
        )
        assertEquals("-WM-4K", playbackSourceSuffixFromText("GMRY-001真琴りょう^WM_4K_prob4.mp4"))
        assertEquals("", playbackSourceSuffixFromText("HNDS-010.restored_KLX.mp4"))
    }
}
