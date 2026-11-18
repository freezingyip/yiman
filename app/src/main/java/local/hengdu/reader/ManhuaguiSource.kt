package local.hengdu.reader

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class ManhuaguiSource : HttpSource() {
    override val id = ID
    override val name = "漫畫櫃"
    override val lang = "zh"
    override val baseUrl = ORIGIN
    override val supportsLatest = false

    override fun getMangaUrl(manga: SManga): String =
        ManhuaguiParser.bookUrl(manga.url) ?: throw IOException("漫畫櫃作品網址無效")
    override fun getChapterUrl(chapter: SChapter): String =
        ManhuaguiParser.chapterUrl(chapter.url) ?: throw IOException("漫畫櫃章節網址無效")

    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (url.scheme != "https" || url.port != 443 || !ManhuaguiParser.imageHost(url.host)) {
                throw IOException("漫畫櫃圖片網址無效")
            }
            chain.proceed(chain.request())
        }.build()
    }

    override fun headersBuilder() = Headers.Builder()
        .add(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        )
        .add("Referer", "$ORIGIN/")

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        if (page != 1) {
            MangasPage(emptyList(), false)
        } else {
            MangasPage(ManhuaguiParser.rank(fetch("$ORIGIN/rank/")), false)
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(
        Dispatchers.IO,
    ) {
        if (page != 1 || query.isBlank()) return@withContext MangasPage(emptyList(), false)
        if (query.length > 100) throw IOException("搜尋文字過長")
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        MangasPage(ManhuaguiParser.search(fetch("$ORIGIN/s/${q}_p1.html")), false)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val url = ManhuaguiParser.bookUrl(manga.url) ?: throw IOException("漫畫櫃作品網址無效")
        if (!fetchDetails && !fetchChapters) return@withContext SMangaUpdate(manga.copy(), chapters)
        val html = fetch(url)
        SMangaUpdate(
            if (fetchDetails) ManhuaguiParser.details(html, manga) else manga.copy(),
            if (fetchChapters) ManhuaguiParser.chapters(html, url) else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = ManhuaguiParser.chapterUrl(chapter.url) ?: throw IOException("漫畫櫃章節網址無效")
        ManhuaguiParser.images(fetch(url), url).mapIndexed { index, image -> Page(index, url, image) }
    }

    override fun imageRequest(page: Page): Request {
        val image = page.imageUrl?.takeIf(ManhuaguiParser::isImageUrl) ?: throw IOException("漫畫櫃圖片網址無效")
        return GET(image, headers)
    }

    private fun fetch(url: String): String {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "www.manhuagui.com" || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) || uri.fragment != null
        ) {
            throw IOException("漫畫櫃網址無效")
        }
        val response = MainActivity.proxy(url, null, "")
        if (response.statusCode != 200 || !response.mimeType.equals("text/html", true)) {
            response.data?.close()
            throw IOException("無法連線漫畫櫃")
        }
        return response.data.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > 2_000_000) throw IOException("漫畫櫃回應過大")
            bytes.toString(Charsets.UTF_8)
        }
    }

    companion object {
        const val ID = 764992021303L
        private const val ORIGIN = "https://www.manhuagui.com"
    }
}

internal object ManhuaguiParser {
    private const val ORIGIN = "https://www.manhuagui.com"
    private val bookPath = Regex("^/comic/\\d+/$")
    private val chapterPath = Regex("^/comic/(\\d+)/(\\d+)\\.html$")
    private val imageDomains = setOf("mhgui.com", "hamreus.com", "manhuagui.com")
    private val packed = Regex("""\}\('((?:\\.|[^'\\])*)',\s*(\d+),\s*(\d+),\s*'([A-Za-z0-9+/=]+)'""")
    private val words = Regex("""\b\w+\b""")
    private val dataPattern = Regex("""\b(imgData|reader)\s*\(\s*(\{[\s\S]*?\})\s*\)\s*\.preInit\s*\(""")

    fun bookUrl(raw: String): String? = checked(raw, bookPath)
    fun chapterUrl(raw: String): String? = checked(raw, chapterPath)

    private fun checked(raw: String, path: Regex): String? = runCatching {
        val url = URI(ORIGIN).resolve(raw)
        url.toASCIIString().takeIf {
            url.scheme == "https" && url.host == "www.manhuagui.com" && url.userInfo == null &&
                (url.port == -1 || url.port == 443) && url.query == null && url.fragment == null &&
                path.matches(url.path)
        }
    }.getOrNull()

    fun imageHost(host: String): Boolean = imageDomains.any {
        host.equals(it, true) || host.lowercase().endsWith(".$it")
    }

    fun isImageUrl(raw: String): Boolean = runCatching {
        val u = URI(raw)
        u.scheme == "https" && u.host != null && imageHost(u.host) && u.userInfo == null &&
            (u.port == -1 || u.port == 443) && u.fragment == null
    }.getOrDefault(false)

