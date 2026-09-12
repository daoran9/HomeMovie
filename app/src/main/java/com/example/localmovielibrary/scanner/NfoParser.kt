package com.example.localmovielibrary.scanner

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import com.example.localmovielibrary.util.cleanMetadataText
import org.xmlpull.v1.XmlPullParser

class NfoParser(private val contentResolver: ContentResolver) {
    fun parse(uri: Uri): NfoMetadata = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            parseMovie(parser)
        }
    }.getOrNull() ?: NfoMetadata()

    private fun parseMovie(parser: XmlPullParser): NfoMetadata {
        val studios = mutableListOf<String>()
        var publisher: String? = null
        val directors = mutableListOf<String>()
        val actors = mutableListOf<String>()
        val actorCredits = linkedMapOf<String, MutableList<String>>()
        val actorThumbs = linkedMapOf<String, String>()
        val genres = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val uniqueIds = mutableListOf<String>()

        var title: String? = null
        var originalTitle: String? = null
        var plot: String? = null
        var outline: String? = null
        var year: Int? = null
        var premiered: String? = null
        var releaseDate: String? = null
        var runtime: Int? = null
        var mpaa: String? = null
        var series: String? = null
        var rating: Double? = null
        var trailer: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name.lowercase()) {
                "title" -> title = parser.readText()
                "originaltitle" -> originalTitle = parser.readText()
                "plot" -> plot = parser.readText()
                "outline" -> outline = parser.readText()
                "year" -> year = parser.readText().toIntOrNull()
                "premiered" -> premiered = parser.readText()
                "releasedate" -> releaseDate = parser.readText()
                "runtime" -> runtime = parser.readText().extractFirstInt()
                "mpaa" -> mpaa = parser.readText()
                "certification" -> mpaa = parser.readText()
                "studio", "maker" -> studios += parser.readText().splitMultiValue()
                "publisher", "label" -> if (publisher.isNullOrBlank()) publisher = parser.readText().clean()
                "series", "set" -> series = parser.readText()
                "director" -> directors += parser.readText().splitMultiValue()
                "genre" -> genres += parser.readText().splitMultiValue()
                "tag" -> {
                    val rawTag = parser.readText()
                    when (val fieldTag = rawTag.asFieldLikeTag()) {
                        is FieldLikeTag.Series -> if (series.isNullOrBlank()) series = fieldTag.value
                        is FieldLikeTag.Studio -> if (studios.isEmpty()) studios += fieldTag.value
                        null -> tags += rawTag.splitMultiValue().filterNot { it.asFieldLikeTag() != null }
                    }
                }
                "rating" -> rating = parseRating(parser) ?: rating
                "trailer" -> trailer = parser.readText().clean()
                "uniqueid" -> {
                    val type = parser.getAttributeValue(null, "type")
                    val value = parser.readText()
                    if (value.isNotBlank()) uniqueIds += listOfNotNull(type?.takeIf { it.isNotBlank() }, value)
                        .joinToString(":")
                }
                "actor" -> parseActor(parser)?.let { actor ->
                    actors += actor.name
                    if (actor.roles.isNotEmpty()) actorCredits.getOrPut(actor.name) { mutableListOf() }.addAll(actor.roles)
                    actor.thumb?.let { actorThumbs[actor.name] = it }
                }
            }
        }

        val cleanedPlot = plot.clean()
        val cleanedOutline = outline.clean()
        return NfoMetadata(
            title = title.clean(),
            originalTitle = originalTitle.clean(),
            plot = cleanedPlot ?: cleanedOutline,
            outline = cleanedOutline ?: cleanedPlot,
            year = year,
            premiered = releaseDate.clean() ?: premiered.clean(),
            runtimeMinutes = runtime,
            mpaa = mpaa.clean(),
            studios = studios.cleanedDistinct(),
            publisher = publisher,
            series = series.clean(),
            directors = directors.cleanedDistinct(),
            actors = actors.cleanedDistinct(),
            actorCredits = actorCredits.mapValues { (_, roles) -> roles.map { it.trim() }.filter { it.isNotBlank() }.distinct() },
            actorThumbs = actorThumbs.toMap(),
            genres = genres.cleanedDistinct(),
            tags = tags.cleanedDistinct(),
            rating = rating,
            trailer = trailer,
            uniqueIds = uniqueIds.cleanedDistinct()
        )
    }

    private data class ParsedActor(val name: String, val roles: List<String>, val thumb: String?)

    private fun parseActor(parser: XmlPullParser): ParsedActor? {
        val depth = parser.depth
        var name: String? = null
        var thumb: String? = null
        val roles = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("name", ignoreCase = true)) {
                name = parser.readText()
            }
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("role", ignoreCase = true)) {
                roles += parser.readText()
            }
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("thumb", ignoreCase = true)) {
                thumb = parser.readText()
            }
        }
        return name.clean()?.let { ParsedActor(it, roles, thumb.clean()) }
    }

    private fun parseRating(parser: XmlPullParser): Double? {
        if (parser.isEmptyElementTag) return null
        val depth = parser.depth
        var value: Double? = null
        var sawNestedTag = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType == XmlPullParser.TEXT && !sawNestedTag) {
                value = parser.text.trim().toDoubleOrNull() ?: value
            }
            if (parser.eventType == XmlPullParser.START_TAG) {
                sawNestedTag = true
                if (parser.name.equals("value", ignoreCase = true)) {
                    value = parser.readText().toDoubleOrNull() ?: value
                }
            }
        }
        return value
    }

    private fun XmlPullParser.readText(): String {
        val elementDepth = depth
        val result = buildString {
            while (next() != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> append(text.orEmpty())
                    XmlPullParser.START_TAG -> {
                        if (name.equals("br", ignoreCase = true)) append('\n')
                    }
                    XmlPullParser.END_TAG -> {
                        if (depth == elementDepth) break
                    }
                }
            }
        }
        return result.trim()
    }

    private fun String?.clean(): String? = this?.cleanMetadataText()?.takeIf { it.isNotBlank() }

    private fun String.extractFirstInt(): Int? =
        Regex("""\d+""").find(this)?.value?.toIntOrNull()

    private fun String.splitMultiValue(): List<String> =
        split(Regex("""[/,，、|;\r\n\t]+""")).map { it.trim() }.filter { it.isNotBlank() }

    private fun List<String>.cleanedDistinct(): List<String> =
        map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun String.asFieldLikeTag(): FieldLikeTag? {
        val value = trim()
        if (value.isBlank()) return null
        val separatorIndex = listOf(value.indexOf(':'), value.indexOf('：'))
            .filter { it >= 0 }
            .minOrNull()
            ?: return null
        val key = value.substring(0, separatorIndex).trim().lowercase()
        val content = value.substring(separatorIndex + 1).trim().takeIf { it.isNotBlank() } ?: return null
        return when (key) {
            "系列", "series", "シリーズ" -> FieldLikeTag.Series(content)
            "片商", "发行", "發行", "maker", "メーカー", "studio", "label", "レーベル", "publisher" -> FieldLikeTag.Studio(content)
            else -> null
        }
    }

    private sealed interface FieldLikeTag {
        val value: String

        data class Series(override val value: String) : FieldLikeTag
        data class Studio(override val value: String) : FieldLikeTag
    }
}
