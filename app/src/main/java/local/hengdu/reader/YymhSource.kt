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

class YymhSource : HttpSource() {
    override val id = ID
    override val name = "歪漫屋"
    override val lang = "zh"
    override val baseUrl = ORIGIN
    override val supportsLatest = false

    override fun getMangaUrl(manga: SManga): String = YymhParser.bookUrl(manga.url) ?: throw IOException("歪漫屋作品網址無效")
    override fun getChapterUrl(chapter: SChapter): String {
        val parts = YymhParser.chapterParts(chapter.url) ?: throw IOException("歪漫屋章節網址無效")
        return "$ORIGIN/home/book/capter/id/${parts.id}"
    }
    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addNetworkInterceptor { chain ->
            if (!YymhParser.imageUrl(chain.request().url.toString())) throw IOException("歪漫屋圖片網址無效")
            chain.proceed(chain.request())
        }.build()
    }
    override fun headersBuilder() = Headers.Builder().add(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36",
    ).add("Referer", "$ORIGIN/")
    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = withContext(Dispatchers.IO) {
        if (page != 1 || query.isBlank()) return@withContext MangasPage(emptyList(), false)
        if (query.length > 100) throw IOException("搜尋文字過長")
        MangasPage(
            YymhParser.search(
                fetch("$ORIGIN/home/api/searchk?keyword=${URLEncoder.encode(query.trim(), "UTF-8")}&type=1&pageNo=1"),
            ),
            false,
        )
    }
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val url = YymhParser.bookUrl(manga.url) ?: throw IOException("歪漫屋作品網址無效")
        val book = YymhParser.bookId(url) ?: throw IOException("歪漫屋作品網址無效")
        val details = if (fetchDetails) YymhParser.details(fetch(url), manga) else manga.copy()
        val catalog = if (fetchChapters) {
            val first = YymhParser.chapterPage(fetch(YymhParser.apiUrl(book, 1)), book, 1)
            val all = first.chapters.toMutableList()
            for (page in 2..first.totalPages.coerceAtMost(
                200,
            )) {
                all.addAll(YymhParser.chapterPage(fetch(YymhParser.apiUrl(book, page)), book, page).chapters)
            }
            if (all.isEmpty()) throw IOException("找不到歪漫屋公開章節")
            all.distinctBy { it.url }.asReversed()
        } else {
            chapters
        }
        SMangaUpdate(details, catalog)
    }
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val parts = YymhParser.chapterParts(chapter.url) ?: throw IOException("歪漫屋章節網址無效")
        val page = YymhParser.chapterPage(fetch(YymhParser.apiUrl(parts.book, parts.page)), parts.book, parts.page)
        val item =
            page.items.firstOrNull { YymhParser.field(it, "id") == parts.id } ?: throw IOException("找不到歪漫屋章節，請重新整理目錄")
        if (!YymhParser.isPublic(item)) throw IOException("此章節需要登入、付費或未公開圖片")
        val images = YymhParser.images(item) ?: throw IOException("此話暫無可讀取圖片")
        images.mapIndexed { index, image -> Page(index, chapter.url, image) }
    }
    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl?.takeIf(YymhParser::imageUrl) ?: throw IOException("歪漫屋圖片網址無效"),
        headers,
    )
    private fun fetch(url: String): String {
        val u = URI(url)
        if (u.scheme != "https" || u.host != "yymh889.com" || u.userInfo != null ||
            (u.port != -1 && u.port != 443)
        ) {
            throw IOException("歪漫屋網址無效")
        }
        val response = MainActivity.proxy(url, null, "")
        if (response.statusCode != 200 || response.mimeType !in setOf("text/html", "application/json", "text/plain")) {
            response.data?.close()
            throw IOException("無法連接歪漫屋")
        }
        val body = response.data.use { it.readBytes() }
        if (body.size > 2_000_000) throw IOException("歪漫屋回應過大")
        return body.toString(Charsets.UTF_8)
    }
    companion object {
        const val ID = 764992021310L
        private const val ORIGIN = "https://yymh889.com"
    }
}

