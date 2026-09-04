package com.example.localmovielibrary.scraper

object NfoWriter {
    fun build(info: ScrapedMovieInfo): String = buildString {
        val canonicalInfo = info.canonicalizeActorIdentities()
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("<movie>")
        tag("title", info.formattedTitle())
        tag("originaltitle", info.originalTitle)
        tag("sorttitle", info.number)
        tag("num", info.number)
        tag("plot", info.plot)
        tag("outline", info.outline)
        tag("premiered", info.premiered)
        tag("releasedate", info.premiered)
        tag("year", info.year)
        tag("runtime", info.runtime)
        tag("studio", info.studio)
        tag("maker", info.studio)
        tag("publisher", info.publisher)
        tag("label", info.publisher)
        tag("series", info.series)
        tag("rating", info.rating)
        tag("trailer", info.trailer)
        tag("website", info.website)
        tag("source", info.source)
        tag("thumb", info.thumbUrl)
        tag("poster", info.posterUrl)
        info.directors.normalizedValues().forEach { tag("director", it) }
        info.genres.normalizedValues().forEach { tag("genre", it) }
        info.tags.normalizedValues().forEach { tag("tag", it) }
        canonicalInfo.actors.normalizedValues().forEach { actor ->
            appendLine("  <actor>")
            tag("name", canonicalInfo.actorDisplayName(actor), indent = "    ")
            canonicalInfo.actorImageUrls[actor]
                ?.takeIf(::isActorIdentityImageUrl)
                ?.let { tag("thumb", it, indent = "    ") }
            tag("type", "Actor", indent = "    ")
            appendLine("  </actor>")
        }
        appendLine("</movie>")
    }

    /**
     * ================================================================================
     * 步骤1：定点更新已有 NFO 的演员别名
     * ================================================================================
     * 目标：全库头像任务按本轮已验证身份更新 actor/name，并折叠历史重复演员块。
     * 数据源：当前 NFO 文本和本次影片的演员别名结果。
     * 操作：
     * 1) 只按精确姓名或当前来源明确给出的别名识别同一演员。
     * 2) 未获本轮来源确认的旧别名不再写回，避免历史错误持续扩散。
     */
    fun mergeActorDisplayNames(existingNfo: String, info: ScrapedMovieInfo): String {
        if (existingNfo.isBlank()) return existingNfo
        if (info.actors.isEmpty()) return existingNfo.sanitizeActorThumbsInBlocks()
        val canonicalInfo = info.canonicalizeActorIdentities()
        val matchedActors = mutableSetOf<String>()
        val updatedNfo = ACTOR_BLOCK.replace(existingNfo) { actorBlock ->
            val actorBody = actorBlock.groupValues[2]
            val nameMatch = ACTOR_NAME.find(actorBody) ?: return@replace actorBlock.value
            val storedName = nameMatch.groupValues[2].unescapeXml().trim()
            val storedPrimaryName = storedName.primaryActorName()
            if (canonicalInfo.excludedActorNames.any { excluded ->
                    actorNamesHaveExactVariant(excluded, storedPrimaryName)
                }
            ) {
                return@replace ""
            }
            if (isNonActorCategoryName(storedPrimaryName)) {
                val fallbackActor = canonicalInfo.findCanonicalActor(storedName) ?: return@replace ""
                if (!matchedActors.add(fallbackActor.actorIdentityKey())) return@replace ""
                val replacement = nameMatch.groupValues[1] + canonicalInfo.actorDisplayName(fallbackActor).escapeXml() + nameMatch.groupValues[3]
                val updatedBody = actorBody.replaceRange(nameMatch.range, replacement)
                    .withActorThumb(canonicalInfo.actorImageUrls[fallbackActor])
                return@replace actorBlock.groupValues[1] + updatedBody + actorBlock.groupValues[3]
            }
            val sourceActor = canonicalInfo.findCanonicalActor(storedName)
                ?: return@replace actorBlock.groupValues[1] + actorBody.sanitizeActorThumbs() + actorBlock.groupValues[3]
            if (!matchedActors.add(sourceActor.actorIdentityKey())) return@replace ""
            val displayName = canonicalInfo.actorDisplayName(sourceActor)
            if (displayName == storedName) {
                val updatedBody = actorBody.withActorThumb(canonicalInfo.actorImageUrls[sourceActor])
                return@replace actorBlock.groupValues[1] + updatedBody + actorBlock.groupValues[3]
            }

            val replacement = nameMatch.groupValues[1] + displayName.escapeXml() + nameMatch.groupValues[3]
            val updatedBody = actorBody.replaceRange(nameMatch.range, replacement)
                .withActorThumb(canonicalInfo.actorImageUrls[sourceActor])
            actorBlock.groupValues[1] + updatedBody + actorBlock.groupValues[3]
        }
        val missingActors = canonicalInfo.actors
            .filter { actor -> actor.actorIdentityKey() !in matchedActors }
            .distinctBy { actor -> actorNameVariants(actor).sorted().joinToString("|") }
        if (missingActors.isEmpty()) return updatedNfo
        val actorBlocks = missingActors.joinToString(separator = "") { actor -> canonicalInfo.actorBlock(actor) }
        return updatedNfo.replaceFirst("</movie>", "$actorBlocks</movie>")
    }

