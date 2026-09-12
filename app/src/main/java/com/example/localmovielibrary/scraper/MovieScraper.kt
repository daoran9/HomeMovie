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

interface ReviewActorFallback {
    suspend fun findReviewActorNames(number: String): List<String>
}

class MovieScraperRegistry(
    scrapers: List<MovieScraper>,
    private val logger: ((String) -> Unit)? = null,
    private val webViewBackedSources: Set<ScrapeSource> = emptySet(),
    private val webViewSourceTimeoutMs: Long = WEBVIEW_SOURCE_TIMEOUT_MS,
    private val dmm2PrioritySourceTimeoutMs: Long = DMM2_SOURCE_TIMEOUT_MS
) {
    private val scrapersBySource = scrapers.associateBy { it.source }

    suspend fun scrape(source: ScrapeSource, number: String): ScrapedMovieInfo {
        /*
         * ================================================================================
         * 步骤1：核验手动指定来源的演员证据
         * ================================================================================
         * 目标：让手动指定来源与自动刮削共用制作商作品假名边界。
         * 数据源：用户选定来源返回的影片资料。
         * 操作：
         * 1) DMM、DMM2、Official 作为官方演员署名输入。
         * 2) 其它来源作为外部身份输入。
         * 3) 身份未核时保留影片资料，只移除未确认演员及其头像。
         */
        logger?.invoke("手动来源演员证据核验开始：number=$number, source=${source.name}")

        // 1.1 获取用户指定来源的原始结果
        val scraper = scrapersBySource[source] ?: error("Unsupported scrape source: $source")
        val info = scraper.scrape(number)

        // 1.2 按来源职责传入官方署名或外部身份槽位
        val review = if (source in setOf(ScrapeSource.Dmm, ScrapeSource.Dmm2, ScrapeSource.Official)) {
            reviewMakerActorEvidence(listOf(info), emptyList())
        } else {
            reviewMakerActorEvidence(emptyList(), listOf(info))
        }

        // 1.3 未核演员被移除时仍保留标题、厂商及其它影片字段
        val actorEvidence = review.infos.singleOrNull() ?: info.copy(
            actors = emptyList(),
            actorAliases = emptyMap(),
            actorImageUrls = emptyMap(),
            actorImageCandidates = emptyMap(),
            actorCredits = emptyMap(),
            verifiedActorNames = emptyList()
        )
        val creditActors = actorEvidence.actorCredits.keys + review.credits.keys
        val result = actorEvidence.copy(
            actorCredits = creditActors.associateWith { actor ->
                (actorEvidence.actorCredits[actor].orEmpty() + review.credits[actor].orEmpty()).distinct()
            },
            unverifiedActorNames = (actorEvidence.unverifiedActorNames + review.unverifiedNames).distinct()
        ).canonicalizeActorIdentities()

        logger?.invoke(
            "手动来源演员证据核验结束：number=$number, source=${source.name}, " +
                "actors=${result.actors.size}, pending=${result.unverifiedActorNames.size}"
        )
        return result
    }

    /*
     * ================================================================================
     * 步骤2：按 DMM/FANZA 优先规则自动刮削
     * ================================================================================
     * 目标：把自动刮削拆成“官方命中”和“外部后备”两条互斥分支。
     * 数据源：DMM/FANZA、旧 DMM、JavLibrary、JavBus、JavDB 和当前番号。
     * 操作：
     * 1) 依次查询 DMM/FANZA 和旧 DMM；任一命中都视为官方命中。
     * 2) 官方命中后仍收集外部演员证据，避免官方只登记部分演员。
     * 3) 官方标题只有演员名时，用 JavBus/JavLibrary 的完整标题修正。
     * 4) JavDB 只补演员证据，不能覆盖或补齐影片资料。
     */
    suspend fun scrapeWithDmmPriority(
        number: String,
        excludedSources: Set<ScrapeSource> = emptySet(),
        sourceTimeoutMs: Long = DEFAULT_SOURCE_TIMEOUT_MS
    ): ScrapedMovieInfo {
        logger?.invoke("开始 DMM/FANZA 优先自动刮削：number=$number")
        var lastError: Throwable? = null
        data class CollectedSource(val source: ScrapeSource, val info: ScrapedMovieInfo)
        val collected = mutableListOf<CollectedSource>()

        suspend fun collect(source: ScrapeSource): ScrapedMovieInfo? {
            if (source in excludedSources) {
                logger?.invoke("自动刮削跳过来源：number=$number, source=${source.name}")
                return null
            }
            val scraper = scrapersBySource[source] ?: return null
            val timeout = prioritySourceTimeoutFor(source, sourceTimeoutMs)
            return try {
                // 2.1 每个来源独立限时，失败后由当前分支决定是否继续。
                val info = withTimeout(timeout.coerceAtLeast(1_000L)) {
                    if (scraper is DmmScraper) {
                        scraper.scrape(number, collected.firstOrNull { it.source == ScrapeSource.Dmm2 }?.info,
                            allowDigital = ScrapeSource.Dmm2 !in excludedSources)
                    } else {
                        scraper.scrape(number)
                    }
                }
                collected += CollectedSource(source, info)
                logger?.invoke("自动刮削源成功：number=$number, source=${source.name}")
                info
            } catch (error: TimeoutCancellationException) {
                lastError = error
                logger?.invoke("自动刮削源超时：number=$number, source=${source.name}, timeout=${timeout}ms")
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                logger?.invoke(
                    "自动刮削源失败：number=$number, source=${source.name}, " +
                        "reason=${error.message ?: error::class.java.simpleName}"
                )
                null
            }
        }

        /*
         * ================================================================================
         * 步骤3：处理官方命中分支
         * ================================================================================
         * 目标：保留同商品官方影片资料，外部来源按字段补缺。
         * 数据源：DMM2 详情和旧 DMM 详情。
         * 操作：
         * 1) DMM2 成功时保持官方影片字段优先。
         * 2) 旧 DMM 命中也保持有效官方字段，不再整组降级。
         * 3) 两个 DMM 来源都参与演员、别名和头像证据融合。
        */
        val dmm2Info = collect(ScrapeSource.Dmm2)
        val dmmInfo = collect(ScrapeSource.Dmm)
        if (dmm2Info != null || dmmInfo != null) {
            // 3.1 DMM2 保持字段优先级，旧 DMM 只补全同一官方家族的缺失证据。
            var merged = mergeInfos(listOfNotNull(dmm2Info, dmmInfo))

            /*
             * ================================================================================
             * 步骤5：补齐官方演员和缺失资料证据
             * ================================================================================
             * 目标：保留官方影片资料，修正仅含演员名的标题，并补齐演员身份、头像和缺失简介。
             * 数据源：JavLibrary、JavBus 的影片字段，以及 JavDB 的演员字段。
             * 操作：
             * 1) 三个外部来源都要查询，不能用官方演员数量推断名单完整。
             * 2) 官方标题仅含演员名时，标题只接受 JavBus/JavLibrary 的完整结果。
             * 3) 简介和空日期、年份、时长只接受 JavBus/JavLibrary；已有 DMM2 值保持不变。
             * 4) JavDB 只参与演员、别名和演员头像融合。
             */
            logger?.invoke("官方演员和缺失资料补证开始：number=$number")
            listOf(ScrapeSource.Javlibrary, ScrapeSource.Javbus, ScrapeSource.Javdb)
                .filterNot { source -> collected.any { it.source == source } }
                .forEach { source -> collect(source) }

            val safeExternalEvidence = collected
                .filter { it.source in SAFE_METADATA_FALLBACK_SOURCES }
                .map { it.info }

            // Official values remain primary; JL/JB supply missing fields and additional classifications.
            val officialInfos = listOfNotNull(dmm2Info, dmmInfo)
            val officialPlot = merged.plot.ifBlank { merged.outline }
            val officialOutline = merged.outline.ifBlank { merged.plot }
            merged = mergeInfos(officialInfos + safeExternalEvidence).copy(
                plot = officialPlot,
                outline = officialOutline,
                // Cross-site score scales are not interchangeable; keep the existing official scale.
                rating = merged.rating
            )
            val javdbActorEvidence = collected
                .filter { it.source == ScrapeSource.Javdb }
                .map { it.info }
            val externalActorEvidence = safeExternalEvidence + javdbActorEvidence
            run {
                val review = reviewMakerActorEvidence(officialInfos, externalActorEvidence)
                val actorEvidence = if (review.infos.isEmpty()) ScrapedMovieInfo(number, "") else mergeInfos(review.infos)
                if (review.unverifiedNames.isNotEmpty()) {
                    logger?.invoke("制作商作品署名待核：number=$number, maker=${merged.studio}, names=${review.unverifiedNames.joinToString("/")}")
                }
                val narrativeEvidence = listOf(ScrapeSource.Javbus, ScrapeSource.Javlibrary)
                    .mapNotNull { source -> collected.firstOrNull { it.source == source }?.info }
                val versionMatchedExternal = safeExternalEvidence.filter { haveCompatibleMovieEditions(officialInfos.first(), it) }

                // 5.2 只把“标题等于演员名”视为官方标题不完整，避免覆盖正常官方标题。
                val officialTitleVariants = actorNameVariants(merged.title)
                val knownActorVariants = buildSet {
                    actorEvidence.actors.flatMapTo(this, ::actorNameVariants)
                    actorEvidence.actorAliases.values.flatten().flatMapTo(this, ::actorNameVariants)
                }
                val officialTitleIsActorOnly = officialTitleVariants.isNotEmpty() &&
                    officialTitleVariants.all(knownActorVariants::contains)

                // 5.3 替换标题时排除同样只有演员名的外部结果。
                val replacementTitle = if (officialTitleIsActorOnly) {
                    narrativeEvidence.firstNotNullOfOrNull { info ->
                        info.title.takeIf { title ->
                            val titleVariants = actorNameVariants(title)
                            title.isNotBlank() &&
                                (titleVariants.isEmpty() || titleVariants.any { it !in knownActorVariants })
                        }
                    }
                } else {
                    null
                }
                if (replacementTitle != null) {
                    logger?.invoke("官方标题仅为演员名，改用外部完整标题：number=$number")
                }
                val releaseDate = merged.premiered.ifBlank {
                    versionMatchedExternal.firstNotNullOfOrNull { it.premiered.takeIf(String::isNotBlank) }.orEmpty()
                }
                merged = merged.copy(
                    title = replacementTitle ?: merged.title,
                    originalTitle = replacementTitle ?: merged.originalTitle,
                    premiered = releaseDate,
                    year = Regex("""^\d{4}""").find(releaseDate)?.value ?: merged.year,
                    runtime = merged.runtime.ifBlank { versionMatchedExternal.firstNotNullOfOrNull { it.runtime.takeIf(String::isNotBlank) }.orEmpty() },
                    plot = merged.plot.ifBlank { narrativeEvidence.firstNotNullOfOrNull { it.plot.takeIf(String::isNotBlank) }.orEmpty() },
                    outline = merged.outline.ifBlank { narrativeEvidence.firstNotNullOfOrNull { it.outline.takeIf(String::isNotBlank) }.orEmpty() },
                    actors = actorEvidence.actors,
                    actorAliases = actorEvidence.actorAliases,
                    actorCredits = review.credits,
                    unverifiedActorNames = review.unverifiedNames,
                    verifiedActorNames = actorEvidence.verifiedActorNames,
                    excludedActorNames = actorEvidence.excludedActorNames,
                    actorImageUrls = actorEvidence.actorImageUrls,
                    actorImageCandidates = actorEvidence.actorImageCandidates
                ).canonicalizeActorIdentities()
            }
            merged = merged.withReviewActorsWhenStructuredSourcesMissing(
                number = number,
                structuredInfos = externalActorEvidence
            )
            logger?.invoke("官方演员和缺失资料补证结束：number=$number")

            logger?.invoke(
                "DMM/FANZA 命中，外部证据收集完成：number=$number, " +
                    "sources=${collected.joinToString { it.source.name }}"
            )
            return merged
        }

        /*
         * ================================================================================
         * 步骤4：处理外部后备分支
         * ================================================================================
         * 目标：DMM/FANZA 未命中时，让三个外部来源共同提供证据。
         * 数据源：JavLibrary、JavBus、JavDB。
         * 操作：
         * 1) 三个来源全部尝试，JL 作为结构化资料和演员主名基准。
         * 2) JB 优先提供简介；JavDB 只提供性别排除、别名和演员头像候选。
         * 3) 标题、详情、类型和影片图片只接受 JL、JB，避免 JavDB 水印素材进入影片库。
         */
        listOf(ScrapeSource.Javlibrary, ScrapeSource.Javbus, ScrapeSource.Javdb)
            .forEach { source -> collect(source) }

        val safeMetadataResults = collected
            .filter { it.source in SAFE_METADATA_FALLBACK_SOURCES }
            .map { it.info }
        val javdbActorEvidence = collected
            .filter { it.source == ScrapeSource.Javdb }
            .map { it.info }
        if (safeMetadataResults.isNotEmpty()) {
            fun firstNonBlank(infos: List<ScrapedMovieInfo>, selector: (ScrapedMovieInfo) -> String): String =
                infos.asSequence().map(selector).firstOrNull { it.isNotBlank() }.orEmpty()

            val narrativeResults = listOf(ScrapeSource.Javbus, ScrapeSource.Javlibrary)
                .mapNotNull { source ->
                    collected.firstOrNull { it.source == source }?.info
                }
            val javlibraryRating = collected
                .firstOrNull { it.source == ScrapeSource.Javlibrary }
                ?.info
                ?.rating
                .orEmpty()
            val safeMetadata = mergeInfos(safeMetadataResults)
            val review = reviewMakerActorEvidence(emptyList(), safeMetadataResults + javdbActorEvidence)
            val actorEvidence = mergeInfos(review.infos)
            var result = safeMetadata.copy(
                plot = firstNonBlank(narrativeResults) { it.plot },
                outline = firstNonBlank(narrativeResults) { it.outline },
                actors = actorEvidence.actors,
                actorAliases = actorEvidence.actorAliases,
                actorCredits = review.credits,
                verifiedActorNames = actorEvidence.verifiedActorNames,
                excludedActorNames = actorEvidence.excludedActorNames,
                actorImageUrls = actorEvidence.actorImageUrls,
                actorImageCandidates = actorEvidence.actorImageCandidates,
                genres = safeMetadata.genres,
                tags = safeMetadata.tags,
                rating = javlibraryRating
            ).canonicalizeActorIdentities()
            result = result.withReviewActorsWhenStructuredSourcesMissing(
                number = number,
                structuredInfos = safeMetadataResults + javdbActorEvidence
            )
            logger?.invoke(
                "DMM/FANZA 未命中，完成外部后备融合：number=$number, " +
                    "sources=${collected.joinToString { it.source.name }}"
            )
            return result
        }

        if (javdbActorEvidence.isNotEmpty()) {
            val actorEvidence = mergeInfos(javdbActorEvidence)
            logger?.invoke("DMM/FANZA 未命中，JavDB 仅保留演员证据：number=$number")
            val result = ScrapedMovieInfo(
                number = number,
                title = "",
                actors = actorEvidence.actors,
                actorAliases = actorEvidence.actorAliases,
                excludedActorNames = actorEvidence.excludedActorNames,
                actorImageUrls = actorEvidence.actorImageUrls,
                actorImageCandidates = actorEvidence.actorImageCandidates,
                source = JAVDB_ACTOR_EVIDENCE_SOURCE
            ).canonicalizeActorIdentities()
            return result.withReviewActorsWhenStructuredSourcesMissing(
                number = number,
                structuredInfos = javdbActorEvidence
            )
        }

        val detail = lastError?.message ?: lastError?.javaClass?.simpleName ?: "未知错误"
        throw IllegalStateException("DMM/FANZA 和外部后备源均失败：$number，最后错误：$detail", lastError)
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
            val timeout = if (source in webViewBackedSources) {
                webViewSourceTimeoutMs
            } else {
                sourceTimeoutMs
            }
            try {
                // 1.1 先执行当前候选源；超时后继续下一个来源。
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
                logger?.invoke("刮削源超时：number=$number, source=${source.name}, timeout=${timeout}ms")
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
        val selectedSources = linkedMapOf<String, String>()
        val conflictingFields = mutableListOf<String>()
        fun firstNonBlank(field: String, selector: (ScrapedMovieInfo) -> String, versioned: Boolean = false): String {
            val candidates = infos.filter { !versioned || haveCompatibleMovieEditions(primary, it) }
                .filter { selector(it).isNotBlank() }
            val selected = candidates.firstOrNull() ?: return ""
            selectedSources[field] = selected.source.ifBlank { "unknown" }
            if (candidates.map(selector).distinct().size > 1) conflictingFields += field
            return selector(selected)
        }

        val classifications = mergeMovieClassifications(infos)
        val directors = mergeDirectorCredits(infos)
        if (infos.flatMap { it.directors }.any { it.trim().isNotEmpty() && it.trim() !in directors }) {
            conflictingFields += "directors"
        }
        if (infos.any { !haveCompatibleMovieEditions(primary, it) }) {
            logger?.invoke("不同发行类型不互补日期、片长、评分、预告和图片：number=${primary.number}")
        }

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

        val result = primary.copy(
            number = primary.number,
            title = firstNonBlank("title", { it.title }),
            originalTitle = firstNonBlank("originalTitle", { it.originalTitle }),
            plot = firstNonBlank("plot", { it.plot }),
            outline = firstNonBlank("outline", { it.outline }),
            year = firstNonBlank("year", { it.year }, versioned = true),
            premiered = firstNonBlank("premiered", { it.premiered }, versioned = true),
            runtime = firstNonBlank("runtime", { it.runtime }, versioned = true),
            studio = firstNonBlank("studio", { it.studio }),
            publisher = firstNonBlank("publisher", { it.publisher }),
            series = firstNonBlank("series", { it.series }),
            directors = directors,
            actors = actors,
            actorAliases = actorAliases,
            verifiedActorNames = infos.flatMap { it.verifiedActorNames }
                .distinctBy { actorNameVariants(it).sorted().joinToString("|") },
            excludedActorNames = infos.flatMap { it.excludedActorNames }
                .filter { it.isNotBlank() }
                .distinctBy { actorNameVariants(it).sorted().joinToString("|") },
            actorImageUrls = actorImages,
            actorImageCandidates = actorImageCandidates,
            genres = classifications.genres,
            tags = classifications.tags,
            rating = firstNonBlank("rating", { it.rating }, versioned = true),
            trailer = firstNonBlank("trailer", { it.trailer }, versioned = true),
            website = primary.website,
            source = primary.source.ifBlank { infos.drop(1).firstOrNull()?.source.orEmpty() },
            thumbUrl = firstNonBlank("thumb", { it.thumbUrl }, versioned = true),
            posterUrl = firstNonBlank("poster", { it.posterUrl }, versioned = true)
        ).canonicalizeActorIdentities()
        logger?.invoke("候选字段选择：number=${primary.number}, selected=$selectedSources, differences=${conflictingFields.joinToString()}")
        return result
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

    /*
     * ================================================================================
     * 步骤6：在全部结构化来源无演员时读取短评姓名清单
     * ================================================================================
     * 目标：补回 DVMM 等详情页未登记女演员、但短评有明确姓名清单的影片。
     * 数据源：DMM/FANZA、JavLibrary、JavBus、JavDB 的结构化结果和 JavDB 短评兜底。
     * 操作：
     * 1) 任一结构化来源已有演员就立即返回，不访问评论。
     * 2) 只调用支持短评兜底的 JavDB 刮削器，并沿用 WebView 来源超时。
     * 3) 短评失败保持原结果，避免评论接口影响影片资料写入。
     */
    private suspend fun ScrapedMovieInfo.withReviewActorsWhenStructuredSourcesMissing(
        number: String,
        structuredInfos: List<ScrapedMovieInfo>
    ): ScrapedMovieInfo {
        if (actors.isNotEmpty() || unverifiedActorNames.isNotEmpty() || structuredInfos.any { info -> info.actors.isNotEmpty() }) return this
        val fallback = scrapersBySource[ScrapeSource.Javdb] as? ReviewActorFallback ?: return this
        logger?.invoke("全部结构化来源无演员，开始读取短评姓名清单：number=$number")
        val reviewActors = try {
            withTimeout(webViewSourceTimeoutMs.coerceAtLeast(1_000L)) {
                fallback.findReviewActorNames(number)
            }
        } catch (error: TimeoutCancellationException) {
            logger?.invoke("短评演员兜底超时：number=$number")
            emptyList()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger?.invoke("短评演员兜底失败：number=$number，${error.message ?: error::class.java.simpleName}")
            emptyList()
        }
        if (reviewActors.isEmpty()) return this
        logger?.invoke("短评演员兜底命中：number=$number，actors=${reviewActors.joinToString("/")}")
        return withSupplementalActors(reviewActors)
    }

    private fun isUsableActorImageUrl(url: String): Boolean {
        val value = url.trim()
        return value.isNotBlank() &&
            !value.equals("null", ignoreCase = true) &&
            !Regex("now[_-]?printing|no[_-]?(?:image|photo)|placeholder", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)
    }

    private fun prioritySourceTimeoutFor(source: ScrapeSource, fallbackTimeoutMs: Long): Long = when {
        source in webViewBackedSources -> webViewSourceTimeoutMs
        source == ScrapeSource.Dmm2 -> dmm2PrioritySourceTimeoutMs
        else -> fallbackTimeoutMs
    }

    private companion object {
        val SAFE_METADATA_FALLBACK_SOURCES = setOf(
            ScrapeSource.Javlibrary,
            ScrapeSource.Javbus
        )
        val DEFAULT_FALLBACK_ORDER = listOf(
            ScrapeSource.Dmm2,
            ScrapeSource.Javdb,
            ScrapeSource.Javlibrary,
            ScrapeSource.Javbus,
            ScrapeSource.Dmm,
            ScrapeSource.Official
        )
        const val DEFAULT_SOURCE_TIMEOUT_MS = 8_000L
        const val DMM2_SOURCE_TIMEOUT_MS = 20_000L
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
            } else if (actorNamesMatchJapaneseNickname(leftName, rightName)) {
                true
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

/*
 * ================================================================================
 * 步骤3：识别带「ちゃん」前缀的演员昵称
 * ================================================================================
 * 目标：在同一影片的多源演员证据中归并「るな / ちゃんるな」这类昵称。
 * 数据源：已标准化的两个演员姓名。
 * 操作：
 * 1) 只移除姓名开头的固定敬称「ちゃん」。
 * 2) 剩余姓名至少两个字符，避免把单字符短名误合并。
 */
private fun actorNamesMatchJapaneseNickname(left: String, right: String): Boolean {
    fun withoutNicknamePrefix(value: String): String? = value
        .removePrefix("ちゃん")
        .takeIf { candidate -> candidate != value && candidate.length >= 2 }

    return withoutNicknamePrefix(left) == right || withoutNicknamePrefix(right) == left
}

internal fun actorNamesHaveExactVariant(left: String, right: String): Boolean =
    actorNameVariants(left).intersect(actorNameVariants(right)).isNotEmpty()

internal fun isActorIdentityImageUrl(url: String): Boolean {
    val normalized = url.trim().lowercase(Locale.ROOT)
    return normalized.isNotBlank() &&
        normalized != "null" &&
        !Regex("now[_-]?printing|no[_-]?(?:image|photo)|placeholder").containsMatchIn(normalized) &&
        ("/avatars/" in normalized || "/actjpgs/" in normalized || "/pics/actress/" in normalized)
}

internal fun isOfficialDmmActorImageUrl(url: String): Boolean {
    val normalized = url.trim().lowercase(Locale.ROOT)
    return Regex("""^https?://(?:[a-z0-9-]+\.)*dmm\.co\.jp(?:/|$)""").containsMatchIn(normalized) &&
        "/actjpgs/" in normalized
}

internal fun prioritizeActorImageUrls(urls: Collection<String>): List<String> = urls.asSequence()
    .map(String::trim)
    .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    .filterNot { Regex("now[_-]?printing|no[_-]?(?:image|photo)|placeholder", RegexOption.IGNORE_CASE).containsMatchIn(it) }
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
    '阶' to '階',
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
    '真' to '眞',
    '结' to '結',
    '枫' to '楓'
)

private val ACTOR_FIELD_LABEL_PREFIX = Regex(
    """^(?:演员|演員|女优|女優|出演者|出演女优|出演女優|cast)\s*[:：]\s*""",
    RegexOption.IGNORE_CASE
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
                .replace(ACTOR_FIELD_LABEL_PREFIX, "")
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

/*
 * ================================================================================
 * 步骤4：过滤外部来源的元数据摘要
 * ================================================================================
 * 目标：不把 JavBus 页面用于 SEO 的日期/片长/番号摘要写成影片剧情。
 * 数据源：JavBus meta description 和当前番号。
 * 操作：
 * 1) 识别同时包含发行日期、长度和当前番号的模板化摘要。
 * 2) 命中时返回不可用，让官方或其它来源继续提供剧情。
 */
internal fun isGenericMovieDescription(value: String, number: String): Boolean {
    val text = value.trim()
    if (text.isBlank() || !containsExactCatalogNumber(text, number)) return false
    val hasRelease = text.contains("发行日期") || text.contains("發行日期")
    val hasRuntime = text.contains("长度") || text.contains("長度")
    return hasRelease && hasRuntime
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
            !isMovieScopedActorName(part) && !isNonActorCategoryName(part) &&
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
        val parts = actorNameParts(rawActor).filterNot(::isNonActorCategoryName).filterNot(::isMovieScopedActorName)
        val primaryName = parts.firstOrNull() ?: return@forEach
        if (excludedActorNames.any { excluded -> actorNamesHaveExactVariant(excluded, primaryName) }) return@forEach
        val names = (parts + aliasesFor(rawActor))
            .filter {
                it.isNotBlank() &&
                    !isMovieScopedActorName(it) && !isNonActorCategoryName(it) &&
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
            .filterNot(::isMovieScopedActorName)
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
    val canonicalCredits = records.mapNotNull { record ->
        val credits = actorCredits.filterKeys { stored ->
            record.knownNames.any { actorNamesHaveExactVariant(it, stored) }
        }.values.flatten().distinct()
        credits.takeIf { it.isNotEmpty() }?.let { record.name to it }
    }.toMap()
    return copy(
        actors = records.map { it.name },
        actorAliases = canonicalAliases,
        actorImageUrls = canonicalImages,
        actorImageCandidates = canonicalImageCandidates,
        actorCredits = canonicalCredits
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