internal object YymhParser {
    private const val ORIGIN = "https://yymh889.com"
    private val digits = Regex("^\\d+$")
    private val bookPattern = Regex("^/home/book/index/id/(\\d+)/?$")
    private val chapterPattern = Regex("^/home/book/capter/id/(\\d+)/?$")
    data class ChapterParts(val id: String, val book: String, val page: Int)
    data class ChapterPage(val chapters: List<SChapter>, val items: List<JsonObject>, val totalPages: Int)
    fun field(obj: JsonObject, key: String): String = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun isPublic(item: JsonObject): Boolean =
        field(item, "score").toDoubleOrNull() == 0.0 && field(item, "imagelist").isNotBlank()
    private fun site(raw: String): URI? = runCatching {
        URI(ORIGIN).resolve(raw).takeIf {
            it.scheme == "https" && it.host == "yymh889.com" && it.userInfo == null &&
                (it.port == -1 || it.port == 443) &&
                it.fragment == null
        }
    }.getOrNull()
    fun bookId(raw: String): String? = site(raw)?.let {
        if (it.query ==
            null
        ) {
            bookPattern.matchEntire(it.path)?.groupValues?.get(1)
        } else {
            null
        }
    }
    fun bookUrl(raw: String): String? = bookId(raw)?.let { "$ORIGIN/home/book/index/id/$it" }
    fun chapterParts(raw: String): ChapterParts? {
        val u = site(raw) ?: return null
        val id = chapterPattern.matchEntire(u.path)?.groupValues?.get(1) ?: return null
        val fields = u.rawQuery?.split('&')?.map { it.split('=', limit = 2) } ?: return null
        if (fields.size != 2 || fields.any { it.size != 2 } ||
            fields.map { it[0] }.toSet() != setOf("book", "page")
        ) {
            return null
        }
        val book = fields.first { it[0] == "book" }[1].takeIf(digits::matches) ?: return null
        val page = fields.first { it[0] == "page" }[1].toIntOrNull()?.takeIf { it in 1..200 } ?: return null
        return ChapterParts(id, book, page)
    }
    fun apiUrl(book: String, page: Int): String {
        if (!digits.matches(book) || page !in 1..200) throw IOException("歪漫屋章節資料無效")
        return "$ORIGIN/home/api/chapter_list/tp/$book-1-$page-10"
    }
    fun imageUrl(raw: String): Boolean = runCatching {
        val u = URI(raw)
        u.scheme == "https" && (u.host == "yymhimg1.top" || u.host.endsWith(".yymhimg1.top")) && u.userInfo == null &&
            (u.port == -1 || u.port == 443) &&
            u.fragment == null
    }.getOrDefault(false)
    private fun image(
        raw: String,
    ): String? = runCatching { URI(ORIGIN).resolve(raw).toASCIIString().takeIf(::imageUrl) }.getOrNull()
    private fun result(raw: String): JsonObject = try {
        val data = Json.parseToJsonElement(raw) as JsonObject
        data["result"] as JsonObject
    } catch (e: Exception) {
        throw IOException("歪漫屋資料格式已變更", e)
    }
    fun search(raw: String): List<SManga> {
        val items = result(raw)["list"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val id = field(item, "id").takeIf(digits::matches) ?: return@mapNotNull null
            val title = field(item, "title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            SManga.create().apply {
                url = "$ORIGIN/home/book/index/id/$id"
                this.title = title
                thumbnail_url =
                    image(field(item, "image").ifBlank { field(item, "cover") })
            }
        }.distinctBy { it.url }
    }
    fun details(html: String, manga: SManga): SManga {
        val doc = Jsoup.parse(html, ORIGIN)
        return manga.copy().apply {
            description = doc.selectFirst("p.book-desc")?.text()?.trim()?.ifBlank { null } ?: description
            author =
                doc.select("p.info").firstOrNull {
                    it.text().contains("作者")
                }?.selectFirst("a")?.text()?.trim()?.ifBlank { null }
                    ?: author
            val img = doc.selectFirst("div.cover img")
            thumbnail_url = img?.let { image(it.attr("webp-src").ifBlank { it.attr("src") }) } ?: thumbnail_url
            initialized = true
        }
    }
    fun chapterPage(raw: String, book: String, page: Int): ChapterPage {
        if (!digits.matches(book) || page !in 1..200) throw IOException("歪漫屋章節資料無效")
        val result = result(raw)
        val items =
            (result["list"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: throw IOException("歪漫屋章節資料格式已變更")
        val chapters = items.mapNotNull { item ->
            if (!isPublic(item)) return@mapNotNull null
            val id = field(item, "id").takeIf(digits::matches) ?: return@mapNotNull null
            SChapter.create().apply {
                url = "$ORIGIN/home/book/capter/id/$id?book=$book&page=$page"
                name =
                    field(item, "title").ifBlank { "章節" }
            }
        }
        val pages = field(result, "totalPage").toIntOrNull()?.coerceIn(1, 200) ?: 1
        return ChapterPage(chapters, items, pages)
    }
    fun images(item: JsonObject): List<String>? {
        val data = field(item, "imagelist")
        if (data.isBlank()) return null
        val images = data.split(',').filter(String::isNotBlank).map { image(it) ?: throw IOException("歪漫屋圖片網址不受支援") }
        if (images.isEmpty() || images.size > 3000) throw IOException("歪漫屋圖片數量無效")
        return images
    }
}
