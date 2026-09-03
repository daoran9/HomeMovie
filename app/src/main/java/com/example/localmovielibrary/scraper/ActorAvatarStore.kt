package com.example.localmovielibrary.scraper

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

class ActorAvatarStore(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun avatarUri(actorName: String): String? {
        val file = avatarFiles(actorName).firstOrNull(::isUsableAvatar) ?: return null
        return Uri.fromFile(file).toString()
    }

    fun hasAvatar(actorName: String): Boolean {
        return avatarFiles(actorName).any(::isUsableAvatar)
    }

    /*
     * ================================================================================
     * 步骤2：清理演员旧头像
     * ================================================================================
     * 目标：强制重匹配前删除历史错配图片，避免网络源失败时继续展示旧身份头像。
     * 数据源：当前演员名及其已知别名对应的本地头像文件。
     * 操作：
     * 1) 找到主名和别名的全部版本化、旧格式头像文件。
     * 2) 仅删除这些演员键对应的文件，不影响其它演员。
     */
    fun clearAvatar(actorName: String): Int {
        val files = avatarFiles(actorName)
            .filter { file -> file.exists() }
            .distinctBy { file -> file.absolutePath }
        files.forEach { file -> file.delete() }
        return files.count { file -> !file.exists() }
    }

    fun saveAvatar(actorName: String, bytes: ByteArray) {
        if (actorName.isBlank() || bytes.isEmpty()) return
        val file = versionedAvatarFile(actorName, bytes)
        file.parentFile?.mkdirs()
        if (!isUsableAvatar(file)) {
            file.writeBytes(bytes)
        }
        avatarFilesForName(actorName)
            .filter { existing -> existing.absolutePath != file.absolutePath }
            .forEach { existing -> existing.delete() }
    }

    /*
     * ================================================================================
     * 步骤1：复制已有头像到演员别名
     * ================================================================================
     * 目标：已有当前名称头像时，不重复请求网络，也能补齐别名索引。
     * 数据源：当前演员名称对应的本地头像文件、待补齐的别名集合。
     * 操作：
     * 1) 找到当前名称已有的可用头像。
     * 2) 只为尚未保存的别名写入同一份头像。
     */
    fun copyAvatarToNames(
        sourceActorName: String,
        aliasNames: Collection<String>,
        overwriteExisting: Boolean = false
    ): Int {
        val source = avatarFiles(sourceActorName).firstOrNull(::isUsableAvatar) ?: return 0
        val bytes = runCatching { source.readBytes() }.getOrNull() ?: return 0
        val namesToSave = aliasNames
            .flatMap { actorNameVariants(it) }
            .filter { it.isNotBlank() && (overwriteExisting || !hasAvatar(it)) }
            .distinct()
        namesToSave.forEach { name -> saveAvatar(name, bytes) }
        return namesToSave.size
    }

    private fun versionedAvatarFile(actorName: String, bytes: ByteArray): File {
        val nameHash = actorNameHash(actorName)
        val contentHash = bytes.sha256()
        return File(directory, "$nameHash-$contentHash.jpg")
    }

    private fun actorNameHash(actorName: String): String {
        val normalized = Normalizer.normalize(actorName.trim(), Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), "")
            .lowercase(Locale.ROOT)
        return normalized.sha256()
    }

    private fun avatarFiles(actorName: String): List<File> {
        return actorNameVariants(actorName)
            .flatMap { variant ->
                avatarFilesForName(variant)
            }
            .distinctBy { it.absolutePath }
    }

    private fun avatarFilesForName(actorName: String): List<File> {
        val nameHash = actorNameHash(actorName)
        val versionedPrefix = "$nameHash-"
        val versioned = directory.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.name.startsWith(versionedPrefix) && file.name.endsWith(".jpg") }
            .sortedByDescending { file -> file.lastModified() }
        val legacy = File(directory, "$nameHash.jpg")
        return versioned + legacy
    }

    private fun isUsableAvatar(file: File): Boolean = file.exists() && file.length() > 0

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DIRECTORY_NAME = "actor_avatars"
    }
}