    private fun imageUrl(raw: String?): String? = runCatching {
        if (raw.isNullOrBlank()) return null
        URI(ORIGIN).resolve(raw).toASCIIString().takeIf(::isImageUrl)
    }.getOrNull()

    fun search(html: String): List<SManga> = Jsoup.parse(html, ORIGIN).select("li.cf .book-cover a[href][title]")
        .mapNotNull { a ->
            val url = bookUrl(a.attr("href")) ?: return@mapNotNull null
            val title = a.attr("title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val image = a.selectFirst("img")
            SManga.create().apply {
                this.url = url
                this.title = title
                thumbnail_url = imageUrl(image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() })
            }
        }.distinctBy { it.url }

    fun rank(html: String): List<SManga> = Jsoup.parse(html, ORIGIN).select(".rank-title h5 a[href]")
        .mapNotNull { a ->
            val url = bookUrl(a.attr("href")) ?: return@mapNotNull null
            val title = a.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            SManga.create().apply {
                this.url = url
                this.title = title
            }
        }.distinctBy { it.url }

    fun details(html: String, manga: SManga): SManga {
        val doc = Jsoup.parse(html, ORIGIN)
        return manga.copy().apply {
            description = doc.selectFirst("#intro-all p")?.text()?.ifBlank { null } ?: description
            author = doc.selectFirst(".detail-list li:nth-child(2) span:nth-child(2) a")?.let {
                it.attr("title").ifBlank(it::text).trim().ifBlank { null }
            } ?: author
            val image = doc.selectFirst(".book-cover img")
            thumbnail_url =
                imageUrl(image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() }) ?: thumbnail_url
            initialized = true
        }
    }

    fun chapters(html: String, book: String): List<SChapter> {
        val bookUri = bookUrl(book)?.let(::URI) ?: throw IOException("漫畫櫃作品網址無效")
        val bookId = bookUri.path.split('/')[2]
        val doc = Jsoup.parse(html, ORIGIN)
        val state = doc.selectFirst("input#__VIEWSTATE")?.attr("value")
        val listing = if (state.isNullOrBlank()) doc else Jsoup.parse(unlz(state), ORIGIN)
        val result = listing.select(".chapter-list ul").flatMap { list ->
            // A book may combine individual chapters with volumes and extras. Their
            // numbers belong to different sequences and must not be inferred as
            // ordinary chapter numbers by Mihon.
            var heading = list.closest(".chapter-list")?.previousElementSibling()
            while (heading != null && heading.normalName() != "h4") heading = heading.previousElementSibling()
            val unnumbered = heading?.text()?.let(ManhuaguiChapterNumbering::isNonChapterGroup) == true
            list.select("a[href]").asReversed().map { it to unnumbered }
        }.mapNotNull { (a, unnumbered) ->
            val url = chapterUrl(a.attr("href")) ?: return@mapNotNull null
            if (chapterPath.matchEntire(URI(url).path)?.groupValues?.get(1) != bookId) return@mapNotNull null
            val title = a.attr("title").ifBlank(a::text).trim()
            if (title.isBlank()) return@mapNotNull null
            SChapter.create().apply {
                this.url = url
                name = title
                if (unnumbered) chapter_number = -2f
            }
        }.distinctBy { it.url }
        if (result.isEmpty()) throw IOException("此作品沒有提供公開章節")
        return result.asReversed()
    }

    fun images(html: String, chapter: String): List<String> {
        val url = chapterUrl(chapter) ?: throw IOException("漫畫櫃章節網址無效")
        val parts = chapterPath.matchEntire(URI(url).path) ?: throw IOException("漫畫櫃章節網址無效")
        val match = dataPattern.find(unpack(html)) ?: throw IOException("來源章節資料不完整")
        val data = try {
            Json.parseToJsonElement(match.groupValues[2]) as JsonObject
        } catch (e: Exception) {
            throw IOException("來源章節資料無效", e)
        }
        fun JsonObject.value(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        val reader = match.groupValues[1] == "reader"
        val bid = data.value(if (reader) "bookId" else "bid")
        val cid = data.value(if (reader) "chapterId" else "cid")
        val token = data["sl"] as? JsonObject ?: throw IOException("來源章節資料無效")
        val expiration = token.value("e")?.takeIf { it.matches(Regex("^\\d+$")) } ?: throw IOException("來源章節資料無效")
        val signature = token.value("m")?.takeIf { it.matches(Regex("^[A-Za-z0-9_+/=-]{1,512}$")) }
            ?: throw IOException("來源章節資料無效")
        if (bid != parts.groupValues[1] || cid != parts.groupValues[2]) throw IOException("來源章節不匹配")
        val entries = data[if (reader) "images" else "files"] as? JsonArray ?: throw IOException("來源章節資料無效")
        if (entries.isEmpty() || entries.size > 3000) throw IOException("此章節沒有公開圖片")
        val prefix = if (reader) {
            ""
        } else {
            data.value("path")?.takeIf { it.endsWith('/') }
                ?: throw IOException("來源章節資料無效")
        }
        return entries.map { item ->
            val value = (item as? JsonPrimitive)?.contentOrNull ?: throw IOException("來源圖片路徑無效")
            if (!reader && !value.matches(Regex("^[-\\w.]+$"))) throw IOException("來源圖片路徑無效")
            val path = safePath(prefix + value)
            val e = URLEncoder.encode(expiration, "UTF-8")
            val m = URLEncoder.encode(signature, "UTF-8")
            "https://i.hamreus.com$path?e=$e&m=$m"
        }
    }

    private fun safePath(path: String): String {
        if (path.length > 2048 || !path.startsWith('/') || path.startsWith("//") ||
            path.any { it == '?' || it == '#' || it == '\\' || it.code < 32 }
        ) {
            throw IOException("來源圖片路徑無效")
        }
        val segments = path.drop(1).split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) throw IOException("來源圖片路徑無效")
        return segments.joinToString("/", prefix = "/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20").replace("%21", "!").replace("%27", "'")
                .replace("%28", "(").replace("%29", ")").replace("%7E", "~")
        }
    }

    private fun unpack(html: String): String {
        val match = packed.find(html) ?: throw IOException("來源章節格式已變更")
        val base = match.groupValues[2].toIntOrNull() ?: throw IOException("來源資料格式無效")
        val count = match.groupValues[3].toIntOrNull() ?: throw IOException("來源資料格式無效")
        if (base !in 2..62 || count !in 0..10000) throw IOException("來源資料格式無效")
        val keys = unlz(match.groupValues[4]).split('|')
        val payload = match.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        fun encode(n: Int): String {
            val digit = n % base
            val char = if (digit > 35) (digit + 29).toChar() else Character.forDigit(digit, 36)
            return (if (n >= base) encode(n / base) else "") + char
        }
        val map = (0 until minOf(count, keys.size)).filter {
            keys[it].isNotEmpty()
        }.associate { encode(it) to keys[it] }
        return words.replace(payload) { map[it.value] ?: it.value }
    }

    internal fun unlz(input: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        if (input.isEmpty() || input.length > 200000 || input.any { it !in alphabet }) throw IOException("壓縮資料無效")
        var idx = 1
        var value = alphabet.indexOf(input[0])
        var position = 32
        fun read(n: Int): Int {
            var bits = 0
            var power = 1
            repeat(n) {
                if (value and position != 0) bits = bits or power
                power = power shl 1
                position = position shr 1
                if (position == 0) {
                    position = 32
                    if (idx >= input.length) throw IOException("壓縮資料不完整")
                    value = alphabet.indexOf(input[idx++])
                }
            }
            return bits
        }
        val first = when (read(2)) {
            0 -> read(8).toChar().toString()
            1 -> read(16).toChar().toString()
            else -> return ""
        }
        val dict = mutableListOf<String?>(null, null, null, first)
        var enlarge = 4
        var bitCount = 3
        var w = first
        val out = StringBuilder(first)
        var dictionaryBytes = first.length
        fun add(entry: String) {
            dictionaryBytes += entry.length
            if (dict.size >= 200000 || dictionaryBytes > 16_000_000) throw IOException("壓縮資料過大")
            dict.add(entry)
        }
        while (idx <= input.length && out.length <= 2_000_000) {
            var code = read(bitCount)
            when (code) {
                0, 1 -> {
                    add(read(if (code == 0) 8 else 16).toChar().toString())
                    code = dict.lastIndex
                    enlarge--
                }
                2 -> return out.toString()
            }
            if (enlarge == 0) {
                enlarge = 1 shl bitCount
                bitCount++
            }
            if (bitCount > 20) throw IOException("壓縮資料過大")
            val entry = dict.getOrNull(code) ?: if (code == dict.size) w + w.first() else throw IOException("壓縮資料錯誤")
            if (out.length + entry.length > 2_000_000) throw IOException("壓縮資料過大")
            out.append(entry)
            add(w + entry.first())
            enlarge--
            w = entry
            if (enlarge == 0) {
                enlarge = 1 shl bitCount
                bitCount++
            }
        }
        throw IOException("壓縮資料過大")
    }
}

internal object ManhuaguiChapterNumbering {
    private val chapter = Regex("^第?\\s*\\d+(?:\\.\\d+)?\\s*[話话回]")
    private val volume = Regex("^第?\\s*\\d+(?:\\.\\d+)?\\s*卷")

    fun isNonChapterGroup(heading: String): Boolean =
        heading.contains("单行本") || heading.contains("單行本") || heading.contains("番外")

    fun hasMixedVolumesAndChapters(names: List<String>): Boolean =
        names.any { volume.containsMatchIn(it.trim()) } && names.any { chapter.containsMatchIn(it.trim()) }
}
