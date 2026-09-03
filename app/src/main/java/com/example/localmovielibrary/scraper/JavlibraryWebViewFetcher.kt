package com.example.localmovielibrary.scraper

import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * ================================================================================
 * 步骤1：排队 JavLibrary WebView 请求
 * ================================================================================
 * 目标：让后台刮削复用可通过 Cloudflare 验证的 Android WebView。
 * 数据源：JavDB、JavLibrary 发起的搜索页和详情页 URL。
 * 操作：
 * 1) 后台请求进入先进先出队列并挂起协程。
 * 2) UI 层观察 currentRequest，使用隐藏 WebView 加载页面。
 */
class JavlibraryWebViewFetcher {
    private data class Pending(
        val request: JavlibraryWebViewRequest,
        val continuation: CancellableContinuation<String>
    )

    private val lock = Any()
    private val queue = ArrayDeque<Pending>()
    private val nextId = AtomicLong(0L)
    private var active: Pending? = null
    private val _currentRequest = MutableStateFlow<JavlibraryWebViewRequest?>(null)
    val currentRequest: StateFlow<JavlibraryWebViewRequest?> = _currentRequest

    suspend fun fetch(url: String): String = suspendCancellableCoroutine { continuation ->
        Log.i(TAG, "步骤1开始：排队 JavLibrary WebView 请求")
        val pending = Pending(
            request = JavlibraryWebViewRequest(nextId.incrementAndGet(), url),
            continuation = continuation
        )
        synchronized(lock) {
            queue.addLast(pending)
            publishNextLocked()
        }
        continuation.invokeOnCancellation {
            synchronized(lock) {
                if (active?.request?.id == pending.request.id) {
                    active = null
                    _currentRequest.value = null
                    publishNextLocked()
                } else {
                    queue.removeIf { it.request.id == pending.request.id }
                }
            }
            Log.i(TAG, "步骤1结束：JavLibrary WebView 请求已取消")
        }
    }

    /*
     * ================================================================================
     * 步骤2：完成或失败当前 WebView 请求
     * ================================================================================
     * 目标：把 WebView 页面结果交回原始刮削协程，并继续处理队列。
     * 数据源：隐藏 WebView 解析出的页面 HTML 或加载错误。
     * 操作：
     * 1) 校验 requestId，避免旧页面回调污染新请求。
     * 2) 释放当前请求并唤醒下一个排队请求。
     */
    fun complete(requestId: Long, html: String) {
        finish(requestId) { continuation ->
            continuation.resume(html)
        }
    }

    fun fail(requestId: Long, error: Throwable) {
        finish(requestId) { continuation ->
            continuation.resumeWithException(error)
        }
    }

    private fun finish(requestId: Long, resume: (CancellableContinuation<String>) -> Unit) {
        var pending: Pending? = null
        synchronized(lock) {
            if (active?.request?.id != requestId) return
            pending = active
            active = null
            _currentRequest.value = null
            publishNextLocked()
        }
        pending?.continuation?.let(resume)
        Log.i(TAG, "步骤2结束：JavLibrary WebView 请求已返回")
    }

    private fun publishNextLocked() {
        if (active != null) return
        active = if (queue.isEmpty()) null else queue.removeFirst()
        _currentRequest.value = active?.request
    }

    private companion object {
        const val TAG = "JavLibraryWebView"
    }
}

data class JavlibraryWebViewRequest(
    val id: Long,
    val url: String
)
