package com.example.localmovielibrary.util

import java.util.Locale

/*
 * ================================================================================
 * 步骤1：清洗元数据文本
 * ================================================================================
 * 目标：把来源返回的实体、换行和 HTML 标签统一成可写入 NFO 的纯文本。
 * 数据源：DMM/FANZA 详情、已有 NFO 和其它刮削源的文本字段。
 * 操作：
 * 1) 解码常见实体，兼容重复编码。
 * 2) 把实际或实体 br 标签转换为换行。
 * 3) 删除其它 HTML 标签并保留有效换行。
 */
fun String.cleanMetadataText(): String {
    var value = replace("\r\n", "\n").replace('\r', '\n')
    var pass = 0
    while (pass < 4) {
        val decoded = value.decodeHtmlEntitiesOnce()
        if (decoded == value) break
        value = decoded
        pass += 1
    }

    return value
        .replace('\u00A0', ' ')
        .replace(HTML_BREAK_TAG, "\n")
        .replace(HTML_TAG, "")
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex("[ ]*\\n[ ]*"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

fun String.metadataKey(): String =
    trim()
        .replace(Regex("""\s+"""), " ")
        .lowercase(Locale.ROOT)

fun List<String>.containsMetadataValue(value: String, exact: Boolean): Boolean {
    val query = value.metadataKey()
    if (query.isBlank()) return false
    return any { item ->
        val candidate = item.metadataKey()
        candidate.isNotBlank() && if (exact) candidate == query else candidate.contains(query)
    }
}

fun List<String>.normalizedMetadataValues(): List<String> =
    map { it.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.isNotBlank() }
        .distinctBy { it.metadataKey() }

private fun String.decodeHtmlEntitiesOnce(): String =
    HTML_ENTITY.replace(this) { match ->
        val body = match.value.substring(1, match.value.length - 1)
        when {
            body.equals("amp", ignoreCase = true) -> "&"
            body.equals("lt", ignoreCase = true) -> "<"
            body.equals("gt", ignoreCase = true) -> ">"
            body.equals("quot", ignoreCase = true) -> "\""
            body.equals("apos", ignoreCase = true) -> "'"
            body.equals("nbsp", ignoreCase = true) -> " "
            body.startsWith("#x", ignoreCase = true) ->
                decodeCodePoint(body.substring(2), 16) ?: match.value
            body.startsWith("#") ->
                decodeCodePoint(body.substring(1), 10) ?: match.value
            else -> match.value
        }
    }

private fun decodeCodePoint(value: String, radix: Int): String? = runCatching {
    val codePoint = value.toInt(radix)
    if (!Character.isValidCodePoint(codePoint)) null else String(Character.toChars(codePoint))
}.getOrNull()

private val HTML_ENTITY = Regex("""&(?:#x[0-9a-fA-F]+|#\d+|[A-Za-z][A-Za-z0-9]+);""")
private val HTML_BREAK_TAG = Regex("""(?is)<\s*br\b[^>]*>""")
private val HTML_TAG = Regex("""(?is)<[^>]+>""")
