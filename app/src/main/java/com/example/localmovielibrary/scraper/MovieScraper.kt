package com.example.localmovielibrary.scraper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.text.Normalizer
import java.util.Locale

interface MovieScraper {
    val source: ScrapeSource

    suspend fun scrape(number: String): ScrapedMovieInfo
}

class MovieScraperRegistry(
    scrapers: List<MovieScraper>,
    private val logger: ((String) -> Unit)? = null,
    private val webViewBackedSources: Set<ScrapeSource> = emptySet(),
    private val webViewSourceTimeoutMs: Long = WEBVIEW_SOURCE_TIMEOUT_MS
) {
    private val scrapersBySource = scrapers.associateBy { it.source }

    suspend fun scrape(source: ScrapeSource, number: String): ScrapedMovieInfo {
        val scraper = scrapersBySource[source] ?: error("Unsupported scrape source: $source")
        return scraper.scrape(number)
    }

    /*
     * ================================================================================
     * 步骤1：按优先级回退并融合刮削源
     * ================================================================================
     * 目标：复用 Fusion 的“优先源 + 后备源补缺”策略，避免单一站点结果不完整。
     * 数据源：设置中的首选源、已注册的刮削器和当前番号。
     * 操作：
     * 1) 首先调用用户选择的源。
     * 2) 结果缺少封面或演员头像时，按优先级尝试后备源并合并缺失字段。
     * 3) 单源请求设置超时；任务取消不转成普通刮削失败。
     */
    suspend fun scrapeWithFallback(
        preferred: ScrapeSource,
        number: String,
        excludedSources: Set<ScrapeSource> = emptySet(),
        fallbackOrder: List<ScrapeSource> = DEFAULT_FALLBACK_ORDER,
        sourceTimeoutMs: Long = DEFAULT_SOURCE_TIMEOUT_MS,
        collectAllSources: Boolean = false
    ): ScrapedMovieInfo {
        logger?.invoke("开始多源融合刮削：number=$number, preferred=${preferred.name}")
        var lastError: Throwable? = null
        val results = mutableListOf<ScrapedMovieInfo>()
        val candidates = (listOf(preferred) + fallbackOrder)
            .distinct()
            .filterNot { it in excludedSources }
        if (excludedSources.isNotEmpty()) {
            logger?.invoke("多源刮削排除来源：${excludedSources.joinToString { it.name }}")
        }
        candidates.forEachIndexed { index, source ->
            val scraper = scrapersBySource[source] ?: return@forEachIndexed
            try {
                // 1.1 先执行当前候选源；超时后继续下一个来源。
                val timeout = if (source in webViewBackedSources) {
                    webViewSourceTimeoutMs
                } else {
                    sourceTimeoutMs
                }
                val info = withTimeout(timeout.coerceAtLeast(1_000L)) {
                    scraper.scrape(number)
                }
                results += info
                val merged = mergeInfos(results)
                logger?.invoke(
                    "刮削源成功：number=$number, source=${source.name}, 已融合=${results.size}, " +
                        "actors=${info.actors.joinToString("/")}, excluded=${info.excludedActorNames.joinToString("/")}"
                )
                if (!collectAllSources && (isSufficient(merged) || index == candidates.lastIndex)) {
                    if (results.size > 1) {
                        logger?.invoke("多源融合完成：number=$number, sources=${results.joinToString { it.source.ifBlank { "unknown" } }}")
                    } else {
                        logger?.invoke("多源刮削完成：number=$number, source=${source.name}")
                    }
                    return merged
                }
                if (!collectAllSources) {
                    logger?.invoke("刮削结果需要补全：number=$number, next=${candidates.getOrNull(index + 1)?.name ?: "none"}")
                }
            } catch (error: TimeoutCancellationException) {
                lastError = error
                logger?.invoke("刮削源超时：number=$number, source=${source.name}, timeout=${if (source in webViewBackedSources) webViewSourceTimeoutMs else sourceTimeoutMs}ms")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                logger?.invoke(
                    "刮削源失败：number=$number, source=${source.name}, " +
                        "reason=${error.message ?: error::class.java.simpleName}"
                )
            }
        }

        if (results.isNotEmpty()) {
            val merged = mergeInfos(results)
            val sourceNames = results.joinToString { result -> result.source.ifBlank { "unknown" } }
            logger?.invoke(
                "${if (collectAllSources) "多源完整融合完成" else "多源融合降级完成"}：number=$number, " +
                    "sources=$sourceNames"
            )
            return merged
        }

        val detail = lastError?.message ?: lastError?.javaClass?.simpleName ?: "未知错误"
        throw IllegalStateException("所有刮削源均失败：$number，最后错误：$detail", lastError)
    }

    private fun mergeInfos(infos: List<ScrapedMovieInfo>): ScrapedMovieInfo {
        val primary = infos.first()
        fun firstNonBlank(selector: (ScrapedMovieInfo) -> String): String =
            infos.asSequence().map(selector).firstOrNull { it.isNotBlank() }.orEmpty()
        fun firstList(selector: (ScrapedMovieInfo) -> List<String>): List<String> =
            infos.asSequence().map(selector).firstOrNull { it.isNotEmpty() }.orEmpty()

        val mergedActors = mergeActors(infos)
        val actors = mergedActors.names
        val actorAliases = mergedActors.aliases
        fun actorImageEntries(info: ScrapedMovieInfo): Sequence<Pair<String, String>> = sequence {
            info.actorImageCandidates.forEach { (name, urls) ->
                urls.forEach { url -> yield(name to url) }
            }
            info.actorImageUrls.forEach { (name, url) -> yield(name to url) }
        }
        val actorImageCandidates = actors.mapNotNull { actor ->
            val knownNames = actorNameParts(actor) + actorAliases[actor].orEmpty()
            val candidates = infos.asSequence()
                .flatMap(::actorImageEntries)
                .filter { (name, url) ->
                    isUsableActorImageUrl(url) && knownNames.any { knownName ->
                        actorNamesHaveExactVariant(name, knownName)
                    }
                }
                .map { (_, url) -> url }
                .toList()
                .let(::prioritizeActorImageUrls)
            candidates.takeIf { it.isNotEmpty() }?.let { actor to it }
        }.toMap()
        val actorImages = actorImageCandidates.mapValues { (_, candidates) -> candidates.first() }

        return primary.copy(
            number = firstNonBlank { it.number },
            title = firstNonBlank { it.title },
            originalTitle = firstNonBlank { it.originalTitle },
            plot = firstNonBlank { it.plot },
            outline = firstNonBlank { it.outline },
            year = firstNonBlank { it.year },
            premiered = firstNonBlank { it.premiered },
            runtime = firstNonBlank { it.runtime },
            studio = firstNonBlank { it.studio },
            publisher = firstNonBlank { it.publisher },
            series = firstNonBlank { it.series },
            directors = firstList { it.directors },
            actors = actors,
            actorAliases = actorAliases,
            excludedActorNames = infos.flatMap { it.excludedActorNames }
                .filter { it.isNotBlank() }
                .distinctBy { actorNameVariants(it).sorted().joinToString("|") },
            actorImageUrls = actorImages,
            actorImageCandidates = actorImageCandidates,
            genres = firstList { it.genres },
            tags = firstList { it.tags },
            rating = firstNonBlank { it.rating },
            trailer = firstNonBlank { it.trailer },
            website = firstNonBlank { it.website },
            source = primary.source.ifBlank { infos.drop(1).firstOrNull()?.source.orEmpty() },
            thumbUrl = firstNonBlank { it.thumbUrl },
            posterUrl = firstNonBlank { it.posterUrl }
        ).canonicalizeActorIdentities()
    }

    /*
     * ================================================================================
     * 步骤2：融合不同来源的演员和别名
     * ================================================================================
     * 目标：保留后续来源新增演员，且不把人数不同的演员列表按位置错配。
     * 数据源：DMM/FANZA、JavDB、JavBus、JavLibrary 的演员列表和显式别名。
     * 操作：
     * 1) 先按同名或来源明确给出的别名合并。
     * 2) 只有单演员影片才允许把唯一的不同姓名视为别名。
     * 3) 多演员无法证明关联时单独保留，不能因顺序相同而错配。
     * 4) 输出统一演员名、别名和头像匹配候选，供 NFO 与头像任务共用。
     */
    private fun mergeActors(infos: List<ScrapedMovieInfo>): MergedActorList {
        logger?.invoke("开始融合演员列表：sources=${infos.size}")
        val excludedActorNames = infos.flatMap { info -> info.excludedActorNames }
            .filter { name -> name.isNotBlank() }
            .distinctBy { name -> actorNameVariants(name).sorted().joinToString("|") }
        val sourceActors = infos.map { info -> info.actorIdentities(excludedActorNames) }
        val primaryIndex = sourceActors.indexOfFirst { actors -> actors.isNotEmpty() }
        if (primaryIndex < 0) return MergedActorList()
        val primaryActors = sourceActors[primaryIndex]
        val records = mutableListOf<MergedActor>()
        primaryActors.forEach { actor ->
            records.mergeExactActor(actor)
        }

        sourceActors.forEachIndexed { index, identities ->
            if (index == primaryIndex || identities.isEmpty()) return@forEachIndexed
            identities.forEach { sourceActor ->
                records.mergeExactActor(sourceActor)
            }
        }

        records.mergeSharedImageRecords()
        records.mergeSingleActorConsensusRecords(sourceActors)
        records.mergeLikelyAliasRecords(sourceActors)

        val result = MergedActorList(
            names = records.map { actor -> actor.name },
            aliases = records.mapNotNull { actor ->
                actor.aliases().takeIf { aliases -> aliases.isNotEmpty() }?.let { aliases -> actor.name to aliases }
            }.toMap()
        )
        logger?.invoke("演员列表融合完成：primary=${primaryActors.size}, merged=${result.names.size}")
        return result
    }

    private fun ScrapedMovieInfo.actorIdentities(
        globallyExcludedActorNames: List<String>
    ): List<ActorIdentity> = actors.mapNotNull { rawActor ->
        val name = rawActor.primaryActorName().ifBlank { rawActor.trim() }
        if (
            name.isBlank() ||
            isNonActorCategoryName(name) ||
            globallyExcludedActorNames.isExcludedActor(name)
        ) return@mapNotNull null
        val aliases = actorAliases.entries
            .filter { (storedName, _) -> actorNamesHaveExactVariant(storedName, rawActor) }
            .flatMap { (_, storedAliases) -> storedAliases }
        ActorIdentity(
            name = name,
            names = (actorNameParts(rawActor) + aliases.flatMap(::actorNameParts))
                .filter { candidate ->
                    candidate.isNotBlank() &&
                        !isNonActorCategoryName(candidate) &&
                        !globallyExcludedActorNames.isExcludedActor(candidate)
                }
                .distinctBy { candidate -> actorNameVariants(candidate).sorted().joinToString("|") }
            , imageKeys = actorImageUrls.entries
                .asSequence()
                .filter { (storedName, url) ->
                    actorNamesHaveExactVariant(storedName, rawActor) &&
                        isActorIdentityImageUrl(url)
                }
                .map { (_, url) -> actorIdentityImageKey(url) }
                .toSet()
        )
    }

    private fun List<String>.isExcludedActor(name: String): Boolean =
        any { excluded -> actorNamesHaveExactVariant(excluded, name) }

    /*
     * ================================================================================
     * 步骤4：按稳定头像地址合并演员身份
     * ================================================================================
     * 目标：同一演员在不同资料源使用完全不同的别名时，仍能借助同一张资料头像归并。
     * 数据源：JavDB avatar CDN、DMM/FANZA actjpgs CDN 返回的稳定头像地址。
     * 操作：
     * 1) 只接受明确的演员头像路径，不使用封面或缩略图地址。
     * 2) 共享同一头像地址时合并记录，并保留所有姓名为 alias。
     */
    private fun MutableList<MergedActor>.mergeSharedImageRecords() {
        var changed = true
        while (changed) {
            changed = false
            outer@ for (leftIndex in indices) {
                for (rightIndex in (leftIndex + 1) until size) {
                    if (this[leftIndex].imageKeys().intersect(this[rightIndex].imageKeys()).isEmpty()) continue
                    val left = this[leftIndex]
                    val right = this[rightIndex]
                    logger?.invoke("按共享演员头像归并：${left.name} <- ${right.name}")
                    left.merge(right)
                    removeAt(rightIndex)
                    changed = true
                    break@outer
                }
            }
        }
    }

    /*
     * ================================================================================
     * 步骤5：按多源单演员共识合并艺名
     * ================================================================================
     * 目标：处理 DMM 与外部资料源使用完全不同艺名、但影片页均确认仅有一位女演员的情况。
     * 数据源：至少三份同番号详情页的演员列表。
     * 操作：
     * 1) 只统计实际解析出演员的资料源；空演员字段不能否定其它源的一致结论。
     * 2) 一个名字必须获得至少两份独立资料源支持，另一名字由剩余来源支持。
     * 3) 不满足三源共识时保留独立演员，交给显式 alias 或共享头像规则处理。
     */
    private fun MutableList<MergedActor>.mergeSingleActorConsensusRecords(
        sourceActors: List<List<ActorIdentity>>
    ) {
        val actorBearingSourceIndexes = sourceActors.indices
            .filter { index -> sourceActors[index].isNotEmpty() }
        if (
            size != 2 ||
            actorBearingSourceIndexes.size < 3 ||
            actorBearingSourceIndexes.any { index -> sourceActors[index].size != 1 }
        ) return
        val left = this[0]
        val right = this[1]
        val leftSources = actorBearingSourceIndexes.filter { index ->
            sourceActors[index].single().names.any { sourceName ->
                left.identityNames().any { known -> actorNamesHaveExactVariant(known, sourceName) }
            }
        }
        val rightSources = actorBearingSourceIndexes.filter { index ->
            sourceActors[index].single().names.any { sourceName ->
                right.identityNames().any { known -> actorNamesHaveExactVariant(known, sourceName) }
            }
        }
        val allSourcesAreCovered = (leftSources + rightSources).distinct().size == actorBearingSourceIndexes.size
        if (!allSourcesAreCovered || maxOf(leftSources.size, rightSources.size) < 2) return

        logger?.invoke("按三源单演员共识归并：${left.name} <- ${right.name}")
        left.merge(right)
        removeAt(1)
    }

    private fun MutableList<MergedActor>.mergeExactActor(actor: ActorIdentity) {
        val exactMatches = filter { record -> record.hasExactName(actor) }
        if (exactMatches.isEmpty()) {
            add(MergedActor(actor))
            return
        }

        val canonical = exactMatches.first()
        // An incoming actor that explicitly names both variants proves the matched records are one person.
        exactMatches.drop(1).forEach { duplicate ->
            canonical.merge(duplicate)
            remove(duplicate)
        }
        canonical.merge(actor)
    }

    /*
     * ================================================================================
     * 步骤3：按多源共识合并短名与全名
     * ================================================================================
     * 目标：处理“宇流木さら / 宇流木さらら”这类来源没有显式 alias 节点的同一演员。
     * 数据源：至少两个独立资料源；多人资料源还必须共享一个已确认的其它演员。
     * 操作：
     * 1) 只接受一方是另一方前缀或后缀、且长度差不超过两个字符的姓名。
     * 2) 要求两种姓名分别得到至少一个独立来源支持，避免按列表位置猜测。
     * 3) 保留先出现的来源姓名为主名，其余姓名写入 alias。
     */
    private fun MutableList<MergedActor>.mergeLikelyAliasRecords(
        sourceActors: List<List<ActorIdentity>>
    ) {
        var changed = true
        while (changed) {
            changed = false
            outer@ for (leftIndex in indices) {
                for (rightIndex in (leftIndex + 1) until size) {
                    val left = this[leftIndex]
                    val right = this[rightIndex]
                    if (!left.hasLikelyAliasVariant(right)) continue
                    val leftSources = sourceActors.indices.filter { index ->
                        sourceActors[index].any { sourceActor ->
                            left.identityNames().any { known ->
                                sourceActor.names.any { sourceName ->
                                    actorNamesHaveExactVariant(known, sourceName)
                                }
                            }
                        }
                    }
                    val rightSources = sourceActors.indices.filter { index ->
                        sourceActors[index].any { sourceActor ->
                            right.identityNames().any { known ->
                                sourceActor.names.any { sourceName ->
                                    actorNamesHaveExactVariant(known, sourceName)
                                }
                            }
                        }
                    }
                    if (leftSources.isEmpty() || rightSources.isEmpty()) continue
                    val distinctSources = (leftSources + rightSources).distinct()
                    if (distinctSources.size < 2) continue
                    val allSourcesAreSingleActor = distinctSources.all { index -> sourceActors[index].size == 1 }
                    if (!allSourcesAreSingleActor && !hasSharedAnchor(left, right, sourceActors, leftSources, rightSources)) {
                        continue
                    }
                    logger?.invoke("按多源演员身份归并：${left.name} <- ${right.name}")
                    left.merge(right)
                    removeAt(rightIndex)
                    changed = true
                    break@outer
                }
            }
        }
    }

    private fun MutableList<MergedActor>.hasSharedAnchor(
        left: MergedActor,
        right: MergedActor,
        sourceActors: List<List<ActorIdentity>>,
        leftSources: List<Int>,
        rightSources: List<Int>
    ): Boolean = leftSources.any { leftIndex ->
        rightSources.any { rightIndex ->
            if (leftIndex == rightIndex) return@any false
            val leftSource = sourceActors[leftIndex]
            val rightSource = sourceActors[rightIndex]
            any { anchor ->
                anchor !== left && anchor !== right &&
                    leftSource.any { sourceActor ->
                        sourceActor.names.any { sourceName ->
                            anchor.identityNames().any { known -> actorNamesHaveExactVariant(known, sourceName) }
                        }
                    } &&
                    rightSource.any { sourceActor ->
                        sourceActor.names.any { sourceName ->
                            anchor.identityNames().any { known -> actorNamesHaveExactVariant(known, sourceName) }
                        }
                    }
            }
        }
    }

    private data class MergedActorList(
        val names: List<String> = emptyList(),
        val aliases: Map<String, List<String>> = emptyMap()
    )

    private data class ActorIdentity(
        val name: String,
        val names: List<String>,
        val imageKeys: Set<String> = emptySet()
    )

    private class MergedActor(initial: ActorIdentity) {
        val name = initial.name
        private val knownNames = initial.names.toMutableList()
        private val knownImageKeys = initial.imageKeys.toMutableSet()
        private val knownAliases = initial.names
            .filterNot { candidate -> actorNamesHaveExactVariant(candidate, name) }
            .toMutableList()

        fun hasExactName(candidate: ActorIdentity): Boolean = knownNames.any { known ->
            candidate.names.any { incoming -> actorNamesHaveExactVariant(known, incoming) }
        }

        fun identityNames(): List<String> = knownNames.toList()

        fun imageKeys(): Set<String> = knownImageKeys.toSet()

        fun hasLikelyAliasVariant(other: MergedActor): Boolean = knownNames.any { left ->
            other.knownNames.any { right -> actorNamesHaveLikelyAliasVariant(left, right) }
        }

        fun merge(candidate: ActorIdentity) {
            mergeNames(candidate.names)
            mergeImageKeys(candidate.imageKeys)
        }

        fun merge(other: MergedActor) {
            mergeNames(other.knownNames)
            mergeImageKeys(other.knownImageKeys)
        }

        private fun mergeNames(names: Collection<String>) {
            names.forEach { incoming ->
                if (knownNames.any { known -> actorNamesHaveExactVariant(known, incoming) }) return@forEach
                knownNames += incoming
                if (!actorNamesHaveExactVariant(incoming, name)) {
                    knownAliases += incoming
                }
            }
        }

        private fun mergeImageKeys(imageKeys: Collection<String>) {
            knownImageKeys += imageKeys
        }

        fun aliases(): List<String> = knownAliases
            .filter { alias -> alias.isNotBlank() && !actorNamesHaveExactVariant(alias, name) }
            .distinctBy { alias -> actorNameVariants(alias).sorted().joinToString("|") }
    }

    private fun isSufficient(info: ScrapedMovieInfo): Boolean {
        if (info.title.isBlank() || info.posterUrl.isBlank() && info.thumbUrl.isBlank()) return false
        if (info.actors.isEmpty()) return false
        return info.actors.all { actor ->
            val identityNames = actorNameParts(actor) + info.actorAliases
                .filterKeys { storedActor -> actorNamesHaveExactVariant(storedActor, actor) }
                .values
                .flatten()
                .flatMap(::actorNameParts)
            info.actorImageUrls.any { (imageActor, imageUrl) ->
                identityNames.any { identity -> actorNamesHaveExactVariant(imageActor, identity) } &&
                    isUsableActorImageUrl(imageUrl)
            }
        }
    }

    private fun isUsableActorImageUrl(url: String): Boolean {
        val value = url.trim()
        return value.isNotBlank() &&
            !value.equals("null", ignoreCase = true) &&
            !Regex("now[_-]?printing|no[_-]?(?:image|photo)|placeholder", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)
    }

    private companion object {
        val DEFAULT_FALLBACK_ORDER = listOf(
            ScrapeSource.Dmm2,
            ScrapeSource.Javdb,
            ScrapeSource.Javlibrary,
            ScrapeSource.Javbus,
            ScrapeSource.Dmm,
            ScrapeSource.Official
        )
        const val DEFAULT_SOURCE_TIMEOUT_MS = 8_000L
        const val WEBVIEW_SOURCE_TIMEOUT_MS = 70_000L
    }
}

