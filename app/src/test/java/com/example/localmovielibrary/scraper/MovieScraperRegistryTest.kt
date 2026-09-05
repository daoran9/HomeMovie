package com.example.localmovielibrary.scraper

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieScraperRegistryTest {
    @Test
    fun scrapeWithDmmPriorityStopsExternalSourcesAfterOfficialHit() = runBlocking {
        val calls = mutableListOf<ScrapeSource>()
        val registry = MovieScraperRegistry(
            listOf(
                RecordingInfoMovieScraper(
                    ScrapeSource.Dmm2,
                    calls,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "官方标题",
                        actors = listOf("官方演员"),
                        thumbUrl = "https://images.example/dmm-thumb.jpg",
                        source = "dmm2"
                    )
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Dmm,
                    calls,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "旧 DMM 标题",
                        runtime = "120",
                        actors = listOf("旧演员"),
                        source = "dmm"
                    )
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Javlibrary,
                    calls,
                    ScrapedMovieInfo(number = "ABC-123", title = "外部标题", source = "javlibrary")
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Javbus,
                    calls,
                    ScrapedMovieInfo(number = "ABC-123", title = "外部标题", source = "javbus")
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Javdb,
                    calls,
                    ScrapedMovieInfo(number = "ABC-123", title = "外部标题", source = "javdb")
                )
            )
        )

        val info = registry.scrapeWithDmmPriority("ABC-123")

        assertEquals(listOf(ScrapeSource.Dmm2, ScrapeSource.Dmm), calls)
        assertEquals("官方标题", info.title)
        assertEquals("120", info.runtime)
        assertEquals(listOf("官方演员", "旧演员"), info.actors)
    }

    @Test
    fun scrapeWithDmmPriorityMergesLegacyDmmActorEvidence() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                InfoMovieScraper(
                    ScrapeSource.Dmm2,
                    ScrapedMovieInfo(number = "ABC-123", title = "官方标题", source = "dmm2")
                ),
                InfoMovieScraper(
                    ScrapeSource.Dmm,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "",
                        actors = listOf("旧 DMM 演员"),
                        actorAliases = mapOf("旧 DMM 演员" to listOf("官方别名")),
                        actorImageUrls = mapOf("旧 DMM 演员" to "https://images.example/dmm-actor.jpg"),
                        excludedActorNames = listOf("男性演员"),
                        source = "dmm"
                    )
                )
            )
        )

        val info = registry.scrapeWithDmmPriority("ABC-123")

        assertEquals(listOf("旧 DMM 演员"), info.actors)
        assertEquals(listOf("官方别名"), info.actorAliases["旧 DMM 演员"])
        assertEquals("https://images.example/dmm-actor.jpg", info.actorImageUrls["旧 DMM 演员"])
        assertEquals(listOf("男性演员"), info.excludedActorNames)
    }

    @Test
    fun scrapeWithDmmPriorityUsesJavlibraryActorsForMsajOfficialHit() = runBlocking {
        val calls = mutableListOf<ScrapeSource>()
        val registry = MovieScraperRegistry(
            listOf(
                RecordingInfoMovieScraper(
                    ScrapeSource.Dmm2,
                    calls,
                    ScrapedMovieInfo(
                        number = "MSAJ-004",
                        title = "官方标题",
                        actors = listOf("DMM 演员"),
                        thumbUrl = "https://images.example/dmm-thumb.jpg",
                        source = "dmm2"
                    )
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Dmm,
                    calls,
                    ScrapedMovieInfo(
                        number = "MSAJ-004",
                        title = "旧 DMM 标题",
                        runtime = "120",
                        actors = listOf("旧 DMM 演员"),
                        source = "dmm"
                    )
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Javlibrary,
                    calls,
                    ScrapedMovieInfo(
                        number = "MSAJ-004",
                        title = "JavLibrary 标题",
                        actors = listOf("JavLibrary 演员"),
                        actorAliases = mapOf("JavLibrary 演员" to listOf("JavLibrary 别名")),
                        source = "javlibrary"
                    )
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Javbus,
                    calls,
                    ScrapedMovieInfo(number = "MSAJ-004", title = "JavBus 标题", source = "javbus")
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Javdb,
                    calls,
                    ScrapedMovieInfo(number = "MSAJ-004", title = "JavDB 标题", source = "javdb")
                )
            )
        )

        val info = registry.scrapeWithDmmPriority("MSAJ-004")

        assertEquals(listOf(ScrapeSource.Dmm2, ScrapeSource.Dmm, ScrapeSource.Javlibrary), calls)
        assertEquals("官方标题", info.title)
        assertEquals("120", info.runtime)
        assertEquals(listOf("JavLibrary 演员"), info.actors)
        assertEquals(listOf("JavLibrary 别名"), info.actorAliases["JavLibrary 演员"])
    }

    @Test
    fun scrapeWithDmmPriorityCollectsAllExternalSourcesAfterOfficialMiss() = runBlocking {
        val calls = mutableListOf<ScrapeSource>()
        val registry = MovieScraperRegistry(
            listOf(
                RecordingInfoMovieScraper(ScrapeSource.Dmm2, calls, fail = true),
                RecordingInfoMovieScraper(
                    ScrapeSource.Javlibrary,
                    calls,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "JL 标题",
                        premiered = "2024-01-01",
                        rating = "7.8",
                        actors = listOf("演员甲"),
                        genres = listOf("剧情"),
                        thumbUrl = "https://javlibrary.example/thumb.jpg",
                        posterUrl = "https://javlibrary.example/poster.jpg",
                        source = "javlibrary"
                    )
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Javbus,
                    calls,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "JB 标题",
                        plot = "JB 简介",
                        actors = listOf("演员甲"),
                        genres = listOf("办公室"),
                        thumbUrl = "https://javbus.example/thumb.jpg",
                        posterUrl = "https://javbus.example/poster.jpg",
                        source = "javbus"
                    )
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Javdb,
                    calls,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "JavDB 标题",
                        plot = "JavDB 简介",
                        actors = listOf("演员甲"),
                        genres = listOf("熟女"),
                        thumbUrl = "https://javdb.example/thumb.jpg",
                        posterUrl = "https://javdb.example/poster.jpg",
                        source = "javdb"
                    )
                ),
                RecordingInfoMovieScraper(
                    ScrapeSource.Dmm,
                    calls,
                    ScrapedMovieInfo(number = "ABC-123", title = "旧 DMM 不应请求", source = "dmm")
                )
            )
        )

        val info = registry.scrapeWithDmmPriority("ABC-123")

        assertEquals(
            listOf(ScrapeSource.Dmm2, ScrapeSource.Javlibrary, ScrapeSource.Javbus, ScrapeSource.Javdb),
            calls
        )
        assertEquals("JL 标题", info.title)
        assertEquals("JB 简介", info.plot)
        assertEquals("7.8", info.rating)
        assertEquals(listOf("剧情", "办公室"), info.genres)
        assertEquals("https://javlibrary.example/thumb.jpg", info.thumbUrl)
        assertEquals("https://javlibrary.example/poster.jpg", info.posterUrl)
        assertEquals(listOf("演员甲"), info.actors)
    }

    @Test
    fun scrapeWithDmmPriorityUsesJavdbOnlyForActorEvidenceWhenNoSafeMetadataExists() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                FailingMovieScraper(ScrapeSource.Dmm2),
                InfoMovieScraper(
                    ScrapeSource.Javdb,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "JavDB 标题",
                        plot = "JavDB 简介",
                        thumbUrl = "https://javdb.example/thumb.jpg",
                        posterUrl = "https://javdb.example/poster.jpg",
                        actors = listOf("演员甲"),
                        actorImageUrls = mapOf("演员甲" to "https://c0.jdbstatic.com/avatars/ab/Ab123.jpg"),
                        source = "javdb"
                    )
                )
            )
        )

        val info = registry.scrapeWithDmmPriority("ABC-123")

        assertEquals("", info.title)
        assertEquals("", info.plot)
        assertEquals("", info.thumbUrl)
        assertEquals("", info.posterUrl)
        assertEquals(JAVDB_ACTOR_EVIDENCE_SOURCE, info.source)
        assertEquals(listOf("演员甲"), info.actors)
        assertEquals("https://c0.jdbstatic.com/avatars/ab/Ab123.jpg", info.actorImageUrls["演员甲"])
    }

    @Test
    fun scrapeRoutesToMatchingSource() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                FakeMovieScraper(ScrapeSource.Dmm, "dmm-title"),
                FakeMovieScraper(ScrapeSource.Missav, "missav-title")
            )
        )

        val info = registry.scrape(ScrapeSource.Missav, "ABC-123")

        assertEquals("missav-title", info.title)
        assertEquals("ABC-123", info.number)
    }

    @Test
    fun scrapeFailsWhenSourceIsNotRegistered() {
        val registry = MovieScraperRegistry(
            listOf(FakeMovieScraper(ScrapeSource.Dmm, "dmm-title"))
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { registry.scrape(ScrapeSource.Official, "ABC-123") }
        }
    }

    @Test
    fun scrapeWithFallbackUsesNextSourceAfterFailure() = runBlocking {
        val logs = mutableListOf<String>()
        val registry = MovieScraperRegistry(
            listOf(
                FailingMovieScraper(ScrapeSource.Dmm2),
                FakeMovieScraper(ScrapeSource.Javbus, "javbus-title")
            ),
            logger = logs::add
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "ABC-123",
            excludedSources = emptySet(),
            fallbackOrder = listOf(ScrapeSource.Javbus)
        )

        assertEquals("javbus-title", info.title)
        assert(logs.any { it.contains("刮削源成功") && it.contains("Javbus") })
    }

    @Test
    fun scrapeWithFallbackMergesMissingActorImages() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                PartialMovieScraper(ScrapeSource.Dmm2),
                CompleteMovieScraper(ScrapeSource.Javbus)
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "ABC-123",
            fallbackOrder = listOf(ScrapeSource.Javbus)
        )

        assertEquals("https://images.example/actor.jpg", info.actorImageUrls["演员A"])
        assertEquals("dmm2", info.source)
    }

    @Test
    fun scrapeWithFallbackPrefersOfficialDmmActorImageOverJavdbAvatar() = runBlocking {
        val jdbAvatar = "https://c0.jdbstatic.com/avatars/yn/Yn256.jpg"
        val dmmAvatar = "https://awsimgsrc.dmm.co.jp/pics_dig/mono/actjpgs/actor.jpg"
        val javbusAvatar = "https://www.javbus.com/pics/actress/actor.jpg"
        val registry = MovieScraperRegistry(
            listOf(
                InfoMovieScraper(
                    ScrapeSource.Javdb,
                    ScrapedMovieInfo(number = "ABC-123", title = "标题", actors = listOf("演员甲"), actorImageUrls = mapOf("演员甲" to jdbAvatar))
                ),
                InfoMovieScraper(
                    ScrapeSource.Javbus,
                    ScrapedMovieInfo(number = "ABC-123", title = "标题", actors = listOf("演员甲"), actorImageUrls = mapOf("演员甲" to javbusAvatar))
                ),
                InfoMovieScraper(
                    ScrapeSource.Dmm2,
                    ScrapedMovieInfo(number = "ABC-123", title = "标题", actors = listOf("演员甲"), actorImageUrls = mapOf("演员甲" to dmmAvatar))
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Javdb,
            number = "ABC-123",
            fallbackOrder = listOf(ScrapeSource.Javbus, ScrapeSource.Dmm2),
            collectAllSources = true
        )

        assertEquals(dmmAvatar, info.actorImageUrls["演员甲"])
        assertEquals(listOf(dmmAvatar, jdbAvatar, javbusAvatar), info.actorImageCandidates["演员甲"])
    }

    @Test
    fun scrapeWithFallbackKeepsDifferentActorNamesSeparateWithoutExplicitAlias() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                ActorImageMovieScraper(
                    ScrapeSource.Dmm2,
                    imageUrl = "https://images.example/actor.jpg"
                ),
                NamedActorMovieScraper(ScrapeSource.Javlibrary, "别名甲")
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "ABC-123",
            fallbackOrder = listOf(ScrapeSource.Javlibrary),
            collectAllSources = true
        )

        assertEquals(listOf("演员A", "别名甲"), info.actors)
        assertTrue(info.actorAliases.isEmpty())
    }

    @Test
    fun scrapeWithFallbackCollectsJavbusAlongsideEveryNonMissavAvatarSource() = runBlocking {
        val calls = mutableListOf<ScrapeSource>()
        val sources = listOf(
            ScrapeSource.Dmm2,
            ScrapeSource.Javdb,
            ScrapeSource.Javlibrary,
            ScrapeSource.Javbus,
            ScrapeSource.Dmm,
            ScrapeSource.Official
        )
        val registry = MovieScraperRegistry(
            sources.map { source ->
                RecordingMovieScraper(source, calls)
            }
        )

        registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "ABC-123",
            fallbackOrder = sources.drop(1),
            collectAllSources = true
        )

        assertEquals(sources, calls)
    }

    @Test
    fun scrapeWithFallbackDoesNotTreatDifferentSingleActorNamesAsAliases() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                NamedActorMovieScraper(ScrapeSource.Dmm2, "横山早苗"),
                NamedActorMovieScraper(ScrapeSource.Javlibrary, "前澤あきな")
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "MSAJ-004",
            fallbackOrder = listOf(ScrapeSource.Javlibrary)
        )

        assertEquals(listOf("横山早苗", "前澤あきな"), info.actors)
        assertTrue(info.actorAliases.isEmpty())
    }

    @Test
    fun scrapeWithFallbackRetainsAliasInsideParenthesizedSourceActorName() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                NamedActorMovieScraper(ScrapeSource.Dmm2, "あかね麗"),
                NamedActorMovieScraper(ScrapeSource.Javdb, "あかね麗（二階堂麗）")
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "NAMH-060",
            fallbackOrder = listOf(ScrapeSource.Javdb)
        )

        assertEquals(listOf("二階堂麗"), info.actorAliases["あかね麗"])
    }

    @Test
    fun scrapeWithFallbackKeepsUnconfirmedJavdbActorSeparateFromDmmActor() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                NamedActorMovieScraper(ScrapeSource.Dmm2, "あかね麗"),
                InfoMovieScraper(
                    ScrapeSource.Javdb,
                    ScrapedMovieInfo(
                        number = "NAMH-060",
                        title = "标题",
                        actors = listOf("二階堂麗"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "NAMH-060",
            fallbackOrder = listOf(ScrapeSource.Javdb)
        )

        assertEquals(listOf("あかね麗", "二階堂麗"), info.actors)
        assertTrue(info.actorAliases.isEmpty())
    }

    @Test
    fun scrapeWithFallbackKeepsAllFourHnds031ActorsIndependent() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                NamedActorMovieScraper(ScrapeSource.Dmm2, "浜崎真緒"),
                InfoMovieScraper(
                    ScrapeSource.Javdb,
                    ScrapedMovieInfo(
                        number = "HNDS-031",
                        title = "标题",
                        actors = listOf("水野朝陽", "篠田あゆみ", "浜崎真緒", "香山美桜"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "HNDS-031",
            fallbackOrder = listOf(ScrapeSource.Javdb)
        )

        assertEquals(listOf("浜崎真緒", "水野朝陽", "篠田あゆみ", "香山美桜"), info.actors)
        assertTrue(info.actorAliases.isEmpty())
    }

    @Test
    fun scrapeWithFallbackDropsNamesExplicitlyMarkedMaleByJavdb() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                InfoMovieScraper(
                    ScrapeSource.Dmm2,
                    ScrapedMovieInfo(
                        number = "DVMM-344",
                        title = "标题",
                        actors = listOf("ウルフ田中", "白石かんな"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                ),
                InfoMovieScraper(
                    ScrapeSource.Javdb,
                    ScrapedMovieInfo(
                        number = "DVMM-344",
                        title = "标题",
                        actors = listOf("白石かんな"),
                        excludedActorNames = listOf("ウルフ田中"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "DVMM-344",
            fallbackOrder = listOf(ScrapeSource.Javdb),
            collectAllSources = true
        )

        assertEquals(listOf("白石かんな"), info.actors)
        assertEquals(listOf("ウルフ田中"), info.excludedActorNames)
    }

    @Test
    fun scrapeWithFallbackMergesDifferentAliasesWhenSourcesShareActorAvatar() = runBlocking {
        val sharedAvatar = "https://c0.jdbstatic.com/avatars/si/Si123.jpg"
        val registry = MovieScraperRegistry(
            listOf(
                InfoMovieScraper(
                    ScrapeSource.Dmm2,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "标题",
                        actors = listOf("椎名由奈"),
                        actorImageUrls = mapOf("椎名由奈" to sharedAvatar),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                ),
                InfoMovieScraper(
                    ScrapeSource.Javdb,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "标题",
                        actors = listOf("椎名ゆな"),
                        actorImageUrls = mapOf("椎名ゆな" to sharedAvatar),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "ABC-123",
            fallbackOrder = listOf(ScrapeSource.Javdb),
            collectAllSources = true
        )

        assertEquals(listOf("椎名由奈"), info.actors)
        assertEquals(listOf("椎名ゆな"), info.actorAliases["椎名由奈"])
        assertEquals(sharedAvatar, info.actorImageUrls["椎名由奈"])
    }

    @Test
    fun scrapeWithFallbackMergesDifferentSingleActorNamesWithThreeSourceConsensus() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                NamedActorMovieScraper(ScrapeSource.Dmm2, "知佳瀬文香"),
                NamedActorMovieScraper(ScrapeSource.Javdb, "水端あさみ"),
                NamedActorMovieScraper(ScrapeSource.Javlibrary, "水端あさみ")
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "MSAJ-010",
            fallbackOrder = listOf(ScrapeSource.Javdb, ScrapeSource.Javlibrary),
            collectAllSources = true
        )

        assertEquals(listOf("知佳瀬文香"), info.actors)
        assertEquals(listOf("水端あさみ"), info.actorAliases["知佳瀬文香"])
    }

    @Test
    fun scrapeWithFallbackMergesSingleActorConsensusWhenOneSourceHasNoCast() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                NamedActorMovieScraper(ScrapeSource.Dmm2, "知佳瀬文香"),
                NamedActorMovieScraper(ScrapeSource.Javdb, "水端あさみ"),
                InfoMovieScraper(
                    ScrapeSource.Javlibrary,
                    ScrapedMovieInfo(number = "MSAJ-010", title = "标题", thumbUrl = "https://images.example/thumb.jpg")
                ),
                NamedActorMovieScraper(ScrapeSource.Javbus, "水端あさみ")
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "MSAJ-010",
            fallbackOrder = listOf(ScrapeSource.Javdb, ScrapeSource.Javlibrary, ScrapeSource.Javbus),
            collectAllSources = true
        )

        assertEquals(listOf("知佳瀬文香"), info.actors)
        assertEquals(listOf("水端あさみ"), info.actorAliases["知佳瀬文香"])
    }

    @Test
    fun scrapeWithFallbackKeepsSingleActorNamesSeparateWhenAnyActorBearingSourceHasMultipleActors() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                NamedActorMovieScraper(ScrapeSource.Dmm2, "演员甲"),
                NamedActorMovieScraper(ScrapeSource.Javdb, "演员乙"),
                InfoMovieScraper(
                    ScrapeSource.Javlibrary,
                    ScrapedMovieInfo(number = "ABC-123", title = "标题", thumbUrl = "https://images.example/thumb.jpg")
                ),
                InfoMovieScraper(
                    ScrapeSource.Javbus,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "标题",
                        actors = listOf("演员甲", "演员乙"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "ABC-123",
            fallbackOrder = listOf(ScrapeSource.Javdb, ScrapeSource.Javlibrary, ScrapeSource.Javbus),
            collectAllSources = true
        )

        assertEquals(listOf("演员甲", "演员乙"), info.actors)
        assertTrue(info.actorAliases.isEmpty())
    }

    @Test
    fun supplementalActorsDoNotRestoreNamesExplicitlyExcludedByMetadata() {
        val info = ScrapedMovieInfo(
            number = "DVMM-344",
            title = "标题",
            actors = listOf("白石かんな"),
            excludedActorNames = listOf("ウルフ田中")
        )

        val merged = info.withSupplementalActors(listOf("ウルフ田中", "白石かんな"))

        assertEquals(listOf("白石かんな"), merged.actors)
        assertTrue(merged.actorAliases.isEmpty())
    }

    @Test
    fun scrapeWithFallbackCoalescesRecordsBridgedByAnExplicitAlias() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                InfoMovieScraper(
                    ScrapeSource.Dmm2,
                    ScrapedMovieInfo(
                        number = "NAMH-045",
                        title = "标题",
                        actors = listOf("泉りおん", "宇流木さらら"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                ),
                InfoMovieScraper(
                    ScrapeSource.Javdb,
                    ScrapedMovieInfo(
                        number = "NAMH-045",
                        title = "标题",
                        actors = listOf("泉りおん", "宇流木さら"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                ),
                InfoMovieScraper(
                    ScrapeSource.Javlibrary,
                    ScrapedMovieInfo(
                        number = "NAMH-045",
                        title = "标题",
                        actors = listOf("泉りおん", "宇流木さら"),
                        actorAliases = mapOf("宇流木さら" to listOf("宇流木さらら")),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "NAMH-045",
            fallbackOrder = listOf(ScrapeSource.Javdb, ScrapeSource.Javlibrary),
            collectAllSources = true
        )

        assertEquals(listOf("泉りおん", "宇流木さらら"), info.actors)
        assertEquals(listOf("宇流木さら"), info.actorAliases["宇流木さらら"])
    }

    @Test
    fun scrapeWithFallbackCoalescesSingleActorPrefixAliasesWithIndependentSources() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                NamedActorMovieScraper(ScrapeSource.Dmm2, "宇流木さらら"),
                NamedActorMovieScraper(ScrapeSource.Javdb, "宇流木さら"),
                NamedActorMovieScraper(ScrapeSource.Javbus, "宇流木さら")
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "NAMH-022",
            fallbackOrder = listOf(ScrapeSource.Javdb, ScrapeSource.Javbus),
            collectAllSources = true
        )

        assertEquals(listOf("宇流木さらら"), info.actors)
        assertEquals(listOf("宇流木さら"), info.actorAliases["宇流木さらら"])
    }

    @Test
    fun scrapeWithFallbackCoalescesMultiActorAliasWhenSourcesShareAnAnchorActor() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                InfoMovieScraper(
                    ScrapeSource.Dmm2,
                    ScrapedMovieInfo(
                        number = "NAMH-045",
                        title = "标题",
                        actors = listOf("泉りおん", "宇流木さらら"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                ),
                InfoMovieScraper(
                    ScrapeSource.Javdb,
                    ScrapedMovieInfo(
                        number = "NAMH-045",
                        title = "标题",
                        actors = listOf("泉りおん", "宇流木さら"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                ),
                InfoMovieScraper(
                    ScrapeSource.Javbus,
                    ScrapedMovieInfo(
                        number = "NAMH-045",
                        title = "标题",
                        actors = listOf("泉りおん", "宇流木さら"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "NAMH-045",
            fallbackOrder = listOf(ScrapeSource.Javdb, ScrapeSource.Javbus),
            collectAllSources = true
        )

        assertEquals(listOf("泉りおん", "宇流木さらら"), info.actors)
        assertEquals(listOf("宇流木さら"), info.actorAliases["宇流木さらら"])
    }

    @Test
    fun scrapeWithFallbackKeepsExtraActorsWhenSourceActorCountsDiffer() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                InfoMovieScraper(
                    ScrapeSource.Dmm2,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "标题",
                        actors = listOf("演员甲", "演员乙"),
                        thumbUrl = "https://images.example/thumb.jpg",
                        source = "dmm2"
                    )
                ),
                InfoMovieScraper(
                    ScrapeSource.Javlibrary,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "标题",
                        actors = listOf("别名甲", "演员乙", "演员丙"),
                        actorAliases = mapOf(
                            "别名甲" to listOf("演员甲"),
                            "演员丙" to listOf("别名丁")
                        ),
                        thumbUrl = "https://images.example/thumb.jpg",
                        source = "javlibrary"
                    )
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "ABC-123",
            fallbackOrder = listOf(ScrapeSource.Javlibrary)
        )

        assertEquals(listOf("演员甲", "演员乙", "演员丙"), info.actors)
        assertEquals(listOf("别名甲"), info.actorAliases["演员甲"])
        assertEquals(listOf("别名丁"), info.actorAliases["演员丙"])
        assertEquals(null, info.actorAliases["演员乙"])
    }

    @Test
    fun scrapeWithFallbackMatchesReorderedMultiActorSourcesByNameInsteadOfPosition() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                InfoMovieScraper(
                    ScrapeSource.Dmm2,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "标题",
                        actors = listOf("演员甲", "演员乙"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                ),
                InfoMovieScraper(
                    ScrapeSource.Javdb,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "标题",
                        actors = listOf("演员乙", "演员甲"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "ABC-123",
            fallbackOrder = listOf(ScrapeSource.Javdb)
        )

        assertEquals(listOf("演员甲", "演员乙"), info.actors)
        assertTrue(info.actorAliases.isEmpty())
    }

    @Test
    fun scrapeWithFallbackDoesNotGuessMultiActorAliasesFromMatchingListSize() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                InfoMovieScraper(
                    ScrapeSource.Dmm2,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "标题",
                        actors = listOf("演员甲", "演员乙"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                ),
                InfoMovieScraper(
                    ScrapeSource.Javlibrary,
                    ScrapedMovieInfo(
                        number = "ABC-123",
                        title = "标题",
                        actors = listOf("别名甲", "别名乙"),
                        thumbUrl = "https://images.example/thumb.jpg"
                    )
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "ABC-123",
            fallbackOrder = listOf(ScrapeSource.Javlibrary)
        )

        assertEquals(listOf("演员甲", "演员乙", "别名甲", "别名乙"), info.actors)
        assertTrue(info.actorAliases.isEmpty())
    }

    @Test
    fun supplementalActorsKeepLibraryActorsBeyondPartialMetadata() {
        val info = ScrapedMovieInfo(
            number = "ABC-123",
            title = "标题",
            actors = listOf("百合良")
        )

        val merged = info.withSupplementalActors(
            listOf("白都四季（百合良）", "演员乙（别名丙）")
        )

        assertEquals(listOf("百合良", "演员乙"), merged.actors)
        assertEquals(listOf("白都四季"), merged.actorAliases["百合良"])
        assertEquals(listOf("别名丙"), merged.actorAliases["演员乙"])
    }

    @Test
    fun supplementalActorsDoNotKeepCategoryAsActorName() {
        val info = ScrapedMovieInfo(
            number = "ABC-123",
            title = "标题",
            actors = listOf("演员甲")
        )

        val merged = info.withSupplementalActors(listOf("歐美（演员乙）"))

        assertEquals(listOf("演员甲", "演员乙"), merged.actors)
        assertTrue(merged.actors.none(::isNonActorCategoryName))
    }

    @Test
    fun scrapeWithFallbackDoesNotMapUnknownActorImageToPrimaryActor() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                NamedActorMovieScraper(ScrapeSource.Dmm2, "演员甲"),
                object : MovieScraper {
                    override val source = ScrapeSource.Javdb

                    override suspend fun scrape(number: String) = ScrapedMovieInfo(
                        number = number,
                        title = "标题",
                        actors = listOf("演员乙（别名丙）"),
                        actorImageUrls = mapOf("演员乙（别名丙）" to "https://images.example/actor.jpg"),
                        thumbUrl = "https://images.example/thumb.jpg",
                        source = "javdb"
                    )
                }
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "ABC-123",
            fallbackOrder = listOf(ScrapeSource.Javdb)
        )

        assertEquals(listOf("演员甲", "演员乙"), info.actors)
        assertEquals(listOf("别名丙"), info.actorAliases["演员乙"])
        assertEquals("https://images.example/actor.jpg", info.actorImageUrls["演员乙"])
        assertEquals(null, info.actorImageUrls["演员甲"])
    }

    @Test
    fun scrapeWithFallbackContinuesWhenPrimarySourceHasNoActors() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                object : MovieScraper {
                    override val source = ScrapeSource.Dmm2

                    override suspend fun scrape(number: String) = ScrapedMovieInfo(
                        number = number,
                        title = "标题",
                        thumbUrl = "https://images.example/thumb.jpg",
                        source = "dmm2"
                    )
                },
                NamedActorMovieScraper(ScrapeSource.Javdb, "演员乙")
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "MSAJ-018",
            fallbackOrder = listOf(ScrapeSource.Javdb)
        )

        assertEquals(listOf("演员乙"), info.actors)
    }

    @Test
    fun scrapeWithFallbackKeepsSuccessfulMetadataWhenSupplementalSourcesFail() = runBlocking {
        val logs = mutableListOf<String>()
        val registry = MovieScraperRegistry(
            listOf(
                PartialMovieScraper(ScrapeSource.Dmm2),
                FailingMovieScraper(ScrapeSource.Javlibrary)
            ),
            logger = logs::add
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Dmm2,
            number = "MSAJ-004",
            fallbackOrder = listOf(ScrapeSource.Javlibrary)
        )

        assertEquals("标题", info.title)
        assertEquals("dmm2", info.source)
        assertTrue(logs.any { it.contains("多源融合降级完成") && it.contains("dmm2") })
    }

    @Test
    fun scrapeWithFallbackDoesNotTreatPlaceholderActorImageAsComplete() = runBlocking {
        val registry = MovieScraperRegistry(
            listOf(
                ActorImageMovieScraper(
                    ScrapeSource.Javbus,
                    imageUrl = "https://pics.dmm.co.jp/mono/actjpgs/now_printing.jpg"
                ),
                ActorImageMovieScraper(
                    ScrapeSource.Dmm2,
                    imageUrl = "https://awsimgsrc.dmm.co.jp/pics_dig/mono/actjpgs/actor.jpg"
                )
            )
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Javbus,
            number = "ABC-123",
            fallbackOrder = listOf(ScrapeSource.Dmm2)
        )

        assertEquals("https://awsimgsrc.dmm.co.jp/pics_dig/mono/actjpgs/actor.jpg", info.actorImageUrls["演员A"])
    }

    @Test
    fun scrapeWithFallbackGivesWebViewSourceItsOwnTimeout() = runBlocking {
        val registry = MovieScraperRegistry(
            scrapers = listOf(DelayedMovieScraper(ScrapeSource.Javlibrary, 35L)),
            webViewBackedSources = setOf(ScrapeSource.Javlibrary),
            webViewSourceTimeoutMs = 80L
        )

        val info = registry.scrapeWithFallback(
            preferred = ScrapeSource.Javlibrary,
            number = "ABC-123",
            fallbackOrder = emptyList(),
            sourceTimeoutMs = 10L
        )

        assertEquals("延迟来源", info.title)
    }

    @Test
    fun scrapeWithDmmPriorityGivesDmm2ItsOwnTimeout() = runBlocking {
        val registry = MovieScraperRegistry(
            scrapers = listOf(DelayedMovieScraper(ScrapeSource.Dmm2, 1_100L)),
            dmm2PrioritySourceTimeoutMs = 2_000L
        )

        val info = registry.scrapeWithDmmPriority(
            number = "ABC-123",
            sourceTimeoutMs = 1_000L
        )

        assertEquals("延迟来源", info.title)
    }

    @Test
    fun actorNameMatchingRecognizesParenthesizedAliases() {
        assertTrue(actorNamesMatch("澤村レイコ（高坂保奈美", "高坂保奈美"))
        assertTrue(actorNamesMatch("あかね麗（二階堂麗）", "二階堂麗"))
        assertTrue(actorNamesMatch("朝倉ゆら", "ゆら"))
        assertTrue(actorNamesMatch("橋本麗香", "麗香"))
    }

    @Test
    fun actorIdentityMatchingDoesNotTreatSuffixNamesAsTheSamePerson() {
        assertFalse(actorNamesHaveExactVariant("宇流木さらら", "宇流木さら"))
        assertTrue(actorNamesHaveExactVariant("宇流木さらら（宇流木さら）", "宇流木さら"))
    }

    @Test
    fun actorNamePartsDropsFieldLabelAndNormalizesSimplifiedCharacters() {
        assertEquals(listOf("櫻井美優"), actorNameParts("演员:（櫻井美優）"))
        assertTrue(actorNamesHaveExactVariant("波多野结衣", "波多野結衣"))
    }

    @Test
    fun canonicalizeActorIdentitiesKeepsFirstNameWhenAliasMapIsReversed() {
        val info = ScrapedMovieInfo(
            number = "NAMH-022",
            title = "示例影片",
            actors = listOf("宇流木さらら", "宇流木さら"),
            actorAliases = mapOf("宇流木さら" to listOf("宇流木さらら"))
        )

        val canonical = info.canonicalizeActorIdentities()

        assertEquals(listOf("宇流木さらら"), canonical.actors)
        assertEquals(listOf("宇流木さら"), canonical.actorAliases["宇流木さらら"])
    }

    @Test
    fun canonicalizeActorIdentitiesCollapsesSeparateNamesFromAliasEvidence() {
        val info = ScrapedMovieInfo(
            number = "MSAJ-010",
            title = "示例影片",
            actors = listOf("知佳瀬文香", "水端あさみ"),
            actorAliases = mapOf("知佳瀬文香" to listOf("水端あさみ"))
        )

        val canonical = info.canonicalizeActorIdentities()

        assertEquals(listOf("知佳瀬文香"), canonical.actors)
        assertEquals(listOf("水端あさみ"), canonical.actorAliases["知佳瀬文香"])
    }

    private class FakeMovieScraper(
        override val source: ScrapeSource,
        private val title: String
    ) : MovieScraper {
        override suspend fun scrape(number: String): ScrapedMovieInfo {
            return ScrapedMovieInfo(
                number = number,
                title = title
            )
        }
    }

    private class FailingMovieScraper(
        override val source: ScrapeSource
    ) : MovieScraper {
        override suspend fun scrape(number: String): ScrapedMovieInfo {
            error("测试源失败")
        }
    }

    private class RecordingMovieScraper(
        override val source: ScrapeSource,
        private val calls: MutableList<ScrapeSource>
    ) : MovieScraper {
        override suspend fun scrape(number: String): ScrapedMovieInfo {
            calls += source
            return ScrapedMovieInfo(
                number = number,
                title = "标题",
                actors = listOf("演员A"),
                thumbUrl = "https://images.example/thumb.jpg",
                source = source.name
            )
        }
    }

    private class PartialMovieScraper(
        override val source: ScrapeSource
    ) : MovieScraper {
        override suspend fun scrape(number: String) = ScrapedMovieInfo(
            number = number,
            title = "标题",
            actors = listOf("演员A"),
            thumbUrl = "https://images.example/thumb.jpg",
            source = "dmm2"
        )
    }

    private class CompleteMovieScraper(
        override val source: ScrapeSource
    ) : MovieScraper {
        override suspend fun scrape(number: String) = ScrapedMovieInfo(
            number = number,
            title = "标题",
            actors = listOf("演员A"),
            actorImageUrls = mapOf("演员A" to "https://images.example/actor.jpg"),
            thumbUrl = "https://images.example/thumb.jpg",
            source = "javbus"
        )
    }

    private class ActorImageMovieScraper(
        override val source: ScrapeSource,
        private val imageUrl: String
    ) : MovieScraper {
        override suspend fun scrape(number: String) = ScrapedMovieInfo(
            number = number,
            title = "标题",
            actors = listOf("演员A"),
            actorImageUrls = mapOf("演员A" to imageUrl),
            thumbUrl = "https://images.example/thumb.jpg",
            source = source.name.lowercase()
        )
    }

    private class NamedActorMovieScraper(
        override val source: ScrapeSource,
        private val actor: String
    ) : MovieScraper {
        override suspend fun scrape(number: String) = ScrapedMovieInfo(
            number = number,
            title = "标题",
            actors = listOf(actor),
            thumbUrl = "https://images.example/thumb.jpg",
            source = source.name.lowercase()
        )
    }

    private class InfoMovieScraper(
        override val source: ScrapeSource,
        private val info: ScrapedMovieInfo
    ) : MovieScraper {
        override suspend fun scrape(number: String): ScrapedMovieInfo = info.copy(number = number)
    }

    private class RecordingInfoMovieScraper(
        override val source: ScrapeSource,
        private val calls: MutableList<ScrapeSource>,
        private val info: ScrapedMovieInfo? = null,
        private val fail: Boolean = false
    ) : MovieScraper {
        override suspend fun scrape(number: String): ScrapedMovieInfo {
            calls += source
            if (fail) error("测试源失败")
            return (info ?: error("测试源未配置结果")).copy(number = number)
        }
    }

    private class DelayedMovieScraper(
        override val source: ScrapeSource,
        private val delayMs: Long
    ) : MovieScraper {
        override suspend fun scrape(number: String): ScrapedMovieInfo {
            delay(delayMs)
            return ScrapedMovieInfo(number = number, title = "延迟来源")
        }
    }
}
