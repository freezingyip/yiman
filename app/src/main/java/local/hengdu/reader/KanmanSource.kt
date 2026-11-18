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
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class KanmanSource : HttpSource() {
    override val id = ID
    override val name = "看漫畫"
    override val lang = "zh"
    override val baseUrl = ORIGIN
    override val supportsLatest = false
    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addNetworkInterceptor { chain ->
            if (!KanmanParser.isImageUrl(chain.request().url.toString())) throw IOException("看漫畫圖片網址無效")
            chain.proceed(chain.request())
        }.build()
    }
    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
        .add("Referer", "$ORIGIN/")
    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = withContext(Dispatchers.IO) {
        if (page != 1 || query.isBlank()) return@withContext MangasPage(emptyList(), false)
        if (query.length > 100) throw IOException("搜尋文字過長")
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        MangasPage(KanmanParser.search(fetch("$ORIGIN/api/getsortlist/?search_key=$q")), false)
    }
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val url = KanmanParser.bookUrl(manga.url) ?: throw IOException("看漫畫作品網址無效")
        if (!fetchDetails && !fetchChapters) return@withContext SMangaUpdate(manga.copy(), chapters)
        val html = fetch(url)
        SMangaUpdate(
            if (fetchDetails) KanmanParser.details(html, manga) else manga.copy(),
            if (fetchChapters) KanmanParser.chapters(html, url) else chapters,
        )
    }
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = KanmanParser.chapterUrl(chapter.url) ?: throw IOException("看漫畫章節網址無效")
        val parts = URI(url).path.split('/')
        val chapterId = parts[2].removeSuffix(".html")
        val api = "$ORIGIN/api/getchapterinfov2?product_id=1&productname=kmh&platformname=pc" +
            "&comic_id=${parts[1]}&chapter_newid=$chapterId&isWebp=1&quality=high"
        KanmanParser.images(fetch(api)).mapIndexed { index, image -> Page(index, url, image) }
    }
    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl?.takeIf(KanmanParser::isImageUrl) ?: throw IOException("看漫畫圖片網址無效"),
        headers,
    )
    private fun fetch(url: String): String {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "www.kanman.com" || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443)
        ) {
            throw IOException("看漫畫網址無效")
        }
        val response = MainActivity.proxy(url, null, "")
        if (response.statusCode != 200 || response.mimeType !in setOf("text/html", "application/json", "text/plain")) {
            response.data?.close()
            throw IOException("無法連接看漫畫")
        }
        return response.data.use {
            it.readBytes().also { bytes ->
                if (bytes.size >
                    2_000_000
                ) {
                    throw IOException("來源資料過大")
                }
            }.toString(Charsets.UTF_8)
        }
    }
    companion object {
        const val ID = 764992021305L
        private const val ORIGIN = "https://www.kanman.com"
    }
}

internal object KanmanParser {
    private const val ORIGIN = "https://www.kanman.com"
    private fun canonical(raw: String, pattern: Regex): String? = runCatching {
        val u = URI(ORIGIN).resolve(raw)
        if (u.scheme != "https" ||
            u.host != "www.kanman.com" ||
            u.userInfo != null ||
            (u.port != -1 && u.port != 443) ||
            u.query != null ||
            u.fragment != null ||
            !pattern.matches(u.path)
        ) {
            return null
        }
        "$ORIGIN${u.path}"
    }.getOrNull()
    fun bookUrl(raw: String) = canonical(raw, Regex("^/\\d+/$"))
    fun chapterUrl(raw: String) = canonical(raw, Regex("^/\\d+/[a-zA-Z0-9_-]+\\.html$"))
    fun isImageUrl(raw: String): Boolean = runCatching {
        val u = URI(raw)
        val h = u.host ?: return false
        u.scheme == "https" && u.userInfo == null && (u.port == -1 || u.port == 443) && u.fragment == null &&
            (h == "image.yqmh.com" || h == "kaimanhua.com" || h.endsWith(".kaimanhua.com"))
    }.getOrDefault(false)
    private fun image(raw: String): String? = runCatching {
        val u = URI(ORIGIN).resolve(raw)
        val secure = if (u.scheme == "http" &&
            u.host == "image.yqmh.com"
        ) {
            URI("https", u.userInfo, u.host, u.port, u.path, u.query, u.fragment).toString()
        } else {
            u.toString()
        }
        secure.takeIf(::isImageUrl)
    }.getOrNull()
    private fun obj(
        raw: String,
    ): JsonObject = try {
        Json.parseToJsonElement(raw).jsonObject
    } catch (
        e: Exception,
    ) {
        throw IOException("看漫畫資料格式無效", e)
    }
    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun search(raw: String): List<SManga> = (obj(raw)["data"] as? JsonArray).orEmpty().mapNotNull { item ->
        val x = item as? JsonObject ?: return@mapNotNull null
        val id = x.string("comic_id").takeIf { it.matches(Regex("^\\d+$")) } ?: return@mapNotNull null
        val title = x.string("comic_name").takeIf(String::isNotBlank) ?: return@mapNotNull null
        SManga.create().apply {
            url = "$ORIGIN/$id/"
            this.title = title
            thumbnail_url = image(x.string("cover_img"))
        }
    }.distinctBy { it.url }
    fun details(html: String, manga: SManga): SManga = manga.copy().apply {
        val doc = Jsoup.parse(html, ORIGIN)
        description = doc.selectFirst(".desc-box .content")?.text()?.takeIf(String::isNotBlank) ?: description
        author =
            doc.selectFirst("div.username")?.let { it.attr("title").ifBlank(it::text) }?.takeIf(String::isNotBlank)
                ?: author
        val cover = doc.selectFirst("div.img-box img")
        thumbnail_url =
            image(cover?.attr("data-src").orEmpty().ifBlank { cover?.attr("src").orEmpty() }) ?: thumbnail_url
        initialized = true
    }
    fun chapters(html: String, book: String): List<SChapter> {
        val base = bookUrl(book) ?: throw IOException("看漫畫作品網址無效")
        val bookId = URI(base).path.split('/')[1]
        val result = Jsoup.parse(html, base).select("#j_chapter_list a[href]").mapNotNull { a ->
            val url = chapterUrl(a.attr("href")) ?: return@mapNotNull null
            if (URI(url).path.split('/')[1] != bookId) return@mapNotNull null
            val title = a.attr("title").ifBlank(a::text).trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            SChapter.create().apply {
                this.url = url
                name = title
            }
        }.distinctBy { it.url }
        if (result.isEmpty()) throw IOException("找不到看漫畫章節")
        return result.asReversed()
    }
    fun images(raw: String): List<String> {
        val data = obj(raw)["data"] as? JsonObject ?: throw IOException("此章節需要登入或暫不可讀")
        val chapter = data["current_chapter"] as? JsonObject ?: throw IOException("此章節需要登入或暫不可讀")
        val price = chapter.string("price").toDoubleOrNull()
        val vip = chapter.string("is_vip").toDoubleOrNull()
        if (price == null || vip == null || price != 0.0 || vip != 0.0) throw IOException("此章節需要登入、付費或未標明免費狀態")
        val images = chapter["chapter_img_list"] as? JsonArray ?: throw IOException("此章節沒有公開圖片")
        if (images.isEmpty() || images.size > 3000) throw IOException("此章節沒有公開圖片")
        return images.map { image((it as? JsonPrimitive)?.contentOrNull.orEmpty()) ?: throw IOException("看漫畫圖片網址不受支援") }
    }
}
