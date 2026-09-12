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
private val SITE_LABELS = setOf(
    "サンプル動画", "サンプル画像", "セール", "期間限定セール", "BIGセール",
    "アウトレット", "ベストヒッツ", "ベスト"
)

internal fun isSiteClassification(value: String): Boolean = value in SITE_LABELS

internal fun mergeMovieClassifications(infos: List<ScrapedMovieInfo>): MovieClassifications {
    fun normalize(values: List<String>) = values.map { it.cleanMetadataText().trim() }
        .filter { it.isNotBlank() && !isSiteClassification(it) }
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

    /*
     * ================================================================================
     * 步骤1：消解作品署名与演员身份
     * ================================================================================
     * 目标：拒绝同一来源把一个作品署名同时绑定给多名演员的冲突关系。
     * 数据源：官方演员署名、外部演员主名及其显式别名。
     * 操作：
     * 1) 单一候选保持原有映射。
     * 2) 多候选时只接受被另一独立来源重复支持的唯一演员。
     * 3) 没有唯一支持者时保留待核，不猜测身份。
     */
    val creditCandidates = external.flatMap { info ->
        info.actors.flatMap { rawActor ->
            val actor = rawActor.primaryActorName()
            val aliases = (actorNameParts(rawActor).drop(1) + info.actorAliases
                .filterKeys { stored -> actorNamesHaveExactVariant(stored, actor) }
                .values.flatten())
                .flatMap(::actorNameParts)
            aliases.mapNotNull { alias ->
                officialNames.firstOrNull { officialName ->
                    actorNamesHaveExactVariant(officialName, alias)
                }?.let { credit -> actor to credit }
            }
        }
    }
    val creditsByActor = linkedMapOf<String, MutableList<String>>()
    val discardedConflictActors = mutableListOf<String>()
    officialNames.forEach { credit ->
        // 1.1 同一作品署名的演员候选按明确姓名变体归并。
        val candidates = creditCandidates
            .filter { (_, candidateCredit) -> actorNamesHaveExactVariant(candidateCredit, credit) }
            .map(Pair<String, String>::first)
            .distinctBy { actor -> actorNameVariants(actor).sorted().joinToString("|") }
        val selected = when (candidates.size) {
            0 -> null
            1 -> candidates.single()
            else -> {
                // 1.2 只有跨来源重复出现的唯一候选能打破来源内部冲突。
                val supportByActor = candidates.associateWith { actor ->
                    external.withIndex().filter { (_, info) ->
                        info.actors.any { rawActor ->
                            actorNamesHaveExactVariant(rawActor.primaryActorName(), actor)
                        }
                    }.map { (index, info) ->
                        info.source.trim().lowercase().ifBlank { "source-$index" }
                    }.toSet().size
                }
                val highestSupport = supportByActor.values.maxOrNull() ?: 0
                val supported = candidates.filter { actor -> supportByActor[actor] == highestSupport }
                supported.singleOrNull()?.takeIf { highestSupport >= 2 }?.also { winner ->
                    discardedConflictActors += candidates.filterNot { actor ->
                        actorNamesHaveExactVariant(actor, winner)
                    }
                }
            }
        }
        if (selected != null) {
            creditsByActor.getOrPut(selected) { mutableListOf() }.add(credit)
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
    fun isDiscardedConflict(name: String): Boolean = discardedConflictActors.any { discarded ->
        actorNamesHaveExactVariant(discarded, name)
    } && credits.keys.none { accepted -> actorNamesHaveExactVariant(accepted, name) }
    val reviewed = (official + external).map { info ->
        val aliases = info.actorAliases.mapNotNull { (actor, names) ->
            if (isMovieCredit(actor) || isUnresolved(actor) || isDiscardedConflict(actor)) return@mapNotNull null
            names.flatMap(::actorNameParts)
                .filterNot { name -> isMovieCredit(name) || isUnresolved(name) || isDiscardedConflict(name) }
                .distinct()
                .takeIf(List<String>::isNotEmpty)
                ?.let { actor to it }
        }.toMap()
        info.copy(
            actors = info.actors.filterNot { actor ->
                val name = actor.primaryActorName()
                isMovieCredit(name) || isUnresolved(name) || isDiscardedConflict(name)
            },
            actorAliases = aliases,
            actorImageUrls = info.actorImageUrls.filterKeys { name ->
                !isMovieCredit(name.primaryActorName()) && !isUnresolved(name.primaryActorName()) &&
                    !isDiscardedConflict(name.primaryActorName())
            },
            actorImageCandidates = info.actorImageCandidates.filterKeys { name ->
                !isMovieCredit(name.primaryActorName()) && !isUnresolved(name.primaryActorName()) &&
                    !isDiscardedConflict(name.primaryActorName())
            },
            verifiedActorNames = info.verifiedActorNames.filterNot {
                isMovieCredit(it) || isUnresolved(it) || isDiscardedConflict(it)
            }
        )
    }.filter { info -> info.actors.isNotEmpty() || info.excludedActorNames.isNotEmpty() }
    return ActorEvidenceReview(reviewed, credits, unresolvedNames)
}
