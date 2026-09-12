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

/** JavDB 仅提供演员身份证据，不能作为影片资料或图片的来源。 */
internal const val JAVDB_ACTOR_EVIDENCE_SOURCE = "javdb-actors-only"

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
    /** Independent source tags only; do not copy genres here. */
    val tags: List<String> = emptyList(),
    val rating: String = "",
    val trailer: String = "",
    val website: String = "",
    val source: String = "",
    val thumbUrl: String = "",
    val posterUrl: String = "",
    /** 同一演员来自多个资料源的头像候选，按下载优先级排序。 */
    val actorImageCandidates: Map<String, List<String>> = emptyMap(),
    /** Verified credits scoped to this movie; never used as global aliases or avatar lookup names. */
    val actorCredits: Map<String, List<String>> = emptyMap(),
    val unverifiedActorNames: List<String> = emptyList(),
    /** Actor names confirmed by a successfully parsed external actor profile. */
    val verifiedActorNames: List<String> = emptyList()
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