internal fun actorNamesMatch(left: String, right: String): Boolean =
    actorNameVariants(left).intersect(actorNameVariants(right)).isNotEmpty() ||
        actorNameVariants(left).any { leftName ->
            actorNameVariants(right).any { rightName ->
                leftName.length >= 2 && rightName.length >= 2 &&
                    (leftName.endsWith(rightName) || rightName.endsWith(leftName))
            }
        }

internal fun actorNamesHaveLikelyAliasVariant(left: String, right: String): Boolean {
    val leftVariants = actorNameVariants(left)
    val rightVariants = actorNameVariants(right)
    return leftVariants.any { leftName ->
        rightVariants.any { rightName ->
            if (leftName == rightName) {
                false
            } else {
                val shorter: String
                val longer: String
                if (leftName.length <= rightName.length) {
                    shorter = leftName
                    longer = rightName
                } else {
                    shorter = rightName
                    longer = leftName
                }
                shorter.length >= 2 &&
                    longer.length - shorter.length in 1..2 &&
                    (longer.startsWith(shorter) || longer.endsWith(shorter))
            }
        }
    }
}

internal fun actorNamesHaveExactVariant(left: String, right: String): Boolean =
    actorNameVariants(left).intersect(actorNameVariants(right)).isNotEmpty()

