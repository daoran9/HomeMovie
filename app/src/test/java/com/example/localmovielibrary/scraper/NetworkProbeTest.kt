package com.example.localmovielibrary.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.logging.Logger

/*
 * ================================================================================
 * 步骤1：验证来源探测分类
 * ================================================================================
 * 目标：把 JavDB 明确返回的出口封禁页与普通 HTTP 403 分开。
 * 数据源：本地 OkHttp 拦截器构造的英文、简体中文和繁体中文响应。
 * 操作：
 * 1) 封禁正文应返回 AccessBanned。
 * 2) 没有封禁标记的 403 应保持 NetworkError。
 */
class NetworkProbeTest {
    private val logger = Logger.getLogger(NetworkProbeTest::class.java.name)

    @Test
    fun probeSourceRecognizesEnglishAccessBan() = runBlocking {
        logger.info("步骤1开始：验证英文出口封禁页")
        // 1.1 使用真实页面中的英文封禁标记。
        val status = probeStatus("The site owner has banned your access", 403)

        // 1.2 确认封禁页不会退化为普通网络失败。
        assertEquals(SourceProbeStatus.AccessBanned, status)
        logger.info("步骤1结束：英文出口封禁页分类正确")
    }

    @Test
    fun probeSourceRecognizesChineseAccessBans() = runBlocking {
        logger.info("步骤1开始：验证中文出口封禁页")
        // 1.1 覆盖简体和繁体页面文案。
        listOf("该网站禁止了你的访问", "該網站禁止了你的訪問").forEach { body ->
            assertEquals(SourceProbeStatus.AccessBanned, probeStatus(body, 403))
        }
        logger.info("步骤1结束：中文出口封禁页分类正确")
    }

    @Test
    fun probeSourceKeepsOrdinaryForbiddenResponseAsNetworkError() = runBlocking {
        logger.info("步骤1开始：验证普通 403")
        // 1.1 构造不含地区、封禁或 CF 标记的响应。
        val status = probeStatus("Forbidden", 403)

        // 1.2 保留原有网络失败语义。
        assertEquals(SourceProbeStatus.NetworkError, status)
        logger.info("步骤1结束：普通 403 分类正确")
    }

    private suspend fun probeStatus(body: String, code: Int): SourceProbeStatus {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test response")
                    .body(body.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()
        return NetworkProbe(
            client = client,
            ioDispatcher = Dispatchers.Unconfined
        ).probeSource(ScrapeSource.Javdb).status
    }
}
