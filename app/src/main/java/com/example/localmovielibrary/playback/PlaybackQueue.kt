package com.example.localmovielibrary.playback

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * ================================================================================
 * 步骤1：保存待播放队列
 * ================================================================================
 * 目标：在导航页面之间传递选定文件夹的逻辑播放项。
 * 数据源：115 文件的 pickcode、文件名和展示标题。
 * 操作：
 * 1) 只保存可重新解析的媒体标识，不保存有时效的 115 直链。
 * 2) 用一次性队列 ID 避免把长列表编码进导航路由。
 */
object PlaybackQueueStore {
    private const val TAG = "PlaybackQueueStore"
    private val queues = ConcurrentHashMap<String, List<PlaybackQueueItem>>()

    fun put(items: List<PlaybackQueueItem>): String {
        Log.i(TAG, "开始保存随机播放队列，条数=${items.size}")
        require(items.isNotEmpty()) { "播放队列不能为空" }
        val queueId = UUID.randomUUID().toString()
        queues[queueId] = items.toList()
        Log.i(TAG, "随机播放队列保存完成，queueId=$queueId")
        return queueId
    }

    fun take(queueId: String): List<PlaybackQueueItem> {
        Log.i(TAG, "开始读取随机播放队列，queueId=$queueId")
        val items = queues.remove(queueId).orEmpty()
        Log.i(TAG, "随机播放队列读取完成，条数=${items.size}")
        return items
    }
}

data class PlaybackQueueItem(
    val mediaUri: String,
    val title: String,
    val fileName: String
)