    private fun ScrapedMovieInfo.findCanonicalActor(storedName: String): String? {
        val storedParts = actorNameParts(storedName)
        val preferredNames = storedParts.firstOrNull()
            ?.takeUnless(::isNonActorCategoryName)
            ?.let(::listOf)
            ?: storedParts.drop(1)
        return preferredNames.flatMap { storedPart ->
            actors.filter { actor ->
                actorIdentityNames(actor).any { identity ->
                    actorNamesHaveExactVariant(identity, storedPart)
                }
            }
        }.distinctBy { actor -> actor.actorIdentityKey() }.singleOrNull()
    }

    private fun ScrapedMovieInfo.actorIdentityNames(actor: String): List<String> =
        (actorNameParts(actor) + actorAliases
            .filterKeys { storedActor -> actorNamesHaveExactVariant(storedActor, actor) }
            .values
            .flatten()
            .flatMap(::actorNameParts))
            .filter { name -> name.isNotBlank() && !isNonActorCategoryName(name) }
            .distinctBy { name -> name.actorIdentityKey() }

    private fun String.actorIdentityKey(): String =
        actorNameVariants(this).sorted().joinToString("|")

    private fun StringBuilder.tag(name: String, value: String, indent: String = "  ") {
        append(indent)
        append("<")
        append(name)
        append(">")
        append(value.escapeXml())
        append("</")
        append(name)
        appendLine(">")
    }

    private fun String.escapeXml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun String.unescapeXml(): String =
        replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")

    private fun ScrapedMovieInfo.actorDisplayName(actor: String, storedName: String = actor): String {
        val primaryName = actor.primaryActorName().ifBlank { storedName.primaryActorName() }.ifBlank { actor.trim() }
        val sourceAliases = (actorNameParts(actor).drop(1) + actorAliases
            .filterKeys { original -> actorNamesHaveExactVariant(original, actor) }
            .values
            .flatten()
            .flatMap(::actorNameParts))
            .filter { alias ->
                alias.isNotBlank() &&
                    !actorNamesHaveExactVariant(alias, primaryName) &&
                    !isNonActorCategoryName(alias) &&
                    !isAnotherKnownActor(alias, actor)
            }
            .distinctBy { alias -> actorNameVariants(alias).sorted().joinToString("|") }
        val aliases = sourceAliases
            .filter { alias ->
                alias.isNotBlank() &&
                    !actorNamesHaveExactVariant(alias, primaryName) &&
                    !isNonActorCategoryName(alias) &&
                    !isAnotherKnownActor(alias, actor)
            }
            .distinctBy { alias -> actorNameVariants(alias).sorted().joinToString("|") }
        return if (aliases.isEmpty()) primaryName else "$primaryName（${aliases.joinToString("、")}）"
    }

    private fun ScrapedMovieInfo.isAnotherKnownActor(alias: String, actor: String): Boolean = actors.any { candidate ->
        !actorNamesHaveExactVariant(candidate, actor) && actorNamesHaveExactVariant(alias, candidate)
    }

