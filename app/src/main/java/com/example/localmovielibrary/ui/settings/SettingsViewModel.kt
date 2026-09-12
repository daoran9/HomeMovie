package com.example.localmovielibrary.ui.settings

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrary.asr.AsrModelManager
import com.example.localmovielibrary.asr.AsrModelOption
import com.example.localmovielibrary.cloud115.Cloud115LoginApp
import com.example.localmovielibrary.cloud115.Cloud115LoginApps
import com.example.localmovielibrary.cloud115.Cloud115QrLoginClient
import com.example.localmovielibrary.cloud115.Cloud115QrLoginStatus
import com.example.localmovielibrary.cloud115.Cloud115QrToken
import com.example.localmovielibrary.cloud115.SavedCloud115Account
import com.example.localmovielibrary.data.repository.AppSettingsRepository
import com.example.localmovielibrary.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrary.data.repository.MovieRepository
import com.example.localmovielibrary.data.repository.StrmScrapeRepository
import com.example.localmovielibrary.data.repository.ActorAvatarUpdateState
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.scraper.ScrapeSource
import com.example.localmovielibrary.scraper.SourceProbeResult
import com.example.localmovielibrary.scraper.SourceProbeStatus
import com.example.localmovielibrary.subtitle.SubtitleSearchProvider
import com.example.localmovielibrary.translate.TranslateProvider
import com.example.localmovielibrary.translate.DeepSeekPromptTemplate
import com.example.localmovielibrary.translate.DeepSeekPromptTemplates
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val cloudStrmRecordRepository: CloudStrmRecordRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val cloud115QrLoginClient: Cloud115QrLoginClient,
    private val asrModelManager: AsrModelManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<SettingsUiState> = _uiState
    val actorAvatarUpdateState: StateFlow<ActorAvatarUpdateState> = scrapeRepository.actorAvatarUpdateState
    private var qrLoginJob: Job? = null
    private var asrDownloadJob: Job? = null
    private var metadataRescrapeMovies: List<MovieEntity> = emptyList()

    fun refreshSavedCloud115Accounts() {
        viewModelScope.launch {
            runCatching { cloud115QrLoginClient.listSavedAccounts() }
                .onSuccess { accounts ->
                    val currentCookies = repository.getCookies()
                    val selected = accounts.firstOrNull { it.cookies == currentCookies }
                    _uiState.update {
                        it.copy(
                            savedCloud115Accounts = accounts,
                            selectedCloud115AccountFileName = selected?.fileName ?: it.selectedCloud115AccountFileName
                        )
                    }
                }
        }
    }

    fun applySavedCloud115Account(account: SavedCloud115Account) {
        viewModelScope.launch {
            runCatching { cloud115QrLoginClient.applySavedAccount(account.fileName) }
                .onSuccess { selected ->
                    _uiState.value = loadState().copy(
                        savedCloud115Accounts = cloud115QrLoginClient.listSavedAccounts(),
                        selectedCloud115AccountFileName = selected.fileName,
                        savedMessage = "已切换 115 账号：${selected.displayName}"
                    )
                }
                .onFailure { error ->
                    _uiState.update { it.copy(savedMessage = error.message ?: "切换 115 账号失败") }
                }
        }
    }

    fun deleteSavedCloud115Account(account: SavedCloud115Account) {
        viewModelScope.launch {
            runCatching { cloud115QrLoginClient.deleteSavedAccount(account.fileName) }
                .onSuccess { deleted ->
                    val accounts = cloud115QrLoginClient.listSavedAccounts()
                    val currentCookies = repository.getCookies()
                    val selected = accounts.firstOrNull { it.cookies == currentCookies }
                    _uiState.update {
                        it.copy(
                            savedCloud115Accounts = accounts,
                            selectedCloud115AccountFileName = selected?.fileName,
                            savedMessage = if (deleted) {
                                "已删除 115 账号：${account.displayName}"
                            } else {
                                "账号文件不存在，已刷新列表"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(savedMessage = error.message ?: "删除 115 账号失败") }
                }
        }
    }

    fun selectCloud115LoginApp(app: Cloud115LoginApp) {
        repository.saveCloud115LoginApp(app.app)
        _uiState.update {
            it.copy(
                selectedCloud115LoginApp = app,
                cloud115QrStatusText = "已选择：${app.description}",
                savedMessage = null
            )
        }
    }

    fun startCloud115QrLogin() {
        qrLoginJob?.cancel()
        val loginApp = _uiState.value.selectedCloud115LoginApp
        _uiState.update {
            it.copy(
                isCloud115QrLoginActive = true,
                cloud115QrToken = null,
                cloud115QrStatusText = "正在获取 115 登录二维码...",
                cloud115QrSavedFile = null,
                savedMessage = null
            )
        }
        qrLoginJob = viewModelScope.launch {
            runCatching {
                val token = cloud115QrLoginClient.requestToken(loginApp)
                _uiState.update {
                    it.copy(
                        cloud115QrToken = token,
                        cloud115QrStatusText = "请使用 115 App 扫码登录：${loginApp.description}"
                    )
                }
                while (true) {
                    delay(QR_LOGIN_POLL_INTERVAL_MS)
                    when (cloud115QrLoginClient.checkStatus(token)) {
                        Cloud115QrLoginStatus.Waiting -> {
                            _uiState.update { it.copy(cloud115QrStatusText = "等待扫码...") }
                        }

                        Cloud115QrLoginStatus.Scanned -> {
                            _uiState.update { it.copy(cloud115QrStatusText = "已扫码，请在手机上确认登录") }
                        }

                        Cloud115QrLoginStatus.Confirmed -> {
                            val result = cloud115QrLoginClient.login(token, loginApp)
                            _uiState.value = loadState().copy(
                                savedCloud115Accounts = cloud115QrLoginClient.listSavedAccounts(),
                                selectedCloud115AccountFileName = result.fileName,
                                isCloud115QrLoginActive = false,
                                cloud115QrToken = null,
                                cloud115QrStatusText = "115 登录成功，Cookie 文件已保存：${result.fileName}",
                                cloud115QrSavedFile = result.filePath,
                                savedMessage = "115 登录成功，已保存 ${result.fileName}"
                            )
                            return@launch
                        }

                        Cloud115QrLoginStatus.Expired -> {
                            _uiState.update {
                                it.copy(
                                    isCloud115QrLoginActive = false,
                                    cloud115QrStatusText = "二维码已过期，请重新获取"
                                )
                            }
                            return@launch
                        }

                        Cloud115QrLoginStatus.Canceled -> {
                            _uiState.update {
                                it.copy(
                                    isCloud115QrLoginActive = false,
                                    cloud115QrStatusText = "扫码登录已取消"
                                )
                            }
                            return@launch
                        }
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCloud115QrLoginActive = false,
                        cloud115QrStatusText = error.message ?: "115 二维码登录失败",
                        savedMessage = error.message ?: "115 二维码登录失败"
                    )
                }
            }
        }
    }

    fun cancelCloud115QrLogin() {
        qrLoginJob?.cancel()
        qrLoginJob = null
        _uiState.update {
            it.copy(
                isCloud115QrLoginActive = false,
                cloud115QrToken = null,
                cloud115QrStatusText = "已取消 115 二维码登录"
            )
        }
    }

    fun updateMissavCookies(value: String) {
        _uiState.update { it.copy(missavCookies = value, savedMessage = null) }
    }

    fun saveMissavCookies(value: String) {
        repository.saveMissavCookies(value)
        _uiState.value = loadState().copy(savedMessage = "MissAV Cookie 已保存")
    }

    fun refreshJavdbCookieStatus() {
        _uiState.update { it.copy(javdbCookies = repository.getJavdbCookies()) }
    }

    fun refreshJavlibraryCookieStatus() {
        _uiState.update { it.copy(javlibraryCookies = repository.getJavlibraryCookies()) }
    }

    fun updateBaseUrl(value: String) {
        _uiState.update { it.copy(strmBaseUrl = value, savedMessage = null) }
    }

    fun updateDefaultScrapeSource(source: ScrapeSource) {
        repository.saveDefaultScrapeSource(source)
        _uiState.update { it.copy(defaultScrapeSource = source, savedMessage = null) }
    }

    fun updateImageDownloadRetryCount(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(2)
        _uiState.update { it.copy(imageDownloadRetryCountText = cleaned, savedMessage = null) }
    }

    fun updateScrapeConcurrencyLimit(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(1)
        _uiState.update { it.copy(scrapeConcurrencyLimitText = cleaned, savedMessage = null) }
    }

    /*
     * ============================================================================================================
     * 步骤：切换 gfriends 演员头像兜底
     * ============================================================================================================
     * 目标：让用户决定是否允许使用 gfriends 补齐其它来源未找到的头像。
     * 数据源：设置页开关与本地 SharedPreferences。
     * 操作：
     * 1) 立即保存开关，后续刮削无需等待离开设置页。
     * 2) 更新页面状态并提示当前状态。
    */
    fun updateGfriendsActorAvatarEnabled(enabled: Boolean) {
        Log.i(TAG, "开始切换 gfriends 演员头像兜底：$enabled")
        // 1.1 持久化当前选择，供所有刮削入口直接读取。
        repository.saveGfriendsActorAvatarEnabled(enabled)
        // 1.2 刷新页面状态，使头像说明同步切换。
        _uiState.update {
            it.copy(
                gfriendsActorAvatarEnabled = enabled,
                savedMessage = if (enabled) "已启用 gfriends 头像兜底" else "已关闭 gfriends 头像兜底"
            )
        }
        Log.i(TAG, "gfriends 演员头像兜底切换完成：$enabled")
    }

    fun updateNewDmm2SkippedPrefix(value: String) {
        _uiState.update {
            it.copy(
                newDmm2SkippedPrefix = value.uppercase().filter { char -> char.isLetterOrDigit() }.take(12),
                savedMessage = null
            )
        }
    }

    fun addDmm2SkippedPrefix() {
        val prefix = _uiState.value.newDmm2SkippedPrefix.trim()
        if (prefix.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请输入要跳过的番号开头") }
            return
        }
        repository.addDmm2SkippedNumberPrefix(prefix)
        _uiState.value = loadState().copy(savedMessage = "已添加 DMM2 跳过前缀：${prefix.uppercase()}")
    }

    fun removeDmm2SkippedPrefix(prefix: String) {
        repository.removeDmm2SkippedNumberPrefix(prefix)
        _uiState.value = loadState().copy(savedMessage = "已移除 DMM2 跳过前缀")
    }

    fun updateBaiduTranslateAppId(value: String) {
        _uiState.update { it.copy(baiduTranslateAppId = value.trim(), savedMessage = null) }
    }

    fun updateBaiduTranslateSecretKey(value: String) {
        _uiState.update { it.copy(baiduTranslateSecretKey = value.trim(), savedMessage = null) }
    }

    fun updateTranslateProvider(provider: TranslateProvider) {
        repository.saveTranslateProvider(provider)
        _uiState.value = loadState().copy(savedMessage = "翻译服务已切换为：${provider.label}")
    }

    fun updateDeepSeekApiKey(value: String) {
        _uiState.update { it.copy(deepSeekApiKey = value.trim(), savedMessage = null) }
    }

    fun updateDeepSeekBaseUrl(value: String) {
        _uiState.update { it.copy(deepSeekBaseUrl = value.trim(), savedMessage = null) }
    }

    fun updateDeepSeekModel(value: String) {
        _uiState.update { it.copy(deepSeekModel = value.trim(), savedMessage = null) }
    }

    fun updateDeepSeekThinkingEnabled(enabled: Boolean) {
        _uiState.update { it.copy(deepSeekThinkingEnabled = enabled, savedMessage = null) }
    }

    fun updateDeepSeekPromptEnabled(enabled: Boolean) {
        _uiState.update { it.copy(deepSeekPromptEnabled = enabled, savedMessage = null) }
    }

    fun updateDeepSeekPromptTemplate(template: DeepSeekPromptTemplate) {
        _uiState.update {
            it.copy(
                deepSeekPromptTemplateId = template.id,
                savedMessage = null
            )
        }
    }

    fun updateDeepSeekCustomPrompt(value: String) {
        _uiState.update { it.copy(deepSeekCustomPrompt = value, savedMessage = null) }
    }

    fun updateDomesticRootCid(value: String) {
        _uiState.update { it.copy(domesticRootCidText = value.filter { char -> char.isDigit() }, savedMessage = null) }
    }

    fun updateDomesticPageEnabled(enabled: Boolean) {
        repository.saveDomesticPageEnabled(enabled)
        _uiState.update { it.copy(domesticPageEnabled = enabled, savedMessage = null) }
    }

    fun updateLibraryNoMediaEnabled(enabled: Boolean) {
        repository.saveLibraryNoMediaEnabled(enabled)
        _uiState.update {
            it.copy(
                libraryNoMediaEnabled = enabled,
                savedMessage = if (enabled) {
                    "已开启 .nomedia 屏蔽图片"
                } else {
                    "已关闭 .nomedia 屏蔽图片"
                }
            )
        }
    }

    fun updateCloudAddButtonMessageEnabled(enabled: Boolean) {
        repository.saveCloudAddButtonMessageEnabled(enabled)
        _uiState.update { it.copy(cloudAddButtonMessageEnabled = enabled, savedMessage = null) }
    }

    fun updateNewExcludedVideoName(value: String) {
        _uiState.update { it.copy(newExcludedVideoName = value, savedMessage = null) }
    }

    fun addExcludedVideoName() {
        val name = _uiState.value.newExcludedVideoName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(savedMessage = "请输入要排除的视频文件名") }
            return
        }
        repository.addCloudExcludedVideoName(name)
        _uiState.value = loadState().copy(savedMessage = "已添加排除视频：$name")
    }

    fun removeExcludedVideoName(name: String) {
        repository.removeCloudExcludedVideoName(name)
        _uiState.value = loadState().copy(savedMessage = "已移除排除视频")
    }

    fun updateAsrModel(model: AsrModelOption) {
        repository.saveAsrModelId(model.id)
        _uiState.value = loadState().copy(savedMessage = "ASR 模型已选择：${model.label}")
    }

    fun updateAsrModelBaseUrl(value: String) {
        _uiState.update { it.copy(asrModelBaseUrl = value.trim(), savedMessage = null) }
    }

    fun updatePlayerLiveSubtitleEnabled(enabled: Boolean) {
        repository.savePlayerLiveSubtitleEnabled(enabled)
        _uiState.update {
            it.copy(
                playerLiveSubtitleEnabled = enabled,
                savedMessage = if (enabled) "已开启播放器实时字幕" else "已关闭播放器实时字幕"
            )
        }
    }

    fun updateExternalSubtitleFontSizeSp(value: Int) {
        repository.saveExternalSubtitleFontSizeSp(value)
        _uiState.update {
            it.copy(
                externalSubtitleFontSizeSp = repository.getExternalSubtitleFontSizeSp(),
                savedMessage = "外挂字幕字号已调整"
            )
        }
    }

    fun updateExternalSubtitleBottomPaddingPercent(value: Int) {
        repository.saveExternalSubtitleBottomPaddingPercent(value)
        _uiState.update {
            it.copy(
                externalSubtitleBottomPaddingPercent = repository.getExternalSubtitleBottomPaddingPercent(),
                savedMessage = "外挂字幕位置已调整"
            )
        }
    }

    fun updateExternalSubtitleBackgroundAlphaPercent(value: Int) {
        repository.saveExternalSubtitleBackgroundAlphaPercent(value)
        _uiState.update {
            it.copy(
                externalSubtitleBackgroundAlphaPercent = repository.getExternalSubtitleBackgroundAlphaPercent(),
                savedMessage = "外挂字幕背景已调整"
            )
        }
    }

    fun updateSubtitleSearchProvider(provider: SubtitleSearchProvider) {
        repository.saveSubtitleSearchProvider(provider)
        _uiState.update {
            it.copy(
                subtitleSearchProvider = provider,
                savedMessage = "在线字幕来源已切换为：${provider.label}"
            )
        }
    }

    fun downloadAsrModel() {
        if (_uiState.value.isAsrModelDownloading) return
        asrDownloadJob?.cancel()
        _uiState.update {
            it.copy(
                isAsrModelDownloading = true,
                asrModelDownloadProgress = 0,
                asrModelDownloadMessage = "准备下载模型...",
                savedMessage = null
            )
        }
        asrDownloadJob = viewModelScope.launch {
            runCatching {
                asrModelManager.downloadCurrentModel { progress, message ->
                    _uiState.update {
                        it.copy(
                            asrModelDownloadProgress = progress,
                            asrModelDownloadMessage = message
                        )
                    }
                }
            }.onSuccess { status ->
                _uiState.value = loadState().copy(
                    isAsrModelDownloading = false,
                    asrModelDownloadProgress = 100,
                    asrModelDownloadMessage = "模型已下载：${formatBytes(status.sizeBytes)}",
                    savedMessage = "ASR 模型下载完成"
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isAsrModelDownloading = false,
                        asrModelDownloadMessage = error.message ?: "ASR 模型下载失败",
                        savedMessage = error.message ?: "ASR 模型下载失败"
                    )
                }
            }
        }
    }

    fun saveBaiduTranslateSettings() {
        val state = _uiState.value
        repository.saveTranslateProvider(state.translateProvider)
        repository.saveBaiduTranslateAppId(state.baiduTranslateAppId)
        repository.saveBaiduTranslateSecretKey(state.baiduTranslateSecretKey)
        repository.saveDeepSeekApiKey(state.deepSeekApiKey)
        repository.saveDeepSeekBaseUrl(state.deepSeekBaseUrl)
        repository.saveDeepSeekModel(state.deepSeekModel)
        repository.saveDeepSeekThinkingEnabled(state.deepSeekThinkingEnabled)
        repository.saveDeepSeekPromptEnabled(state.deepSeekPromptEnabled)
        repository.saveDeepSeekPromptTemplateId(state.deepSeekPromptTemplateId)
        repository.saveDeepSeekCustomPrompt(state.deepSeekCustomPrompt)
        repository.saveDomesticRootCid(state.domesticRootCidText)
        repository.saveDomesticPageEnabled(state.domesticPageEnabled)
        repository.saveLibraryNoMediaEnabled(state.libraryNoMediaEnabled)
        repository.saveCloudAddButtonMessageEnabled(state.cloudAddButtonMessageEnabled)
        repository.saveCloudExcludedVideoNames(state.cloudExcludedVideoNames.toSet())
        repository.saveAsrModelId(state.selectedAsrModelId)
        repository.saveAsrModelBaseUrl(state.asrModelBaseUrl)
        repository.savePlayerLiveSubtitleEnabled(state.playerLiveSubtitleEnabled)
        repository.saveSubtitleSearchProvider(state.subtitleSearchProvider)
        _uiState.value = loadState().copy(savedMessage = "翻译配置已保存")
    }

    fun save() {
        val state = _uiState.value
        repository.saveCookies(state.cookies)
        repository.saveMissavCookies(state.missavCookies)
        repository.saveJavdbCookies(state.javdbCookies)
        repository.saveJavlibraryCookies(state.javlibraryCookies)
        repository.saveStrmBaseUrl(state.strmBaseUrl)
        repository.saveDefaultScrapeSource(state.defaultScrapeSource)
        repository.saveGfriendsActorAvatarEnabled(state.gfriendsActorAvatarEnabled)
        repository.saveImageDownloadRetryCount(state.imageDownloadRetryCountText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT)
        repository.saveScrapeConcurrencyLimit(state.scrapeConcurrencyLimitText.toIntOrNull() ?: AppSettingsRepository.DEFAULT_SCRAPE_CONCURRENCY_LIMIT)
        repository.saveDmm2SkippedNumberPrefixes(state.dmm2SkippedPrefixes.toSet())
        repository.saveTranslateProvider(state.translateProvider)
        repository.saveBaiduTranslateAppId(state.baiduTranslateAppId)
        repository.saveBaiduTranslateSecretKey(state.baiduTranslateSecretKey)
        repository.saveDeepSeekApiKey(state.deepSeekApiKey)
        repository.saveDeepSeekBaseUrl(state.deepSeekBaseUrl)
        repository.saveDeepSeekModel(state.deepSeekModel)
        repository.saveDeepSeekThinkingEnabled(state.deepSeekThinkingEnabled)
        repository.saveDeepSeekPromptEnabled(state.deepSeekPromptEnabled)
        repository.saveDeepSeekPromptTemplateId(state.deepSeekPromptTemplateId)
        repository.saveDeepSeekCustomPrompt(state.deepSeekCustomPrompt)
        repository.saveDomesticRootCid(state.domesticRootCidText)
        repository.saveDomesticPageEnabled(state.domesticPageEnabled)
        repository.saveLibraryNoMediaEnabled(state.libraryNoMediaEnabled)
        repository.saveCloudAddButtonMessageEnabled(state.cloudAddButtonMessageEnabled)
        repository.saveCloudExcludedVideoNames(state.cloudExcludedVideoNames.toSet())
        repository.saveAsrModelId(state.selectedAsrModelId)
        repository.saveAsrModelBaseUrl(state.asrModelBaseUrl)
        repository.savePlayerLiveSubtitleEnabled(state.playerLiveSubtitleEnabled)
        repository.saveSubtitleSearchProvider(state.subtitleSearchProvider)
        _uiState.value = loadState().copy(savedMessage = "设置已保存")
    }

    fun saveStrmDirectory(uri: Uri) {
        /*
         * ============================================================================================================
         * 步骤1：校验 STRM 保存目录
         * ============================================================================================================
         * 目标：保持 STRM 目录与影片库目录职责分离。
         * 操作：
         * 1) 比较 SAF tree URI 对应的真实目录。
         * 2) 拒绝与影片库相同的目录，避免两个配置互相覆盖。
         */
        Log.i(TAG, "开始保存 STRM 目录")
        if (isSameTree(uri, repository.getLibraryRootUri())) {
            // 1.1 返回可见提示，不写入错误配置
            _uiState.value = loadState().copy(savedMessage = "STRM 目录不能与影片库目录相同，请选择其他目录")
            Log.i(TAG, "STRM 目录校验失败：与影片库目录相同")
            return
        }

        /*
         * ============================================================================================================
         * 步骤2：保存 STRM 目录
         * ============================================================================================================
         * 目标：只更新 STRM 配置，不触碰影片库配置。
         * 操作：
         * 1) 持久化 SAF 授权与 STRM tree URI。
         * 2) 重新加载页面状态。
         */
        repository.saveStrmTreeUri(uri)
        _uiState.value = loadState().copy(savedMessage = "STRM 保存位置已更新")
        Log.i(TAG, "STRM 目录保存完成")
    }

    fun scanLibrary(uri: Uri) {
        /*
         * ============================================================================================================
         * 步骤1：校验影片库目录
         * ============================================================================================================
         * 目标：保持影片库目录与 STRM 目录职责分离。
         * 操作：
         * 1) 比较 SAF tree URI 对应的真实目录。
         * 2) 拒绝与 STRM 相同的目录，避免扫描临时 STRM 文件。
         */
        Log.i(TAG, "开始保存影片库目录")
        if (isSameTree(uri, repository.getStrmTreeUri())) {
            // 1.1 返回可见提示，不保存也不启动扫描
            _uiState.value = loadState().copy(savedMessage = "影片库目录不能与 STRM 目录相同，请选择其他目录")
            Log.i(TAG, "影片库目录校验失败：与 STRM 目录相同")
            return
        }

        /*
         * ============================================================================================================
         * 步骤2：保存并扫描影片库目录
         * ============================================================================================================
         * 目标：只更新影片库配置，再扫描该目录中的本地影片。
         * 操作：
         * 1) 持久化 SAF 授权与影片库 tree URI。
         * 2) 异步扫描影片库并刷新页面状态。
         */
        repository.saveLibraryRootUri(uri)
        _uiState.update { it.copy(isScanning = true, savedMessage = null) }
        viewModelScope.launch {
            runCatching { movieRepository.scanLibrary(uri) }
                .onSuccess { count ->
                    _uiState.value = loadState().copy(savedMessage = "影片库扫描完成：$count 部影片")
                }
                .onFailure { error ->
                    _uiState.value = loadState().copy(savedMessage = error.message ?: "影片库扫描失败")
                }
        }
        Log.i(TAG, "影片库目录已保存，扫描任务已启动")
    }

    private fun isSameTree(uri: Uri, storedUriString: String?): Boolean {
        if (storedUriString.isNullOrBlank()) {
            return false
        }
        val storedUri = Uri.parse(storedUriString)
        val currentDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val storedDocumentId = runCatching { DocumentsContract.getTreeDocumentId(storedUri) }.getOrNull()
        return uri.authority == storedUri.authority &&
            currentDocumentId != null &&
            currentDocumentId == storedDocumentId
    }

    fun reorganizeExistingLibraries() {
        _uiState.update { it.copy(isReorganizing = true, savedMessage = null) }
        viewModelScope.launch {
            val libraryRootUri = repository.getLibraryRootUri()
            if (libraryRootUri.isNullOrBlank()) {
                _uiState.value = loadState().copy(savedMessage = "请先选择影片库目录")
                return@launch
            }
            runCatching { movieRepository.reorganizeLibraryByActorFolders(Uri.parse(libraryRootUri)) }
                .onSuccess { result ->
                    val message = if (result.hasFailures) {
                        "整理完成：移动 ${result.movedFolders} 个文件夹，重新扫描 ${result.movieCount} 部影片，${result.failedRoots.size} 个目录失败"
                    } else {
                        "整理完成：移动 ${result.movedFolders} 个文件夹，重新扫描 ${result.movieCount} 部影片"
                    }
                    _uiState.value = loadState().copy(savedMessage = message)
                }
                .onFailure { error ->
                    _uiState.value = loadState().copy(savedMessage = error.message ?: "影片库整理失败")
                }
        }
    }

    fun clearScrapeLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                scrapeRepository.clearLogs()
            }
            _uiState.update { it.copy(scrapeLog = "") }
        }
    }

    /*
     * ================================================================================
     * 步骤1：启动全库演员头像补齐
     * ================================================================================
     * 目标：让设置页可以处理当前数据库中所有影片的缺失头像。
     * 数据源：Room 中的轻量影片记录；下载和多源回退由刮削仓库负责。
     * 操作：
     * 1) 在 IO 线程读取影片和演员列表。
     * 2) 交给仓库后台任务，避免阻塞设置页。
    */
    fun updateMissingActorAvatars() {
        val state = uiState.value
        if (actorAvatarUpdateState.value.isUpdating || state.isBatchRescrapingMetadata || state.isLoadingMetadataRescrapeMovies) return
        viewModelScope.launch {
            _uiState.update { it.copy(savedMessage = "正在读取影片演员列表...") }
            runCatching { movieRepository.getMoviesForActorAvatarUpdate() }
                .onSuccess { movies ->
                    scrapeRepository.startUpdateMissingActorAvatars(
                        movies = movies,
                        forceRefresh = true,
                        allowGfriends = repository.isGfriendsActorAvatarEnabled()
                    )
                    _uiState.update {
                        it.copy(
                            savedMessage = if (repository.isGfriendsActorAvatarEnabled()) {
                                "已开始全库重匹配演员头像（包含 gfriends 兜底）"
                            } else {
                                "已开始全库重匹配演员头像（不使用 gfriends）"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(savedMessage = error.message ?: "读取影片演员列表失败")
                    }
                }
        }
    }

    /*
     * ================================================================================
     * 步骤2：准备批量多源重刮影片
     * ================================================================================
     * 目标：读取当前影片库候选，供用户选择部分影片或全库。
     * 数据源：当前影片库根目录下的 Room 轻量记录。
     * 操作：
     * 1) 只读取当前配置的影片库根目录，不跨库处理旧资料。
     * 2) 向界面提供稳定的影片 ID 和文件名，不创建元数据快照。
     */
    fun openMetadataRescrapePicker() {
        val state = uiState.value
        if (actorAvatarUpdateState.value.isUpdating || state.isBatchRescrapingMetadata || state.isLoadingMetadataRescrapeMovies) return
        viewModelScope.launch {
            Log.i("SettingsViewModel", "开始读取批量多源重刮候选")
            _uiState.update {
                it.copy(
                    isLoadingMetadataRescrapeMovies = true,
                    batchMetadataRescrapeMessage = "正在读取当前影片库...",
                    savedMessage = null
                )
            }
            try {
                // 2.1 按当前影片库根目录读取候选。
                val libraryRootUri = repository.getLibraryRootUri()
                    ?.takeIf { it.isNotBlank() }
                    ?: error("请先选择影片库目录")
                val movies = movieRepository.getMoviesForMetadataRescrape(libraryRootUri)
                metadataRescrapeMovies = movies
                if (movies.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            batchMetadataRescrapeMessage = "当前影片库没有可重刮影片",
                            savedMessage = "当前影片库没有可重刮影片"
                        )
                    }
                    return@launch
                }

                // 2.2 打开选择界面，默认全选但允许逐片取消。
                _uiState.update {
                    it.copy(
                        isMetadataRescrapePickerVisible = true,
                        metadataRescrapeCandidates = movies.map { movie ->
                            MetadataRescrapeCandidate(
                                id = movie.id,
                                displayName = movie.videoName.substringBeforeLast(".strm", movie.videoName)
                            )
                        },
                        batchMetadataRescrapeMessage = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message ?: "读取影片列表失败"
                _uiState.update {
                    it.copy(batchMetadataRescrapeMessage = message, savedMessage = message)
                }
            } finally {
                _uiState.update { it.copy(isLoadingMetadataRescrapeMovies = false) }
                Log.i("SettingsViewModel", "批量多源重刮候选读取结束")
            }
        }
    }

    fun dismissMetadataRescrapePicker() {
        Log.i("SettingsViewModel", "关闭批量多源重刮选择")
        _uiState.update { it.copy(isMetadataRescrapePickerVisible = false) }
    }

    /*
     * ================================================================================
     * 步骤3：批量多源重刮选定影片
     * ================================================================================
     * 目标：用正式自动优先链重写用户选定影片的 NFO、图片和 Room 记录。
     * 数据源：步骤2读取的当前影片库候选和用户选择的影片 ID。
     * 操作：
     * 1) 按候选原顺序逐片重刮，单片失败时记录原因并继续。
     * 2) 汇总成功和失败数量，不保存快照或额外缓存。
     */
    fun rescrapeSelectedMovieMetadata(selectedMovieIds: Set<Long>) {
        val state = uiState.value
        if (actorAvatarUpdateState.value.isUpdating || state.isBatchRescrapingMetadata) return
        val movies = metadataRescrapeMovies.filter { it.id in selectedMovieIds }
        if (movies.isEmpty()) {
            _uiState.update { it.copy(savedMessage = "请至少选择一部影片") }
            return
        }

        viewModelScope.launch {
            Log.i("SettingsViewModel", "开始批量多源重刮：${movies.size} 部")
            scrapeRepository.appendLog("开始批量多源重刮：total=${movies.size}")
            _uiState.update {
                it.copy(
                    isMetadataRescrapePickerVisible = false,
                    isBatchRescrapingMetadata = true,
                    batchMetadataRescrapeMessage = "正在重刮：0/${movies.size}",
                    savedMessage = null
                )
            }
            var succeeded = 0
            var failed = 0
            try {
                movies.forEachIndexed { index, movie ->
                    // 3.1 更新逐片进度并复用正式多源重刮链。
                    _uiState.update {
                        it.copy(batchMetadataRescrapeMessage = "正在重刮：${index + 1}/${movies.size}  ${movie.videoName}")
                    }
                    try {
                        scrapeRepository.rescrapeMovie(movie, ScrapeSource.Dmm2, automaticPriority = true)
                        movieRepository.refreshMovieRecoveringMovedStrm(movie.id)
                            ?: error("影片刷新失败：${movie.videoName}")
                        succeeded += 1
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failed += 1
                        val reason = error.message ?: error::class.java.simpleName
                        Log.w("SettingsViewModel", "批量多源重刮失败：${movie.videoName}", error)
                        scrapeRepository.appendLog("批量多源重刮失败：${movie.videoName}，$reason")
                    }
                }

                // 3.2 汇总本轮结果，失败详情保留在刮削日志。
                val result = "批量多源重刮完成：$succeeded/${movies.size}${if (failed > 0) "，失败 $failed（见刮削日志）" else ""}"
                scrapeRepository.appendLog("批量多源重刮结束：success=$succeeded, failed=$failed, total=${movies.size}")
                _uiState.update {
                    it.copy(batchMetadataRescrapeMessage = result, savedMessage = result)
                }
            } finally {
                metadataRescrapeMovies = emptyList()
                _uiState.update {
                    it.copy(
                        isBatchRescrapingMetadata = false,
                        metadataRescrapeCandidates = emptyList()
                    )
                }
                Log.i("SettingsViewModel", "批量多源重刮结束：成功 $succeeded，失败 $failed")
            }
        }
    }

    fun rebuildCloudStrmIndex() {
        _uiState.update { it.copy(isRebuildingStrmIndex = true, savedMessage = null) }
        viewModelScope.launch {
            runCatching { cloudStrmRecordRepository.rebuildIndexAndNormalizeSegments() }
                .onSuccess { result ->
                    _uiState.value = loadState().copy(
                        savedMessage = "STRM 索引已重建：${result.indexed} 个，规范化分段 ${result.renamed} 个"
                    )
                }
                .onFailure { error ->
                    _uiState.value = loadState().copy(savedMessage = error.message ?: "STRM 索引重建失败")
                }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(savedMessage = null) }
    }

    /*
     * ================================================================================
     * 步骤1：测试 DMM/FANZA 或 JavDB 当前网络
     * ================================================================================
     * 目标：让用户确认代理软件的分流规则是否生效，不替用户切换 VPN。
     * 数据源：StrmScrapeRepository 的来源连通性探测结果。
     * 操作：
     * 1) 同时只运行一个来源测试，避免把两个按钮的结果混在一起。
     * 2) 把地区限制、出口封禁、CF 验证和普通网络失败转换成可读提示。
     */
    fun testScrapeSource(source: ScrapeSource) {
        if (source !in setOf(ScrapeSource.Dmm, ScrapeSource.Dmm2, ScrapeSource.Javdb)) return
        if (_uiState.value.testingScrapeSource != null) return
        _uiState.update {
            it.copy(
                testingScrapeSource = source,
                savedMessage = null
            )
        }
        viewModelScope.launch {
            Log.i(TAG, "来源连通性测试开始：${source.name}")
            runCatching { scrapeRepository.probeScrapeSource(source) }
                .onSuccess { result ->
                    val message = formatSourceProbeMessage(source, result)
                    _uiState.update { state ->
                        state.copy(
                            testingScrapeSource = null,
                            dmmProbeMessage = if (source == ScrapeSource.Dmm || source == ScrapeSource.Dmm2) message else state.dmmProbeMessage,
                            javdbProbeMessage = if (source == ScrapeSource.Javdb) message else state.javdbProbeMessage,
                            savedMessage = message
                        )
                    }
                    Log.i(TAG, "来源连通性测试结束：${source.name}，$message")
                }
                .onFailure { error ->
                    val message = "测试失败：${error.message ?: error::class.java.simpleName}"
                    _uiState.update { state ->
                        state.copy(
                            testingScrapeSource = null,
                            dmmProbeMessage = if (source == ScrapeSource.Dmm || source == ScrapeSource.Dmm2) message else state.dmmProbeMessage,
                            javdbProbeMessage = if (source == ScrapeSource.Javdb) message else state.javdbProbeMessage,
                            savedMessage = message
                        )
                    }
                    Log.i(TAG, "来源连通性测试结束：${source.name}，$message")
                }
        }
    }

    private fun formatSourceProbeMessage(source: ScrapeSource, result: SourceProbeResult): String {
        val label = if (source == ScrapeSource.Javdb) "JavDB" else "DMM/FANZA"
        return when (result.status) {
            SourceProbeStatus.Reachable -> "$label 当前网络可访问"
            SourceProbeStatus.RegionBlocked -> "$label 被地区限制，请检查出口节点"
            SourceProbeStatus.AccessBanned -> "$label 当前出口已被站点封禁，请更换节点"
            SourceProbeStatus.CloudflareChallenge -> "$label 需要 CF 验证，请先在浏览器完成验证"
            SourceProbeStatus.NetworkError -> "$label 网络失败${result.detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
        }
    }

    private fun loadState(): SettingsUiState =
        SettingsUiState(
            cookies = repository.getCookies(),
            missavCookies = repository.getMissavCookies(),
            javdbCookies = repository.getJavdbCookies(),
            javlibraryCookies = repository.getJavlibraryCookies(),
            strmTreeUri = repository.getStrmTreeUri(),
            strmTreeDisplayName = repository.getStrmTreeDisplayName(),
            libraryRootUri = repository.getLibraryRootUri(),
            libraryRootDisplayName = repository.getLibraryRootDisplayName(),
            strmBaseUrl = repository.getStrmBaseUrl(),
            defaultScrapeSource = repository.getDefaultScrapeSource(),
            gfriendsActorAvatarEnabled = repository.isGfriendsActorAvatarEnabled(),
            imageDownloadRetryCountText = repository.getImageDownloadRetryCount().toString(),
            scrapeConcurrencyLimitText = repository.getScrapeConcurrencyLimit().toString(),
            dmm2SkippedPrefixes = repository.getDmm2SkippedNumberPrefixes().toList().sorted(),
            translateProvider = repository.getTranslateProvider(),
            baiduTranslateAppId = repository.getBaiduTranslateAppId(),
            baiduTranslateSecretKey = repository.getBaiduTranslateSecretKey(),
            deepSeekApiKey = repository.getDeepSeekApiKey(),
            deepSeekBaseUrl = repository.getDeepSeekBaseUrl(),
            deepSeekModel = repository.getDeepSeekModel(),
            deepSeekThinkingEnabled = repository.isDeepSeekThinkingEnabled(),
            deepSeekPromptEnabled = repository.isDeepSeekPromptEnabled(),
            deepSeekPromptOptions = DeepSeekPromptTemplates.options,
            deepSeekPromptTemplateId = repository.getDeepSeekPromptTemplateId(),
            deepSeekCustomPrompt = repository.getDeepSeekCustomPrompt(),
            domesticRootCidText = repository.getDomesticRootCidText(),
            domesticPageEnabled = repository.isDomesticPageEnabled(),
            libraryNoMediaEnabled = repository.isLibraryNoMediaEnabled(),
            cloudAddButtonMessageEnabled = repository.isCloudAddButtonMessageEnabled(),
            cloudExcludedVideoNames = repository.getCloudExcludedVideoNames().toList().sorted(),
            asrModelOptions = asrModelManager.availableModels(),
            selectedAsrModelId = repository.getAsrModelId(),
            asrModelBaseUrl = repository.getAsrModelBaseUrl(),
            isAsrModelReady = asrModelManager.currentStatus().isReady,
            asrModelSizeText = formatBytes(asrModelManager.currentStatus().sizeBytes),
            playerLiveSubtitleEnabled = repository.isPlayerLiveSubtitleEnabled(),
            externalSubtitleFontSizeSp = repository.getExternalSubtitleFontSizeSp(),
            externalSubtitleBottomPaddingPercent = repository.getExternalSubtitleBottomPaddingPercent(),
            externalSubtitleBackgroundAlphaPercent = repository.getExternalSubtitleBackgroundAlphaPercent(),
            subtitleSearchProvider = repository.getSubtitleSearchProvider(),
            subtitleSearchProviderOptions = SubtitleSearchProvider.entries,
            selectedCloud115LoginApp = Cloud115LoginApps.find(repository.getCloud115LoginApp()),
            savedCloud115Accounts = emptyList(),
            scrapeLog = ""
        )

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val QR_LOGIN_POLL_INTERVAL_MS = 1_500L

        fun factory(
            repository: AppSettingsRepository,
            movieRepository: MovieRepository,
            cloudStrmRecordRepository: CloudStrmRecordRepository,
            scrapeRepository: StrmScrapeRepository,
            cloud115QrLoginClient: Cloud115QrLoginClient,
            asrModelManager: AsrModelManager
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(
                        repository,
                        movieRepository,
                        cloudStrmRecordRepository,
                        scrapeRepository,
                        cloud115QrLoginClient,
                        asrModelManager
                    ) as T
            }

        private fun formatBytes(bytes: Long): String {
            val mb = bytes / 1024.0 / 1024.0
            return if (mb >= 1.0) String.format("%.1f MB", mb) else "${bytes / 1024} KB"
        }
    }
}

