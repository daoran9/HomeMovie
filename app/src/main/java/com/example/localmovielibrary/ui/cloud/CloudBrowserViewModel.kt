package com.example.localmovielibrary.ui.cloud

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.cloud115.Cloud115FileItem
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.Cloud115StrmRepository
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.DomesticMovieRepository
import com.example.localmovielibrary.data.repository.GeneratedStrmFile
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.scraper.MissavCookieRequiredException
import com.example.localmovielibrary.scraper.MovieNumberExtractor
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.ui.shared.HiddenMissavWebRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class CloudBrowserViewModel(
    private val strmRepository: Cloud115StrmRepository,
    private val recordRepository: CloudStrmRecordRepository,
    private val settingsRepository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val domesticMovieRepository: DomesticMovieRepository
) : ViewModel() {
    private val backStack = mutableListOf(CloudPathItem(0L, "根目录"))
    private val _uiState = MutableStateFlow(CloudBrowserUiState(path = backStack.toList(), isLoading = true))
    val uiState: StateFlow<CloudBrowserUiState> = _uiState
    private var loadJob: Job? = null
    private val scrollPositions = mutableMapOf<Long, CloudScrollPosition>()
    private val addLocks = mutableMapOf<String, Mutex>()
    private val missavWebViewQueue = ArrayDeque<PendingMissavScrape>()
    private val missavCloudAddQueue = ArrayDeque<PendingCloudAdd>()
    private var isMissavCloudAddRunning = false
    private var randomPlaybackJob: Job? = null
    private var folderLibraryAddJob: Job? = null

    init {
        loadCurrent()
    }

    fun domesticRootCid(): Long? = settingsRepository.getDomesticRootCid()

    fun isCloudAddButtonMessageEnabled(): Boolean =
        settingsRepository.isCloudAddButtonMessageEnabled()

    private fun progressMessage(text: String?): String? =
        if (settingsRepository.isCloudAddButtonMessageEnabled()) text else null

    fun openFolder(item: Cloud115FileItem) {
        val cid = item.cid ?: return
        backStack += CloudPathItem(cid, item.name)
        loadCurrent()
    }

    fun goBackFolder(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        loadCurrent()
        return true
    }

    fun canGoBackFolder(): Boolean = backStack.size > 1

    fun refresh() {
        loadCurrent()
    }

    /*
     * ================================================================================
     * 步骤2：准备文件夹随机播放
     * ================================================================================
     * 目标：把当前 115 文件夹及其子目录的视频洗牌后交给播放器。
     * 数据源：当前目录 CID 和递归目录读取结果。
     * 操作：
     * 1) 在 IO 协程中递归读取视频，避免阻塞 Compose 页面。
     * 2) 只把 pickcode 和文件名传给播放器，每次播放时再解析直链。
     * 3) 用一次性状态事件通知页面导航，空目录和失败都给出可见提示。
     */
    fun playRandomFolder() {
        if (_uiState.value.isRandomPlaybackLoading) return
        val folder = backStack.lastOrNull() ?: return
        randomPlaybackJob?.cancel()
        randomPlaybackJob = viewModelScope.launch {
            Log.i(TAG, "开始准备文件夹随机播放，cid=${folder.cid}")
            _uiState.update {
                it.copy(
                    isRandomPlaybackLoading = true,
                    message = progressMessage("正在读取文件夹视频...")
                )
            }
            runCatching {
                strmRepository.listVideoFilesRecursively(folder.cid)
                    .asSequence()
                    .filter { it.pickcode?.isNotBlank() == true }
                    .distinctBy { it.pickcode }
                    .toList()
                    .shuffled()
            }.onSuccess { items ->
                Log.i(TAG, "文件夹随机播放队列准备完成，视频数=${items.size}")
                _uiState.update { state ->
                    state.copy(
                        isRandomPlaybackLoading = false,
                        randomPlaybackItems = items.takeIf { it.isNotEmpty() },
                        message = if (items.isEmpty()) {
                            progressMessage("当前文件夹及子文件夹没有可播放视频")
                        } else {
                            progressMessage("已随机准备 ${items.size} 部视频")
                        }
                    )
                }
            }.onFailure { error ->
                Log.i(TAG, "文件夹随机播放队列准备失败：${error.message}")
                _uiState.update {
                    it.copy(
                        isRandomPlaybackLoading = false,
                        message = progressMessage(error.message ?: "读取文件夹视频失败")
                    )
                }
            }
        }
    }

    fun consumeRandomPlaybackItems(): List<Cloud115FileItem>? {
        val items = _uiState.value.randomPlaybackItems ?: return null
        _uiState.update { it.copy(randomPlaybackItems = null) }
        return items
    }

    /*
     * ================================================================================
     * 步骤3：批量添加115文件夹到媒体库
     * ================================================================================
     * 目标：把选中的115文件夹及子文件夹视频批量转成 STRM、入库并刮削。
     * 数据源：115递归目录扫描结果、媒体库目录和 STRM 保存位置。
     * 操作：
     * 1) 先检查两个本地目录配置，避免扫描完成后才发现无法写入。
     * 2) 按 pickcode 去重，逐个串行处理，控制115请求和刮削压力。
     * 3) 单个视频失败只记录并继续，保留批量任务的整体进度。
     */
    fun addFolderToLibrary(item: Cloud115FileItem) {
        val folderCid = item.cid ?: return
        if (!item.isDirectory) return
        if (folderCid in _uiState.value.addingFolderCids) {
            _uiState.update { it.copy(message = progressMessage("这个文件夹正在添加")) }
            return
        }
        if (settingsRepository.getLibraryRootUri().isNullOrBlank()) {
            _uiState.update { it.copy(message = progressMessage("请先到设置页选择影片库目录")) }
            return
        }
        if (settingsRepository.getStrmTreeUri().isNullOrBlank()) {
            _uiState.update { it.copy(message = progressMessage("请先到设置页选择 STRM 保存目录")) }
            return
        }
        if (settingsRepository.getDefaultScrapeSource() == ScrapeSource.Missav) {
            _uiState.update {
                it.copy(message = progressMessage("MissAV 刮削需要逐部网页验证，暂不支持整目录批量入库"))
            }
            return
        }
        if (_uiState.value.addingFolderCids.isNotEmpty()) {
            _uiState.update { it.copy(message = progressMessage("已有整目录入库任务正在处理")) }
            return
        }

        folderLibraryAddJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    addingFolderCids = it.addingFolderCids + folderCid,
                    folderBatchProgress = FolderBatchProgress(folderName = item.name),
                    message = progressMessage("正在扫描整目录：${item.name}")
                )
            }
            var successCount = 0
            var skippedCount = 0
            var failedCount = 0
            var totalCount = 0
            try {
                /*
                 * ================================================================================
                 * 步骤4：扫描并建立批量候选集
                 * ================================================================================
                 * 目标：只把支持的视频交给后续入库步骤。
                 * 数据源：当前115文件夹及其子文件夹。
                 * 操作：
                 * 1) 递归读取目录，保留已扫到的部分结果。
                 * 2) 按 pickcode 去重，避免同一文件重复生成 STRM。
                 */
                val candidates = strmRepository.listVideoFilesRecursively(folderCid)
                    .asSequence()
                    .filter { it.pickcode?.isNotBlank() == true }
                    .distinctBy { it.pickcode }
                    .toList()
                totalCount = candidates.size
                _uiState.update {
                    it.copy(
                        folderBatchProgress = FolderBatchProgress(
                            folderName = item.name,
                            total = totalCount
                        ),
                        message = progressMessage("已扫描 $totalCount 部，开始批量入库")
                    )
                }

                /*
                 * ================================================================================
                 * 步骤5：批量生成 STRM
                 * ================================================================================
                 * 目标：先完成本地 STRM 写入，再把网络刮削从 115 写入链路中拆出。
                 * 数据源：批量候选视频和已有 CloudStrmRecord。
                 * 操作：
                 * 1) 已存在的 pickcode 直接跳过。
                 * 2) 同批同番号只保留第一路进入刮削，其他路延后追加到同一影片。
                 * 3) 只生成首路 STRM，待后续阶段并发刮削。
                 */
                val preparedScrapes = mutableListOf<PreparedCloudAdd>()
                val deferredPlaybackSources = mutableListOf<DeferredCloudPlaybackSource>()
                val pendingScrapeNumbers = mutableSetOf<String>()
                for ((index, candidate) in candidates.withIndex()) {
                    ensureActive()
                    val pickcode = candidate.pickcode.orEmpty()
                    _uiState.update {
                        it.copy(
                            folderBatchProgress = FolderBatchProgress(
                                folderName = item.name,
                                current = index + 1,
                                total = totalCount,
                                success = successCount,
                                skipped = skippedCount,
                                failed = failedCount,
                                currentFileName = candidate.name
                            ),
                            message = progressMessage("正在生成 STRM ${index + 1}/$totalCount：${candidate.name}")
                        )
                    }
                    try {
                        if (recordRepository.isFinalizedInLibrary(
                                pickcode = pickcode,
                                libraryRootUri = settingsRepository.getLibraryRootUri().orEmpty()
                            )
                        ) {
                            skippedCount += 1
                            scrapeRepository.appendLog("整目录入库跳过已存在 pickcode：${candidate.name}")
                            continue
                        }
                        val number = MovieNumberExtractor.extract(candidate.name)?.uppercase()
                        if (number != null && number in pendingScrapeNumbers) {
                            deferredPlaybackSources += DeferredCloudPlaybackSource(
                                item = candidate,
                                pickcode = pickcode
                            )
                            scrapeRepository.appendLog("整目录入库延后同番号播放源：$number / ${candidate.name}")
                            continue
                        }
                        val generated = withContext(Dispatchers.IO) {
                            withAddLock(candidate.name) {
                                strmRepository.generateStrmForVideo(candidate, forceDistinct = false)
                            }
                        }
                        if (!generated.shouldScrape) {
                            processGeneratedCloudVideoAdd(candidate, pickcode, generated)
                            successCount += 1
                            _uiState.update { state ->
                                state.copy(addedPickcodes = state.addedPickcodes + pickcode)
                            }
                            scrapeRepository.appendLog("整目录入库完成（附加播放源）：${candidate.name}")
                        } else {
                            preparedScrapes += PreparedCloudAdd(
                                item = candidate,
                                pickcode = pickcode,
                                generated = generated
                            )
                            generated.movieNumberHint?.uppercase()?.let { pendingScrapeNumbers += it }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failedCount += 1
                        val message = error.message ?: error::class.java.simpleName
                        scrapeRepository.appendLog("整目录入库失败：${candidate.name}，原因：$message")
                    }
                }

                /*
                 * ================================================================================
                 * 步骤6：并发刮削并整理影片库
                 * ================================================================================
                 * 目标：让刮削网络请求重叠执行，缩短整目录入库时间。
                 * 数据源：步骤5生成的 STRM 和刮削并发设置。
                 * 操作：
                 * 1) 只限制刮削阶段并发，不放大 115 目录扫描请求。
                 * 2) 每个番号仍使用独立锁，避免同番号文件互相覆盖。
                 * 3) 单个任务失败只计数并继续，全部完成后统一汇总。
                 */
                if (preparedScrapes.isNotEmpty() || deferredPlaybackSources.isNotEmpty()) {
                    val scrapeConcurrency = settingsRepository.getScrapeConcurrencyLimit().coerceIn(1, 4)
                    val progressMutex = Mutex()
                    var completedCount = successCount + skippedCount + failedCount
                    val scrapeSemaphore = Semaphore(scrapeConcurrency)
                    if (preparedScrapes.isNotEmpty()) {
                        coroutineScope {
                            preparedScrapes.map { prepared ->
                                async(Dispatchers.IO) {
                                    scrapeSemaphore.withPermit {
                                        ensureActive()
                                        try {
                                            withAddLock(prepared.item.name) {
                                                processGeneratedCloudVideoAdd(
                                                    item = prepared.item,
                                                    pickcode = prepared.pickcode,
                                                    generated = prepared.generated
                                                )
                                            }
                                            progressMutex.withLock {
                                                successCount += 1
                                                completedCount += 1
                                                _uiState.update { state ->
                                                    state.copy(
                                                        addedPickcodes = state.addedPickcodes + prepared.pickcode,
                                                        folderBatchProgress = FolderBatchProgress(
                                                            folderName = item.name,
                                                            current = completedCount,
                                                            total = totalCount,
                                                            success = successCount,
                                                            skipped = skippedCount,
                                                            failed = failedCount,
                                                            currentFileName = prepared.item.name
                                                        ),
                                                        message = progressMessage("正在刮削 $completedCount/$totalCount：${prepared.item.name}")
                                                    )
                                                }
                                            }
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: Throwable) {
                                            val message = error.message ?: error::class.java.simpleName
                                            progressMutex.withLock {
                                                failedCount += 1
                                                completedCount += 1
                                                _uiState.update { state ->
                                                    state.copy(
                                                        folderBatchProgress = FolderBatchProgress(
                                                            folderName = item.name,
                                                            current = completedCount,
                                                            total = totalCount,
                                                            success = successCount,
                                                            skipped = skippedCount,
                                                            failed = failedCount,
                                                            currentFileName = prepared.item.name
                                                        ),
                                                        message = progressMessage("刮削失败 $completedCount/$totalCount：${prepared.item.name}")
                                                    )
                                                }
                                            }
                                            scrapeRepository.appendLog("整目录刮削失败：${prepared.item.name}，原因：$message")
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                    }

                    /*
                     * ================================================================================
                     * 步骤7：追加同番号播放源
                     * ================================================================================
                     * 目标：一部影片只刮削一次，同时保留同番号的每个网盘视频。
                     * 数据源：步骤5延后的同番号候选和步骤6已经整理完成的影片目录。
                     * 操作：
                     * 1) 首路刮削完成后再生成附加 STRM，确保目标影片目录已经存在。
                     * 2) 附加源只绑定已有影片，不重写 NFO、封面或影片卡片。
                     */
                    deferredPlaybackSources.forEach { deferred ->
                        ensureActive()
                        try {
                            withContext(Dispatchers.IO) {
                                withAddLock(deferred.item.name) {
                                    val generated = strmRepository.generateStrmForVideo(deferred.item, forceDistinct = false)
                                    processGeneratedCloudVideoAdd(deferred.item, deferred.pickcode, generated)
                                }
                            }
                            successCount += 1
                            completedCount += 1
                            _uiState.update { state ->
                                state.copy(
                                    addedPickcodes = state.addedPickcodes + deferred.pickcode,
                                    folderBatchProgress = FolderBatchProgress(
                                        folderName = item.name,
                                        current = completedCount,
                                        total = totalCount,
                                        success = successCount,
                                        skipped = skippedCount,
                                        failed = failedCount,
                                        currentFileName = deferred.item.name
                                    ),
                                    message = progressMessage("正在追加播放源 $completedCount/$totalCount：${deferred.item.name}")
                                )
                            }
                            scrapeRepository.appendLog("整目录入库完成（附加播放源）：${deferred.item.name}")
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            val message = error.message ?: error::class.java.simpleName
                            failedCount += 1
                            completedCount += 1
                            _uiState.update { state ->
                                state.copy(
                                    folderBatchProgress = FolderBatchProgress(
                                        folderName = item.name,
                                        current = completedCount,
                                        total = totalCount,
                                        success = successCount,
                                        skipped = skippedCount,
                                        failed = failedCount,
                                        currentFileName = deferred.item.name
                                    ),
                                    message = progressMessage("追加播放源失败 $completedCount/$totalCount：${deferred.item.name}")
                                )
                            }
                            scrapeRepository.appendLog("整目录追加播放源失败：${deferred.item.name}，原因：$message")
                        }
                    }
                }
                val completedMessage = "整目录入库完成：成功 $successCount，跳过 $skippedCount，失败 $failedCount"
                scrapeRepository.appendLog(completedMessage)
                _uiState.update {
                    it.copy(
                        message = progressMessage(completedMessage),
                        addedFolderCids = it.addedFolderCids + folderCid,
                        folderBatchProgress = FolderBatchProgress(
                            folderName = item.name,
                            current = totalCount,
                            total = totalCount,
                            success = successCount,
                            skipped = skippedCount,
                            failed = failedCount
                        )
                    )
                }
            } catch (error: CancellationException) {
                scrapeRepository.appendLog("整目录入库已取消：${item.name}")
                throw error
            } catch (error: Throwable) {
                val message = error.message ?: "整目录扫描失败"
                scrapeRepository.appendLog("整目录入库失败：${item.name}，原因：$message")
                _uiState.update { it.copy(message = progressMessage(message)) }
            } finally {
                _uiState.update { state ->
                    state.copy(addingFolderCids = state.addingFolderCids - folderCid)
                }
            }
        }
    }

    fun scrollPositionFor(cid: Long): CloudScrollPosition =
        scrollPositions[cid] ?: CloudScrollPosition()

    fun saveScrollPosition(cid: Long, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        scrollPositions[cid] = CloudScrollPosition(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset
        )
    }

    fun addDomesticFolder(item: Cloud115FileItem) {
        val cid = item.cid ?: return
        if (cid in _uiState.value.addingDomesticFolderCids || cid in _uiState.value.addedDomesticFolderCids) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    addingDomesticFolderCids = it.addingDomesticFolderCids + cid,
                    message = progressMessage("正在添加国产目录：${item.name}")
                )
            }
            runCatching { domesticMovieRepository.addFolder(item) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            addingDomesticFolderCids = state.addingDomesticFolderCids - cid,
                            addedDomesticFolderCids = state.addedDomesticFolderCids + cid,
                            message = progressMessage("已添加到国产：${item.name}")
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            addingDomesticFolderCids = state.addingDomesticFolderCids - cid,
                            message = error.message ?: "国产目录添加失败"
                        )
                    }
                }
        }
    }

    fun toggleSortDirection() {
        val current = _uiState.value
        val nextAscending = !current.sortAscending
        val currentFolderCid = current.path.lastOrNull()?.cid ?: 0L
        scrollPositions[currentFolderCid] = CloudScrollPosition()
        _uiState.update {
            it.copy(sortAscending = nextAscending)
        }
        viewModelScope.launch {
            val sortedItems = withContext(Dispatchers.Default) {
                current.items.sortedByCloudOption(current.sortOption, nextAscending)
            }
            _uiState.update {
                if (it.sortAscending == nextAscending) {
                    it.copy(
                        items = sortedItems,
                        scrollResetVersion = it.scrollResetVersion + 1
                    )
                } else {
                    it
                }
            }
        }
    }

    fun setSortOption(option: CloudSortOption) {
        val current = _uiState.value
        if (current.sortOption == option) return
        val currentFolderCid = current.path.lastOrNull()?.cid ?: 0L
        scrollPositions[currentFolderCid] = CloudScrollPosition()
        _uiState.update { it.copy(sortOption = option) }
        viewModelScope.launch {
            val sortedItems = withContext(Dispatchers.Default) {
                current.items.sortedByCloudOption(option, current.sortAscending)
            }
            _uiState.update {
                if (it.sortOption == option) {
                    it.copy(
                        items = sortedItems,
                        scrollResetVersion = it.scrollResetVersion + 1
                    )
                } else {
                    it
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun consumeOpenMovie() {
        _uiState.update { it.copy(openMovieId = null) }
    }

    fun onHiddenMissavHtmlReady(requestId: Long, html: String, cookie: String) {
        val pending = _uiState.value.pendingMissavScrape ?: return
        val request = _uiState.value.hiddenMissavRequest ?: return
        if (request.id != requestId) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    hiddenMissavRequest = null,
                    message = "已通过 WebView 获取 MissAV 页面，正在写入元数据..."
                )
            }
            runCatching {
                settingsRepository.saveMissavCookies(cookie)
                val scrapeResult = scrapeRepository.scrapeStrmUriWithMissavHtmlOutput(
                    sourceRootUri = pending.sourceRootUri,
                    strmUri = pending.strmUri,
                    html = html,
                    cookie = cookie,
                    outputRootUri = pending.libraryRootUri
                )
                scrapeRepository.appendLog("MissAV WebView 刮削完成，开始扫描影片库中的整理结果：${pending.number}")
                val refreshedMovie = movieRepository.scanSingleMovie(
                    rootUri = Uri.parse(pending.libraryRootUri),
                    videoUri = Uri.parse(scrapeResult.strmUri),
                    mergeByMovieNumber = true
                )
                if (refreshedMovie != null) {
                    recordRepository.updateStrmLocation(
                        pickcode = pending.pickcode,
                        strmUri = refreshedMovie.videoUri,
                        libraryRootUri = refreshedMovie.libraryRootUri,
                        movieId = refreshedMovie.id
                    )
                } else {
                    scrapeRepository.appendLog("MissAV WebView 刮削后未定位到整理后的 STRM：${pending.number}")
                }
                CloudAddResult(
                    pickcode = pending.pickcode,
                    message = "已添加并刮削：${pending.number}"
                )
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - result.pickcode,
                        pendingMissavScrape = null,
                        addedPickcodes = it.addedPickcodes + result.pickcode,
                        message = progressMessage(result.message)
                    )
                }
                finishMissavWebViewTaskAndContinue()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - pending.pickcode,
                        pendingMissavScrape = null,
                        message = error.message ?: "MissAV WebView 刮削失败"
                    )
                }
                finishMissavWebViewTaskAndContinue()
            }
        }
    }

    fun onHiddenMissavFailed(requestId: Long, message: String) {
        val request = _uiState.value.hiddenMissavRequest ?: return
        val pending = _uiState.value.pendingMissavScrape
        if (request.id != requestId) return
        scrapeRepository.appendLog("网盘添加影片时 MissAV 隐藏 WebView 抓取失败：$message")
        _uiState.update {
            it.copy(
                addingPickcodes = pending?.let { pendingItem -> it.addingPickcodes - pendingItem.pickcode } ?: it.addingPickcodes,
                hiddenMissavRequest = null,
                pendingMissavScrape = null,
                message = message
            )
        }
        finishMissavWebViewTaskAndContinue()
    }

    fun addVideoToLibrary(item: Cloud115FileItem) {
        addVideoToLibraryWithConflictCheck(item)
    }

    private fun addVideoToLibraryWithConflictCheck(item: Cloud115FileItem) {
        val pickcode = item.pickcode
        if (pickcode.isNullOrBlank()) {
            _uiState.update { it.copy(message = "这个视频没有 pickcode，无法添加") }
            return
        }
        if (pickcode in _uiState.value.addedPickcodes || pickcode in _uiState.value.addingPickcodes) {
            _uiState.update { it.copy(message = "这个视频已经添加或正在添加") }
            return
        }
        _uiState.update {
            it.copy(
                addingPickcodes = it.addingPickcodes + pickcode,
                message = progressMessage("\u6b63\u5728\u68c0\u67e5 ${item.name}")
            )
        }
        viewModelScope.launch {
            val conflict = recordRepository.findStandardSameNumberCandidate(item.name, pickcode)
            if (conflict != null) {
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - pickcode,
                        pendingReplaceConflict = PendingReplaceConflict(
                            item = item,
                            oldPickcode = conflict.pickcode,
                            oldFileName = conflict.fileName,
                            movieNumber = conflict.movieNumber.orEmpty()
                        )
                    )
                }
                return@launch
            }
            enqueueAddVideo(item, forceDistinct = false, alreadyMarkedAdding = true)
        }
    }

    fun dismissReplaceConflict() {
        _uiState.update { it.copy(pendingReplaceConflict = null) }
    }

    fun confirmReplacePickcode() {
        val conflict = _uiState.value.pendingReplaceConflict ?: return
        _uiState.update { it.copy(pendingReplaceConflict = null) }
        viewModelScope.launch {
            val newPickcode = conflict.item.pickcode ?: return@launch
            runCatching {
                recordRepository.replacePickcode(
                    oldPickcode = conflict.oldPickcode,
                    newPickcode = newPickcode,
                    newVideoName = conflict.item.name
                )
                scrapeRepository.appendLog("已替换同番号影片 pickcode：${conflict.movieNumber} ${conflict.oldPickcode} -> $newPickcode")
                CloudAddResult(pickcode = newPickcode, message = "已替换 ${conflict.movieNumber} 的播放源")
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        addedPickcodes = (it.addedPickcodes - conflict.oldPickcode) + result.pickcode,
                        message = progressMessage(result.message)
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(message = error.message ?: "替换播放源失败") }
            }
        }
    }

    fun addConflictAsNewMovie() {
        val conflict = _uiState.value.pendingReplaceConflict ?: return
        _uiState.update { it.copy(pendingReplaceConflict = null) }
        enqueueAddVideo(conflict.item, forceDistinct = true)
    }

    private fun enqueueAddVideo(item: Cloud115FileItem, forceDistinct: Boolean, alreadyMarkedAdding: Boolean = false) {
        val pickcode = item.pickcode
        if (pickcode.isNullOrBlank()) {
            _uiState.update { it.copy(message = "这个视频没有 pickcode，无法添加") }
            return
        }
        if (pickcode in _uiState.value.addedPickcodes || (!alreadyMarkedAdding && pickcode in _uiState.value.addingPickcodes)) {
            _uiState.update { it.copy(message = "这个视频已经在添加队列中") }
            return
        }
        if (settingsRepository.getDefaultScrapeSource() == ScrapeSource.Missav) {
            enqueueMissavCloudAdd(
                PendingCloudAdd(
                    item = item,
                    pickcode = pickcode,
                    forceDistinct = forceDistinct,
                    alreadyMarkedAdding = alreadyMarkedAdding
                )
            )
            return
        }
        viewModelScope.launch {
            _uiState.update {
                val nextAddingPickcodes = if (alreadyMarkedAdding) {
                    it.addingPickcodes
                } else {
                    it.addingPickcodes + pickcode
                }
                it.copy(
                    addingPickcodes = nextAddingPickcodes,
                    message = progressMessage("正在添加 ${item.name}")
                )
            }
            scrapeRepository.appendLog("网盘添加已加入队列：${item.name} / $pickcode")
            runCatching {
                withContext(Dispatchers.IO) {
                    withAddLock(item.name) {
                        processCloudVideoAdd(item, pickcode, forceDistinct)
                    }
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - result.pickcode,
                        addedPickcodes = it.addedPickcodes + result.pickcode,
                        message = progressMessage(result.message)
                    )
                }
            }.onFailure { error ->
                val message = error.message ?: error::class.java.simpleName
                scrapeRepository.appendLog("网盘添加失败：${item.name} / $pickcode，原因：$message")
                val pending = buildPendingMissavContext(item, pickcode)
                if (
                    settingsRepository.getDefaultScrapeSource() == ScrapeSource.Missav &&
                    error is MissavCookieRequiredException &&
                    pending != null
                ) {
                    enqueueOrStartMissavWebView(pending)
                    return@onFailure
                }
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - pickcode,
                        message = message
                    )
                }
            }
        }
    }

    private fun enqueueMissavCloudAdd(pending: PendingCloudAdd) {
        if (pending.pickcode in _uiState.value.addedPickcodes ||
            (!pending.alreadyMarkedAdding && pending.pickcode in _uiState.value.addingPickcodes)
        ) {
            _uiState.update { it.copy(message = "这个视频已经在添加队列中") }
            return
        }
        _uiState.update {
            val nextAddingPickcodes = if (pending.alreadyMarkedAdding) {
                it.addingPickcodes
            } else {
                it.addingPickcodes + pending.pickcode
            }
            it.copy(
                addingPickcodes = nextAddingPickcodes,
                message = progressMessage("已加入 MissAV 单线程添加队列：${pending.item.name}")
            )
        }
        scrapeRepository.appendLog("MissAV 网盘添加已加入单线程队列：${pending.item.name} / ${pending.pickcode}")
        missavCloudAddQueue.addLast(pending)
        startNextMissavCloudAdd()
    }

    private fun startNextMissavCloudAdd() {
        if (isMissavCloudAddRunning) return
        val pending = missavCloudAddQueue.removeFirstOrNull() ?: return
        isMissavCloudAddRunning = true
        viewModelScope.launch {
            _uiState.update {
                it.copy(message = progressMessage("正在处理 MissAV 队列：${pending.item.name}"))
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    withAddLock(pending.item.name) {
                        processCloudVideoAdd(pending.item, pending.pickcode, pending.forceDistinct)
                    }
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - result.pickcode,
                        addedPickcodes = it.addedPickcodes + result.pickcode,
                        message = progressMessage(result.message)
                    )
                }
                isMissavCloudAddRunning = false
                startNextMissavCloudAdd()
            }.onFailure { error ->
                val message = error.message ?: error::class.java.simpleName
                val pendingMissav = buildPendingMissavContext(pending.item, pending.pickcode)
                if (error is MissavCookieRequiredException && pendingMissav != null) {
                    scrapeRepository.appendLog("MissAV 需要 WebView 获取页面，暂停队列等待：${pendingMissav.number}")
                    startMissavWebView(pendingMissav)
                    return@onFailure
                }
                scrapeRepository.appendLog("网盘添加失败：${pending.item.name} / ${pending.pickcode}，原因：$message")
                _uiState.update {
                    it.copy(
                        addingPickcodes = it.addingPickcodes - pending.pickcode,
                        message = message
                    )
                }
                isMissavCloudAddRunning = false
                startNextMissavCloudAdd()
            }
        }
    }

    private fun enqueueOrStartMissavWebView(pending: PendingMissavScrape) {
        val state = _uiState.value
        if (state.hiddenMissavRequest != null || state.pendingMissavScrape != null) {
            if (missavWebViewQueue.none { it.pickcode == pending.pickcode }) {
                missavWebViewQueue.addLast(pending)
            }
            scrapeRepository.appendLog("MissAV 隐藏 WebView 正在处理，加入等待队列：${pending.number}")
            _uiState.update {
                it.copy(message = progressMessage("MissAV WebView 正在处理，已将 ${pending.number} 加入等待队列"))
            }
            return
        }
        startMissavWebView(pending)
    }

    private fun startMissavWebView(pending: PendingMissavScrape) {
        scrapeRepository.appendLog("网盘添加影片时 MissAV 返回 Cloudflare/403，切换到隐藏 WebView：${pending.number}")
        _uiState.update {
            it.copy(
                hiddenMissavRequest = HiddenMissavWebRequest(
                    id = System.currentTimeMillis(),
                    number = pending.number,
                    url = "https://missav.ai/cn/${pending.number.lowercase()}"
                ),
                pendingMissavScrape = pending,
                message = progressMessage("MissAV 返回验证页，正在使用隐藏 WebView 获取页面")
            )
        }
    }

    private fun startNextQueuedMissavWebView() {
        val next = if (missavWebViewQueue.isEmpty()) null else missavWebViewQueue.removeFirst()
        next?.let { startMissavWebView(it) }
    }

    private fun finishMissavWebViewTaskAndContinue() {
        if (isMissavCloudAddRunning) {
            isMissavCloudAddRunning = false
            startNextMissavCloudAdd()
        } else {
            startNextQueuedMissavWebView()
        }
    }

    private suspend fun processCloudVideoAdd(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ): CloudAddResult {
        scrapeRepository.appendLog("开始处理网盘添加队列：${item.name} / $pickcode")
        val generated = strmRepository.generateStrmForVideo(item, forceDistinct = forceDistinct)
        return processGeneratedCloudVideoAdd(item, pickcode, generated)
    }

    private suspend fun processGeneratedCloudVideoAdd(
        item: Cloud115FileItem,
        pickcode: String,
        generated: GeneratedStrmFile
    ): CloudAddResult {
        val strmRoot = settingsRepository.getStrmTreeUri()
            ?: error("请先到设置页选择 STRM 保存目录")
        val libraryRoot = settingsRepository.getLibraryRootUri()
            ?: error("请先到设置页选择影片库目录")
        val libraryRootUri = Uri.parse(libraryRoot)

        if (!generated.shouldScrape) {
            val number = generated.movieNumberHint
                ?: MovieNumberExtractor.extract(generated.fileName)
                ?: MovieNumberExtractor.extract(item.name)
                ?: error("附加播放源已写入，但无法识别番号")
            val movie = movieRepository.findMovieByNumberAndVariant(libraryRoot, number, generated.fileName)
                ?: movieRepository.findMovieByNumber(libraryRoot, number)
                ?: error("附加播放源已写入，但影片库中没有找到 $number")
            recordRepository.updateStrmLocation(
                pickcode = pickcode,
                strmUri = generated.strmUri,
                libraryRootUri = movie.libraryRootUri,
                movieId = movie.id
            )
            scrapeRepository.appendLog("附加播放源 STRM 写入完成，不单独入库：${generated.fileName}")
            return CloudAddResult(
                pickcode = pickcode,
                message = "已加入播放源：${generated.movieNumberHint ?: item.name}"
            )
        }

        val number = MovieNumberExtractor.extract(generated.fileName)
            ?: MovieNumberExtractor.extract(item.name)
            ?: error("STRM 已添加，但无法从文件名提取番号，无法自动刮削")

        /*
         * ================================================================================
         * 步骤6：把临时 STRM 刮削到影片库
         * ================================================================================
         * 目标：保持 STRM 临时目录和影片库目录职责分离。
         * 数据源：STRM 临时文件、115 番号和影片库目录。
         * 操作：
         * 1) 从 STRM 临时目录读取源文件，不把临时目录写入 Room 影片库记录。
         * 2) 刮削结果、NFO 和图片统一写入影片库目录。
         * 3) 刮削完成后只扫描影片库目录，避免影片记录指向 STRM 临时目录。
         */
        scrapeRepository.appendLog("开始刮削并整理到影片库：$number，来源：${settingsRepository.getDefaultScrapeSource()}")
        val source = settingsRepository.getDefaultScrapeSource()
        val scrapeResult = scrapeRepository.scrapeStrmUriWithOutput(
            sourceRootUri = strmRoot,
            strmUri = generated.strmUri,
            source = source,
            forceDistinct = generated.forceDistinct,
            outputRootUri = libraryRoot
        )
        scrapeRepository.appendLog("刮削完成，开始扫描影片库中的整理结果：$number")
        val refreshedMovie = movieRepository.scanSingleMovie(
            libraryRootUri,
            Uri.parse(scrapeResult.strmUri),
            mergeByMovieNumber = !generated.forceDistinct
        )
        val movieForScrape = refreshedMovie
            ?: movieRepository.findMovieByNumberAndVariant(libraryRoot, number, generated.fileName)
            ?: error("刮削完成，但影片库中没有找到 $number")
        recordRepository.updateStrmLocation(
            pickcode = pickcode,
            strmUri = movieForScrape.videoUri,
            libraryRootUri = movieForScrape.libraryRootUri,
            movieId = movieForScrape.id
        )
        scrapeRepository.appendLog("影片库入库完成：$number")
        return CloudAddResult(
            pickcode = pickcode,
            message = if (generated.created) {
                "已添加并刮削：$number"
            } else {
                "STRM 已存在，已刷新并刮削：$number"
            }
        )
    }

    private suspend fun <T> withAddLock(fileName: String, block: suspend () -> T): T {
        val key = MovieNumberExtractor.extract(fileName)?.uppercase() ?: fileName.trim().lowercase()
        val lock = synchronized(addLocks) {
            addLocks.getOrPut(key) { Mutex() }
        }
        if (lock.isLocked) {
            scrapeRepository.appendLog("同番号添加任务等待整理完成：$key")
        }
        return lock.withLock {
            block()
        }
    }

    private suspend fun buildPendingMissavContext(item: Cloud115FileItem, pickcode: String): PendingMissavScrape? {
        val libraryRoot = settingsRepository.getLibraryRootUri() ?: return null
        val sourceRoot = settingsRepository.getStrmTreeUri() ?: return null
        val number = MovieNumberExtractor.extract(item.name) ?: return null
        val strmUri = recordRepository.get(pickcode)?.strmUri ?: return null
        return PendingMissavScrape(
            libraryRootUri = libraryRoot,
            sourceRootUri = sourceRoot,
            strmUri = strmUri,
            number = number,
            sourceName = item.name,
            pickcode = pickcode
        )
    }

    private fun loadCurrent() {
        val current = backStack.last()
        val requestedPath = backStack.toList()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    path = requestedPath,
                    isLoading = it.items.isEmpty() || it.path != requestedPath,
                    errorMessage = null
                )
            }
            runCatching {
                val sortOption = _uiState.value.sortOption
                val sortAscending = _uiState.value.sortAscending
                val items = strmRepository.listFiles(current.cid)
                coroutineScope {
                    val addedPickcodes = async { strmRepository.existingPickcodesForVisibleItems(items) }
                    val addedDomesticFolderCids = async { domesticMovieRepository.addedFolderCidsForVisibleItems(items) }
                    CloudDirectoryLoadResult(
                        items = withContext(Dispatchers.Default) { items.sortedByCloudOption(sortOption, sortAscending) },
                        addedPickcodes = addedPickcodes.await(),
                        addedDomesticFolderCids = addedDomesticFolderCids.await()
                    )
                }
            }.onSuccess { result ->
                if (backStack != requestedPath) return@onSuccess
                _uiState.update {
                    it.copy(
                        items = result.items,
                        addedPickcodes = result.addedPickcodes,
                        addedDomesticFolderCids = result.addedDomesticFolderCids,
                        excludedVideoNames = settingsRepository.getCloudExcludedVideoNames(),
                        path = backStack.toList(),
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                if (backStack != requestedPath) return@onFailure
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "读取 115 目录失败"
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "CloudBrowserViewModel"

        fun factory(
            strmRepository: Cloud115StrmRepository,
            recordRepository: CloudStrmRecordRepository,
            settingsRepository: AppSettingsRepository,
            movieRepository: MovieRepository,
            scrapeRepository: StrmScrapeRepository,
            domesticMovieRepository: DomesticMovieRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CloudBrowserViewModel(strmRepository, recordRepository, settingsRepository, movieRepository, scrapeRepository, domesticMovieRepository) as T
            }
    }
}

enum class CloudSortOption {
    Name,
    ModifiedTime,
    Size
}

private fun List<Cloud115FileItem>.sortedByCloudOption(
    option: CloudSortOption,
    ascending: Boolean
): List<Cloud115FileItem> {
    val comparator = when (option) {
        CloudSortOption.Name -> if (ascending) {
            compareBy<Cloud115FileItem> { it.name.naturalCloudNameKey() }
        } else {
            compareByDescending { it.name.naturalCloudNameKey() }
        }
        CloudSortOption.ModifiedTime -> if (ascending) {
            compareBy<Cloud115FileItem> { it.modifiedAt ?: Long.MAX_VALUE }
        } else {
            compareByDescending { it.modifiedAt ?: Long.MIN_VALUE }
        }
        CloudSortOption.Size -> if (ascending) {
            compareBy { it.size ?: Long.MAX_VALUE }
        } else {
            compareByDescending { it.size ?: Long.MIN_VALUE }
        }
    }
    return sortedWith(comparator.thenBy { it.name.lowercase() })
}

private fun String.naturalCloudNameKey(): String =
    lowercase().replace(Regex("\\d+")) { match -> match.value.padStart(10, '0') }

data class CloudBrowserUiState(
    val items: List<Cloud115FileItem> = emptyList(),
    val path: List<CloudPathItem> = emptyList(),
    val isLoading: Boolean = false,
    val sortOption: CloudSortOption = CloudSortOption.ModifiedTime,
    val sortAscending: Boolean = false,
    val addingPickcodes: Set<String> = emptySet(),
    val addedPickcodes: Set<String> = emptySet(),
    val excludedVideoNames: Set<String> = emptySet(),
    val addingDomesticFolderCids: Set<Long> = emptySet(),
    val addedDomesticFolderCids: Set<Long> = emptySet(),
    val addingFolderCids: Set<Long> = emptySet(),
    val addedFolderCids: Set<Long> = emptySet(),
    val folderBatchProgress: FolderBatchProgress? = null,
    val isRandomPlaybackLoading: Boolean = false,
    val randomPlaybackItems: List<Cloud115FileItem>? = null,
    val hiddenMissavRequest: HiddenMissavWebRequest? = null,
    val pendingMissavScrape: PendingMissavScrape? = null,
    val pendingReplaceConflict: PendingReplaceConflict? = null,
    val openMovieId: Long? = null,
    val scrollResetVersion: Int = 0,
    val errorMessage: String? = null,
    val message: String? = null
)

data class CloudPathItem(
    val cid: Long,
    val name: String
)

data class CloudScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)

private data class CloudAddResult(
    val pickcode: String,
    val message: String
)

private data class CloudDirectoryLoadResult(
    val items: List<Cloud115FileItem>,
    val addedPickcodes: Set<String>,
    val addedDomesticFolderCids: Set<Long>
)

data class FolderBatchProgress(
    val folderName: String,
    val current: Int = 0,
    val total: Int = 0,
    val success: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val currentFileName: String? = null
)

data class PendingMissavScrape(
    val libraryRootUri: String,
    val sourceRootUri: String,
    val strmUri: String,
    val number: String,
    val sourceName: String,
    val pickcode: String
)

private data class PendingCloudAdd(
    val item: Cloud115FileItem,
    val pickcode: String,
    val forceDistinct: Boolean,
    val alreadyMarkedAdding: Boolean
)

private data class PreparedCloudAdd(
    val item: Cloud115FileItem,
    val pickcode: String,
    val generated: GeneratedStrmFile
)

private data class DeferredCloudPlaybackSource(
    val item: Cloud115FileItem,
    val pickcode: String
)

data class PendingReplaceConflict(
    val item: Cloud115FileItem,
    val oldPickcode: String,
    val oldFileName: String,
    val movieNumber: String
)

