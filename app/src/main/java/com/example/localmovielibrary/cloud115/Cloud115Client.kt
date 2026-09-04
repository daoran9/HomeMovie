package com.example.localmovielibrary.cloud115

interface Cloud115Client {
    suspend fun listFiles(cid: Long): List<Cloud115FileItem>
    suspend fun fetchVideoInfo(pickcode: String): Cloud115VideoInfo
    suspend fun fetchDirectUrl(pickcode: String): String
    suspend fun downloadBytes(url: String): ByteArray
}

data class Cloud115VideoInfo(
    val name: String,
    val sizeBytes: Long?
)
