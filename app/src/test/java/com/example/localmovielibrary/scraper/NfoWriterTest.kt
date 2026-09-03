package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfoWriterTest {
    @Test
    fun build_removesZeroTrimmedNumberPrefixFromTitle() {
        val nfo = NfoWriter.build(
            ScrapedMovieInfo(
                number = "SNIS-00253",
                title = "SNIS-253 New Face NO.1 STYLE Aoi AV debut",
                originalTitle = "SNIS-253 New Face NO.1 STYLE Aoi AV debut"
            )
        )

        assertTrue(nfo.contains("<title>[SNIS-00253]New Face NO.1 STYLE Aoi AV debut</title>"))
        assertFalse(nfo.contains("[SNIS-00253]SNIS-253"))
    }

    @Test
    fun build_removesCompactNumberPrefixFromTitle() {
        val nfo = NfoWriter.build(
            ScrapedMovieInfo(
                number = "SNIS-00253",
                title = "snis00253 New Face NO.1 STYLE Aoi AV debut",
                originalTitle = "snis00253 New Face NO.1 STYLE Aoi AV debut"
            )
        )

        assertTrue(nfo.contains("<title>[SNIS-00253]New Face NO.1 STYLE Aoi AV debut</title>"))
        assertFalse(nfo.contains("[SNIS-00253]snis00253"))
    }

    @Test
    fun build_includesResolvedActorAliasesInTheActorName() {
        val nfo = NfoWriter.build(
            ScrapedMovieInfo(
                number = "MSAJ-004",
                title = "示例影片",
                actors = listOf("横山早苗"),
                actorAliases = mapOf("横山早苗" to listOf("前澤あきな"))
            )
        )

        assertTrue(nfo.contains("<name>横山早苗（前澤あきな）</name>"))
    }

    @Test
    fun build_splitsParenthesizedAliasesWithoutNestedParentheses() {
        val nfo = NfoWriter.build(
            ScrapedMovieInfo(
                number = "NAMH-060",
                title = "示例影片",
                actors = listOf("あかね麗（二階堂麗）")
            )
        )

        assertTrue(nfo.contains("<name>あかね麗（二階堂麗）</name>"))
        assertFalse(nfo.contains("（二階堂麗）（"))
    }

    @Test
    fun build_writesOnlyOfficialDmmActorImages() {
        val jdbAvatar = "https://c0.jdbstatic.com/avatars/yn/Yn256.jpg"
        val dmmAvatar = "https://awsimgsrc.dmm.co.jp/pics_dig/mono/actjpgs/actor.jpg"

        val jdbNfo = NfoWriter.build(
            ScrapedMovieInfo(number = "ABC-123", title = "示例影片", actors = listOf("演员甲"), actorImageUrls = mapOf("演员甲" to jdbAvatar))
        )
        val dmmNfo = NfoWriter.build(
            ScrapedMovieInfo(number = "ABC-123", title = "示例影片", actors = listOf("演员甲"), actorImageUrls = mapOf("演员甲" to dmmAvatar))
        )

        assertFalse(jdbNfo.contains(jdbAvatar))
        assertTrue(dmmNfo.contains("<thumb>$dmmAvatar</thumb>"))
    }

    @Test
    fun mergeActorDisplayNames_preservesOtherNfoFields() {
        val existingNfo = """
            <movie>
              <title>已有标题</title>
              <plot>已有简介</plot>
              <actor><name>横山早苗</name><thumb>https://old.example/avatar.jpg</thumb></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "MSAJ-004",
                title = "示例影片",
                actors = listOf("横山早苗"),
                actorAliases = mapOf("横山早苗" to listOf("前澤あきな"))
            )
        )

        assertTrue(updated.contains("<title>已有标题</title>"))
        assertTrue(updated.contains("<plot>已有简介</plot>"))
        assertTrue(updated.contains("<name>横山早苗（前澤あきな）</name>"))
        assertFalse(updated.contains("<thumb>https://old.example/avatar.jpg</thumb>"))
    }

    @Test
    fun mergeActorDisplayNames_removesStoredCategoryAliases() {
        val existingNfo = """
            <movie>
              <actor><name>百合良（白都四季、歐美）</name></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "MSAJ-018",
                title = "示例影片",
                actors = listOf("百合良"),
                actorAliases = mapOf("百合良" to listOf("白都四季"))
            )
        )

        assertTrue(updated.contains("<name>百合良（白都四季）</name>"))
        assertFalse(updated.contains("歐美"))
    }

    @Test
    fun mergeActorDisplayNames_removesActorsExplicitlyMarkedMaleByMetadata() {
        val existingNfo = """
            <movie>
              <actor><name>ウルフ田中</name></actor>
              <actor><name>白石かんな</name></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "DVMM-344",
                title = "示例影片",
                actors = listOf("白石かんな"),
                excludedActorNames = listOf("ウルフ田中")
            )
        )

        assertFalse(updated.contains("ウルフ田中"))
        assertTrue(updated.contains("<name>白石かんな</name>"))
    }

    @Test
    fun mergeActorDisplayNames_removesStoredCategoryAliasesWithoutNewAliases() {
        val existingNfo = """
            <movie>
              <actor><name>百合良（歐美）</name></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "MSAJ-018",
                title = "示例影片",
                actors = listOf("百合良")
            )
        )

        assertTrue(updated.contains("<name>百合良</name>"))
        assertFalse(updated.contains("歐美"))
    }

    @Test
    fun mergeActorDisplayNames_replacesStoredCategoryActorWithItsKnownAlias() {
        val existingNfo = """
            <movie>
              <actor><name>歐美（清巳れの）</name></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "ABC-123",
                title = "示例影片",
                actors = listOf("清巳れの")
            )
        )

        assertTrue(updated.contains("<name>清巳れの</name>"))
        assertFalse(updated.contains("歐美"))
    }

    @Test
    fun mergeActorDisplayNames_removesAliasesThatAreOtherActorsInTheSameMovie() {
        val existingNfo = """
            <movie>
              <actor><name>浜崎真緒（水野朝陽）</name></actor>
              <actor><name>香山美桜（水野朝阳）</name></actor>
              <actor><name>水野朝陽（篠田あゆみ）</name></actor>
              <actor><name>篠田あゆみ（香山美樱）</name></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "HNDS-031",
                title = "示例影片",
                actors = listOf("浜崎真緒", "香山美桜", "水野朝陽", "篠田あゆみ")
            )
        )

        assertTrue(updated.contains("<name>浜崎真緒</name>"))
        assertTrue(updated.contains("<name>香山美桜</name>"))
        assertTrue(updated.contains("<name>水野朝陽</name>"))
        assertTrue(updated.contains("<name>篠田あゆみ</name>"))
        assertFalse(updated.contains("浜崎真緒（水野朝陽）"))
        assertFalse(updated.contains("香山美桜（水野朝阳）"))
        assertFalse(updated.contains("水野朝陽（浜崎真緒）"))
        assertFalse(updated.contains("水野朝陽（篠田あゆみ）"))
        assertFalse(updated.contains("篠田あゆみ（香山美樱）"))
    }

    @Test
    fun mergeActorDisplayNames_removesStaleAliasNotReturnedByCurrentSources() {
        val existingNfo = """
            <movie>
              <actor><name>あかね麗（二階堂麗、今井勇太）</name></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "NAMH-060",
                title = "示例影片",
                actors = listOf("あかね麗"),
                actorAliases = mapOf("あかね麗" to listOf("二階堂麗"))
            )
        )

        assertTrue(updated.contains("<name>あかね麗（二階堂麗）</name>"))
        assertFalse(updated.contains("今井勇太"))
    }

    @Test
    fun mergeActorDisplayNames_collapsesSeparateActorBlocksForAnExplicitAlias() {
        val existingNfo = """
            <movie>
              <actor><name>泉りおん</name></actor>
              <actor><name>宇流木さらら</name></actor>
              <actor><name>宇流木さら</name></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "ABC-123",
                title = "示例影片",
                actors = listOf("泉りおん", "宇流木さらら", "宇流木さら"),
                actorAliases = mapOf("宇流木さらら" to listOf("宇流木さら"))
            )
        )

        assertEquals(1, Regex("<name>宇流木さらら（宇流木さら）</name>").findAll(updated).count())
        assertFalse(updated.contains("<name>宇流木さら</name>"))
    }

    @Test
    fun mergeActorDisplayNames_dropsUnconfirmedStoredAlias() {
        val existingNfo = """
            <movie>
              <actor><name>演员甲（错误别名）</name></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "ABC-123",
                title = "示例影片",
                actors = listOf("演员甲")
            )
        )

        assertTrue(updated.contains("<name>演员甲</name>"))
        assertFalse(updated.contains("错误别名"))
    }

    @Test
    fun mergeActorDisplayNames_addsActorsWhenExistingNfoHasNone() {
        val existingNfo = """
            <movie>
              <title>已有标题</title>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "MSAJ-018",
                title = "示例影片",
                actors = listOf("演员甲")
            )
        )

        assertTrue(updated.contains("<title>已有标题</title>"))
        assertTrue(updated.contains("<actor>"))
        assertTrue(updated.contains("<name>演员甲</name>"))
    }

    @Test
    fun mergeActorDisplayNames_removesJavdbThumbAndReplacesItWithOfficialDmmImage() {
        val jdbAvatar = "https://c0.jdbstatic.com/avatars/yn/Yn256.jpg"
        val dmmAvatar = "https://pics.dmm.co.jp/mono/actjpgs/actor.jpg"
        val existingNfo = """
            <movie>
              <actor><name>演员甲</name><thumb>$jdbAvatar</thumb></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(
                number = "ABC-123",
                title = "示例影片",
                actors = listOf("演员甲"),
                actorImageUrls = mapOf("演员甲" to dmmAvatar)
            )
        )

        assertFalse(updated.contains(jdbAvatar))
        assertTrue(updated.contains("<thumb>$dmmAvatar</thumb>"))
    }

    @Test
    fun mergeActorDisplayNames_cleansOnlyActorThumbsWhenNoCurrentActorsAreAvailable() {
        val jdbAvatar = "https://c0.jdbstatic.com/avatars/yn/Yn256.jpg"
        val movieThumb = "https://images.example/movie-thumb.jpg"
        val existingNfo = """
            <movie>
              <thumb>$movieThumb</thumb>
              <actor><name>演员甲</name><thumb>$jdbAvatar</thumb></actor>
            </movie>
        """.trimIndent()

        val updated = NfoWriter.mergeActorDisplayNames(
            existingNfo,
            ScrapedMovieInfo(number = "ABC-123", title = "示例影片")
        )

        assertTrue(updated.contains("<thumb>$movieThumb</thumb>"))
        assertFalse(updated.contains(jdbAvatar))
    }
}
