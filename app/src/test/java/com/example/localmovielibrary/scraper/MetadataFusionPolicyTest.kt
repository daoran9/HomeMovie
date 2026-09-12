package com.example.localmovielibrary.scraper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MetadataFusionPolicyTest {
    private fun info(source: String = "dmm") = ScrapedMovieInfo("ABC-001", "Official title", source = source)

    private fun fuse(vararg entries: Pair<ScrapeSource, ScrapedMovieInfo>): ScrapedMovieInfo = runBlocking {
        val registry = MovieScraperRegistry(entries.map { (kind, value) ->
            object : MovieScraper {
                override val source = kind
                override suspend fun scrape(number: String) = value
            }
        })
        registry.scrapeWithDmmPriority(entries.first().second.number)
    }

    @Test fun classificationsMergeUniqueValuesAndSeparateTechnicalTags() {
        val result = mergeMovieClassifications(listOf(
            info().copy(genres = listOf("乱交", "ハイビジョン", "サンプル動画", "新分類"),
                tags = listOf("乱交", "ハイビジョン", "サンプル動画", "新分類")),
            info("javbus").copy(genres = listOf("濫交", "独有分类"), tags = listOf("4K"))
        ))
        assertEquals(listOf("滥交", "新分類", "独有分类"), result.genres)
        assertEquals(listOf("高清", "滥交", "新分類", "4K"), result.tags)
    }

    @Test fun directorsRequireAnExplicitJointCredit() {
        assertEquals(listOf("A"), mergeDirectorCredits(listOf(info().copy(directors = listOf("A")),
            info("javbus").copy(directors = listOf("B")))))
        assertEquals(listOf("A", "B"), mergeDirectorCredits(listOf(info().copy(directors = listOf("A")),
            info("javbus").copy(directors = listOf("A", "B")))))
    }

    @Test fun legacyOfficialValuesRemainPrimaryAndSafeSourcesFillMissingFields() {
        val merged = fuse(
            ScrapeSource.Dmm to info().copy(runtime = "130", studio = "ONE MORE", genres = listOf("乱交")),
            ScrapeSource.Javlibrary to info("javlibrary").copy(title = "External title", runtime = "134",
                studio = "Other maker", publisher = "Label", genres = listOf("濫交", "独有分类")),
            ScrapeSource.Javdb to info("javdb").copy(genres = listOf("Wrong source genre"), publisher = "Wrong label")
        )
        assertEquals("Official title", merged.title)
        assertEquals("130", merged.runtime)
        assertEquals("ONE MORE", merged.studio)
        assertEquals("Label", merged.publisher)
        assertEquals(listOf("滥交", "独有分类"), merged.genres)
        assertEquals("dmm", merged.source)
    }

    @Test fun incompatibleOfficialEditionsDoNotSupplyRuntimeOrDate() {
        val merged = fuse(
            ScrapeSource.Dmm2 to info("dmm2").copy(website = "https://video.dmm.co.jp/av/content/?id=abc00001"),
            ScrapeSource.Dmm to info().copy(website = "https://www.dmm.co.jp/mono/dvd/-/detail/=/cid=abc001/",
                premiered = "2020-01-01", runtime = "134", thumbUrl = "https://example.com/dvd.jpg")
        )
        assertEquals("", merged.runtime)
        assertEquals("", merged.premiered)
        assertEquals("", merged.thumbUrl)
    }

    @Test fun chosenDateDeterminesYearAndScoresAreNotMixedAcrossSites() {
        val merged = fuse(
            ScrapeSource.Dmm to info().copy(premiered = "2020-01-02", year = "1999"),
            ScrapeSource.Javlibrary to info("javlibrary").copy(year = "2001", rating = "9.9")
        )
        assertEquals("2020", merged.year)
        assertEquals("", merged.rating)
    }

    @Test fun explicitSourceAliasResolvesPseudonymProneMaker() {
        val merged = fuse(
            ScrapeSource.Dmm to info().copy(studio = "豊彦", actors = listOf("作品署名")),
            ScrapeSource.Javlibrary to info("javlibrary").copy(actors = listOf("確認芸名"),
                actorAliases = mapOf("確認芸名" to listOf("作品署名", "旧芸名")))
        )
        assertEquals(listOf("確認芸名"), merged.actors)
        assertEquals(listOf("旧芸名"), merged.actorAliases["確認芸名"])
        assertEquals(listOf("作品署名"), merged.actorCredits["確認芸名"])
        assertTrue(merged.unverifiedActorNames.isEmpty())
        val nfo = NfoWriter.build(merged)
        assertTrue(nfo.contains("<role>作品署名</role>"))
        assertFalse(nfo.contains("<name>確認芸名（作品署名"))
    }

    @Test fun numberAndMakerDoNotDiscardStructuredActors() {
        val review = reviewMakerActorEvidence(listOf(info().copy(number = "MSAJ-001", studio = "Other maker",
            actors = listOf("芸名"))), emptyList())
        assertEquals(listOf("芸名"), review.infos.single().actors)
        assertTrue(review.unverifiedNames.isEmpty())
    }

    @Test fun repeatedMakerPseudonymRemainsPendingWithoutProfileEvidence() {
        val merged = fuse(
            ScrapeSource.Dmm to info().copy(studio = "豊彦", actors = listOf("作品署名")),
            ScrapeSource.Javbus to info("javbus").copy(actors = listOf("作品署名"))
        )
        assertTrue(merged.actors.isEmpty())
        assertEquals(listOf("作品署名"), merged.unverifiedActorNames)
        assertThrows(IllegalStateException::class.java) { NfoWriter.build(merged) }
    }

    @Test fun actorProfileEvidenceResolvesPseudonymProneMaker() {
        val officialImage = "https://pics.dmm.co.jp/mono/actjpgs/kamiya.jpg"
        val merged = fuse(
            ScrapeSource.Dmm2 to info("dmm2").copy(number = "MSAJ-002", studio = "豊彦",
                actors = listOf("神谷裕子"), actorImageUrls = mapOf("神谷裕子" to officialImage)),
            ScrapeSource.Javdb to info("javdb").copy(number = "MSAJ-002", actors = listOf("神谷裕子"),
                verifiedActorNames = listOf("神谷裕子"))
        )
        assertEquals(listOf("神谷裕子"), merged.actors)
        assertTrue(merged.unverifiedActorNames.isEmpty())
        assertTrue(merged.actorCredits.isEmpty())
        assertEquals(officialImage, merged.actorImageUrls["神谷裕子"])
        assertTrue(NfoWriter.build(merged).contains("<name>神谷裕子</name>"))
    }

    @Test fun sourceNameCanCarryAnExplicitScopedCreditWithoutPromotingItToAnAlias() {
        val merged = fuse(
            ScrapeSource.Dmm2 to info("dmm2").copy(number = "MSAJ-002", studio = "豊彦",
                actors = listOf("神谷裕子", "作品署名")),
            ScrapeSource.Javbus to info("javbus").copy(number = "MSAJ-002", actors = listOf("神谷裕子"),
                actorAliases = mapOf("神谷裕子" to listOf("作品署名")))
        )
        assertEquals(listOf("神谷裕子"), merged.actors)
        assertEquals(listOf("作品署名"), merged.actorCredits["神谷裕子"])
        assertTrue(merged.actorAliases.values.flatten().isEmpty())
        assertTrue(merged.unverifiedActorNames.isEmpty())
        val nfo = NfoWriter.build(merged)
        assertTrue(nfo.contains("<role>作品署名</role>"))
        assertTrue(nfo.contains("<name>神谷裕子</name>"))
    }

    @Test fun creditImagesCannotBeReboundToStageNames() {
        val review = reviewMakerActorEvidence(
            listOf(info().copy(studio = "豊彦", actors = listOf("署名"))),
            listOf(info("javdb").copy(actors = listOf("芸名"), actorAliases = mapOf("芸名" to listOf("署名")),
                actorImageUrls = mapOf("署名" to "https://pics.dmm.co.jp/mono/actjpgs/unknown.jpg")))
        )
        assertTrue(review.infos.single().actorImageUrls.isEmpty())
        assertEquals(listOf("署名"), review.credits["芸名"])
    }

    @Test fun avatarCandidatesPreferValidOfficialLinksAndKeepAlternatives() {
        val official = "https://pics.dmm.co.jp/mono/actjpgs/actor.jpg"
        val alternate = "https://c0.jdbstatic.com/avatars/actor.jpg"
        assertEquals(listOf(official, alternate), prioritizeActorImageUrls(listOf(alternate, "", official,
            "https://pics.dmm.co.jp/mono/actjpgs/now_printing.jpg")))
        assertEquals(listOf(alternate), prioritizeActorImageUrls(listOf("", alternate)))
    }

    @Test fun scopedCreditsNeverBecomeGlobalAliasesWhenLibraryNamesAreAdded() {
        val value = info().copy(actors = listOf("芸名"), actorCredits = mapOf("芸名" to listOf("本片署名")),
            unverifiedActorNames = listOf("未確認名"))
        val result = value.withSupplementalActors(listOf("芸名（本片署名）", "未確認名")).canonicalizeActorIdentities()
        assertEquals(listOf("芸名"), result.actors)
        assertTrue(result.actorAliases.isEmpty())
        assertTrue(result.isMovieScopedActorName("本片署名"))
        assertTrue(result.isMovieScopedActorName("未確認名"))
    }

    @Test fun canonicalizationDoesNotReintroduceMovieCreditsFromAnAliasMap() {
        val result = info().copy(actors = listOf("芸名（本片署名）"),
            actorAliases = mapOf("芸名" to listOf("本片署名", "旧芸名")),
            actorCredits = mapOf("芸名" to listOf("本片署名"))).canonicalizeActorIdentities()
        assertEquals(listOf("芸名"), result.actors)
        assertEquals(listOf("旧芸名"), result.actorAliases["芸名"])
        assertTrue(NfoWriter.build(result).contains("<role>本片署名</role>"))
    }

    @Test fun nfoWritingPreservesIndependentTagsEvenWhenTheyMatchGenres() {
        val nfo = NfoWriter.build(info().copy(genres = listOf("乱交", "高畫質", "サンプル動画"),
            tags = listOf("乱交", "高畫質", "サンプル動画")))
        assertTrue(nfo.contains("<genre>滥交</genre>"))
        assertTrue(nfo.contains("<tag>高清</tag>"))
        assertTrue(nfo.contains("<tag>滥交</tag>"))
        assertFalse(nfo.contains("サンプル動画"))
    }

    @Test fun genresWithoutIndependentTagsOnlyProduceTechnicalTags() {
        val nfo = NfoWriter.build(info("javlibrary").copy(
            genres = listOf("乱交", "高畫質", "サンプル動画")))
        assertTrue(nfo.contains("<genre>滥交</genre>"))
        assertTrue(nfo.contains("<tag>高清</tag>"))
        assertFalse(nfo.contains("<tag>滥交</tag>"))
    }

    @Test fun movieCreditsFollowCanonicalActorWithoutJoiningGlobalNames() {
        val value = info().copy(actors = listOf("芸名A", "芸名B"),
            actorAliases = mapOf("芸名A" to listOf("芸名B")),
            actorCredits = mapOf("芸名B" to listOf("本片署名")))
        val result = value.canonicalizeActorIdentities()
        assertEquals(listOf("芸名A"), result.actors)
        assertEquals(listOf("本片署名"), result.actorCredits["芸名A"])
        assertEquals(listOf("芸名B"), result.actorAliases["芸名A"])
    }

    @Test fun pendingMakerIdentityCannotTriggerCommentFallback() = runBlocking {
        var commentCalls = 0
        val official = object : MovieScraper {
            override val source = ScrapeSource.Dmm
            override suspend fun scrape(number: String) = info().copy(studio = "豊彦", actors = listOf("署名"))
        }
        val comments = object : MovieScraper, ReviewActorFallback {
            override val source = ScrapeSource.Javdb
            override suspend fun scrape(number: String) = info("javdb")
            override suspend fun findReviewActorNames(number: String): List<String> {
                commentCalls++
                return listOf("猜测演员")
            }
        }
        val result = MovieScraperRegistry(listOf(official, comments)).scrapeWithDmmPriority("ABC-001")
        assertEquals(0, commentCalls)
        assertTrue(result.actors.isEmpty())
        assertEquals(listOf("署名"), result.unverifiedActorNames)
    }
}
