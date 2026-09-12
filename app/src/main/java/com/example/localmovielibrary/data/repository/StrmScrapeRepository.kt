package com.example.localmovielibrary.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.scraper.ActorAliasLookup
import com.example.localmovielibrary.scraper.ActorAvatarStore
import com.example.localmovielibrary.scraper.Dmm2Scraper
import com.example.localmovielibrary.scraper.DmmScraper
import com.example.localmovielibrary.scraper.GfriendsActorAvatarRepository
import com.example.localmovielibrary.scraper.JavdbScraper
import com.example.localmovielibrary.scraper.JavlibraryScraper
import com.example.localmovielibrary.scraper.JavlibraryWebViewFetcher
import com.example.localmovielibrary.scraper.JavbusScraper
import com.example.localmovielibrary.scraper.JAVDB_ACTOR_EVIDENCE_SOURCE
import com.example.localmovielibrary.scraper.MissavScraper
import com.example.localmovielibrary.scraper.MovieNumberExtractor
import com.example.localmovielibrary.scraper.MovieScraperRegistry
import com.example.localmovielibrary.scraper.NetworkProbe
import com.example.localmovielibrary.scraper.NfoWriter
import com.example.localmovielibrary.scraper.OfficialScraper
import com.example.localmovielibrary.scraper.ScrapeLogStore
import com.example.localmovielibrary.scraper.ScrapeRunResult
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.scraper.ScrapedMovieInfo
import com.example.localmovielibrary.scraper.SourceProbeResult
import com.example.localmovielibrary.scraper.actorNameParts
import com.example.localmovielibrary.scraper.actorNameVariants
import com.example.localmovielibrary.scraper.actorNamesHaveExactVariant
import com.example.localmovielibrary.scraper.actorNamesMatch
import com.example.localmovielibrary.scraper.dmmFanzaActorImageCandidates
import com.example.localmovielibrary.scraper.isNonActorCategoryName
import com.example.localmovielibrary.scraper.canonicalizeActorIdentities
import com.example.localmovielibrary.scraper.prioritizeActorImageUrls
import com.example.localmovielibrary.scraper.isMovieScopedActorName
import com.example.localmovielibrary.util.MovieVariant
import com.example.localmovielibrary.util.detectMovieVariant
import com.example.localmovielibrary.util.extractMovieNumberInfo
import com.example.localmovielibrary.util.movieMetadataBaseNames
import com.example.localmovielibrary.util.playbackSourceSuffixFromText
import com.example.localmovielibrary.scraper.primaryActorName
import com.example.localmovielibrary.scraper.withSupplementalActors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.net.URI
import java.util.Locale
import kotlin.system.measureTimeMillis

