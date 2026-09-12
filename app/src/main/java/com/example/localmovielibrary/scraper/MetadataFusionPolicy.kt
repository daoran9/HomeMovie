package com.example.localmovielibrary.scraper

import com.example.localmovielibrary.util.cleanMetadataText
import java.text.Normalizer

internal data class MovieClassifications(val genres: List<String>, val tags: List<String>)

// Only reviewed correspondences are normalized. Unknown labels retain their source wording.
private val CLASSIFICATION_NAMES = listOf(
    listOf("高中女生", "女子校生"), listOf("学校泳装", "學校泳裝", "競泳・スクール水着"),
    listOf("滥交", "濫交", "乱交"), listOf("无毛", "無毛", "パイパン"),
    listOf("单体作品", "單體作品", "単体作品"), listOf("中出", "中出し"),
    listOf("肛交", "アナル"), listOf("吞精", "ごっくん"), listOf("药物", "藥物", "ドラッグ"),
    listOf("電マ", "女优按摩棒", "女優按摩棒"), listOf("高清", "高畫質", "高画質", "ハイビジョン")
).flatMap { group -> group.map { it to group.first() } }.toMap()

private val TECHNICAL_TAGS = setOf("高清", "4K", "VR", "VR専用")
private val SITE_LABELS = setOf("サンプル動画", "サンプル画像", "セール", "期間限定セール")

internal fun mergeMovieClassifications(infos: List<ScrapedMovieInfo>): MovieClassifications {
    fun normalize(values: List<String>) = values.map { it.cleanMetadataText().trim() }
        .filter { it.isNotBlank() && it !in SITE_LABELS }
        .map { CLASSIFICATION_NAMES[it] ?: it }.distinct()
    val allGenres = normalize(infos.flatMap { it.genres })
    val allTags = normalize(infos.flatMap { it.tags })
    val genres = allGenres.filterNot { it in TECHNICAL_TAGS }
    // Independent source tags may legitimately share a name with a genre.
    return MovieClassifications(genres, (allGenres.filter { it in TECHNICAL_TAGS } + allTags).distinct())
}

internal fun mergeDirectorCredits(infos: List<ScrapedMovieInfo>): List<String> {
    val lists = infos.map { it.directors.map(String::trim).filter(String::isNotBlank).distinct() }
        .filter(List<String>::isNotEmpty)
    val result = lists.firstOrNull()?.toMutableList() ?: return emptyList()
    // A source explicitly naming an existing director together with another establishes a joint credit.
    lists.drop(1).forEach { credits ->
        if (credits.any { it in result }) result += credits.filterNot { it in result }
    }
    return result
}

internal fun isPseudonymProneMaker(maker: String): Boolean =
    Normalizer.normalize(maker, Normalizer.Form.NFKC).trim() == "豊彦"

internal fun haveCompatibleMovieEditions(left: ScrapedMovieInfo, right: ScrapedMovieInfo): Boolean {
    fun edition(info: ScrapedMovieInfo): String? = when {
        dmmDigitalContentId(info.website) != null -> "digital"
        "/mono/dvd/" in info.website -> "dvd"
        "/rental/" in info.website -> "rental"
        else -> null
    }
    val first = edition(left)
    val second = edition(right)
    return first == null || second == null || first == second
}

internal data class ActorEvidenceReview(
    val infos: List<ScrapedMovieInfo>,
    val credits: Map<String, List<String>> = emptyMap(),
    val unverifiedNames: List<String> = emptyList()
)

internal fun ScrapedMovieInfo.isMovieScopedActorName(name: String): Boolean =
    (actorCredits.values.flatten() + unverifiedActorNames).any { actorNamesHaveExactVariant(it, name) }