internal fun isActorIdentityImageUrl(url: String): Boolean {
    val normalized = url.trim().lowercase(Locale.ROOT)
    return normalized.isNotBlank() &&
        normalized != "null" &&
        !Regex("now[_-]?printing|no[_-]?(?:image|photo)|placeholder").containsMatchIn(normalized) &&
        ("/avatars/" in normalized || "/actjpgs/" in normalized)
}

internal fun isOfficialDmmActorImageUrl(url: String): Boolean {
    val normalized = url.trim().lowercase(Locale.ROOT)
    return Regex("""^https?://(?:[a-z0-9-]+\.)*dmm\.co\.jp(?:/|$)""").containsMatchIn(normalized) &&
        "/actjpgs/" in normalized
}

internal fun prioritizeActorImageUrls(urls: Collection<String>): List<String> = urls.asSequence()
    .map(String::trim)
    .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    .distinct()
    .sortedBy(::actorImageUrlPriority)
    .toList()

private fun actorImageUrlPriority(url: String): Int {
    val normalized = url.lowercase(Locale.ROOT)
    return when {
        isOfficialDmmActorImageUrl(url) -> 0
        "jdbstatic.com" in normalized || "javdb" in normalized -> 1
        "javbus" in normalized -> 2
        else -> 3
    }
}