class StrmScrapeRepository(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logStore: ScrapeLogStore = ScrapeLogStore(context),
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val networkProbe: NetworkProbe = NetworkProbe(ioDispatcher = ioDispatcher),
    private val dmmScraper: DmmScraper = DmmScraper(
        client = httpClient,
        ioDispatcher = ioDispatcher,
        logger = logStore::append
    ),
    private val dmm2Scraper: Dmm2Scraper = Dmm2Scraper(client = httpClient, ioDispatcher = ioDispatcher, logger = logStore::append),
    private val officialScraper: OfficialScraper = OfficialScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val javbusScraper: JavbusScraper = JavbusScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val javlibraryWebViewFetcher: JavlibraryWebViewFetcher? = null,
    private val javdbScraper: JavdbScraper = JavdbScraper(
        client = httpClient,
        ioDispatcher = ioDispatcher,
        cookieProvider = settingsRepository::getJavdbCookies,
        logger = logStore::append,
        webViewFetcher = javlibraryWebViewFetcher
    ),
    private val javlibraryScraper: JavlibraryScraper = JavlibraryScraper(
        client = httpClient,
        ioDispatcher = ioDispatcher,
        cookieProvider = settingsRepository::getJavlibraryCookies,
        logger = logStore::append,
        webViewFetcher = javlibraryWebViewFetcher
    ),
    private val missavScraper: MissavScraper = MissavScraper(
        cookieProvider = settingsRepository::getMissavCookies,
        client = httpClient,
        ioDispatcher = ioDispatcher
    ),
    private val scraperRegistry: MovieScraperRegistry = MovieScraperRegistry(
        listOf(dmmScraper, dmm2Scraper, officialScraper, javbusScraper, javdbScraper, javlibraryScraper, missavScraper),
        logger = logStore::append,
        webViewBackedSources = if (javlibraryWebViewFetcher == null) {
            emptySet()
        } else {
            setOf(ScrapeSource.Javdb, ScrapeSource.Javlibrary)
        }
    ),
    private val imageDownloadService: ImageDownloadService = ImageDownloadService(
        httpClient = httpClient,
        retryCountProvider = settingsRepository::getImageDownloadRetryCount,
        logger = logStore::append,
        ioDispatcher = ioDispatcher
    ),
    private val refreshMovieMetadata: suspend (Long) -> Boolean = { false }
) {
    private val actorAvatarStore = ActorAvatarStore(context)
    private val gfriendsActorAvatarRepository = GfriendsActorAvatarRepository(
        context = context,
        client = httpClient,
        ioDispatcher = ioDispatcher,
        logger = logStore::append
    )
    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var actorAvatarJob: Job? = null
    private val scrapeAdmissionMutex = Mutex()
    private val missavScrapeMutex = Mutex()
    private val libraryDirectoryMutex = Mutex()
    private val _scrapeQueueState = MutableStateFlow(ScrapeQueueState())
    private val _actorAvatarUpdateState = MutableStateFlow(ActorAvatarUpdateState())
    val scrapeQueueState: StateFlow<ScrapeQueueState> = _scrapeQueueState
    val actorAvatarUpdateState: StateFlow<ActorAvatarUpdateState> = _actorAvatarUpdateState

    fun logDates(): List<String> = logStore.dates()

    fun readLogs(date: String = logDates().firstOrNull().orEmpty()): String = logStore.read(date)

    fun logUpdates(): StateFlow<Long> = logStore.updates

    fun clearLogs(date: String? = null) {
        if (date == null) logStore.clearAll() else logStore.clear(date)
    }

    fun appendLog(message: String) {
        logStore.append(message)
    }

    private suspend fun <T> runQueuedScrapeTask(
        label: String,
        serialMutex: Mutex? = null,
        block: suspend () -> T
    ): T = withContext(ioDispatcher) {
        _scrapeQueueState.update { state ->
            state.copy(waitingCount = state.waitingCount + 1)
        }
        if (serialMutex != null) {
            return@withContext serialMutex.withLock {
                runAdmittedScrapeTask(label, block)
            }
        }
        runAdmittedScrapeTask(label, block)
    }

    private suspend fun <T> runAdmittedScrapeTask(label: String, block: suspend () -> T): T {
        var admitted = false
        var waitingLogged = false
        try {
            while (!admitted) {
                val limit = settingsRepository.getScrapeConcurrencyLimit()
                scrapeAdmissionMutex.withLock {
                    val state = _scrapeQueueState.value
                    if (state.runningCount < limit) {
                        val nextRunningCount = state.runningCount + 1
                        _scrapeQueueState.value = state.copy(
                            isRunning = true,
                            runningLabel = label,
                            runningCount = nextRunningCount,
                            waitingCount = (state.waitingCount - 1).coerceAtLeast(0),
                            startedAtMillis = state.startedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
                        )
                        admitted = true
                    }
                }
                if (!admitted) {
                    if (!waitingLogged) {
                        logStore.append("刮削任务等待队列：$label，当前并发=${_scrapeQueueState.value.runningCount}/$limit")
                        waitingLogged = true
                    }
                    delay(SCRAPE_QUEUE_POLL_INTERVAL_MS)
                }
            }
            try {
                return block()
            } finally {
                scrapeAdmissionMutex.withLock {
                    _scrapeQueueState.update { state ->
                        val nextRunningCount = (state.runningCount - 1).coerceAtLeast(0)
                        state.copy(
                            isRunning = nextRunningCount > 0,
                            runningLabel = if (nextRunningCount > 0) state.runningLabel else null,
                            runningCount = nextRunningCount,
                            startedAtMillis = if (nextRunningCount > 0) state.startedAtMillis else 0L
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (!admitted) {
                _scrapeQueueState.update { state ->
                    state.copy(waitingCount = (state.waitingCount - 1).coerceAtLeast(0))
                }
            }
            throw error
        }
    }

    private fun appendMovieDivider(title: String, number: String, fileName: String, source: ScrapeSource? = null) {
        val sourceText = source?.let { ", source=${it.label}" }.orEmpty()
        logStore.append("----------------------------------------")
        logStore.append("$title: number=$number, file=$fileName$sourceText")
    }

    suspend fun findStrmUriByNumber(
        libraryRootUri: String,
        number: String,
        partLabel: String?,
        nameHint: String? = null
    ): String? = withContext(ioDispatcher) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(libraryRootUri)) ?: return@withContext null
        val hintToken = nameHint?.distinctPickcodeSuffix()?.removePrefix("_")
        val expectedVariant = nameHint?.let { detectMovieVariant(it) }
        fun walk(directory: DocumentFile): String? {
            directory.listFiles().forEach { child ->
                if (child.isDirectory && !child.isExcludedAssetDirectory()) {
                    walk(child)?.let { return it }
                    return@forEach
                }
                if (!child.isFile || !child.name.orEmpty().endsWith(".strm", ignoreCase = true)) return@forEach
                val name = child.name.orEmpty()
                if (!name.contains(number, ignoreCase = true)) return@forEach
                if (hintToken != null && !name.contains(hintToken, ignoreCase = true)) return@forEach
                if (expectedVariant != null && detectMovieVariant(name) != expectedVariant) return@forEach
                if (partLabel != null && !Regex("""(?i)[-_ ]${Regex.escape(partLabel)}(?:\.strm$|[^a-z0-9])""").containsMatchIn(name)) {
                    return@forEach
                }
                return child.uri.toString()
            }
            return null
        }
        return@withContext walk(root)
    }

    fun getDefaultScrapeSource(): ScrapeSource = settingsRepository.getDefaultScrapeSource()

    suspend fun canReachGoogle(): Boolean = withContext(ioDispatcher) {
        var reachable = false
        logStore.append("Start Google connectivity check, timeout 5s")
        val elapsedMs = measureTimeMillis {
            reachable = networkProbe.canReachGoogle()
        }
        logStore.append("Google connectivity ${if (reachable) "passed" else "failed"}, elapsed=${elapsedMs}ms")
        reachable
    }

    /*
     * ================================================================================
     * 步骤2：提供来源连通性测试
     * ================================================================================
     * 目标：设置页复用实际刮削使用的网络环境测试 DMM/FANZA 与 JavDB。
     * 数据源：共享 HTTP 客户端和 NetworkProbe 的来源响应分类。
     * 操作：
     * 1) 只允许测试 DMM2/DMM/JavDB 三个官方或独立来源。
     * 2) 不修改代理、不写入 Cookie，只返回当前网络结果。
     */
    suspend fun probeScrapeSource(source: ScrapeSource): SourceProbeResult = withContext(ioDispatcher) {
        networkProbe.probeSource(source)
    }

    suspend fun scrapeMovie(
        movie: MovieEntity,
        source: ScrapeSource,
        forceDistinct: Boolean = false,
        automaticPriority: Boolean = false
    ): ScrapedMovieInfo = scrapeMovieWithOutput(movie, source, forceDistinct, automaticPriority).info

    suspend fun scrapeMovieWithOutput(
        movie: MovieEntity,
        source: ScrapeSource,
        forceDistinct: Boolean = false,
        automaticPriority: Boolean = false
    ): ScrapedMovieWriteResult = runQueuedScrapeTask(
        label = "scrape:${movie.videoName}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val target = findTargetForMovie(movie)
        scrapeTargetWithOutput(target, source, forceDistinct, automaticPriority = automaticPriority)
    }

    suspend fun scrapeStrmUriWithOutput(
        sourceRootUri: String,
        strmUri: String,
        source: ScrapeSource,
        forceDistinct: Boolean = false,
        outputRootUri: String? = null,
        automaticPriority: Boolean = source != ScrapeSource.Missav
    ): ScrapedMovieWriteResult = runQueuedScrapeTask(
        label = "scrape-uri:${Uri.parse(strmUri).lastPathSegment.orEmpty()}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val sourceRoot = DocumentFile.fromTreeUri(context, Uri.parse(sourceRootUri))
            ?: error("STRM 源目录不可用")
        val outputRoot = outputRootUri?.let { uriString ->
            DocumentFile.fromTreeUri(context, Uri.parse(uriString))
                ?: error("影片库目录不可用")
        }
        val target = findTargetFast(sourceRoot, sourceRootUri, strmUri)
            ?: error("当前 STRM 文件不存在")
        scrapeTargetWithOutput(target, source, forceDistinct, outputRoot, automaticPriority)
    }

    private suspend fun scrapeTargetWithOutput(
        target: StrmTarget,
        source: ScrapeSource,
        forceDistinct: Boolean,
        outputRoot: DocumentFile? = null,
        automaticPriority: Boolean = false
    ): ScrapedMovieWriteResult {
        val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
            ?: error("无法从文件名提取番号：${target.file.name}")

        val excludedSources = excludedSourcesFor(number)
        if (excludedSources.isNotEmpty()) {
            logStore.append("DMM2 skipped for $number; continue fallback sources")
        }
        logStore.append("Start scrape: file=${target.file.name}, number=$number, source=${source.label}")
        appendMovieDivider("Start movie scrape", number, target.file.name.orEmpty(), source)
        logStore.append(
            if (automaticPriority) {
                "Use DMM/FANZA priority scrape chain: $number"
            } else {
                "Use single-source scrape: ${source.label}, number=$number"
            }
        )
        val scrapedInfo = if (automaticPriority) {
            scraperRegistry.scrapeWithDmmPriority(
                number = number,
                excludedSources = excludedSources
            )
        } else {
            scraperRegistry.scrape(source, number)
        }
        val info = scrapedInfo.withResolvedActorAliases(
            downloadActorAvatars(
                scrapedInfo,
                allowExternalActorSources = !automaticPriority || scrapedInfo.source !in setOf("dmm2", "dmm"),
                reuseMergedActorIdentities = true
            )
        )
        logStore.append("Metadata fetched: ${info.title.ifBlank { number }}")
        val strmUri = writeOrganizedScrapeFiles(target, info, number, forceDistinct, outputRoot)
        logStore.append("Movie scrape finished: $number")
        return ScrapedMovieWriteResult(info = info, strmUri = strmUri)
    }

    suspend fun scrapeMovieWithMissavHtml(movie: MovieEntity, html: String, cookie: String): ScrapedMovieInfo =
        scrapeMovieWithMissavHtmlOutput(movie, html, cookie).info

    suspend fun scrapeMovieWithMissavHtmlOutput(movie: MovieEntity, html: String, cookie: String): ScrapedMovieWriteResult =
        runQueuedScrapeTask(
            label = "missav-webview-scrape:${movie.videoName}",
            serialMutex = missavScrapeMutex
        ) {
            if (cookie.isNotBlank()) {
                settingsRepository.saveMissavCookies(cookie)
                logStore.append("MissAV WebView cookie saved")
            }
            val target = findTargetForMovie(movie)
            val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
                ?: error("无法从文件名提取番号：${target.file.name}")

            logStore.append("Parse MissAV WebView HTML: $number")
            appendMovieDivider("Start MissAV WebView scrape", number, target.file.name.orEmpty(), ScrapeSource.Missav)
            val scrapedInfo = missavScraper.scrapeFromHtml(number, html)
            val info = scrapedInfo.withResolvedActorAliases(downloadActorAvatars(scrapedInfo))
            logStore.append("MissAV WebView metadata parsed: ${info.title.ifBlank { number }}")
            val strmUri = writeOrganizedScrapeFiles(target, info, number)
            logStore.append("MissAV WebView scrape finished: $number")
            ScrapedMovieWriteResult(info = info, strmUri = strmUri)
        }

    /*
     * ================================================================================
     * 步骤7：用 MissAV WebView 页面刮削临时 STRM
     * ================================================================================
     * 目标：在网盘批量入库时，不先把临时 STRM 错当成影片库影片。
     * 数据源：STRM 临时目录、WebView HTML、影片库输出目录。
     * 操作：
     * 1) 从临时目录定位 STRM，不写入临时影片库记录。
     * 2) 解析 WebView HTML 并把整理结果写入影片库目录。
     * 3) 返回影片库中的最终 STRM URI，交给调用方扫描入库。
     */
    suspend fun scrapeStrmUriWithMissavHtmlOutput(
        sourceRootUri: String,
        strmUri: String,
        html: String,
        cookie: String,
        outputRootUri: String
    ): ScrapedMovieWriteResult = runQueuedScrapeTask(
        label = "missav-webview-uri:${Uri.parse(strmUri).lastPathSegment.orEmpty()}",
        serialMutex = missavScrapeMutex
    ) {
        if (cookie.isNotBlank()) {
            settingsRepository.saveMissavCookies(cookie)
            logStore.append("MissAV WebView cookie saved")
        }
        val sourceRoot = DocumentFile.fromTreeUri(context, Uri.parse(sourceRootUri))
            ?: error("STRM 源目录不可用")
        val outputRoot = DocumentFile.fromTreeUri(context, Uri.parse(outputRootUri))
            ?: error("影片库目录不可用")
        val target = findTargetFast(sourceRoot, sourceRootUri, strmUri)
            ?: error("当前 STRM 文件不存在")
        val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
            ?: error("无法从文件名提取番号：${target.file.name}")
        logStore.append("Parse MissAV WebView HTML: $number")
        appendMovieDivider("Start MissAV WebView URI scrape", number, target.file.name.orEmpty(), ScrapeSource.Missav)
        val scrapedInfo = missavScraper.scrapeFromHtml(number, html)
        val info = scrapedInfo.withResolvedActorAliases(
            downloadActorAvatars(scrapedInfo, reuseMergedActorIdentities = true)
        )
        logStore.append("MissAV WebView metadata parsed: ${info.title.ifBlank { number }}")
        val finalStrmUri = writeOrganizedScrapeFiles(target, info, number, outputRoot = outputRoot)
        logStore.append("MissAV WebView URI scrape finished: $number")
        ScrapedMovieWriteResult(info = info, strmUri = finalStrmUri)
    }

    suspend fun rescrapeMovie(
        movie: MovieEntity,
        source: ScrapeSource,
        automaticPriority: Boolean = false
    ): ScrapedMovieInfo = runQueuedScrapeTask(
        label = "rescrape:${movie.videoName}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val target = findTargetForMovie(movie)
        val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
            ?: MovieNumberExtractor.extract(movie.title)
            ?: error("无法从文件名提取番号：${target.file.name}")

        val excludedSources = excludedSourcesFor(number)
        if (excludedSources.isNotEmpty()) {
            logStore.append("DMM2 skipped for $number; continue fallback sources")
        }
        logStore.append("Start rescrape: file=${target.file.name}, number=$number, source=${source.label}")
        appendMovieDivider("Start movie rescrape", number, target.file.name.orEmpty(), source)
        logStore.append(
            if (automaticPriority) {
                "Use DMM/FANZA priority scrape chain for rescrape: $number"
            } else {
                "Use single-source scrape for rescrape: ${source.label}, number=$number"
            }
        )
        val scrapedInfo = if (automaticPriority) {
            scraperRegistry.scrapeWithDmmPriority(
                number = number,
                excludedSources = excludedSources
            )
        } else {
            scraperRegistry.scrape(source, number)
        }
        val info = scrapedInfo.withResolvedActorAliases(
            downloadActorAvatars(
                scrapedInfo,
                allowExternalActorSources = automaticPriority.not() || scrapedInfo.source !in setOf("dmm2", "dmm"),
            )
        )
        logStore.append("Rescrape metadata fetched: ${info.title.ifBlank { number }}")
        rewriteScrapeFilesInPlace(target, info)
        logStore.append("Movie rescrape finished: $number")
        info
    }

    suspend fun rescrapeMovieWithMissavHtml(movie: MovieEntity, html: String, cookie: String): ScrapedMovieInfo =
        runQueuedScrapeTask(
            label = "missav-webview-rescrape:${movie.videoName}",
            serialMutex = missavScrapeMutex
        ) {
            if (cookie.isNotBlank()) {
                settingsRepository.saveMissavCookies(cookie)
                logStore.append("MissAV WebView cookie saved")
            }
            val target = findTargetForMovie(movie)
            val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
                ?: MovieNumberExtractor.extract(movie.title)
                ?: error("无法从文件名提取番号：${target.file.name}")

            logStore.append("Parse MissAV WebView HTML for rescrape: $number")
            appendMovieDivider("Start MissAV WebView rescrape", number, target.file.name.orEmpty(), ScrapeSource.Missav)
            val scrapedInfo = missavScraper.scrapeFromHtml(number, html)
            val info = scrapedInfo.withResolvedActorAliases(downloadActorAvatars(scrapedInfo))
            logStore.append("MissAV WebView rescrape parsed: ${info.title.ifBlank { number }}")
            rewriteScrapeFilesInPlace(target, info)
            logStore.append("MissAV WebView rescrape finished: $number")
            info
        }

    suspend fun scrapeUnscrapedStrm(source: ScrapeSource): ScrapeRunResult = runQueuedScrapeTask(
        label = "batch:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        logStore.append("Start batch scrape: ${source.label}")
        if (!canReachGoogle()) {
            error("Google 连通性测试失败")
        }

        val rootUri = settingsRepository.getLibraryRootUri()
            ?: error("请先在设置中选择影片库目录")
        val root = DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
            ?: error("影片库目录不可用")
        if (!root.canWrite()) {
            logStore.append("Warning: library root may not be writable; NFO/images may fail")
        }

        val targets = mutableListOf<StrmTarget>()
        collectTargets(root, targets)
        logStore.append("Found unscraped STRM files: ${targets.size}")

        var success = 0
        var skipped = 0
        var failed = 0
        targets.forEach { target ->
            val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
            if (number == null) {
                skipped += 1
                logStore.append("Skipped: cannot extract number from ${target.file.name}")
                return@forEach
            }
            val excludedSources = excludedSourcesFor(number)
            if (excludedSources.isNotEmpty()) {
                logStore.append("DMM2 skipped for $number; continue fallback sources")
            }

            runCatching {
                logStore.append("Scraping $number, file=${target.file.name}")
                appendMovieDivider("Start batch movie scrape", number, target.file.name.orEmpty(), source)
                logStore.append(
                    if (source == ScrapeSource.Missav) {
                        "Use single-source scrape for batch: ${source.label}, number=$number"
                    } else {
                        "Use DMM/FANZA priority scrape chain for batch: $number"
                    }
                )
                val scrapedInfo = if (source == ScrapeSource.Missav) {
                    scraperRegistry.scrape(source, number)
                } else {
                    scraperRegistry.scrapeWithDmmPriority(
                        number = number,
                        excludedSources = excludedSources
                    )
                }
                val info = scrapedInfo.withResolvedActorAliases(
                    downloadActorAvatars(
                        scrapedInfo,
                        allowExternalActorSources = scrapedInfo.source !in setOf("dmm2", "dmm"),
                        reuseMergedActorIdentities = true
                    )
                )
                logStore.append("Metadata fetched: $number")
                writeOrganizedScrapeFiles(target, info, number)
                success += 1
                logStore.append("Success: $number -> ${info.title}")
            }.onFailure { error ->
                failed += 1
                logStore.append("Failed: $number, ${error.message ?: error::class.java.simpleName}")
            }
        }
        val result = ScrapeRunResult(targets.size, success, skipped, failed)
        logStore.append("Batch scrape finished: success=$success, failed=$failed, skipped=$skipped")
        result
    }

    private fun dmm2SkipMessage(source: ScrapeSource, number: String): String? {
        if (source != ScrapeSource.Dmm2) return null
        val prefix = number.substringBefore('-', missingDelimiterValue = number).uppercase()
        if (prefix.isBlank()) return null
        return if (prefix in settingsRepository.getDmm2SkippedNumberPrefixes()) {
            "DMM2不支持${prefix}番号刮削"
        } else {
            null
        }
    }

    private fun excludedSourcesFor(number: String): Set<ScrapeSource> =
        if (dmm2SkipMessage(ScrapeSource.Dmm2, number) != null) {
            setOf(ScrapeSource.Dmm2)
        } else {
            emptySet()
        }

    suspend fun clearScrapeFiles(movie: MovieEntity): String = withContext(ioDispatcher) {
        val target = findTargetForMovie(movie)
        val number = MovieNumberExtractor.extract(target.file.name.orEmpty())
            ?: MovieNumberExtractor.extract(movie.title)
            ?: error("无法安全提取影片番号")

        logStore.append("Start clearing scrape files: $number")
        val restoredFileName = "$number.strm"
        val directoryName = target.directory.name.orEmpty()
        val isOrganizedFolder = directoryName.contains(number, ignoreCase = true) &&
            (directoryName.startsWith("\u3010") || directoryName.startsWith("[")) &&
            target.parentDirectory != null

        if (isOrganizedFolder) {
            val parent = target.parentDirectory ?: error("无法定位父目录")
            copyStrmFile(target.file, parent, restoredFileName)
            logStore.append("STRM restored to parent: $restoredFileName")
            deleteRecursively(target.directory)
            logStore.append("Deleted generated movie directory: ${target.directory.name}")
        } else {
            val metadataBaseNames = movieMetadataBaseNames(target.file.name.orEmpty())
            metadataBaseNames.forEach { baseName ->
                deleteMetadataFiles(target.directory, baseName)
            }
            logStore.append("Deleted metadata files for baseNames=${metadataBaseNames.joinToString()}")
        }

        logStore.append("Clear scrape files finished: $number")
        number
    }

    fun startUpdateMissingActorAvatars(
        movies: List<MovieEntity>,
        forceRefresh: Boolean = false,
        allowGfriends: Boolean = settingsRepository.isGfriendsActorAvatarEnabled()
    ) {
        if (actorAvatarJob?.isActive == true) {
            logStore.append("Actor avatar update is already running")
            return
        }
        actorAvatarJob = backgroundScope.launch {
            _actorAvatarUpdateState.value = ActorAvatarUpdateState(
                isUpdating = true,
                message = if (forceRefresh) "正在全库重匹配演员头像（不使用 gfriends）..." else "正在补齐缺失演员头像..."
            )
            runCatching { updateMissingActorAvatarsInternal(movies, forceRefresh, allowGfriends) }
                .onSuccess { result ->
                    _actorAvatarUpdateState.value = ActorAvatarUpdateState(
                        isUpdating = false,
                        message = if (result.forceRefresh) {
                            "演员头像重匹配完成：${result.totalActors} 人，处理 ${result.scrapedMovies} 部影片"
                        } else if (result.totalMissing == 0) {
                            "演员头像已是最新"
                        } else {
                            "演员头像已补齐：${result.downloaded}/${result.totalMissing}"
                        },
                        refreshVersion = _actorAvatarUpdateState.value.refreshVersion + 1
                    )
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logStore.append("Actor avatar background update failed: ${error.message ?: error::class.java.simpleName}")
                    _actorAvatarUpdateState.value = ActorAvatarUpdateState(
                        isUpdating = false,
                        message = error.message ?: "演员头像更新失败",
                        refreshVersion = _actorAvatarUpdateState.value.refreshVersion
                    )
                }
        }
    }

    private suspend fun updateMissingActorAvatarsInternal(
        movies: List<MovieEntity>,
        forceRefresh: Boolean,
        allowGfriends: Boolean
    ): ActorAvatarUpdateResult {
        val allActors = movies
            .flatMap { it.actors }
            .map { it.trim() }
            .filter { actor -> actor.isNotBlank() && !isNonActorCategoryName(actor.primaryActorName()) }
            .distinctBy { it.normalizedActorName() }
        val missingActors = if (forceRefresh) allActors else allActors.filterNot { actorAvatarStore.hasAvatar(it) }
        val aliasSyncActors = allActors.filter { actorAvatarStore.hasAvatar(it) }

        if (missingActors.isEmpty() && aliasSyncActors.isEmpty()) {
            logStore.append("Actor avatar update: no missing avatars or aliases")
            return ActorAvatarUpdateResult(
                totalMissing = 0,
                downloaded = 0,
                scrapedMovies = 0,
                totalActors = allActors.size,
                forceRefresh = forceRefresh
            )
        }

        logStore.append("Start actor avatar update, missing=${missingActors.size}, aliasSync=${aliasSyncActors.size}")
        val pending = missingActors.toMutableSet()
        val aliasPending = aliasSyncActors.toMutableSet()
        val visitedNumbers = mutableSetOf<String>()
        var scrapedMovies = 0

        for (movie in movies) {
            if (!forceRefresh && pending.isEmpty() && aliasPending.isEmpty()) break
            val missingForMovie = movie.actors.any { actor -> pending.any { it.sameActorExactly(actor) } }
            val aliasesForMovie = aliasPending.filter { actor -> movie.actors.any { it.sameActorExactly(actor) } }
            if (!forceRefresh && !missingForMovie && aliasesForMovie.isEmpty()) continue
            val number = MovieNumberExtractor.extract(movie.videoName)
                ?: MovieNumberExtractor.extract(movie.title)
                ?: MovieNumberExtractor.extract(movie.originalTitle.orEmpty())
                ?: continue
            if (!visitedNumbers.add(number.uppercase())) {
                continue
            }

            runCatching {
                logStore.append("Query metadata sources for actor avatars: $number")
                /*
                 * ================================================================================
                 * 步骤1：按影片刮削规则查询演员资料
                 * ================================================================================
                 * 目标：全库头像任务不能绕过 DMM/FANZA 严格番号优先规则。
                 * 数据源：DMM/FANZA；仅当官方未命中时再查询 JavLibrary、JavBus、JavDB。
                 * 操作：
                 * 1) 复用影片刮削的官方命中与外部后备分支。
                 * 2) 官方命中时只更新官方确认的演员和头像，不回写外部演员或别名。
                 */
                val info = scraperRegistry.scrapeWithDmmPriority(
                    number = number,
                    excludedSources = excludedSourcesFor(number)
                )
                scrapedMovies += 1
                val libraryActorInfo = info
                    .withLibraryActors(movie.actors)
                val avatarInfo = if (info.source in setOf("dmm2", "dmm")) {
                    libraryActorInfo
                } else {
                    libraryActorInfo.withExternalActorsWhenMissing(number)
                }
                val resolvedInfo = avatarInfo.withResolvedActorAliases(downloadActorAvatars(
                    avatarInfo,
                    forceRefresh = forceRefresh,
                    allowGfriends = allowGfriends,
                    allowExternalActorSources = info.source !in setOf("dmm2", "dmm"),
                    reuseMergedActorIdentities = true
                ))
                updateActorAliasesInNfo(movie, resolvedInfo)
                val resolved = pending.filter { actorAvatarStore.hasAvatar(it) }
                pending.removeAll(resolved.toSet())
                aliasPending.removeAll(aliasesForMovie.toSet())
                if (resolved.isNotEmpty()) {
                    logStore.append("Actor avatars resolved: ${resolved.joinToString(", ")}")
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                logStore.append("Metadata sources actor avatar query failed: $number, ${error.message ?: error::class.java.simpleName}")
            }
        }

        /*
         * ================================================================================
         * 步骤2：最后使用 Fusion 共用头像库兜底
         * ================================================================================
         * 目标：只有 DMM/FANZA、JavDB 别名和资料源都没有结果时才访问 gfriends。
         * 数据源：前面各影片回查后仍未解决的演员集合。
         * 操作：
         * 1) 禁止再次发起 DMM/JavDB 请求。
         * 2) 仅查询 gfriends 并写入剩余演员头像。
         */
        val unresolved = pending.filterNot { actorAvatarStore.hasAvatar(it) }
        if (unresolved.isNotEmpty() && allowGfriends) {
            downloadActorAvatars(
                ScrapedMovieInfo(number = "", title = "", actors = unresolved),
                allowDmmName = false,
                allowJavdbAliases = false,
                allowJavlibraryAliases = false,
                allowSourceImages = false,
                allowGfriends = true
            )
        } else if (unresolved.isNotEmpty()) {
            logStore.append("Actor avatars unresolved; gfriends disabled: ${unresolved.size}")
        }

        val downloaded = missingActors.count { actorAvatarStore.hasAvatar(it) }
        logStore.append("Actor avatar update finished: downloaded=$downloaded/${missingActors.size}, scrapedMovies=$scrapedMovies")
        return ActorAvatarUpdateResult(
            totalMissing = missingActors.size,
            downloaded = downloaded,
            scrapedMovies = scrapedMovies,
            totalActors = allActors.size,
            forceRefresh = forceRefresh
        )
    }

    private fun findTargetForMovie(movie: MovieEntity): StrmTarget {
        if (!movie.videoName.endsWith(".strm", ignoreCase = true)) {
            error("当前影片不是 STRM 文件")
        }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(movie.libraryRootUri))
            ?: error("影片库目录不可用")
        return findTargetFast(root, movie.libraryRootUri, movie.videoUri)
            ?: findTarget(root, movie.videoUri)
            ?: findMovedTargetForMovie(root, movie)?.also { target ->
                logStore.append("当前记录 STRM 已移动，已定位到整理后的文件：${target.file.name}")
            }
            ?: error("当前 STRM 文件不存在")
    }

    /**
     * ================================================================================
     * 步骤1：写回全库任务发现的演员别名
     * ================================================================================
     * 目标：让头像更新任务发现的 JavDB/JavLibrary 别名进入影片库和演员索引。
     * 数据源：已有 NFO、当前影片和本次融合出的演员别名。
     * 操作：
     * 1) 仅替换已有 actor/name，保留其它 NFO 标签。
     * 2) 写入成功后刷新该影片的 Room 记录。
     */
    private suspend fun updateActorAliasesInNfo(movie: MovieEntity, info: ScrapedMovieInfo) {
        if (movie.nfoUri.isNullOrBlank()) return
        val nfoFile = DocumentFile.fromSingleUri(context, Uri.parse(movie.nfoUri))
            ?.takeIf { it.isFile }
            ?: return
        val existingNfo = context.contentResolver.openInputStream(nfoFile.uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { reader -> reader.readText() }
            ?: return
        val updatedNfo = NfoWriter.mergeActorDisplayNames(existingNfo, info)
        if (updatedNfo == existingNfo) return

        context.contentResolver.openOutputStream(nfoFile.uri, "wt")?.use { output ->
            output.write(updatedNfo.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入演员别名 NFO：${nfoFile.name}")
        logStore.append("Actor aliases written to NFO: ${movie.videoName}")
        val refreshed = refreshMovieMetadata(movie.id)
        logStore.append(
            if (refreshed) "Actor aliases refreshed in library: ${movie.videoName}"
            else "Actor aliases wrote NFO but library refresh skipped: ${movie.videoName}"
        )
    }

    private fun findMovedTargetForMovie(root: DocumentFile, movie: MovieEntity): StrmTarget? {
        val sourceName = movie.videoName.orEmpty()
        val number = MovieNumberExtractor.extract(sourceName)
            ?: MovieNumberExtractor.extract(movie.title)
            ?: MovieNumberExtractor.extract(movie.originalTitle.orEmpty())
            ?: return null
        val hintToken = sourceName.distinctPickcodeSuffix()?.removePrefix("_")?.takeIf { it.isNotBlank() }
        val expectedVariant = detectMovieVariant(sourceName)
        val partLabel = extractMovieNumberInfo(sourceName)?.partLabel

        fun walk(directory: DocumentFile, parentDirectory: DocumentFile?): StrmTarget? {
            directory.listFiles().forEach { child ->
                if (child.isDirectory && !child.isExcludedAssetDirectory()) {
                    walk(child, directory)?.let { return it }
                    return@forEach
                }
                if (!child.isFile || !child.name.orEmpty().endsWith(".strm", ignoreCase = true)) return@forEach
                val name = child.name.orEmpty()
                if (!name.contains(number, ignoreCase = true)) return@forEach
                if (hintToken != null && !name.contains(hintToken, ignoreCase = true)) return@forEach
                if (detectMovieVariant(name) != expectedVariant) return@forEach
                if (partLabel != null && !Regex("""(?i)[-_ ]${Regex.escape(partLabel)}(?:\.strm$|[^a-z0-9])""").containsMatchIn(name)) {
                    return@forEach
                }
                val baseName = name.substringBeforeLast('.', name)
                return StrmTarget(directory, child, baseName, parentDirectory)
            }
            return null
        }

        return walk(root, null)
    }

    private suspend fun writeOrganizedScrapeFiles(
        target: StrmTarget,
        info: ScrapedMovieInfo,
        fallbackNumber: String,
        forceDistinct: Boolean = false,
        outputRoot: DocumentFile? = null
    ): String {
        val sourceName = target.file.name.orEmpty()
        val baseNumber = info.number.ifBlank { fallbackNumber }.uppercase()
        val writeInfo = info.copy(number = baseNumber)
        val distinctSuffix = if (forceDistinct) target.file.name.orEmpty().distinctPickcodeSuffix() else null
        val baseName = buildMovieBaseName(writeInfo, baseNumber) + distinctSuffix.orEmpty()
        val destinationParent = outputRoot ?: target.directory
        val reuseSourceDirectory = outputRoot == null || outputRoot.uri == target.directory.uri
        /*
         * ================================================================================
         * 步骤1：并发安全地准备影片目录
         * ================================================================================
         * 目标：允许刮削网络请求并发，同时避免 SAF 下重复创建演员或影片目录。
         * 数据源：刮削结果、影片库根目录和当前 STRM 所在目录。
         * 操作：
         * 1) 只锁目录查找/创建这一小段本地操作。
         * 2) 网络刮削、图片下载和文件写入不受该锁阻塞。
         */
        val movieDirectory = libraryDirectoryMutex.withLock {
            if (reuseSourceDirectory && target.directory.name == baseName) {
                target.directory
            } else {
                val actorDirectory = createOrReuseActorDirectory(destinationParent, writeInfo)
                createOrReuseMovieDirectory(actorDirectory, baseName)
            }
        }
        logStore.append("影片目录准备完成：${movieDirectory.name}")

        logStore.append("Movie directory: ${movieDirectory.name}")

        val strmName = "$baseName${playbackSourceSuffixFromText(sourceName)}.strm"
        val newStrm = copyStrmFile(target.file, movieDirectory, strmName)
        logStore.append("STRM written: $strmName")

        try {
            val nfoName = "$baseName.nfo"
            logStore.append("Write NFO: $nfoName")
            writeTextFile(movieDirectory, nfoName, NfoWriter.build(writeInfo))
            logStore.append("NFO written: $nfoName")

            val poster = info.posterUrl.ifBlank { info.thumbUrl }
            val posterName = "$baseName-poster.jpg"
            if (poster.isNotBlank()) {
                logStore.append("Download poster: $poster")
                tryDownloadImageToFile(movieDirectory, posterName, poster, info.imageRefererFor(poster), "Poster")
            } else {
                logStore.append("Poster URL is blank; skipped")
            }

            if (info.thumbUrl.isNotBlank()) {
                val thumbName = "$baseName-thumb.jpg"
                logStore.append("Download thumb: ${info.thumbUrl}")
                val thumbWritten = tryDownloadImageToFile(movieDirectory, thumbName, info.thumbUrl, info.imageRefererFor(info.thumbUrl), "Thumb")

                val fanartName = "$baseName-fanart.jpg"
                logStore.append("Copy thumb as fanart: $fanartName")
                tryDownloadImageToFile(movieDirectory, fanartName, info.thumbUrl, info.imageRefererFor(info.thumbUrl), "Fanart")
                if (thumbWritten && shouldBuildPortraitPosterFromWideCover(poster, info.thumbUrl)) {
                    writePortraitPosterFromWideCover(movieDirectory, posterName, thumbName)
                }
            } else {
                logStore.append("Thumb URL is blank; skipped")
            }
            deleteLegacyNfoXml(target)
            deleteOldStrmIfMoved(target, newStrm)
            return newStrm.uri.toString()
        } catch (error: Throwable) {
            rollbackCopiedStrm(target, newStrm)
            throw error
        }
    }

    private suspend fun rewriteScrapeFilesInPlace(target: StrmTarget, info: ScrapedMovieInfo) {
        val baseName = movieMetadataBaseNames(target.file.name.orEmpty()).first()
        val directory = target.directory
        val nfoName = "$baseName.nfo"
        val replacesJavdbMetadata = directory.findFile(nfoName)
            ?.let(::isJavdbMetadataNfo)
            ?: false
        val movieNumber = extractMovieNumberInfo(info.number)?.number
            ?: info.number.trim().uppercase()
        val writeInfo = info.copy(number = movieNumber)
        logStore.append("Rewrite NFO: $nfoName")
        writeTextFile(directory, nfoName, NfoWriter.build(writeInfo))
        logStore.append("NFO rewritten: $nfoName")

        /*
         * ================================================================================
         * 步骤7：替换重新刮削后的影片图片
         * ================================================================================
         * 目标：重新刮削纠正番号或资料源后，NFO 与封面、缩略图、背景图保持同一部影片。
         * 数据源：本次融合后的 posterUrl、thumbUrl 和当前影片目录。
         * 操作：
         * 1) 仅在新图片地址存在时覆盖同名图片，避免空字段删除原图。
         * 2) 替换旧 JavDB 资料时，未被安全来源覆盖的图片必须删除，避免水印残留。
         * 3) 其它网络失败仍保留原图片。
         */
        logStore.append("开始刷新重新刮削图片：$baseName")
        val posterName = "$baseName-poster.jpg"
        val thumbName = "$baseName-thumb.jpg"
        val fanartName = "$baseName-fanart.jpg"
        val poster = info.posterUrl.ifBlank { info.thumbUrl }
        if (poster.isNotBlank()) {
            logStore.append("Refresh poster: $poster")
            tryDownloadImageToFile(directory, posterName, poster, info.imageRefererFor(poster), "Poster")
        } else if (replacesJavdbMetadata || info.source == JAVDB_ACTOR_EVIDENCE_SOURCE) {
            deleteScrapeImage(directory, posterName, "JavDB poster")
        } else {
            logStore.append("Poster URL is blank; keeping existing poster")
        }
        if (info.thumbUrl.isNotBlank()) {
            logStore.append("Refresh thumb: ${info.thumbUrl}")
            val thumbWritten = tryDownloadImageToFile(directory, thumbName, info.thumbUrl, info.imageRefererFor(info.thumbUrl), "Thumb")
            tryDownloadImageToFile(directory, fanartName, info.thumbUrl, info.imageRefererFor(info.thumbUrl), "Fanart")
            if (thumbWritten && shouldBuildPortraitPosterFromWideCover(poster, info.thumbUrl)) {
                writePortraitPosterFromWideCover(directory, posterName, thumbName)
            }
        } else if (replacesJavdbMetadata || info.source == JAVDB_ACTOR_EVIDENCE_SOURCE) {
            deleteScrapeImage(directory, thumbName, "JavDB thumb")
            deleteScrapeImage(directory, fanartName, "JavDB fanart")
        } else {
            logStore.append("Thumb URL is blank; keeping existing thumb and fanart")
        }
        logStore.append("重新刮削图片刷新完成：$baseName")
        deleteLegacyNfoXml(target)
    }

    private suspend fun downloadActorAvatars(
        info: ScrapedMovieInfo,
        allowDmmName: Boolean = true,
        allowJavdbAliases: Boolean = true,
        allowJavlibraryAliases: Boolean = true,
        allowSourceImages: Boolean = true,
        allowGfriends: Boolean = settingsRepository.isGfriendsActorAvatarEnabled(),
        forceRefresh: Boolean = false,
        reuseMergedActorIdentities: Boolean = false,
        allowExternalActorSources: Boolean = true
    ): Map<String, List<String>> {
        /*
         * ================================================================================
         * 步骤8：补齐演员头像
         * ================================================================================
         * 目标：打通 DMM/FANZA 官方头像、当前资料源和 Fusion 的 gfriends 头像库。
         * 数据源：当前影片演员列表、资料源头像、DMM2 GraphQL 和 gfriends Filetree.json。
         * 操作：
         * 1) 已存在本地头像的演员不重复下载。
         * 2) 默认先查当前演员名，再查当前资料明确给出的别名。
         * 3) 未命中时按 JavDB、JavBus 的候选头像依次下载。
         * 4) 最后查询 Fusion 的 gfriends 头像库。
         */
        val actorNames = info.actors
            .map { it.trim() }
            .filter { actor -> actor.isNotBlank() && !isNonActorCategoryName(actor.primaryActorName()) }
            .distinctBy { it.normalizedActorName() }
        if (actorNames.isEmpty()) return emptyMap()

        logStore.append("开始补齐演员头像：${actorNames.size} 人")
        var downloaded = 0
        var changed = false
        var javdbActorsLoaded = false
        var javdbActors = emptyList<ActorAliasLookup>()
        var javlibraryActorsLoaded = false
        var javlibraryActors = emptyList<ActorAliasLookup>()
        val resolvedActorAliases = mutableMapOf<String, List<String>>()
        val allowKnownAliasDmmLookup = allowJavdbAliases || allowJavlibraryAliases
        if (reuseMergedActorIdentities) {
            logStore.append("Reuse merged actor identities for avatar aliases: ${info.number}")
        }

        fun ScrapedMovieInfo.toActorAliasLookups(): List<ActorAliasLookup> = actors.mapNotNull { rawActor ->
            val name = rawActor.trim()
            if (name.isBlank()) return@mapNotNull null
            val aliases = actorAliases
                .filterKeys { storedActor -> storedActor.sameActorExactly(name) }
                .values
                .flatten()
                .flatMap(::actorNameParts)
                .filter { alias -> alias.isNotBlank() && !alias.sameActorExactly(name) }
                .distinctBy { alias -> alias.normalizedActorName() }
            ActorAliasLookup(name = name, aliases = aliases)
        }

        fun ActorAliasLookup.allNames(): List<String> = (listOf(name) + aliases)
            .flatMap(::actorNameParts)
            .filter { candidate -> candidate.isNotBlank() && !isNonActorCategoryName(candidate) }
            .distinctBy { candidate -> candidate.normalizedActorName() }

        suspend fun loadJavdbActors(): List<ActorAliasLookup> {
            if (javdbActorsLoaded) return javdbActors
            javdbActorsLoaded = true
            if (!allowExternalActorSources || !allowJavdbAliases || info.number.isBlank()) return emptyList()
            if (reuseMergedActorIdentities || info.source.equals("javdb", ignoreCase = true)) {
                javdbActors = info.toActorAliasLookups()
                return javdbActors
            }
            javdbActors = runCatching {
                withTimeout(WEBVIEW_ALIAS_TIMEOUT_MS) {
                    javdbScraper.findActors(info.number)
                }
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                logStore.append("JavDB 演员别名回查失败：${info.number}，${error.message ?: error::class.java.simpleName}")
            }.getOrDefault(emptyList())
            return javdbActors
        }

        suspend fun loadJavlibraryActors(): List<ActorAliasLookup> {
            if (javlibraryActorsLoaded) return javlibraryActors
            javlibraryActorsLoaded = true
            if (!allowExternalActorSources || !allowJavlibraryAliases || info.number.isBlank()) return emptyList()
            if (reuseMergedActorIdentities || info.source.equals("javlibrary", ignoreCase = true)) {
                javlibraryActors = info.toActorAliasLookups()
                return javlibraryActors
            }
            javlibraryActors = runCatching {
                withTimeout(WEBVIEW_ALIAS_TIMEOUT_MS) {
                    javlibraryScraper.findActors(info.number)
                }
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                logStore.append("JavLibrary 演员别名回查失败：${info.number}，${error.message ?: error::class.java.simpleName}")
            }.getOrDefault(emptyList())
            return javlibraryActors
        }

        suspend fun loadExternalActorNamesFor(
            actorName: String,
            persistedAliases: Collection<String>
        ): List<String> {
            val knownNames = (actorNameParts(actorName) + persistedAliases.flatMap(::actorNameParts))
                .filter { candidate -> candidate.isNotBlank() && !isNonActorCategoryName(candidate) }
                .distinctBy { candidate -> candidate.normalizedActorName() }
            if (!allowExternalActorSources) return emptyList()
            return listOf(loadJavdbActors(), loadJavlibraryActors())
                .flatMap { sourceActors ->
                    val matchedActors = sourceActors.filter { externalActor ->
                        externalActor.allNames().any { externalName ->
                            knownNames.any { knownName -> knownName.sameActorExactly(externalName) }
                        }
                    }
                    matchedActors.flatMap { externalActor -> externalActor.allNames() }
                }
                .filter { candidate -> candidate.isNotBlank() }
                .distinctBy { candidate -> candidate.normalizedActorName() }
        }

        fun actorImageCandidates(sourceInfo: ScrapedMovieInfo, actorName: String): List<String> {
            val identityNames = actorNameParts(actorName) + sourceInfo.actorAliases
                .filterKeys { storedActor -> storedActor.sameActorExactly(actorName) }
                .values
                .flatten()
                .flatMap(::actorNameParts)
            val candidates = buildList {
                sourceInfo.actorImageCandidates.forEach { (name, urls) ->
                    if (identityNames.any { identity -> name.sameActorExactly(identity) }) {
                        addAll(urls)
                    }
                }
                sourceInfo.actorImageUrls.forEach { (name, url) ->
                    if (identityNames.any { identity -> name.sameActorExactly(identity) }) {
                        add(url)
                    }
                }
            }
            return prioritizeActorImageUrls(candidates)
                .flatMap(::dmmFanzaActorImageCandidates)
                .distinct()
        }

        actorNames.forEach { actorName ->
            val existingAvatar = actorAvatarStore.hasAvatar(actorName)
            var saved = existingAvatar && !forceRefresh
            var aliasesCopied = 0
            var resolvedAliases: List<String>? = null

            suspend fun loadActorAliases(): List<String> {
                resolvedAliases?.let { return it }
                val primaryName = actorName.primaryActorName()
                val persistedAliases = info.actorAliases
                    .filterKeys { savedActor -> savedActor.sameActorExactly(actorName) }
                    .values
                    .flatten()
                resolvedAliases = (actorNameParts(actorName).drop(1) + persistedAliases +
                    loadExternalActorNamesFor(actorName, persistedAliases))
                    .flatMap(::actorNameParts)
                    .filter { alias ->
                        alias.isNotBlank() &&
                            !info.isMovieScopedActorName(alias) &&
                            !isNonActorCategoryName(alias) &&
                            !alias.sameActorExactly(primaryName)
                    }
                    .distinctBy { alias -> alias.normalizedActorName() }
                return resolvedAliases.orEmpty()
            }

            /*
             * ================================================================================
             * 步骤8.1：保留旧头像直到新候选成功
             * ================================================================================
             * 目标：强制刷新尝试新候选，但网络失败不提前清掉已有头像。
             * 数据源：当前演员名、资料源返回的别名和 ActorAvatarStore 本地缓存。
             * 操作：
             * 1) 强制刷新时不把本地缓存当本次下载成功。
             * 2) 新图片解码成功后由 saveAvatar 替换旧版本。
             */
            if (forceRefresh) {
                saved = false
            }

            fun copyExistingAvatarToAliases(aliasNames: Collection<String>, sourceLabel: String) {
                if (!saved || !actorAvatarStore.hasAvatar(actorName) || aliasNames.isEmpty()) return
                val copied = actorAvatarStore.copyAvatarToNames(
                    sourceActorName = actorName,
                    aliasNames = aliasNames,
                    overwriteExisting = forceRefresh
                )
                if (copied > 0) {
                    aliasesCopied += copied
                    changed = true
                    logStore.append("Actor avatar aliases saved: $actorName, source=$sourceLabel, count=$copied")
                }
            }

            if (existingAvatar) {
                logStore.append(
                    if (forceRefresh) "Actor avatar exists; re-matching: $actorName"
                    else "Actor avatar exists; checking aliases: $actorName"
                )
            }

            suspend fun tryDownload(
                imageUrl: String,
                sourceLabel: String,
                referer: String? = info.imageRefererFor(imageUrl),
                aliasNames: Collection<String> = emptyList()
            ) {
                runCatching {
                    logStore.append("Download actor avatar: $actorName, source=$sourceLabel")
                    val bytes = imageDownloadService.downloadImageBytes(imageUrl, referer)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                    ) ?: error("演员头像无法解码：$sourceLabel")
                    bitmap.recycle()
                    val namesToSave = (listOf(actorName) + aliasNames)
                        .flatMap { name -> actorNameVariants(name) }
                        .distinct()
                    namesToSave.forEach { name -> actorAvatarStore.saveAvatar(name, bytes) }
                    saved = true
                    changed = true
                    downloaded += 1
                    logStore.append("Actor avatar downloaded: $actorName, source=$sourceLabel")
                }.onFailure { error ->
                    logStore.append("Actor avatar download failed: $actorName, ${error.message ?: error::class.java.simpleName}")
                }
            }

            suspend fun downloadDmmAvatar(lookupName: String, sourceLabel: String) {
                runCatching {
                    withTimeout(DMM_FANZA_AVATAR_TIMEOUT_MS) {
                        dmm2Scraper.findActorImageByName(lookupName)
                    }
                }.onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    logStore.append("DMM/FANZA 演员名回查失败：$lookupName，${error.message ?: error::class.java.simpleName}")
                }.getOrNull()?.let { official ->
                    copyExistingAvatarToAliases(listOf(lookupName, official.name), sourceLabel)
                    dmmFanzaActorImageCandidates(official.imageUrl).forEach { url ->
                        if (!saved) {
                            tryDownload(
                                url,
                                sourceLabel,
                                referer = null,
                                aliasNames = listOf(lookupName, official.name)
                            )
                        }
                    }
                }
            }

            /*
             * ================================================================================
             * 步骤8.2：按已确认身份顺序回查 DMM/FANZA 头像
             * ================================================================================
             * 目标：优先使用当前演员名在官方库中的头像。
             * 数据源：当前资料明确给出的 actorAliases 与 DMM/FANZA 演员姓名查询。
             * 操作：
             * 1) 先查主名，再用当前资料明确给出的别名补齐。
             * 2) 所有查询均未命中时，才继续资料源图片和 gfriends 兜底。
             */
            val aliases = if (allowKnownAliasDmmLookup) {
                val aliases = loadActorAliases()
                if (aliases.isNotEmpty()) {
                    resolvedActorAliases[actorName] = aliases
                }
                aliases
            } else {
                emptyList()
            }

            if (allowDmmName && !saved) {
                downloadDmmAvatar(actorName, "DMM/FANZA:$actorName")
            }
            aliases.forEach { alias ->
                if (!saved) {
                    downloadDmmAvatar(alias, "DMM/FANZA 别名:$alias")
                }
            }

            /*
             * ================================================================================
             * 步骤8.3：按资料源候选补齐头像
             * ================================================================================
             * 目标：官方头像不可用时，保留 JavDB、JavBus 的已绑定图片作为后备。
             * 数据源：多源融合后的 actorImageCandidates。
             * 操作：
             * 1) 候选已按 DMM/FANZA、JavDB、JavBus 顺序排列。
             * 2) 图片 Referer 由图片域名生成，避免混合资料源时被 CDN 拒绝。
             * 3) 仅当前两轮官方回查未命中时下载第一个可用候选。
             */
            if (!saved && allowSourceImages) {
                actorImageCandidates(info, actorName).forEach { url ->
                    if (!saved) {
                        tryDownload(url, "metadata")
                    }
                }
            }

            // 8.4 所有网络资料源都不可用时，最后查询 Fusion 的 gfriends 头像库。
            if (!saved && allowGfriends) {
                val gfriendsUrl = runCatching { gfriendsActorAvatarRepository.findAvatar(actorName) }
                    .onFailure { error ->
                        logStore.append("gfriends 演员头像查询失败：$actorName，${error.message ?: error::class.java.simpleName}")
                    }
                    .getOrNull()
                gfriendsUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    tryDownload(url, "gfriends", referer = null)
                }
            }
            if (allowKnownAliasDmmLookup && saved) {
                copyExistingAvatarToAliases(
                    loadActorAliases(),
                    "JavDB/JavLibrary"
                )
            }
            if (!saved && aliasesCopied == 0) {
                logStore.append("Actor avatar unavailable: $actorName")
            }
        }
        if (changed) {
            _actorAvatarUpdateState.update { state ->
                state.copy(refreshVersion = state.refreshVersion + 1)
            }
        }
        logStore.append("演员头像补齐完成：$downloaded/${actorNames.size}")
        return resolvedActorAliases
    }

    private fun ScrapedMovieInfo.withResolvedActorAliases(
        resolvedAliases: Map<String, List<String>>
    ): ScrapedMovieInfo {
        val mergedAliases = actorAliases.toMutableMap()
        resolvedAliases.forEach { (actor, aliases) ->
            val primaryName = actor.primaryActorName()
            mergedAliases[actor] = (mergedAliases[actor].orEmpty() + aliases)
                .flatMap(::actorNameParts)
                .filter { alias ->
                    alias.isNotBlank() &&
                        !isMovieScopedActorName(alias) &&
                        !isNonActorCategoryName(alias) &&
                    !alias.sameActorExactly(primaryName) &&
                    excludedActorNames.none { excluded -> alias.sameActorExactly(excluded) }
                }
                .distinctBy { alias -> alias.normalizedActorName() }
        }
        return copy(actorAliases = mergedAliases.filterValues { it.isNotEmpty() })
            .canonicalizeActorIdentities()
    }

    private fun ScrapedMovieInfo.withLibraryActors(libraryActors: List<String>): ScrapedMovieInfo {
        if (libraryActors.isEmpty()) return this
        if (actors.isEmpty() && unverifiedActorNames.isNotEmpty()) return this

        /*
         * ================================================================================
         * 步骤9：限制影片库旧演员只做已确认的查询提示
         * ================================================================================
         * 目标：避免旧 Room/NFO 演员污染本轮新资料，尤其是同番号曾误命中的演员。
         * 数据源：本轮资料演员、显式别名和影片库旧演员显示名。
         * 操作：
         * 1) 本轮已有演员时，只保留能精确对应当前身份的旧姓名片段。
         * 2) 本轮没有演员时，才允许旧演员作为头像查询种子。
         * 3) 不把未确认的旧姓名写回当前 NFO。
         */
        val currentIdentityNames = if (actors.isEmpty()) {
            emptyList()
        } else {
            (actors.flatMap(::actorNameParts) +
                actorAliases.keys.flatMap(::actorNameParts) +
                actorAliases.values.flatten().flatMap(::actorNameParts))
                .filterNot(::isNonActorCategoryName)
                .distinctBy { it.normalizedActorName() }
        }
        val lookupActors = if (actors.isEmpty()) {
            libraryActors
        } else {
            libraryActors.flatMap { rawActor ->
                actorNameParts(rawActor).filter { part ->
                    currentIdentityNames.any { current -> actorNamesHaveExactVariant(current, part) }
                }
            }
        }
        if (lookupActors.isEmpty()) {
            logStore.append("Skip unconfirmed library actors for avatar lookup: $number")
            return this
        }

        val mergedInfo = withSupplementalActors(lookupActors)
        if (mergedInfo.actors == actors && mergedInfo.actorAliases == actorAliases) return this
        logStore.append(
            "Merge library actors for avatar lookup: $number, " +
                "metadata=${actors.size}, merged=${mergedInfo.actors.size}"
        )
        return mergedInfo
    }

    private suspend fun ScrapedMovieInfo.withExternalActorsWhenMissing(number: String): ScrapedMovieInfo {
        if (actors.isNotEmpty()) return this
        if (unverifiedActorNames.isNotEmpty()) return this
        logStore.append("Metadata and library actors empty; query JavLibrary/JavDB: $number")
        val external = runCatching {
            scraperRegistry.scrapeWithFallback(
                preferred = ScrapeSource.Javlibrary,
                number = number,
                fallbackOrder = listOf(ScrapeSource.Javdb, ScrapeSource.Javbus),
                collectAllSources = true
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            logStore.append("External actor lookup failed: $number, ${error.message ?: error::class.java.simpleName}")
        }.getOrNull() ?: return this
        if (external.actors.isEmpty()) {
            logStore.append("External actor lookup returned no actors: $number")
            return this
        }
        logStore.append("External actors found: $number, count=${external.actors.size}")
        return copy(
            actors = external.actors,
            actorAliases = external.actorAliases,
            excludedActorNames = external.excludedActorNames,
            actorImageUrls = external.actorImageUrls,
            website = website.ifBlank { external.website },
            source = source.ifBlank { external.source }
        )
    }

    private fun buildMovieBaseName(info: ScrapedMovieInfo, fallbackNumber: String): String {
        val actor = info.actors.firstOrNull { it.isNotBlank() }
            ?.sanitizeFileName()
            ?.ifBlank { null }
            ?: "\u672A\u77E5\u6F14\u5458"
        val number = info.number.ifBlank { fallbackNumber }
            .uppercase()
            .sanitizeFileName()
        return "\u3010$actor\u3011$number"
    }

    private fun createOrReuseActorDirectory(parent: DocumentFile, info: ScrapedMovieInfo): DocumentFile {
        val desiredName = actorGroupFolderName(info)
        if (parent.name == desiredName) return parent
        parent.findFile(desiredName)?.let { existing ->
            if (existing.isDirectory) return existing
        }
        logStore.append("Create actor directory: $desiredName")
        return parent.createDirectory(desiredName)
            ?: error("无法创建演员目录：$desiredName")
    }

    private fun actorGroupFolderName(info: ScrapedMovieInfo): String {
        val actors = info.actors
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        return when (actors.size) {
            0 -> "\u672A\u77E5\u6F14\u5458"
            1 -> actors.first().sanitizeFileName().ifBlank { "\u672A\u77E5\u6F14\u5458" }
            else -> "\u591A\u4EBA\u4F5C\u54C1"
        }
    }

    private fun createOrReuseMovieDirectory(parent: DocumentFile, desiredName: String): DocumentFile {
        parent.findFile(desiredName)?.let { existing ->
            if (existing.isDirectory) return existing
        }
        var candidate = desiredName
        var index = 1
        while (parent.findFile(candidate) != null) {
            candidate = "$desiredName-$index"
            index += 1
        }
        logStore.append("Create movie directory: $candidate")
        return parent.createDirectory(candidate) ?: error("无法创建影片目录：$candidate")
    }

    private fun copyStrmFile(source: DocumentFile, directory: DocumentFile, fileName: String): DocumentFile {
        val content = context.contentResolver.openInputStream(source.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("无法读取源 STRM 文件：${source.name}")
        return writeTextFile(directory, fileName, content)
    }

    private fun deleteOldStrmIfMoved(target: StrmTarget, newStrm: DocumentFile) {
        if (target.file.uri == newStrm.uri) return
        if (target.file.delete()) {
            logStore.append("Old STRM deleted: ${target.file.name}")
        } else {
            logStore.append("Warning: failed to delete old STRM: ${target.file.name}")
        }
    }

    private fun rollbackCopiedStrm(target: StrmTarget, newStrm: DocumentFile) {
        if (target.file.uri == newStrm.uri) return
        if (newStrm.delete()) {
            logStore.append("Rollback copied STRM after scrape file write failure: ${newStrm.name}")
        } else {
            logStore.append("Warning: failed to rollback copied STRM: ${newStrm.name}")
        }
    }

    private fun deleteLegacyNfoXml(target: StrmTarget) {
        val legacyName = "${target.baseName}.nfo.xml"
        target.directory.findFile(legacyName)?.let { legacy ->
            if (legacy.delete()) {
                logStore.append("Deleted legacy NFO file: $legacyName")
            }
        }
    }

    private fun collectTargets(directory: DocumentFile, out: MutableList<StrmTarget>) {
        val children = directory.listFiles().toList()
        val names = children.mapNotNull { it.name?.lowercase() }.toSet()
        children.filter { it.isFile && it.name.orEmpty().endsWith(".strm", ignoreCase = true) }.forEach { strm ->
            val baseName = strm.name.orEmpty().substringBeforeLast('.', strm.name.orEmpty())
            val hasMetadata = movieMetadataBaseNames(strm.name.orEmpty())
                .any { metadataBaseName -> "${metadataBaseName.lowercase()}.nfo" in names }
            if (!hasMetadata) {
                out += StrmTarget(directory, strm, baseName, parentDirectory = null)
            }
        }
        children.filter { it.isDirectory && !it.isExcludedAssetDirectory() }.forEach { collectTargets(it, out) }
    }

    private fun findTarget(directory: DocumentFile, videoUri: String, parentDirectory: DocumentFile? = null): StrmTarget? {
        directory.listFiles().forEach { child ->
            if (child.isFile && child.uri.toString() == videoUri) {
                val baseName = child.name.orEmpty().substringBeforeLast('.', child.name.orEmpty())
                return StrmTarget(directory, child, baseName, parentDirectory)
            }
            if (child.isDirectory && !child.isExcludedAssetDirectory()) {
                findTarget(child, videoUri, directory)?.let { return it }
            }
        }
        return null
    }

    private fun findTargetFast(root: DocumentFile, rootUriString: String, videoUriString: String): StrmTarget? {
        val rootDocId = Uri.parse(rootUriString).treeDocumentId() ?: return null
        val videoDocId = Uri.parse(videoUriString).documentId() ?: return null
        if (!videoDocId.startsWith(rootDocId)) return null
        val relativePath = videoDocId
            .removePrefix(rootDocId)
            .removePrefix("/")
            .takeIf { it.isNotBlank() }
            ?: return null
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        val parentSegments = segments.dropLast(1)
        val fileName = segments.last()
        var parentDirectory: DocumentFile? = null
        val directory = parentSegments.fold(root as DocumentFile?) { current, segment ->
            parentDirectory = current
            current?.findFile(segment)?.takeIf { it.isDirectory }
        } ?: root.takeIf { parentSegments.isEmpty() } ?: return null
        val file = directory.findFile(fileName)?.takeIf { it.isFile } ?: return null
        val baseName = file.name.orEmpty().substringBeforeLast('.', file.name.orEmpty())
        return StrmTarget(
            directory = directory,
            file = file,
            baseName = baseName,
            parentDirectory = parentDirectory?.takeIf { directory.uri != root.uri }
        )
    }

    private fun Uri.treeDocumentId(): String? {
        val index = pathSegments.indexOf("tree")
        return index.takeIf { it >= 0 && it + 1 < pathSegments.size }
            ?.let { Uri.decode(pathSegments[it + 1]) }
    }

    private fun Uri.documentId(): String? {
        val index = pathSegments.indexOf("document")
        return index.takeIf { it >= 0 && it + 1 < pathSegments.size }
            ?.let { Uri.decode(pathSegments[it + 1]) }
    }

    private fun writeTextFile(directory: DocumentFile, fileName: String, content: String): DocumentFile {
        directory.findFile(fileName)?.delete()
        val file = directory.createFile(GENERIC_FILE_MIME_TYPE, fileName)
            ?: error("无法创建文件：$fileName")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入文件：$fileName")
        return file
    }

    private suspend fun downloadImageToFile(directory: DocumentFile, fileName: String, url: String, referer: String? = null) {
        val bytes = imageDownloadService.downloadImageBytes(url, referer)
        directory.findFile(fileName)?.delete()
        val file = directory.createFile("image/jpeg", fileName)
            ?: error("无法创建图片：$fileName")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(bytes)
        } ?: error("无法写入图片：$fileName")
    }

    private suspend fun tryDownloadImageToFile(
        directory: DocumentFile,
        fileName: String,
        url: String,
        referer: String?,
        label: String
    ): Boolean {
        return runCatching {
            downloadImageToFile(directory, fileName, url, referer)
        }.onSuccess {
            logStore.append("$label written: $fileName")
        }.onFailure { error ->
            logStore.append("$label download failed; skipped image: ${error.message ?: error::class.java.simpleName}")
        }.isSuccess
    }

    /*
     * ================================================================================
     * 步骤8：从高清包装图生成竖版海报
     * ================================================================================
     * 目标：避免 JavLibrary 和 JavBus 把 147x200 的预览小图当作影片海报。
     * 数据源：同一来源下载的横版高清包装图，右侧为正面竖版封面。
     * 操作：
     * 1) 仅处理已由 URL 规则确认的 DMM/JavBus 同源图片对。
     * 2) 按 2:3 比例截取右侧正面封面，并覆盖低清 poster 文件。
     */
    private fun writePortraitPosterFromWideCover(
        directory: DocumentFile,
        posterName: String,
        thumbName: String
    ) {
        logStore.append("开始从高清包装图生成竖版海报：$posterName")
        val thumbFile = directory.findFile(thumbName)
        if (thumbFile == null) {
            logStore.append("高清包装图不存在，保留原海报：$posterName")
            return
        }
        val source = context.contentResolver.openInputStream(thumbFile.uri)?.use(BitmapFactory::decodeStream)
        if (source == null || source.width <= source.height) {
            source?.recycle()
            logStore.append("高清包装图不是横版，保留原海报：$posterName")
            return
        }

        val cropWidth = (source.height * PORTRAIT_POSTER_ASPECT_RATIO).toInt().coerceIn(1, source.width)
        val crop = Bitmap.createBitmap(source, source.width - cropWidth, 0, cropWidth, source.height)
        source.recycle()
        runCatching {
            directory.findFile(posterName)?.delete()
            val posterFile = directory.createFile("image/jpeg", posterName)
                ?: error("无法创建图片：$posterName")
            context.contentResolver.openOutputStream(posterFile.uri, "wt")?.use { output ->
                crop.compress(Bitmap.CompressFormat.JPEG, PORTRAIT_POSTER_JPEG_QUALITY, output)
            } ?: error("无法写入图片：$posterName")
        }.onSuccess {
            logStore.append("高清竖版海报已生成：$posterName")
        }.onFailure { error ->
            logStore.append("高清竖版海报生成失败，保留原海报：${error.message ?: error::class.java.simpleName}")
        }
        crop.recycle()
        logStore.append("高清包装图海报处理完成：$posterName")
    }

    private fun isJavdbMetadataNfo(file: DocumentFile): Boolean =
        context.contentResolver.openInputStream(file.uri)
            ?.bufferedReader()
            ?.use { reader -> reader.readText().contains("<source>javdb</source>") }
            ?: false

    private fun deleteScrapeImage(directory: DocumentFile, fileName: String, label: String) {
        if (directory.findFile(fileName)?.delete() == true) {
            logStore.append("$label removed: $fileName")
        }
    }

    private fun deleteMetadataFiles(directory: DocumentFile, baseName: String) {
        val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
        val names = buildList {
            add("$baseName.nfo")
            add("$baseName.nfo.xml")
            imageExtensions.forEach { ext ->
                add("$baseName-poster.$ext")
                add("$baseName-thumb.$ext")
                add("$baseName-fanart.$ext")
                add("poster.$ext")
                add("thumb.$ext")
                add("fanart.$ext")
                add("movie-poster.$ext")
                add("movie-fanart.$ext")
            }
        }
        names.forEach { name ->
            directory.findFile(name)?.delete()
        }
    }

    private fun deleteRecursively(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    private fun String.sanitizeFileName(): String {
        return replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    private fun String.distinctPickcodeSuffix(): String? {
        val token = substringBeforeLast('.', this)
            .substringAfterLast('_', "")
            .takeIf { it.length >= 8 && it.all { char -> char.isLetterOrDigit() } }
            ?: return null
        return "_${token.take(8)}"
    }

    private fun String.normalizedActorName(): String = actorNameVariants(this).firstOrNull().orEmpty()

    private fun String.sameActorExactly(other: String): Boolean =
        actorNamesHaveExactVariant(this, other)

    private fun ScrapedMovieInfo.imageRefererFor(imageUrl: String): String? =
        imageRefererFor(imageUrl, number, website)

    private fun DocumentFile.isExcludedAssetDirectory(): Boolean {
        val normalized = name.orEmpty().trim().lowercase().replace('_', ' ').replace('-', ' ')
        return normalized == "extrafanart" || normalized == "behind the scenes"
    }

    private data class StrmTarget(
        val directory: DocumentFile,
        val file: DocumentFile,
        val baseName: String,
        val parentDirectory: DocumentFile?
    )

    private val ScrapeSource.label: String
        get() = when (this) {
            ScrapeSource.Dmm -> "DMM"
            ScrapeSource.Dmm2 -> "DMM2"
            ScrapeSource.Official -> "Official"
            ScrapeSource.Javbus -> "JavBus"
            ScrapeSource.Javdb -> "JavDB"
            ScrapeSource.Javlibrary -> "JavLibrary"
            ScrapeSource.Missav -> "MissAV"
        }

    private fun ScrapeSource.serialScrapeMutex(): Mutex? =
        if (this == ScrapeSource.Missav) missavScrapeMutex else null

    companion object {
        const val GENERIC_FILE_MIME_TYPE = "application/octet-stream"
        const val JAVBUS_BASE_URL = "https://www.javbus.com/"
        const val JAVDB_BASE_URL = "https://javdb.com/"
        const val JAVLIBRARY_BASE_URL = "https://www.javlibrary.com/"
        const val PORTRAIT_POSTER_ASPECT_RATIO = 0.6666667f
        const val PORTRAIT_POSTER_JPEG_QUALITY = 96
        internal fun imageRefererFor(imageUrl: String, number: String, sourcePageUrl: String): String? {
            val imageHost = imageUrl.hostOrNull() ?: return null
            return when {
                imageHost == "javbus.com" || imageHost.endsWith(".javbus.com") ->
                    "$JAVBUS_BASE_URL${number.trim()}"
                imageHost == "javlibrary.com" || imageHost.endsWith(".javlibrary.com") ->
                    sourcePageUrl.takeIf { it.hostOrNull()?.endsWith("javlibrary.com") == true } ?: JAVLIBRARY_BASE_URL
                imageHost == "javdb.com" || imageHost.endsWith(".javdb.com") || imageHost.endsWith(".jdbstatic.com") ->
                    sourcePageUrl.takeIf {
                        val pageHost = it.hostOrNull()
                        pageHost == "javdb.com" || pageHost?.endsWith(".javdb.com") == true
                    } ?: JAVDB_BASE_URL
                else -> sourcePageUrl.takeIf { it.hostOrNull() == imageHost }
            }
        }

        private fun String.hostOrNull(): String? = runCatching {
            URI(this).host?.lowercase(Locale.ROOT)
        }.getOrNull()

        const val SCRAPE_QUEUE_POLL_INTERVAL_MS = 250L
        const val DMM_FANZA_AVATAR_TIMEOUT_MS = 8_000L
        const val WEBVIEW_ALIAS_TIMEOUT_MS = 75_000L
    }
}

/*
 * ================================================================================
 * 步骤1：识别低清竖图与高清包装图
 * ================================================================================
 * 目标：只处理 JavLibrary 和 JavBus 已知的一对同源封面地址，不影响普通竖版海报。
 * 数据源：资料源返回的 posterUrl 和 thumbUrl。
 * 操作：
 * 1) DMM 的 pl.jpg 与 ps.jpg 视为同一包装图的高清横图和低清预览图。
 * 2) JavBus 的 cover/{id}_b 与 thumb/{id} 视为同一包装图的高清横图和低清预览图。
 */
internal fun shouldBuildPortraitPosterFromWideCover(posterUrl: String, thumbUrl: String): Boolean {
    val poster = posterUrl.substringBefore('?').lowercase()
    val thumb = thumbUrl.substringBefore('?').lowercase()
    if (thumb.contains("dmm.co.jp/") && thumb.endsWith("pl.jpg") && poster == thumb.removeSuffix("pl.jpg") + "ps.jpg") {
        return true
    }

    val javbusCover = Regex("^(https?://[^/]+)/pics/cover/([^/?#]+)_b(\\.[a-z0-9]+)$")
        .find(thumb)
        ?: return false
    val expectedPoster = "${javbusCover.groupValues[1]}/pics/thumb/${javbusCover.groupValues[2]}${javbusCover.groupValues[3]}"
    return poster == expectedPoster
}

data class ActorAvatarUpdateResult(
    val totalMissing: Int,
    val downloaded: Int,
    val scrapedMovies: Int,
    val totalActors: Int = totalMissing,
    val forceRefresh: Boolean = false
)

data class ScrapedMovieWriteResult(
    val info: ScrapedMovieInfo,
    val strmUri: String
)

data class ActorAvatarUpdateState(
    val isUpdating: Boolean = false,
    val message: String? = null,
    val refreshVersion: Int = 0
)

data class ScrapeQueueState(
    val isRunning: Boolean = false,
    val runningLabel: String? = null,
    val runningCount: Int = 0,
    val waitingCount: Int = 0,
    val startedAtMillis: Long = 0L
)