internal fun reviewMakerActorEvidence(
    official: List<ScrapedMovieInfo>,
    external: List<ScrapedMovieInfo>
): ActorEvidenceReview {
    val maker = (official + external.filterNot { it.source in setOf("javdb", JAVDB_ACTOR_EVIDENCE_SOURCE) })
        .firstOrNull { it.studio.isNotBlank() }?.studio.orEmpty()
    if (!isPseudonymProneMaker(maker)) return ActorEvidenceReview(official + external)

    val officialNames = official.flatMap { info -> info.actors }
        .flatMap(::actorNameParts)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    if (officialNames.isEmpty()) return ActorEvidenceReview(official + external)

    val creditsByActor = linkedMapOf<String, MutableList<String>>()
    external.forEach { info ->
        info.actors.forEach { rawActor ->
            val actor = rawActor.primaryActorName()
            val aliases = (actorNameParts(rawActor).drop(1) + info.actorAliases
                .filterKeys { stored -> actorNamesHaveExactVariant(stored, actor) }
                .values.flatten())
                .flatMap(::actorNameParts)
            aliases.forEach { alias ->
                val credit = officialNames.firstOrNull { officialName ->
                    actorNamesHaveExactVariant(officialName, alias)
                } ?: return@forEach
                creditsByActor.getOrPut(actor) { mutableListOf() }.add(credit)
            }
        }
    }
    val credits = creditsByActor.mapValues { (_, names) -> names.distinct() }
    val movieScopedNames = credits.values.flatten()
    val profileVerifiedNames = external.flatMap { info -> info.verifiedActorNames } + credits.keys
    // A matching profile does not resolve disjoint casts; require explicit joint credits.
    val excludedNames = (official + external).flatMap { it.excludedActorNames }
    fun cast(info: ScrapedMovieInfo): List<List<String>> = info.actors
        .filterNot { actor ->
            isNonActorCategoryName(actor.primaryActorName()) ||
                excludedNames.any { actorNamesHaveExactVariant(it, actor.primaryActorName()) }
        }
        .map { actor ->
            (actorNameParts(actor) + info.actorAliases
                .filterKeys { actorNamesHaveExactVariant(it, actor) }.values.flatten()
                .flatMap(::actorNameParts)).filterNot { name ->
                    isNonActorCategoryName(name) ||
                        excludedNames.any { actorNamesHaveExactVariant(it, name) }
                }.distinct()
        }
    val externalCasts = external.map(::cast)
    val allCasts = official.map(::cast) + externalCasts
    fun hasConflictingCast(name: String): Boolean {
        val jointlyCreditedNames = allCasts.filter { actors ->
            actors.any { names -> names.any { actorNamesHaveExactVariant(it, name) } }
        }.flatten().flatten()
        return externalCasts.flatten().any { names ->
            names.none { candidate -> jointlyCreditedNames.any { actorNamesHaveExactVariant(it, candidate) } }
        }
    }
    val verifiedOfficialNames = officialNames.filter { officialName ->
        profileVerifiedNames.any { verified -> actorNamesHaveExactVariant(officialName, verified) } &&
            !hasConflictingCast(officialName)
    }
    fun isMovieCredit(name: String): Boolean = movieScopedNames.any { credit ->
        actorNamesHaveExactVariant(credit, name)
    }
    fun isVerified(name: String): Boolean = verifiedOfficialNames.any { verified ->
        actorNamesHaveExactVariant(verified, name)
    }
    val unresolvedNames = officialNames.filterNot(::isMovieCredit).filterNot(::isVerified)
    fun isUnresolved(name: String): Boolean = unresolvedNames.any { unresolved ->
        actorNamesHaveExactVariant(unresolved, name)
    }
    val reviewed = (official + external).map { info ->
        val aliases = info.actorAliases.mapNotNull { (actor, names) ->
            if (isMovieCredit(actor) || isUnresolved(actor)) return@mapNotNull null
            names.flatMap(::actorNameParts)
                .filterNot { name -> isMovieCredit(name) || isUnresolved(name) }
                .distinct()
                .takeIf(List<String>::isNotEmpty)
                ?.let { actor to it }
        }.toMap()
        info.copy(
            actors = info.actors.filterNot { actor ->
                val name = actor.primaryActorName()
                isMovieCredit(name) || isUnresolved(name)
            },
            actorAliases = aliases,
            actorImageUrls = info.actorImageUrls.filterKeys { name ->
                !isMovieCredit(name.primaryActorName()) && !isUnresolved(name.primaryActorName())
            },
            actorImageCandidates = info.actorImageCandidates.filterKeys { name ->
                !isMovieCredit(name.primaryActorName()) && !isUnresolved(name.primaryActorName())
            },
            verifiedActorNames = info.verifiedActorNames.filterNot { isMovieCredit(it) || isUnresolved(it) }
        )
    }.filter { info -> info.actors.isNotEmpty() || info.excludedActorNames.isNotEmpty() }
    return ActorEvidenceReview(reviewed, credits, unresolvedNames)
}