    private fun ScrapedMovieInfo.actorBlock(actor: String): String = buildString {
        appendLine("  <actor>")
        append("    <name>")
        append(actorDisplayName(actor).escapeXml())
        appendLine("</name>")
        actorImageUrls[actor]?.takeIf(::isActorIdentityImageUrl)?.let { imageUrl ->
            append("    <thumb>")
            append(imageUrl.escapeXml())
            appendLine("</thumb>")
        }
        appendLine("    <type>Actor</type>")
        appendLine("  </actor>")
    }

    private fun ScrapedMovieInfo.formattedTitle(): String {
        val number = number.trim().uppercase()
        val rawTitle = title.trim().ifBlank { originalTitle.trim() }
        if (number.isBlank()) return rawTitle

        val body = rawTitle
            .withoutNumberPrefix(number)
            .ifBlank { originalTitle.trim().withoutNumberPrefix(number) }
            .ifBlank { rawTitle }
            .trim()

        return "[$number]$body"
    }

    private fun String.withoutNumberPrefix(number: String): String {
        if (isBlank()) return this
        val numberVariants = numberPrefixVariants(number)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("|") { Regex.escape(it) }
        if (numberVariants.isBlank()) return trim()

        val prefixPattern = Regex(
            pattern = "^\\s*[\\[\\u3010(\\uFF08]?\\s*(?:$numberVariants)\\s*[\\]\\u3011)\\uFF09]?\\s*[-_:\\uFF1A\\uFF0D\\u2014\\s]*",
            option = RegexOption.IGNORE_CASE
        )
        return replace(prefixPattern, "").trim()
    }

    private fun numberPrefixVariants(number: String): List<String> {
        val normalized = number.trim().uppercase()
        val compact = normalized.replace("-", "")
        val withoutLeadingZeros = Regex("""^([A-Z]{2,10})-(0*)(\d{1,6})$""")
            .find(normalized)
            ?.let { match ->
                val prefix = match.groupValues[1]
                val digits = match.groupValues[3].toIntOrNull()?.toString() ?: match.groupValues[3]
                "$prefix-$digits"
            }
        return buildList {
            add(normalized)
            add(compact)
            withoutLeadingZeros?.let {
                add(it)
                add(it.replace("-", ""))
            }
        }
    }

    private fun List<String>.normalizedValues(): List<String> =
        flatMap { value ->
            value.split(Regex("[,\\uFF0C\\u3001|;\\r\\n\\t]+"))
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun String.withActorThumb(candidateUrl: String?): String {
        val identityImageUrl = candidateUrl?.takeIf(::isActorIdentityImageUrl)
        val withoutThumbs = ACTOR_THUMB.replace(this, "")
        if (identityImageUrl == null) return sanitizeActorThumbs()
        val nameMatch = ACTOR_NAME.find(withoutThumbs) ?: return withoutThumbs
        val thumb = "\n    <thumb>${identityImageUrl.escapeXml()}</thumb>"
        return withoutThumbs.replaceRange(nameMatch.range.last + 1, nameMatch.range.last + 1, thumb)
    }

    private fun String.sanitizeActorThumbs(): String = ACTOR_THUMB.replace(this) { thumb ->
        val url = thumb.groupValues[2].unescapeXml().trim()
        if (isOfficialDmmActorImageUrl(url)) thumb.value else ""
    }

    private fun String.sanitizeActorThumbsInBlocks(): String = ACTOR_BLOCK.replace(this) { actorBlock ->
        actorBlock.groupValues[1] + actorBlock.groupValues[2].sanitizeActorThumbs() + actorBlock.groupValues[3]
    }

    private val ACTOR_BLOCK = Regex("""(?is)(<actor\b[^>]*>)(.*?)(</actor>)""")
    private val ACTOR_NAME = Regex("""(?is)(<name\b[^>]*>)(.*?)(</name>)""")
    private val ACTOR_THUMB = Regex("""(?is)(\s*<thumb\b[^>]*>)(.*?)(</thumb>)""")
}