internal fun actorIdentityImageKey(url: String): String =
    url.trim().substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)

internal fun isNonActorCategoryName(value: String): Boolean =
    actorNameVariants(value).any { name -> name in NON_ACTOR_CATEGORY_NAME_VARIANTS }

private val NON_ACTOR_CATEGORY_NAME_VARIANTS = setOf(
    "有碼",
    "有码",
    "無碼",
    "无码",
    "歐美",
    "欧美"
)

/*
 * ================================================================================
 * 步骤2：统一演员姓名的繁简和异体字
 * ================================================================================
 * 目标：同一演员在不同资料源使用繁体、简体或日文异体字时，仍能精确去重。
 * 数据源：JavDB、JavLibrary 和旧 NFO 中的演员显示名。
 * 操作：
 * 1) 只转换已确认的姓名字符变体，不改变日文假名或拉丁字符。
 * 2) 归一结果仅用于比较，NFO 仍保留来源返回的显示名。
 */
private val ACTOR_NAME_CHAR_VARIANTS = mapOf(
    '亚' to '亜',
    '樱' to '桜',
    '阳' to '陽',
    '丽' to '麗',
    '泽' to '澤',
    '华' to '華',
    '兰' to '蘭',
    '龙' to '龍',
    '艳' to '艷',
    '庆' to '慶',
    '荣' to '榮',
    '纪' to '紀',
    '惠' to '恵',
    '穗' to '穂',
    '岛' to '島',
    '边' to '邊',
    '叶' to '葉',
    '绪' to '緒',
    '真' to '眞'
)

