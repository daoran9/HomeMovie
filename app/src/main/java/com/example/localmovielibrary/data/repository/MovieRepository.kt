package com.example.localmovielibrary.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrary.cloud115.Cloud115Client
import com.example.localmovielibrary.data.local.CloudStrmRecordDao
import com.example.localmovielibrary.data.local.CloudStrmRecordEntity
import com.example.localmovielibrary.data.local.MovieActorMetadataList
import com.example.localmovielibrary.data.local.MovieDao
import com.example.localmovielibrary.data.local.MovieEntity
import com.example.localmovielibrary.data.local.MovieListItem
import com.example.localmovielibrary.data.local.MoviePlaybackKeyItem
import kotlinx.coroutines.flow.map
import com.example.localmovielibrary.playback.PickcodeExtractor
import com.example.localmovielibrary.scanner.LibraryScanner
import com.example.localmovielibrary.scanner.NfoParser
import com.example.localmovielibrary.scraper.actorNameParts
import com.example.localmovielibrary.scraper.actorNameVariants
import com.example.localmovielibrary.scraper.isNonActorCategoryName
import com.example.localmovielibrary.scraper.primaryActorName
import com.example.localmovielibrary.util.detectMovieVariant
import com.example.localmovielibrary.util.extractMovieNumberInfo
import com.example.localmovielibrary.util.isEmbeddedSubtitleMarkerPart
import com.example.localmovielibrary.util.containsMetadataValue
import com.example.localmovielibrary.util.metadataKey
import com.example.localmovielibrary.util.movieKeyFromText
import com.example.localmovielibrary.util.movieVersionKeyFromText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class MovieRepository(
    private val context: Context,
    private val movieDao: MovieDao,
    private val cloudStrmRecordDao: CloudStrmRecordDao,
    private val scanner: LibraryScanner,
    private val contentResolver: ContentResolver,
    private val cloud115Client: Cloud115Client
) {
    fun observeMovies(): Flow<List<MovieEntity>> =
        movieDao.observeMovieListInvalidation()
            .map { loadMovieListItems(favoritesOnly = false) }
            .distinctUntilChanged()

    fun observeFavoriteMovies(): Flow<List<MovieEntity>> =
        movieDao.observeFavoriteMovieListInvalidation()
            .map { loadMovieListItems(favoritesOnly = true) }
            .distinctUntilChanged()

    fun observeMoviePlaybackKeys(): Flow<List<MoviePlaybackKeyItem>> =
        movieDao.observeMoviePlaybackKeyInvalidation()
            .map { loadMoviePlaybackKeys() }
            .distinctUntilChanged()

    suspend fun getLibrarySummaries(): MovieLibrarySummaries = withContext(Dispatchers.IO) {
        MovieLibrarySummaries(
            collections = summarizeTexts(movieDao.getSeriesMetadataTexts().mapNotNull { it.value }),
            actors = summarizeActors(movieDao.getActorMetadataLists()),
            tags = summarizeValues(movieDao.getTagMetadataLists().flatMap { it.items }),
            genres = summarizeValues(movieDao.getGenreMetadataLists().flatMap { it.items }),
            studios = summarizeValues(movieDao.getStudioMetadataLists().flatMap { it.items })
        )
    }

    suspend fun getMoviesForActorAvatarUpdate(): List<MovieEntity> = withContext(Dispatchers.IO) {
        movieDao.getMoviesForMetadataLookupLite()
    }

    private suspend fun loadMovieListItems(favoritesOnly: Boolean): List<MovieEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MovieListItem>()
        var offset = 0
        while (true) {
            val page = if (favoritesOnly) {
                movieDao.getFavoriteMovieListItemsPage(MOVIE_LIST_PAGE_SIZE, offset)
            } else {
                movieDao.getMovieListItemsPage(MOVIE_LIST_PAGE_SIZE, offset)
            }
            if (page.isEmpty()) break
            result += page
            if (page.size < MOVIE_LIST_PAGE_SIZE) break
            offset += MOVIE_LIST_PAGE_SIZE
        }
        result.map { it.toMovieEntity() }
    }

    private suspend fun loadMoviePlaybackKeys(): List<MoviePlaybackKeyItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MoviePlaybackKeyItem>()
        var offset = 0
        while (true) {
            val page = movieDao.getMoviePlaybackKeyItemsPage(MOVIE_LIST_PAGE_SIZE, offset)
            if (page.isEmpty()) break
            result += page
            if (page.size < MOVIE_LIST_PAGE_SIZE) break
            offset += MOVIE_LIST_PAGE_SIZE
        }
        result
    }

    fun observeMovie(id: Long): Flow<MovieEntity?> = movieDao.observeMovie(id)

    suspend fun findSimilarMovies(current: MovieEntity, limit: Int = 12): List<MovieEntity> = withContext(Dispatchers.IO) {
        val currentCode = current.similarCodeInfo()
        val currentActors = current.actors.map { it.similarNormalized() }.filter { it.isNotBlank() }.toSet()
        if (currentCode == null && currentActors.isEmpty()) return@withContext emptyList()

        val candidates = similarCandidates(current, currentCode, currentActors)
        candidates
            .asSequence()
            .filter { it.id != current.id }
            .map { movie ->
                val movieCode = movie.similarCodeInfo()
                val sameActorScore = movie.actors.count { it.similarNormalized() in currentActors }
                val samePrefix = currentCode != null && movieCode?.prefix == currentCode.prefix
                val distance = if (currentCode != null && movieCode != null && samePrefix) {
                    abs(movieCode.number - currentCode.number)
                } else {
                    Int.MAX_VALUE
                }
                val score = sameActorScore * 1000 + if (samePrefix) 250 else 0
                movie to SimilarMovieRank(score = score, distance = distance)
            }
            .filter { (_, rank) -> rank.score > 0 }
            .sortedWith(
                compareByDescending<Pair<MovieEntity, SimilarMovieRank>> { it.second.score }
                    .thenBy { it.second.distance }
                    .thenBy { pair -> pair.first.sortTitle.ifBlank { pair.first.title }.lowercase(Locale.ROOT) }
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    private suspend fun similarCandidates(
        current: MovieEntity,
        currentCode: SimilarCodeInfo?,
        currentActors: Set<String>
    ): List<MovieEntity> {
        val candidates = linkedMapOf<Long, MovieEntity>()
        currentActors
            .take(SIMILAR_ACTOR_PREFILTER_LIMIT)
            .forEach { actor ->
                movieDao.getSimilarCandidatesByActorLite("%${actor.escapeLikePattern()}%")
                    .forEach { candidates[it.id] = it }
            }
        currentCode?.let { code ->
            movieDao.getSimilarCandidatesByCodeLite("%${code.prefix.lowercase(Locale.ROOT)}%")
                .forEach { candidates[it.id] = it }
            movieDao.getSimilarCandidatesByCodeLite("%${code.prefix.uppercase(Locale.ROOT)}%")
                .forEach { candidates[it.id] = it }
        }
        return candidates.values.toList()
    }

    suspend fun searchMovies(query: String, scope: String): List<MovieEntity> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isBlank()) return@withContext emptyList()
        val pattern = "%${text.escapeLikePattern()}%"
        val normalizedScope = scope.lowercase(Locale.ROOT)
        val directMatches = when (normalizedScope) {
            "title" -> movieDao.searchMoviesByTitleLite(pattern)
            "actor" -> filterActorMovies(movieDao.getMoviesForMetadataLookupLite(), text, exact = false)
            "tag" -> filterMetadataMovies(movieDao.getMoviesForTagLookupLite(pattern), text, exact = false) { it.tags }
            "genre" -> filterMetadataMovies(movieDao.getMoviesForGenreLookupLite(pattern), text, exact = false) { it.genres }
            else -> movieDao.searchMoviesLite(pattern)
        }
        val numberQuery = movieNumberSearchQuery(text)
            ?: return@withContext directMatches
        if (normalizedScope !in setOf("all", "title")) return@withContext directMatches

        /*
         * ================================================================================
         * 步骤1：补充番号分隔符搜索
         * ================================================================================
         * 目标：让 NAMH 022、NAMH-022 和 NAMH022 命中同一影片。
         * 数据源：标题、原始标题和文件名中的标准化番号。
         * 操作：
         * 1) 用前缀和数字构造可跨连字符、空格和下划线的 SQL 候选查询。
         * 2) 再按解析出的完整番号过滤，避免 NAMH-1022 等近似结果混入。
         */
        val numberMatches = when (normalizedScope) {
            "title" -> movieDao.searchMoviesByTitleLite(numberQuery.candidateLikePattern)
            else -> movieDao.searchMoviesLite(numberQuery.candidateLikePattern)
        }.filter { movie -> movie.matchesMovieNumber(numberQuery.number) }
        (numberMatches + directMatches)
            .distinctBy { it.id }
            .sortedBy { it.sortTitle.ifBlank { it.title }.lowercase(Locale.ROOT) }
    }

    suspend fun filterMovies(type: String, value: String): List<MovieEntity> = withContext(Dispatchers.IO) {
        val text = value.trim()
        if (text.isBlank()) return@withContext emptyList()
        val pattern = "%${text.escapeLikePattern()}%"
        when (type.lowercase(Locale.ROOT)) {
            "actor" -> filterActorMovies(movieDao.getMoviesForMetadataLookupLite(), text, exact = true)
            "tag" -> filterMetadataMovies(movieDao.getMoviesForTagLookupLite(pattern), text, exact = true) { it.tags }
            "genre" -> filterMetadataMovies(movieDao.getMoviesForGenreLookupLite(pattern), text, exact = true) { it.genres }
            "year" -> movieDao.searchMoviesByYearLite(text)
            "studio" -> filterMetadataMovies(movieDao.getMoviesForStudioLookupLite(pattern), text, exact = true) { it.studios }
            "collection" -> filterCollectionMovies(text, exact = true)
            else -> emptyList()
        }
    }

    private suspend fun filterMetadataMovies(
        candidates: List<MovieEntity>,
        value: String,
        exact: Boolean,
        values: (MovieEntity) -> List<String>
    ): List<MovieEntity> {
        return candidates
            .filter { movie -> values(movie).containsMetadataValue(value, exact) }
            .sortedBy { it.sortTitle.ifBlank { it.title }.lowercase(Locale.ROOT) }
    }

    private fun filterActorMovies(
        candidates: List<MovieEntity>,
        value: String,
        exact: Boolean
    ): List<MovieEntity> {
        val matchingMovieIds = actorIdentityMovieIds(
            candidates.map { movie -> MovieActorMetadataList(movie.id, movie.actors) },
            value,
            exact
        )
        return candidates
            .filter { movie -> movie.id in matchingMovieIds }
            .sortedBy { it.sortTitle.ifBlank { it.title }.lowercase(Locale.ROOT) }
    }

    private suspend fun filterCollectionMovies(value: String, exact: Boolean): List<MovieEntity> {
        val query = value.metadataKey()
        if (query.isBlank()) return emptyList()
        val pattern = "%${value.escapeLikePattern()}%"
        return movieDao.getMoviesForCollectionLookupLite(pattern)
            .filter { movie ->
                val candidate = movie.series.orEmpty().metadataKey()
                candidate.isNotBlank() && if (exact) candidate == query else candidate.contains(query)
            }
            .sortedBy { it.sortTitle.ifBlank { it.title }.lowercase(Locale.ROOT) }
    }

    suspend fun scanLibrary(rootUri: Uri): Int {
        return withContext(Dispatchers.IO) {
            contentResolver.takePersistableUriPermission(
                rootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val movies = scanner.scan(rootUri)
            val existingMovies = movieDao.getMoviesByLibraryRootLite(rootUri.toString())
            val allMovies = movieDao.getMoviesSnapshotLite()
            val existingByUri = allMovies.associateBy { it.videoUri }
            val existingById = allMovies.associateBy { it.id }
            val existingMovieIds = allMovies.map { it.id }.filter { it > 0 }
            val cloudRecords = (
                cloudStrmRecordDao.getByLibraryRoot(rootUri.toString()) +
                    existingMovieIds.takeIf { it.isNotEmpty() }?.let { cloudStrmRecordDao.getByMovieIds(it) }.orEmpty()
                ).distinctBy { it.pickcode }
            val existingByPickcode = buildExistingMoviePickcodeMap(allMovies, existingById, cloudRecords)
            val existingByNumber = buildExistingMovieNumberMap(allMovies, rootUri.toString())
            val matchedExistingIds = linkedSetOf<Long>()
            val synchronizedMovies = movies.map { scanned ->
                val pickcode = scanned.extractPickcodeFromStrm()
                val old = pickcode?.let { existingByPickcode[it] }
                    ?: existingByUri[scanned.videoUri]
                    ?: scanned.movieNumberKey()?.let { existingByNumber[it] }
                if (old == null) {
                    scanned
                } else {
                    matchedExistingIds += old.id
                    scanned.copy(
                        id = old.id,
                        isFavorite = old.isFavorite,
                        isWatched = old.isWatched,
                        scannedAtMillis = old.scannedAtMillis,
                        updatedAt = old.updatedAt
                    )
                }
            }
            val removedCurrentRootIds = existingMovies
                .asSequence()
                .map { it.id }
                .filter { it !in matchedExistingIds }
                .toList()
            val staleMovedDuplicateIds = findStaleMovedDuplicateIds(
                allMovies = allMovies,
                currentLibraryRootUri = rootUri.toString(),
                scannedMovies = synchronizedMovies,
                cloudRecords = cloudRecords,
                protectedIds = matchedExistingIds + removedCurrentRootIds
            )
            val removedIds = (removedCurrentRootIds + staleMovedDuplicateIds).distinct()
            movieDao.synchronizeLibraryMovies(removedIds, synchronizedMovies)
            if (removedIds.isNotEmpty()) {
                cloudStrmRecordDao.deleteByMovieIds(removedIds)
            }
            updateCloudStrmRecordsAfterScan(movies, rootUri.toString(), cloudRecords)
            movies.size
        }
    }

    private fun buildExistingMovieNumberMap(
        movies: List<MovieEntity>,
        currentLibraryRootUri: String
    ): Map<String, MovieEntity> {
        val result = linkedMapOf<String, MovieEntity>()
        movies
            .sortedBy { movie -> if (movie.libraryRootUri == currentLibraryRootUri) 0 else 1 }
            .forEach { movie ->
                movie.movieNumberKey()?.let { key ->
                    result.putIfAbsent(key, movie)
                }
            }
        return result
    }

    private fun findStaleMovedDuplicateIds(
        allMovies: List<MovieEntity>,
        currentLibraryRootUri: String,
        scannedMovies: List<MovieEntity>,
        cloudRecords: List<CloudStrmRecordEntity>,
        protectedIds: Set<Long>
    ): List<Long> {
        val scannedKeys = scannedMovies.mapNotNull { it.movieNumberKey() }.toSet()
        val scannedPickcodes = scannedMovies.mapNotNull { it.extractPickcodeFromStrm() }.toSet()
        val pickcodeByMovieId = cloudRecords
            .mapNotNull { record -> record.movieId?.let { it to record.pickcode } }
            .groupBy({ it.first }, { it.second })
        return allMovies
            .asSequence()
            .filter { it.libraryRootUri != currentLibraryRootUri }
            .filter { it.id !in protectedIds }
            .filter { movie ->
                val sameNumber = movie.movieNumberKey()?.let { it in scannedKeys } == true
                val samePickcode = pickcodeByMovieId[movie.id]?.any { it in scannedPickcodes } == true
                (samePickcode || sameNumber) && !canOpenUri(movie.videoUri)
            }
            .map { it.id }
            .toList()
    }

    suspend fun reorganizeExistingLibraries(): LibraryReorganizeResult = withContext(Dispatchers.IO) {
        val rootUris = movieDao.getLibraryRootUris()

        var refreshedMovies = 0
        var movedFolders = 0
        val failedRoots = mutableListOf<String>()

        rootUris.forEach { rootUri ->
            runCatching { reorganizeLibraryByActorFolders(Uri.parse(rootUri)) }
                .onSuccess { result ->
                    refreshedMovies += result.movieCount
                    movedFolders += result.movedFolders
                }
                .onFailure { error ->
                    val message = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
                    failedRoots += "$rootUri: $message"
                }
        }

        LibraryReorganizeResult(
            rootCount = rootUris.size,
            movieCount = refreshedMovies,
            movedFolders = movedFolders,
            failedRoots = failedRoots
        )
    }

    suspend fun reorganizeLibraryByActorFolders(rootUri: Uri): LibraryReorganizeResult = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                rootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: error("影片库目录不可用")
        if (!root.canWrite()) error("影片库目录没有写入权限")

        var movedFolders = 0
        root.listFiles()
            .filter { it.isDirectory }
            .filter { it.isRootMovieDirectory() }
            .forEach { movieDirectory ->
                val groupName = movieDirectory.actorGroupFolderName()
                val actorDirectory = root.findOrCreateDirectory(groupName)
                if (actorDirectory.uri == movieDirectory.uri) return@forEach
                val targetName = actorDirectory.uniqueChildDirectoryName(movieDirectory.name.orEmpty())
                val copied = copyDirectoryRecursively(movieDirectory, actorDirectory, targetName)
                if (copied != null) {
                    deleteRecursively(movieDirectory)
                    movedFolders += 1
                }
            }

        val count = scanLibrary(rootUri)
        LibraryReorganizeResult(rootCount = 1, movieCount = count, movedFolders = movedFolders)
    }

    suspend fun setFavorite(movieId: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        movieDao.setFavorite(movieId, isFavorite, System.currentTimeMillis())
    }

    suspend fun setWatched(movieId: Long, isWatched: Boolean) = withContext(Dispatchers.IO) {
        movieDao.setWatched(movieId, isWatched, System.currentTimeMillis())
    }

    suspend fun deleteMovie(movieId: Long) = withContext(Dispatchers.IO) {
        movieDao.deleteById(movieId)
    }

    /*
     * ================================================================================
     * 步骤3：删除本地 STRM 影片
     * ================================================================================
     * 目标：只删除当前影片的文件，不能把共享演员目录当成影片目录。
     * 数据源：Room 影片 URI、SAF 文档树和 STRM 中记录的 pickcode。
     * 操作：
     * 1) 目录名能确认属于同一番号时，删除完整影片目录及其多播放源。
     * 2) 根层或共享演员目录中的单个 STRM 只删除该文件。
     * 3) SAF 删除失败时保留 Room 和网盘索引，向界面返回失败原因。
     */
    suspend fun deleteMovieWithFiles(movieId: Long): DeleteMovieResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始删除本地影片：movieId=$movieId")
        val movie = movieDao.getMovieLite(movieId)
        val pickcodes = linkedSetOf<String>()
        if (movie != null && movie.videoName.endsWith(".strm", ignoreCase = true)) {
            val rootUri = Uri.parse(movie.libraryRootUri)
            val videoUri = Uri.parse(movie.videoUri)
            val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull()
                ?: return@withContext DeleteMovieResult.failed(movieId, "影片库目录 URI 无效，未删除本地记录")
            val videoDocumentId = runCatching { DocumentsContract.getDocumentId(videoUri) }.getOrNull()
                ?: return@withContext DeleteMovieResult.failed(movieId, "本地 STRM URI 无效，未删除本地记录")
            if (!isDocumentWithinTree(rootDocumentId, videoDocumentId)) {
                return@withContext DeleteMovieResult.failed(movieId, "本地 STRM 不在当前影片库目录中，未删除本地记录")
            }
            val target = DocumentFile.fromSingleUri(context, videoUri)
                ?.takeIf { it.isFile }
                ?: return@withContext DeleteMovieResult.failed(movieId, "未找到本地 STRM，未删除本地记录")
            val parentDocumentId = videoDocumentId.substringBeforeLast('/', missingDelimiterValue = "")
            val directParent = parentDocumentId
                .takeIf { it.isNotBlank() && it != rootDocumentId }
                ?.let { documentId ->
                    DocumentFile.fromSingleUri(
                        context,
                        DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)
                    )
                }
            val movieDirectory = dedicatedMovieDirectoryDocumentId(
                rootDocumentId = rootDocumentId,
                videoDocumentId = videoDocumentId,
                parentName = directParent?.name,
                videoName = movie.videoName
            )?.let { directParent }
            val filesToRead = movieDirectory?.listFiles()
                ?.filter { it.isFile && it.name.orEmpty().endsWith(".strm", ignoreCase = true) }
                ?: listOf(target)
            filesToRead.forEach { file ->
                readPickcode(file)?.let { pickcodes += it }
            }
            val deleted = movieDirectory?.let(::deleteRecursively) ?: target.delete()
            if (!deleted) {
                Log.w(TAG, "删除本地影片失败：movieId=$movieId, uri=${target.uri}")
                return@withContext DeleteMovieResult.failed(movieId, "本地文件删除失败，未删除本地记录")
            }
        }
        movieDao.deleteById(movieId)
        Log.i(TAG, "本地影片删除完成：movieId=$movieId, pickcodes=${pickcodes.size}")
        DeleteMovieResult(movieId = movieId, pickcodes = pickcodes)
    }

    suspend fun refreshMovie(movieId: Long): Boolean = withContext(Dispatchers.IO) {
        val old = movieDao.getMovieLite(movieId) ?: return@withContext false
        val rootUri = Uri.parse(old.libraryRootUri)
        val refreshed = scanner.scanFile(rootUri, Uri.parse(old.videoUri))
            ?: findMovedMovieStrmUri(old)?.let { movedUri ->
                scanner.scanFile(rootUri, movedUri)
            }
            ?: return@withContext false

        movieDao.upsert(
            refreshed.copy(
                id = old.id,
                isFavorite = old.isFavorite,
                isWatched = old.isWatched,
                updatedAt = System.currentTimeMillis()
            )
        )
        true
    }

    suspend fun refreshMovieRecoveringMovedStrm(movieId: Long): MovieEntity? = withContext(Dispatchers.IO) {
        val old = movieDao.getMovieLite(movieId) ?: return@withContext null
        val rootUri = Uri.parse(old.libraryRootUri)
        val refreshed = scanner.scanFile(rootUri, Uri.parse(old.videoUri))
            ?: findMovedMovieStrmUri(old)?.let { movedUri ->
                scanner.scanFile(rootUri, movedUri)
            }
            ?: return@withContext null

        val movie = refreshed.copy(
            id = old.id,
            isFavorite = old.isFavorite,
            isWatched = old.isWatched,
            scannedAtMillis = old.scannedAtMillis,
            updatedAt = System.currentTimeMillis()
        )
        movieDao.upsert(movie)
        movie
    }

    suspend fun scanSingleMovie(rootUri: Uri, videoUri: Uri, mergeByMovieNumber: Boolean = true): MovieEntity? = withContext(Dispatchers.IO) {
        val scanned = scanner.scanFile(rootUri, videoUri) ?: return@withContext null
        val pickcode = scanned.extractPickcodeFromStrm()
        val old = pickcode?.let { pick ->
            cloudStrmRecordDao.get(pick)?.movieId?.let { movieDao.getMovieLite(it) }
        }
            ?: movieDao.getMovieByVideoUriLite(scanned.videoUri)
            ?: scanned.movieNumberKey()?.takeIf { mergeByMovieNumber }?.let { key ->
                movieDao.getMovieNumberCandidatesByLibraryRootLite(rootUri.toString(), key.movieNumberCandidateLikePattern())
                    .firstOrNull { it.movieNumberKey() == key }
            }
        val movie = old?.let {
            scanned.copy(
                id = it.id,
                isFavorite = it.isFavorite,
                isWatched = it.isWatched,
                scannedAtMillis = it.scannedAtMillis,
                updatedAt = it.updatedAt
            )
        } ?: scanned
        movieDao.upsert(movie)
        val saved = movieDao.getMovieByVideoUriLite(movie.videoUri) ?: movie
        if (pickcode != null) {
            updateCloudStrmRecordLocation(pickcode, saved, rootUri.toString())
        }
        saved
    }

    suspend fun findMovieByNumber(rootUri: String, number: String): MovieEntity? = withContext(Dispatchers.IO) {
        val normalized = number.movieNumberKeyFromText() ?: number.uppercase(Locale.ROOT)
        movieDao.getMovieNumberCandidatesByLibraryRootLite(rootUri, normalized.movieNumberCandidateLikePattern())
            .firstOrNull { it.movieNumberKey() == normalized }
    }

    suspend fun findMovieByNumberAndVariant(rootUri: String, number: String, sourceText: String): MovieEntity? = withContext(Dispatchers.IO) {
        val normalized = number.movieNumberKeyFromText() ?: number.uppercase(Locale.ROOT)
        val expectedKey = normalized + detectMovieVariant(sourceText).suffix
        movieDao.getMovieNumberCandidatesByLibraryRootLite(rootUri, expectedKey.movieNumberCandidateLikePattern())
            .firstOrNull { movie -> movie.movieNumberKey() == expectedKey }
    }

    suspend fun getPlaybackParts(movieId: Long): List<MoviePlaybackPart> = withContext(Dispatchers.IO) {
        val movie = movieDao.getMovieLite(movieId) ?: return@withContext emptyList()
        val indexedParts = getIndexedPlaybackParts(movie)
        if (indexedParts.isNotEmpty()) return@withContext indexedParts

        val root = DocumentFile.fromTreeUri(context, Uri.parse(movie.libraryRootUri)) ?: return@withContext movie.singlePart()
        val target = findFileWithParentFast(root, movie.libraryRootUri, movie.videoUri)
            ?: findFileWithParent(root, movie.videoUri)
            ?: findStrmWithParentByMovieNumber(root, movie)
            ?: return@withContext movie.singlePart()
        val number = movie.videoName.movieNumberKeyFromText()
            ?: movie.title.movieNumberKeyFromText()
            ?: return@withContext movie.singlePart()
        val parts = target.parent.listFiles()
            .filter { it.isFile && it.isSupportedVideoFile() }
            .filter { it.name.orEmpty().movieNumberKeyFromText() == number }
            .map { file ->
                MoviePlaybackPart(
                    label = file.name.orEmpty().playbackPartUiLabel(),
                    videoUri = file.uri.toString(),
                    fileName = file.name.orEmpty()
                )
            }
            .distinctBy { it.videoUri }
            .sortedWith(compareBy<MoviePlaybackPart> { it.label.playbackPartUiSortKey() }.thenBy { it.fileName.lowercase() })
        parts.ifEmpty { movie.singlePart() }
    }

    private suspend fun getIndexedPlaybackParts(movie: MovieEntity): List<MoviePlaybackPart> {
        val number = extractMovieNumberInfo(movie.videoName)?.number
            ?: extractMovieNumberInfo(movie.title)?.number
            ?: movie.videoName.movieNumberKeyFromText()
            ?: movie.title.movieNumberKeyFromText()
        val records = if (number != null) {
            cloudStrmRecordDao.getPlaybackRecords(movie.id, number, movie.libraryRootUri, movie.videoUri)
        } else {
            cloudStrmRecordDao.getByMovieId(movie.id)
        }

        /*
         * ================================================================================
         * 步骤1：从实际 STRM 内容构造播放源
         * ================================================================================
         * 目标：保留每个数据库 pickcode 对应的播放源，并修复历史 STRM 的错误地址。
         * 数据源：每条 CloudStrmRecord 对应的 STRM 文本地址。
         * 操作：
         * 1) 数据库 pickcode 是播放源身份，STRM 内容只用于校验和读取旧名称。
         * 2) 发现两者不一致时按记录 pickcode 修复 STRM，并补齐 115 文件大小。
         */
        val parts = buildList {
            records.filter { it.strmUri.isNotBlank() }.forEach { record ->
                resolveIndexedPlaybackPart(record)?.let(::add)
            }
        }
            .distinctBy { it.sourceKey }
            .sortedWith(
                compareBy<MoviePlaybackPart> { it.label.playbackPartUiSortKey() }
                    .thenBy { it.fileName.lowercase(Locale.ROOT) }
                    .thenBy { it.sourceKey }
            )
        val labelCounts = parts.groupingBy { it.label }.eachCount()
        val labelOccurrences = mutableMapOf<String, Int>()
        return parts.map { part ->
            if (labelCounts[part.label] == 1) return@map part
            val index = labelOccurrences.getOrDefault(part.label, 0) + 1
            labelOccurrences[part.label] = index
            part.copy(label = "${part.label} $index")
        }
    }

    private suspend fun resolveIndexedPlaybackPart(record: CloudStrmRecordEntity): MoviePlaybackPart? {
        val content = readStrmText(record.strmUri) ?: return null
        val strmSource = parseStrmPlaybackSource(content)
        val strmPickcode = strmSource?.pickcode
        val pickcodeMismatch = strmPickcode?.equals(record.pickcode, ignoreCase = true) != true
        val needsMetadata = pickcodeMismatch || record.sourceName.isNullOrBlank() || record.sourceSizeBytes == null
        val metadata = if (needsMetadata) {
            runCatching { cloud115Client.fetchVideoInfo(record.pickcode) }
                .onFailure { error ->
                    Log.i(TAG, "115播放源信息读取失败，pickcode=${record.pickcode}，原因=${error.message}")
                }
                .getOrNull()
        } else {
            null
        }
        val sourceName = metadata?.name
            ?: record.sourceName
            ?: strmSource?.fileName
            ?: record.fileName
        val sourceSizeBytes = metadata?.sizeBytes ?: record.sourceSizeBytes

        if (metadata != null && (record.sourceName != sourceName || record.sourceSizeBytes != sourceSizeBytes)) {
            cloudStrmRecordDao.upsert(
                record.copy(
                    sourceName = sourceName,
                    sourceSizeBytes = sourceSizeBytes,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        if (pickcodeMismatch) {
            val repairedContent = replaceStrmPlaybackSource(content, record.pickcode, sourceName) ?: return null
            if (!writeStrmText(record.strmUri, repairedContent)) {
                Log.i(TAG, "115播放源 STRM 修复失败，pickcode=${record.pickcode}")
                return null
            }
            Log.i(TAG, "已修复历史 STRM 播放源，pickcode=${record.pickcode}")
        }
        return MoviePlaybackPart(
            label = sourceName.playbackPartUiLabel(),
            videoUri = record.strmUri,
            fileName = sourceName,
            sourceKey = record.pickcode.lowercase(Locale.ROOT),
            sourceSizeBytes = sourceSizeBytes
        )
    }

    private fun readStrmText(uriString: String): String? =
        runCatching {
            contentResolver.openInputStream(Uri.parse(uriString))
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        }.getOrNull()

    private fun writeStrmText(uriString: String, content: String): Boolean =
        runCatching {
            contentResolver.openOutputStream(Uri.parse(uriString), "wt")?.bufferedWriter(Charsets.UTF_8)?.use { output ->
                output.write(content)
            } != null
        }.getOrDefault(false)

    private fun canOpenUri(uriString: String): Boolean =
        runCatching {
            contentResolver.openInputStream(Uri.parse(uriString))?.use { true } == true
        }.getOrDefault(false)

    private fun findFileWithParent(directory: DocumentFile, videoUri: String): FileWithParent? {
        directory.listFiles().forEach { child ->
            if (child.isFile && child.uri.toString() == videoUri) {
                return FileWithParent(parent = directory, file = child)
            }
            if (child.isDirectory) {
                findFileWithParent(child, videoUri)?.let { return it }
            }
        }
        return null
    }

    private fun findFileWithParentFast(root: DocumentFile, rootUriString: String, videoUriString: String): FileWithParent? {
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
        val fileName = segments.last()
        val parent = segments.dropLast(1).fold(root as DocumentFile?) { directory, segment ->
            directory?.findFile(segment)?.takeIf { it.isDirectory }
        } ?: return null
        val file = parent.findFile(fileName)?.takeIf { it.isFile } ?: return null
        return FileWithParent(parent = parent, file = file)
    }

    private fun findMovedMovieStrmUri(movie: MovieEntity): Uri? {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(movie.libraryRootUri)) ?: return null
        return findStrmWithParentByMovieNumber(root, movie)?.file?.uri
    }

    private fun findStrmWithParentByMovieNumber(root: DocumentFile, movie: MovieEntity): FileWithParent? {
        val number = extractMovieNumberInfo(movie.videoName)?.number
            ?: extractMovieNumberInfo(movie.title)?.number
            ?: movie.videoName.movieNumberKeyFromText()
            ?: movie.title.movieNumberKeyFromText()
            ?: return null
        val expectedVariant = detectMovieVariant(movie.videoName)
        fun walk(directory: DocumentFile): FileWithParent? {
            directory.listFiles().forEach { child ->
                if (child.isDirectory) {
                    walk(child)?.let { return it }
                } else if (
                    child.isFile &&
                    child.name.orEmpty().endsWith(".strm", ignoreCase = true) &&
                    child.name.orEmpty().contains(number, ignoreCase = true) &&
                    detectMovieVariant(child.name.orEmpty()) == expectedVariant
                ) {
                    return FileWithParent(directory, child)
                }
            }
            return null
        }
        return walk(root)
    }

    private fun readPickcode(file: DocumentFile): String? {
        val content = runCatching {
            context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        }.getOrNull().orEmpty()
        return PickcodeExtractor.extract(content)
    }

    private fun MovieEntity.extractPickcodeFromStrm(): String? {
        if (!videoName.endsWith(".strm", ignoreCase = true) && !videoUri.endsWith(".strm", ignoreCase = true)) {
            return null
        }
        val content = runCatching {
            contentResolver.openInputStream(Uri.parse(videoUri))
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        }.getOrNull().orEmpty()
        return PickcodeExtractor.extract(content)
    }

    private fun buildExistingMoviePickcodeMap(
        existingMovies: List<MovieEntity>,
        existingById: Map<Long, MovieEntity>,
        cloudRecords: List<CloudStrmRecordEntity>
    ): Map<String, MovieEntity> {
        val result = linkedMapOf<String, MovieEntity>()
        cloudRecords.forEach { record ->
            val movie = record.movieId?.let { existingById[it] }
                ?: existingMovies.firstOrNull { it.videoUri == record.strmUri }
                ?: return@forEach
            result[record.pickcode] = movie
        }
        existingMovies.forEach { movie ->
            val pickcode = movie.extractPickcodeFromStrm() ?: return@forEach
            result.putIfAbsent(pickcode, movie)
        }
        return result
    }

    private suspend fun updateCloudStrmRecordsAfterScan(
        scannedMovies: List<MovieEntity>,
        libraryRootUri: String,
        existingRecords: List<CloudStrmRecordEntity>
    ) {
        val existingByPickcode = existingRecords.associateBy { it.pickcode }
        scannedMovies.forEach { scanned ->
            val pickcode = scanned.extractPickcodeFromStrm() ?: return@forEach
            val saved = movieDao.getMovieByVideoUriLite(scanned.videoUri) ?: return@forEach
            val existing = existingByPickcode[pickcode]
            if (existing == null) {
                val info = extractMovieNumberInfo(saved.videoName) ?: extractMovieNumberInfo(saved.title)
                cloudStrmRecordDao.upsert(
                    CloudStrmRecordEntity(
                        pickcode = pickcode,
                        fileName = saved.videoName,
                        movieNumber = info?.number,
                        variant = detectMovieVariant(saved.videoName).suffix.takeIf { it.isNotBlank() },
                        partLabel = info?.partLabel,
                        strmUri = saved.videoUri,
                        libraryRootUri = libraryRootUri,
                        movieId = saved.id,
                        createdAt = saved.scannedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else if (existing.strmUri != saved.videoUri || existing.movieId != saved.id || existing.libraryRootUri != libraryRootUri) {
                cloudStrmRecordDao.upsert(
                    existing.copy(
                        fileName = saved.videoName,
                        strmUri = saved.videoUri,
                        libraryRootUri = libraryRootUri,
                        movieId = saved.id,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private suspend fun updateCloudStrmRecordLocation(pickcode: String, movie: MovieEntity, libraryRootUri: String) {
        val existing = cloudStrmRecordDao.get(pickcode)
        val info = extractMovieNumberInfo(movie.videoName) ?: extractMovieNumberInfo(movie.title)
        val now = System.currentTimeMillis()
        cloudStrmRecordDao.upsert(
            (existing ?: CloudStrmRecordEntity(
                pickcode = pickcode,
                fileName = movie.videoName,
                movieNumber = info?.number,
                variant = detectMovieVariant(movie.videoName).suffix.takeIf { it.isNotBlank() },
                partLabel = info?.partLabel,
                strmUri = movie.videoUri,
                libraryRootUri = libraryRootUri,
                movieId = movie.id,
                createdAt = movie.scannedAtMillis.takeIf { it > 0 } ?: now,
                updatedAt = now
            )).copy(
                fileName = movie.videoName,
                movieNumber = info?.number ?: existing?.movieNumber,
                variant = detectMovieVariant(movie.videoName).suffix.takeIf { it.isNotBlank() },
                partLabel = info?.partLabel,
                strmUri = movie.videoUri,
                libraryRootUri = libraryRootUri,
                movieId = movie.id,
                updatedAt = now
            )
        )
    }

    private fun deleteRecursively(file: DocumentFile): Boolean {
        if (file.isDirectory) {
            val childrenDeleted = file.listFiles().all(::deleteRecursively)
            if (!childrenDeleted) return false
        }
        return file.delete()
    }

    private fun cleanupEmptyActorDirectory(actorDirectory: DocumentFile?, root: DocumentFile) {
        if (actorDirectory == null) return
        if (actorDirectory.uri == root.uri) return
        if (!actorDirectory.isDirectory) return
        if (!actorDirectory.isAutoOrganizedActorDirectory()) return
        if (actorDirectory.listFiles().isEmpty()) {
            actorDirectory.delete()
        }
    }

    private fun DocumentFile.isAutoOrganizedActorDirectory(): Boolean {
        val name = name.orEmpty().trim()
        if (name.isBlank()) return false
        if (name == "\u591A\u4EBA\u4F5C\u54C1" || name == "\u672A\u77E5\u6F14\u5458") return true
        return listFiles().isEmpty() && !name.contains(Regex("""[\\/:*?"<>|]"""))
    }

    private fun DocumentFile.isRootMovieDirectory(): Boolean {
        val name = name.orEmpty()
        if (!name.startsWith("\u3010") && !name.startsWith("[")) return false
        if (name.movieNumberKeyFromText() == null) return false
        return listFiles().any { child ->
            child.isFile && (child.isSupportedVideoFile() || child.name.orEmpty().endsWith(".nfo", ignoreCase = true))
        }
    }

    private fun DocumentFile.actorGroupFolderName(): String {
        val actorsFromNfo = firstNfoFile()
            ?.let { NfoParser(contentResolver).parse(it.uri).actors }
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (actorsFromNfo.size > 1) return "\u591A\u4EBA\u4F5C\u54C1"
        if (actorsFromNfo.size == 1) return actorsFromNfo.first().sanitizeDocumentName()

        val actorFromFolder = name.orEmpty().extractBracketActorName()
        return actorFromFolder?.sanitizeDocumentName()?.takeIf { it.isNotBlank() } ?: "\u672A\u77E5\u6F14\u5458"
    }

    private fun DocumentFile.firstNfoFile(): DocumentFile? =
        listFiles().firstOrNull { it.isFile && it.name.orEmpty().endsWith(".nfo", ignoreCase = true) }

    private fun DocumentFile.findOrCreateDirectory(name: String): DocumentFile {
        findFile(name)?.let { existing ->
            if (existing.isDirectory) return existing
        }
        return createDirectory(name) ?: error("Unable to create directory: $name")
    }

    private fun DocumentFile.uniqueChildDirectoryName(baseName: String): String {
        val safeBaseName = baseName.sanitizeDocumentName().ifBlank { "movie" }
        if (findFile(safeBaseName) == null) return safeBaseName
        var index = 1
        while (true) {
            val candidate = "$safeBaseName-$index"
            if (findFile(candidate) == null) return candidate
            index += 1
        }
    }

    private fun copyDirectoryRecursively(source: DocumentFile, targetParent: DocumentFile, targetName: String): DocumentFile? {
        val target = targetParent.createDirectory(targetName) ?: return null
        var success = true
        source.listFiles().forEach { child ->
            success = if (child.isDirectory) {
                copyDirectoryRecursively(child, target, child.name.orEmpty().sanitizeDocumentName()) != null && success
            } else {
                copyFile(child, target) && success
            }
        }
        if (!success) {
            deleteRecursively(target)
            return null
        }
        return target
    }

    private fun copyFile(source: DocumentFile, targetParent: DocumentFile): Boolean {
        val fileName = source.name.orEmpty().takeIf { it.isNotBlank() } ?: return false
        val target = targetParent.createFile("application/octet-stream", fileName) ?: return false
        return runCatching {
            contentResolver.openInputStream(source.uri)?.use { input ->
                contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            true
        }.getOrDefault(false)
    }
}

data class MovieLibrarySummaries(
    val collections: List<MovieMetadataSummary> = emptyList(),
    val actors: List<MovieMetadataSummary> = emptyList(),
    val tags: List<MovieMetadataSummary> = emptyList(),
    val genres: List<MovieMetadataSummary> = emptyList(),
    val studios: List<MovieMetadataSummary> = emptyList()
)

data class MovieMetadataSummary(
    val value: String,
    val count: Int
)

data class MoviePlaybackPart(
    val label: String,
    val videoUri: String,
    val fileName: String,
    val sourceKey: String = videoUri,
    val sourceSizeBytes: Long? = null
)

internal data class StrmPlaybackSource(
    val pickcode: String,
    val fileName: String
)

/*
 * ================================================================================
 * 步骤1：解析 STRM 的 115 播放地址
 * ================================================================================
 * 目标：从下载地址恢复可读的网盘原始文件名和实际 pickcode。
 * 数据源：STRM 首个非空文本行，格式为 /download_m3u/{pickcode}/{fileName}。
 * 操作：
 * 1) 定位受支持的播放路由和后续两个路径段。
 * 2) 保留路径中的加号，并解码百分号编码后的文件名。
 */
internal fun parseStrmPlaybackSource(content: String): StrmPlaybackSource? {
    // 1.1 STRM 的有效地址始终位于首个非空行，避免把尾部注释当作 URL。
    val address = content.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() } ?: return null
    // 1.2 路由后的第一段是 pickcode，第二段是 Uri.encode 写入的原始文件名。
    val match = STRM_PLAYBACK_SOURCE_PATTERN.find(address) ?: return null
    val pickcode = match.groupValues[1].trim().takeIf { it.isNotBlank() } ?: return null
    val fileName = decodeStrmPathSegment(match.groupValues[2]).trim().takeIf { it.isNotBlank() } ?: return null
    return StrmPlaybackSource(pickcode = pickcode, fileName = fileName)
}

/*
 * ================================================================================
 * 步骤2：恢复错误 STRM 的播放身份
 * ================================================================================
 * 目标：把历史文件中错误复用的 pickcode 改回数据库记录所属的播放源。
 * 数据源：原 STRM 地址、记录 pickcode 和 115 返回的原文件名。
 * 操作：
 * 1) 仅替换 download_m3u、play、video_proxy 路由中的 pickcode 与文件名。
 * 2) 保留地址基址、查询参数和 STRM 中的其他文本。
 */
internal fun replaceStrmPlaybackSource(content: String, pickcode: String, fileName: String): String? {
    // 2.1 定位首条实际播放地址，空行和尾部内容保持原样。
    val address = content.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() } ?: return null
    val match = STRM_PLAYBACK_SOURCE_PATTERN.find(address) ?: return null
    val pickcodeRange = match.groups[1]?.range ?: return null
    val fileNameRange = match.groups[2]?.range ?: return null
    // 2.2 使用记录身份和官方文件名重组路径，避免继续播放另一条来源。
    val repairedAddress = buildString {
        append(address.substring(0, pickcodeRange.first))
        append(pickcode)
        append(address.substring(pickcodeRange.last + 1, fileNameRange.first))
        append(encodeStrmPathSegment(fileName))
        append(address.substring(fileNameRange.last + 1))
    }
    return content.replaceFirst(address, repairedAddress)
}

private val STRM_PLAYBACK_SOURCE_PATTERN =
    Regex("""(?i)(?:^|/)(?:download_m3u|play|video_proxy)/([^/?#]+)/([^?#\r\n]+)""")

private fun decodeStrmPathSegment(value: String): String =
    java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())

private fun encodeStrmPathSegment(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

data class DeleteMovieResult(
    val movieId: Long,
    val pickcodes: Set<String>,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean
        get() = errorMessage == null

    companion object {
        fun failed(movieId: Long, message: String): DeleteMovieResult =
            DeleteMovieResult(movieId = movieId, pickcodes = emptySet(), errorMessage = message)
    }
}

internal fun isDedicatedMovieDirectoryName(directoryName: String?, videoName: String): Boolean {
    val directoryNumber = extractMovieNumberInfo(directoryName.orEmpty())?.number ?: return false
    val movieNumber = extractMovieNumberInfo(videoName)?.number ?: return false
    return directoryNumber == movieNumber
}

internal fun isDocumentWithinTree(rootDocumentId: String, documentId: String): Boolean =
    documentId == rootDocumentId || documentId.startsWith("$rootDocumentId/")

internal fun dedicatedMovieDirectoryDocumentId(
    rootDocumentId: String,
    videoDocumentId: String,
    parentName: String?,
    videoName: String
): String? {
    if (!isDocumentWithinTree(rootDocumentId, videoDocumentId)) return null
    val parentDocumentId = videoDocumentId.substringBeforeLast('/', missingDelimiterValue = "")
    if (parentDocumentId.isBlank() || parentDocumentId == rootDocumentId) return null
    return parentDocumentId.takeIf { isDedicatedMovieDirectoryName(parentName, videoName) }
}

private data class FileWithParent(
    val parent: DocumentFile,
    val file: DocumentFile
)

private fun MovieEntity.singlePart(): List<MoviePlaybackPart> =
    listOf(MoviePlaybackPart(label = videoName.playbackPartUiLabel(), videoUri = videoUri, fileName = videoName))

private fun CloudStrmRecordEntity.playbackRecordKey(): String =
    pickcode.ifBlank { strmUri.ifBlank { fileName } }

data class LibraryReorganizeResult(
    val rootCount: Int,
    val movieCount: Int,
    val movedFolders: Int = 0,
    val failedRoots: List<String> = emptyList()
) {
    val hasFailures: Boolean
        get() = failedRoots.isNotEmpty()
}

private data class SimilarMovieRank(val score: Int, val distance: Int)

private data class SimilarCodeInfo(val prefix: String, val number: Int)

private const val SIMILAR_ACTOR_PREFILTER_LIMIT = 4
private const val MOVIE_LIST_PAGE_SIZE = 80
private const val TAG = "MovieRepository"

private fun MovieEntity.similarCodeInfo(): SimilarCodeInfo? {
    val source = listOf(title, originalTitle.orEmpty(), videoName).joinToString(" ")
    val match = Regex("""(?i)\b([a-z]{2,10})[-_ ]?(\d{2,6})\b""").find(source) ?: return null
    return SimilarCodeInfo(
        prefix = match.groupValues[1].uppercase(Locale.ROOT),
        number = match.groupValues[2].toIntOrNull() ?: return null
    )
}

private fun String.similarNormalized(): String = trim().lowercase(Locale.ROOT)

private fun MovieEntity.movieNumberKey(): String? {
    val source = listOf(videoName, title, originalTitle.orEmpty(), uniqueIds.joinToString(" "))
        .joinToString(" ")
    return movieVersionKeyFromText(source)
}

private fun String.movieNumberKeyFromText(): String? {
    return movieKeyFromText(this)
}

private fun String.escapeLikePattern(): String =
    replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

private fun String.movieNumberCandidateLikePattern(): String {
    val info = extractMovieNumberInfo(this)
    if (info != null) {
        val (prefix, digits) = info.number.split("-", limit = 2)
        return "%${prefix.escapeLikePattern()}%${digits.escapeLikePattern()}%"
    }
    return "%${escapeLikePattern()}%"
}

internal data class MovieNumberSearchQuery(
    val number: String,
    val candidateLikePattern: String
)

/*
 * ================================================================================
 * 步骤1：标准化影片搜索中的番号
 * ================================================================================
 * 目标：把用户输入的不同番号分隔符转换为同一个候选搜索条件。
 * 数据源：搜索框原始文本和 MovieNumberUtils 的番号解析结果。
 * 操作：
 * 1) 仅在输入包含完整番号时生成查询条件。
 * 2) 使用前缀和数字之间的通配符匹配存量文件名的分隔符差异。
 */
internal fun movieNumberSearchQuery(text: String): MovieNumberSearchQuery? {
    // 1.1 解析失败说明这不是番号搜索，保留普通全文匹配行为。
    val number = extractMovieNumberInfo(text)?.number ?: return null
    // 1.2 例如 NAMH 022 生成 %NAMH%022%，供 SQLite 候选筛选使用。
    return MovieNumberSearchQuery(number = number, candidateLikePattern = number.movieNumberCandidateLikePattern())
}

private fun MovieEntity.matchesMovieNumber(number: String): Boolean =
    listOf(videoName, title, originalTitle.orEmpty(), uniqueIds.joinToString(" "))
        .any { source -> extractMovieNumberInfo(source)?.number == number }

internal fun summarizeActors(values: List<MovieActorMetadataList>): List<MovieMetadataSummary> =
    ActorIdentityIndex(values).summaries()

internal fun actorIdentityMovieIds(
    values: List<MovieActorMetadataList>,
    value: String,
    exact: Boolean
): Set<Long> = ActorIdentityIndex(values).movieIdsMatching(value, exact)

/*
 * ================================================================================
 * 步骤2：构建演员别名身份组
 * ================================================================================
 * 目标：让“主名（别名）”跨影片连通，演员页、搜索和影片数量使用同一身份判断。
 * 数据源：Room 保存的每部影片演员文本，其中括号内姓名是该演员的显式别名。
 * 操作：
 * 1) 每条演员文本拆成主名和全部显式别名，并保留影片 ID。
 * 2) 两条记录只要共享任一规范化姓名，就合并为同一个身份组。
 * 3) 汇总时按身份组去重影片；查询时返回整组关联的所有影片。
 */
private class ActorIdentityIndex(values: List<MovieActorMetadataList>) {
    private val records = values.flatMap { movie ->
        movie.actors.mapNotNull { rawActor ->
            val names = actorNameParts(rawActor)
            val displayName = names.firstOrNull().orEmpty()
            val identityKeys = names.flatMap(::actorNameVariants).toSet()
            if (displayName.isBlank() || identityKeys.isEmpty() || isNonActorCategoryName(displayName)) {
                null
            } else {
                ActorIdentityRecord(movie.movieId, displayName, names, identityKeys)
            }
        }
    }
    private val parents = IntArray(records.size) { index -> index }
    private val groups by lazy { buildGroups() }

    init {
        connectExplicitAliases()
    }

    fun summaries(): List<MovieMetadataSummary> = groups.values
        .map { group ->
            MovieMetadataSummary(
                value = group.first().displayName,
                count = group.map { it.movieId }.distinct().size
            )
        }
        .sortedWith(compareByDescending<MovieMetadataSummary> { it.count }.thenBy { it.value.lowercase(Locale.ROOT) })

    fun movieIdsMatching(value: String, exact: Boolean): Set<Long> {
        val query = value.primaryActorName()
        val queryKey = query.metadataKey()
        val queryIdentityKeys = actorNameVariants(query)
        if (query.isBlank() || queryKey.isBlank() || queryIdentityKeys.isEmpty()) return emptySet()

        // 2.1 先找到直接命中的记录，再通过其身份组扩展到别名所在的其它影片。
        val matchingRoots = records.indices.mapNotNull { index ->
            val record = records[index]
            val matchesExactIdentity = record.identityKeys.any { it in queryIdentityKeys }
            val matchesPartialName = !exact && record.names.any { name ->
                name.metadataKey().contains(queryKey)
            }
            find(index).takeIf { matchesExactIdentity || matchesPartialName }
        }.toSet()

        return matchingRoots.flatMapTo(mutableSetOf()) { root ->
            groups[root].orEmpty().map(ActorIdentityRecord::movieId)
        }
    }

    private fun connectExplicitAliases() {
        val firstRecordByIdentityKey = mutableMapOf<String, Int>()
        records.forEachIndexed { index, record ->
            // 2.2 同一姓名键出现于任意两条记录时，保留它们之间的别名连通关系。
            record.identityKeys.forEach { identityKey ->
                val firstIndex = firstRecordByIdentityKey.putIfAbsent(identityKey, index)
                if (firstIndex != null) union(firstIndex, index)
            }
        }
    }

    private fun buildGroups(): Map<Int, List<ActorIdentityRecord>> =
        records.indices.groupBy(::find).mapValues { (_, indexes) -> indexes.map(records::get) }

    private fun union(left: Int, right: Int) {
        val leftRoot = find(left)
        val rightRoot = find(right)
        if (leftRoot != rightRoot) parents[rightRoot] = leftRoot
    }

    private fun find(index: Int): Int {
        var root = index
        while (parents[root] != root) root = parents[root]
        var current = index
        while (parents[current] != current) {
            val next = parents[current]
            parents[current] = root
            current = next
        }
        return root
    }
}

private data class ActorIdentityRecord(
    val movieId: Long,
    val displayName: String,
    val names: List<String>,
    val identityKeys: Set<String>
)

private fun summarizeValues(values: List<String>): List<MovieMetadataSummary> =
    values.map { it.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.isNotBlank() }
        .groupBy { it.metadataKey() }
        .map { (_, grouped) -> MovieMetadataSummary(grouped.first(), grouped.size) }
        .sortedWith(compareByDescending<MovieMetadataSummary> { it.count }.thenBy { it.value.lowercase(Locale.ROOT) })

private fun summarizeTexts(values: List<String>): List<MovieMetadataSummary> =
    values.map { it.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.isNotBlank() }
        .groupBy { it.metadataKey() }
        .map { (_, grouped) -> MovieMetadataSummary(grouped.first(), grouped.size) }
        .sortedWith(compareByDescending<MovieMetadataSummary> { it.count }.thenBy { it.value.lowercase(Locale.ROOT) })

private fun String.extractBracketActorName(): String? {
    val match = Regex("""^[\u3010\[]([^\u3011\]]+)[\u3011\]]""").find(this) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

private fun String.sanitizeDocumentName(): String =
    replace(Regex("""[\\/:*?"<>|]"""), "_").trim()

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

private fun String.segmentPartLabel(): String? {
    extractMovieNumberInfo(this)?.partLabel?.let { return it }
    val baseName = substringBeforeLast('.', this)
    Regex("""(?i)(?:^|[._ -])part\s*0*([0-9]{1,2})(?=$|[^a-z0-9])""")
        .find(baseName)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return "P$it" }
    Regex("""(?i)(?:^|[._ -])p\s*0*([0-9]{1,2})(?=$|[^a-z0-9])""")
        .find(baseName)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return "P$it" }
    val match = Regex("""(?i)\b[a-z]{2,10}[-_ ]?\d{2,6}[-_ ]([a-z])(?:$|[^a-z0-9])""")
        .find(baseName)
        ?: return null
    return match.groupValues[1]
        .uppercase(Locale.ROOT)
        .takeUnless(::isEmbeddedSubtitleMarkerPart)
}

private fun String.playbackPartLabel(): String {
    detectMovieVariant(this).displayName.takeIf { it.isNotBlank() }?.let { return it }
    return segmentPartLabel() ?: "姝ｇ墖"
}

private fun String.playbackPartSortKey(): Int =
    when {
        this == "姝ｇ墖" -> 0
        this == "4K" -> 50
        length == 1 && first() in 'A'..'Z' -> 10 + first().code - 'A'.code
        matches(Regex("""P\d{1,2}""")) -> 100 + (drop(1).toIntOrNull() ?: 99)
        else -> Int.MAX_VALUE
    }

private fun String.playbackPartDisplayLabel(): String {
    val part = segmentPartLabel()
    val variant = detectMovieVariant(this)
        .displayName
        .takeIf { it.isNotBlank() }
    return when {
        part != null && variant != null -> "$part $variant"
        part != null -> part
        variant != null -> variant
        else -> "默认"
    }
}

private fun String.playbackPartDisplaySortKey(): Int {
    val partToken = substringBefore(' ')
    val partScore = when {
        this == "默认" -> 0
        partToken == "4K" || partToken == "8K" || partToken == "60FPS" || partToken == "4K60FPS" || partToken == "8K60FPS" -> 0
        partToken.length == 1 && partToken.first() in 'A'..'Z' -> 10 + partToken.first().code - 'A'.code
        partToken.matches(Regex("""P\d{1,2}""")) -> 100 + (partToken.drop(1).toIntOrNull() ?: 99)
        else -> 9_000
    }
    val variantScore = when {
        contains("8K") -> 3
        contains("4K") -> 2
        contains("60FPS") -> 1
        else -> 0
    }
    return partScore * 10 + variantScore
}

private fun String.playbackPartUiLabel(): String {
    val part = segmentPartLabel()
    val variant = detectMovieVariant(this)
        .displayName
        .takeIf { it.isNotBlank() }
    return when {
        part != null && variant != null -> "$part $variant"
        part != null -> part
        variant != null -> variant
        else -> "\u6B63\u7247"
    }
}

private fun String.playbackPartUiSortKey(): Int {
    val partToken = substringBefore(' ')
    val partScore = when {
        this == "\u6B63\u7247" -> 0
        partToken == "4K" || partToken == "8K" || partToken == "60FPS" || partToken == "4K60FPS" || partToken == "8K60FPS" -> 0
        partToken.length == 1 && partToken.first() in 'A'..'Z' -> 10 + partToken.first().code - 'A'.code
        partToken.matches(Regex("""P\d{1,2}""")) -> 100 + (partToken.drop(1).toIntOrNull() ?: 99)
        else -> 9_000
    }
    val variantScore = when {
        contains("8K") -> 3
        contains("4K") -> 2
        contains("60FPS") -> 1
        else -> 0
    }
    return partScore * 10 + variantScore
}

private fun DocumentFile.isSupportedVideoFile(): Boolean {
    val extension = name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in setOf("mp4", "mkv", "avi", "mov", "wmv", "m4v", "webm", "mpg", "mpeg", "strm", "ts", "iso", "flv")
}
