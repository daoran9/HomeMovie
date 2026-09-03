package com.example.localmovielibrary.scraper

enum class ScrapeSource {
    Dmm,
    Dmm2,
    Official,
    Javbus,
    Javdb,
    Javlibrary,
    Missav
}

data class ScrapedMovieInfo(
    val number: String,
    val title: String,
    val originalTitle: String = title,
    val plot: String = "",
    val outline: String = plot,
    val year: String = "",
    val premiered: String = "",
    val runtime: String = "",
    val studio: String = "",
    val publisher: String = "",
    val series: String = "",
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val actorAliases: Map<String, List<String>> = emptyMap(),
    /** 资料源明确标记为男性或其它非演员身份的姓名，禁止并入女演员列表。 */
    val excludedActorNames: List<String> = emptyList(),
    val actorImageUrls: Map<String, String> = emptyMap(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rating: String = "",
    val trailer: String = "",
    val website: String = "",
    val source: String = "",
    val thumbUrl: String = "",
    val posterUrl: String = ""
)

data class ActorAliasLookup(
    val name: String,
    val aliases: List<String> = emptyList()
)

data class ScrapeRunResult(
    val scanned: Int,
    val success: Int,
    val skipped: Int,
    val failed: Int
)