/**
 * ================================================================================
 * 步骤1：拆分演员显示名
 * ================================================================================
 * 目标：把 NFO/JavDB/JavLibrary 中的“主名（别名）”还原成可独立融合的名字。
 * 数据源：影片页和 NFO 内的演员显示文本。
 * 操作：
 * 1) 统一全角括号和分隔符。
 * 2) 保留可显示的原始名字，用于后续写回 NFO。
 */
internal fun actorNameParts(value: String): List<String> =
    value
        .replace('（', '(')
        .replace('）', ')')
        .split(Regex("[(),、，/／|;；]+"))
        .map { actor ->
            Normalizer.normalize(actor.trim(), Normalizer.Form.NFKC)
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        .filter { it.isNotBlank() }
        .distinctBy { actor ->
            actor.replace(Regex("\\s+"), "").lowercase(Locale.ROOT)
        }

internal fun actorNameVariants(value: String): Set<String> =
    actorNameParts(value)
        .map { actor ->
            actor
                .replace(Regex("\\s+"), "")
                .map { character -> ACTOR_NAME_CHAR_VARIANTS[character] ?: character }
                .joinToString("")
                .lowercase(Locale.ROOT)
        }
        .toSet()

internal fun String.primaryActorName(): String = actorNameParts(this).firstOrNull().orEmpty()

/*
 * ================================================================================
 * 步骤3：校验网页中的完整影片番号
 * ================================================================================
 * 目标：网页搜索结果只接受相同厂牌和相同序号，避免短厂牌误命中更长厂牌。
 * 数据源：JavLibrary、JavDB 等搜索页的链接文本和详情页识别码。
 * 操作：
 * 1) 解析输入的厂牌前缀与序号。
 * 2) 用字母数字边界约束前缀和序号，允许连字符、下划线或无分隔符。
 */
internal fun containsExactCatalogNumber(value: String, number: String): Boolean {
    val match = Regex("""(?i)^([a-z]{2,12})[-_\s]?(\d{2,6})$""").matchEntire(number.trim())
        ?: return false
    val prefix = Regex.escape(match.groupValues[1])
    val serial = Regex.escape(match.groupValues[2])
    return Regex(
        """(?<![A-Z0-9])$prefix[-_\s]?$serial(?![A-Z0-9])""",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(value)
}

/**
 * ==============================================================================
 * 步骤2：合并影片库已有演员
 * ==============================================================================
 * 目标：DMM/FANZA 的演员列表不完整时，继续处理 NFO 已有的额外演员和别名。
 * 数据源：当前刮削资料与影片库中保存的演员显示名。
 * 操作：
 * 1) 同名或已有别名的演员合并到已有记录。
 * 2) 无法证明关联的演员作为新增演员保留，不按位置猜测。
 */
internal fun ScrapedMovieInfo.withSupplementalActors(supplementalActors: List<String>): ScrapedMovieInfo {
    if (supplementalActors.isEmpty()) return this
    val mergedActors = actors.toMutableList()
    val mergedAliases = actorAliases.mapValues { (_, aliases) -> aliases.toMutableList() }.toMutableMap()

    supplementalActors.forEach { rawActor ->
        val parts = actorNameParts(rawActor).filter { part ->
            !isNonActorCategoryName(part) &&
                excludedActorNames.none { excluded -> actorNamesHaveExactVariant(excluded, part) }
        }
        val primaryName = parts.firstOrNull().orEmpty()
        if (parts.isEmpty() || primaryName.isBlank()) return@forEach
        val exactMatches = mergedActors.filter { current ->
            val identityNames = actorNameParts(current) + mergedAliases[current].orEmpty().flatMap(::actorNameParts)
            parts.any { part -> identityNames.any { identity -> actorNamesHaveExactVariant(identity, part) } }
        }
        val matchedActor = exactMatches.singleOrNull()
        val targetActor = matchedActor ?: primaryName.also { mergedActors += it }
        val additionalAliases = parts.filterNot { part -> actorNamesHaveExactVariant(part, targetActor) }
        if (additionalAliases.isNotEmpty()) {
            mergedAliases[targetActor] = (mergedAliases[targetActor].orEmpty() + additionalAliases)
                .filter { alias -> alias.isNotBlank() && !actorNamesHaveExactVariant(alias, targetActor) }
                .distinctBy { alias -> actorNameVariants(alias).sorted().joinToString("|") }
                .toMutableList()
        }
    }

    val normalizedAliases = mergedAliases.filterValues { aliases -> aliases.isNotEmpty() }
    if (mergedActors == actors && normalizedAliases == actorAliases) return this
    return copy(actors = mergedActors, actorAliases = normalizedAliases)
}

/*
 * ================================================================================
 * 步骤4：统一显式演员身份并重映射头像
 * ================================================================================
 * 目标：在写 NFO、刷新 Room 前把分散 actor 块折叠成“主名 + alias”。
 * 数据源：当前资料源演员列表、显式 alias 映射和已下载头像索引。
 * 操作：
 * 1) 按精确姓名变体建立身份记录，保留第一条主名。
 * 2) 合并 alias 指向的记录，删除重复 actor。
 * 3) 把旧 actor/alias 键下的头像映射到新的主名。
 */
internal fun ScrapedMovieInfo.canonicalizeActorIdentities(): ScrapedMovieInfo {
    if (actors.isEmpty()) return this
    val records = mutableListOf<CanonicalActorRecord>()

    fun aliasesFor(rawActor: String): List<String> = actorAliases.entries
        .filter { (storedActor, _) -> actorNamesHaveExactVariant(storedActor, rawActor) }
        .flatMap { (_, aliases) -> aliases.flatMap(::actorNameParts) }

    actors.forEach { rawActor ->
        val parts = actorNameParts(rawActor).filterNot(::isNonActorCategoryName)
        val primaryName = parts.firstOrNull() ?: return@forEach
        if (excludedActorNames.any { excluded -> actorNamesHaveExactVariant(excluded, primaryName) }) return@forEach
        val names = (parts + aliasesFor(rawActor))
            .filter {
                it.isNotBlank() &&
                    !isNonActorCategoryName(it) &&
                    excludedActorNames.none { excluded -> actorNamesHaveExactVariant(excluded, it) }
            }
            .distinctBy { actorNameVariants(it).sorted().joinToString("|") }
        val matches = records.filter { record ->
            record.knownNames.any { known -> names.any { incoming -> actorNamesHaveExactVariant(known, incoming) } }
        }
        val target = matches.firstOrNull() ?: CanonicalActorRecord(primaryName).also { record ->
            records += record
        }
        matches.drop(1).forEach { duplicate ->
            target.merge(duplicate)
            records.remove(duplicate)
        }
        target.addNames(names)
    }

    actorAliases.forEach { (storedActor, aliases) ->
        val storedNames = actorNameParts(storedActor).filterNot(::isNonActorCategoryName)
        val target = records.firstOrNull { record ->
            record.knownNames.any { known -> storedNames.any { stored -> actorNamesHaveExactVariant(known, stored) } }
        } ?: return@forEach
        aliases.flatMap(::actorNameParts)
            .filterNot(::isNonActorCategoryName)
            .filterNot { alias -> excludedActorNames.any { excluded -> actorNamesHaveExactVariant(excluded, alias) } }
            .forEach { alias ->
                val duplicate = records.firstOrNull { record ->
                    record !== target && record.knownNames.any { known -> actorNamesHaveExactVariant(known, alias) }
                }
                if (duplicate != null) {
                    target.merge(duplicate)
                    records.remove(duplicate)
                } else {
                    target.addNames(listOf(alias))
                }
            }
    }

    val canonicalAliases = records.mapNotNull { record ->
        record.aliases()
            .takeIf { it.isNotEmpty() }
            ?.let { aliases -> record.name to aliases }
    }.toMap()
    val canonicalImageCandidates = records.mapNotNull { record ->
        val candidates = buildList {
            actorImageCandidates.forEach { (storedActor, imageUrls) ->
                if (record.knownNames.any { known -> actorNamesHaveExactVariant(storedActor, known) }) {
                    addAll(imageUrls)
                }
            }
            actorImageUrls.forEach { (storedActor, imageUrl) ->
                if (record.knownNames.any { known -> actorNamesHaveExactVariant(storedActor, known) }) {
                    add(imageUrl)
                }
            }
        }.let(::prioritizeActorImageUrls)
        candidates.takeIf { it.isNotEmpty() }?.let { imageUrls -> record.name to imageUrls }
    }.toMap()
    val canonicalImages = canonicalImageCandidates.mapValues { (_, candidates) -> candidates.first() }
    return copy(
        actors = records.map { it.name },
        actorAliases = canonicalAliases,
        actorImageUrls = canonicalImages,
        actorImageCandidates = canonicalImageCandidates
    )
}

private class CanonicalActorRecord(val name: String) {
    val knownNames = mutableListOf<String>()
    private val knownAliases = mutableListOf<String>()

    fun addNames(names: Collection<String>) {
        names.forEach { incoming ->
            if (incoming.isBlank() || knownNames.any { known -> actorNamesHaveExactVariant(known, incoming) }) return@forEach
            knownNames += incoming
            if (!actorNamesHaveExactVariant(name, incoming)) knownAliases += incoming
        }
    }

    fun merge(other: CanonicalActorRecord) {
        addNames(other.knownNames)
    }

    fun aliases(): List<String> = knownAliases
        .filterNot { alias -> actorNamesHaveExactVariant(alias, name) }
        .distinctBy { alias -> actorNameVariants(alias).sorted().joinToString("|") }
}
