package local.hengdu.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLDecoder

internal object HengduLibrarySnapshot {
    const val MANHUAREN_SOURCE_ID = 764992021301L
    const val BAOZI_SOURCE_ID = 764992021302L
    const val MANHUAGUI_SOURCE_ID = ManhuaguiSource.ID

    data class Chapter(val url: String, val title: String, val order: Long, val page: Int, val read: Boolean)
    data class History(val chapterUrl: String, val page: Int, val updated: Long)
    data class Book(
        val sourceId: Long,
        val url: String,
        val title: String,
        val author: String?,
        val description: String?,
        val cover: String?,
        val added: Long,
        val chapters: List<Chapter>,
        val catalogValid: Boolean,
        val history: History?,
    )
    data class Snapshot(val books: List<Book>, val skipped: Int)

    private val bookPath = Regex("^/manhua-[A-Za-z0-9_-]+/$")
    private val chapterPath = Regex("^/m[0-9]+/$")
    private val manhuaguiBookPath = Regex("^/comic/([0-9]+)/$")
    private val manhuaguiChapterPath = Regex("^/comic/([0-9]+)/[0-9]+\\.html$")

    private fun validUrl(url: String, path: Regex): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "www.manhuaren.com" && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.rawQuery == null && uri.rawFragment == null &&
            path.matches(uri.rawPath)
    }.getOrDefault(false)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun coverUrl(value: String?, sourceId: Long): String? {
        if (value.isNullOrBlank() || value.length > 16384) return null
        return runCatching {
            val raw = if (value.startsWith("/proxy?")) {
                val query = URI(value).rawQuery ?: return null
                val encoded = query.split('&').firstOrNull { it.startsWith("url=") }?.substring(4) ?: return null
                URLDecoder.decode(encoded, Charsets.UTF_8.name())
            } else {
                value
            }
            val uri = URI(raw)
            val host = uri.host?.lowercase().orEmpty()
            raw.takeIf {
                when (sourceId) {
                    BAOZI_SOURCE_ID -> BaoziSourceParser.isImageUrl(raw)
                    MANHUAGUI_SOURCE_ID -> ManhuaguiParser.isImageUrl(raw)
                    else -> uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
                        (host == "cdndm5.com" || host.endsWith(".cdndm5.com"))
                }
            }
        }.getOrNull()
    }

    fun parse(text: String): Snapshot {
        require(text.length <= 8_000_000)
        val root = Json.parseToJsonElement(text).jsonObject
        val library = root["library"]?.jsonObject ?: error("Missing library")
        val catalogs = root["catalogs"]?.jsonObject ?: error("Missing catalogs")
        val progress = root["reading"]?.jsonObject ?: error("Missing reading progress")
        val saved = root["saved"]?.jsonObject ?: error("Missing saved pages")
        val history = root["history"]?.jsonObject ?: error("Missing history")
        require(library.size <= 10000 && catalogs.size <= 10000 && progress.size <= 200000 && saved.size <= 200000)
        var skipped = 0
        val books = mutableListOf<Book>()
        library.forEach { (key, value) ->
            val item = value as? JsonObject
            val sourceId = when {
                item?.text("source") == "漫畫人" && validUrl(key, bookPath) -> MANHUAREN_SOURCE_ID
                item?.text("source") == "漫畫櫃" && ManhuaguiParser.bookUrl(key) == key -> MANHUAGUI_SOURCE_ID
                item?.text(
                    "source",
                ) in listOf(null, "", "包子漫畫") && BaoziSourceParser.bookUrl(key) == key -> BAOZI_SOURCE_ID
                else -> null
            }
            if (item == null || sourceId == null || item.text("url") != key) {
                skipped++
                return@forEach
            }
            val title = item.text("title")?.takeIf { it.isNotBlank() && it.length <= 1000 }
            if (title == null) {
                skipped++
                return@forEach
            }
            val entries = catalogs[key]?.jsonArray ?: emptyList()
            require(entries.size <= 10000)
            val latest = (history[key] as? JsonObject)?.let { record ->
                val url = record.text("chapterUrl")?.takeIf { validChapter(sourceId, key, it) } ?: return@let null
                val time =
                    (record["updated"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..4_102_444_800_000L }
                        ?: return@let null
                val page = (record["page"] as? JsonPrimitive)?.longOrNull?.coerceIn(0, 999)?.toInt() ?: 0
                History(url, page, time)
            }
            val seen = HashSet<String>()
            var catalogValid = true
            val chapters = entries.mapIndexedNotNull { index, raw ->
                val entry = raw as? JsonObject ?: run {
                    catalogValid = false
                    return@mapIndexedNotNull null
                }
                val url = entry.text("url") ?: run {
                    catalogValid = false
                    return@mapIndexedNotNull null
                }
                if (!validChapter(sourceId, key, url)) {
                    catalogValid = false
                    return@mapIndexedNotNull null
                }
                if (url in seen) return@mapIndexedNotNull null
                val name = entry.text("title")?.takeIf { it.isNotBlank() && it.length <= 1000 }
                    ?: run {
                        catalogValid = false
                        return@mapIndexedNotNull null
                    }
                seen.add(url)
                val state = progress[url] as? JsonObject
                val pageLong = (state?.get("page") as? JsonPrimitive)?.longOrNull
                    ?: (saved[url] as? JsonPrimitive)?.longOrNull
                    ?: latest?.takeIf { it.chapterUrl == url }?.page?.toLong() ?: 0
                val page = pageLong.coerceIn(0, 999).toInt()
                val read = (state?.get("complete") as? JsonPrimitive)?.contentOrNull == "true"
                Chapter(url, name, (entries.size - 1 - index).toLong(), page, read)
            }
            val added = (item["updated"] as? JsonPrimitive)?.longOrNull?.coerceIn(0, 4_102_444_800_000L) ?: 0
            books +=
                Book(
                    sourceId, key, title,
                    item.text(
                        "author",
                    )?.take(
                        1000,
                    ),
                    item.text(
                        "summary",
                    )?.take(50000),
                    coverUrl(item.text("cover"), sourceId), added, chapters, catalogValid, latest,
                )
        }
        return Snapshot(books, skipped)
    }

    private fun validChapter(sourceId: Long, book: String, url: String): Boolean = when (sourceId) {
        MANHUAREN_SOURCE_ID -> validUrl(url, chapterPath)
        BAOZI_SOURCE_ID -> BaoziSourceParser.chapterUrl(url) == url
        MANHUAGUI_SOURCE_ID -> runCatching {
            if (ManhuaguiParser.bookUrl(book) != book ||
                ManhuaguiParser.chapterUrl(url) != url
            ) {
                return@runCatching false
            }
            val bookId = manhuaguiBookPath.matchEntire(URI(book).rawPath)?.groupValues?.get(1)
            val chapterId = manhuaguiChapterPath.matchEntire(URI(url).rawPath)?.groupValues?.get(1)
            bookId != null && bookId == chapterId
        }.getOrDefault(false)
        else -> false
    }
}
