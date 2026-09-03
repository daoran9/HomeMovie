package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Dmm2ScraperTest {
    @Test
    fun dmmFanzaActorImageCandidatesSwitchBetweenOfficialCdnHosts() {
        val awsUrl = "https://awsimgsrc.dmm.co.jp/pics_dig/mono/actjpgs/actor.jpg"
        val picsUrl = "https://pics.dmm.co.jp/mono/actjpgs/actor.jpg"

        assertEquals(listOf(awsUrl, picsUrl), dmmFanzaActorImageCandidates(awsUrl))
        assertEquals(listOf(picsUrl, awsUrl), dmmFanzaActorImageCandidates(picsUrl))
    }

    @Test
    fun dmmFanzaActorImageCandidatesKeepNonOfficialSourceUntouched() {
        val url = "https://www.javbus.com/pics/actress/actor.jpg"

        assertEquals(listOf(url), dmmFanzaActorImageCandidates(url))
    }

    @Test
    fun dmmContentIdMatchScorePrefersExactCatalogNumberOverLongerPrefix() {
        val exact = dmmContentIdMatchScore("1namh00022", "namh00022")
        val extendedPrefix = dmmContentIdMatchScore("1hnamh00022", "namh00022")

        assertTrue(exact > extendedPrefix)
    }
}