data class SettingsUiState(
    val cookies: String = "",
    val missavCookies: String = "",
    val javdbCookies: String = "",
    val javlibraryCookies: String = "",
    val strmTreeUri: String? = null,
    val strmTreeDisplayName: String = "尚未选择目录",
    val libraryRootUri: String? = null,
    val libraryRootDisplayName: String = "尚未选择目录",
    val strmBaseUrl: String = AppSettingsRepository.DEFAULT_STRM_BASE_URL,
    val defaultScrapeSource: ScrapeSource = ScrapeSource.Dmm2,
    val gfriendsActorAvatarEnabled: Boolean = false,
    val imageDownloadRetryCountText: String = AppSettingsRepository.DEFAULT_IMAGE_DOWNLOAD_RETRY_COUNT.toString(),
    val scrapeConcurrencyLimitText: String = AppSettingsRepository.DEFAULT_SCRAPE_CONCURRENCY_LIMIT.toString(),
    val dmm2SkippedPrefixes: List<String> = emptyList(),
    val newDmm2SkippedPrefix: String = "",
    val translateProvider: TranslateProvider = TranslateProvider.Baidu,
    val baiduTranslateAppId: String = AppSettingsRepository.DEFAULT_BAIDU_TRANSLATE_APP_ID,
    val baiduTranslateSecretKey: String = AppSettingsRepository.DEFAULT_BAIDU_TRANSLATE_SECRET_KEY,
    val deepSeekApiKey: String = "",
    val deepSeekBaseUrl: String = AppSettingsRepository.DEFAULT_DEEPSEEK_BASE_URL,
    val deepSeekModel: String = AppSettingsRepository.DEFAULT_DEEPSEEK_MODEL,
    val deepSeekThinkingEnabled: Boolean = false,
    val deepSeekPromptEnabled: Boolean = true,
    val deepSeekPromptOptions: List<DeepSeekPromptTemplate> = DeepSeekPromptTemplates.options,
    val deepSeekPromptTemplateId: String = DeepSeekPromptTemplates.DEFAULT_ID,
    val deepSeekCustomPrompt: String = "",
    val domesticRootCidText: String = "",
    val domesticPageEnabled: Boolean = false,
    val libraryNoMediaEnabled: Boolean = true,
    val cloudAddButtonMessageEnabled: Boolean = true,
    val cloudExcludedVideoNames: List<String> = emptyList(),
    val newExcludedVideoName: String = "",
    val asrModelOptions: List<AsrModelOption> = emptyList(),
    val selectedAsrModelId: String = AppSettingsRepository.DEFAULT_ASR_MODEL_ID,
    val asrModelBaseUrl: String = AppSettingsRepository.DEFAULT_ASR_MODEL_BASE_URL,
    val isAsrModelReady: Boolean = false,
    val asrModelSizeText: String = "0 KB",
    val playerLiveSubtitleEnabled: Boolean = false,
    val externalSubtitleFontSizeSp: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP,
    val externalSubtitleBottomPaddingPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT,
    val externalSubtitleBackgroundAlphaPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT,
    val subtitleSearchProvider: SubtitleSearchProvider = SubtitleSearchProvider.Xunlei,
    val subtitleSearchProviderOptions: List<SubtitleSearchProvider> = SubtitleSearchProvider.entries,
    val isAsrModelDownloading: Boolean = false,
    val asrModelDownloadProgress: Int = 0,
    val asrModelDownloadMessage: String = "",
    val selectedCloud115LoginApp: Cloud115LoginApp = Cloud115LoginApps.default,
    val savedCloud115Accounts: List<SavedCloud115Account> = emptyList(),
    val selectedCloud115AccountFileName: String? = null,
    val cloud115QrToken: Cloud115QrToken? = null,
    val isCloud115QrLoginActive: Boolean = false,
    val cloud115QrStatusText: String = "",
    val cloud115QrSavedFile: String? = null,
    val isScanning: Boolean = false,
    val isReorganizing: Boolean = false,
    val isRebuildingStrmIndex: Boolean = false,
    val isScraping: Boolean = false,
    val isLoadingMetadataRescrapeMovies: Boolean = false,
    val isMetadataRescrapePickerVisible: Boolean = false,
    val isBatchRescrapingMetadata: Boolean = false,
    val metadataRescrapeCandidates: List<MetadataRescrapeCandidate> = emptyList(),
    val batchMetadataRescrapeMessage: String? = null,
    val scrapeLog: String = "",
    val testingScrapeSource: ScrapeSource? = null,
    val dmmProbeMessage: String? = null,
    val javdbProbeMessage: String? = null,
    val savedMessage: String? = null
) {
    val hasMissavCookie: Boolean
        get() = missavCookies.isNotBlank()

    val hasJavdbCookie: Boolean
        get() = javdbCookies.isNotBlank()

    val hasJavlibraryCookie: Boolean
        get() = javlibraryCookies.isNotBlank()
}

data class MetadataRescrapeCandidate(
    val id: Long,
    val displayName: String
)

