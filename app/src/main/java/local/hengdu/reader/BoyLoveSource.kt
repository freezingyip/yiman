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

class BoyLoveSource : HttpSource() {
    override val id = ID
    override val name = "BoyLove"
    override val lang = "zh"
    override val baseUrl = BoyLoveParser.ORIGIN
    override val supportsLatest = false

    override fun getMangaUrl(manga: SManga): String =
        BoyLoveParser.bookUrl(manga.url) ?: throw IOException("BoyLove 作品網址無效")
    override fun getChapterUrl(chapter: SChapter): String =
        BoyLoveParser.chapterUrl(chapter.url) ?: throw IOException("BoyLove 章節網址無效")
    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addNetworkInterceptor { chain ->
            if (!BoyLoveParser.imageUrlAllowed(chain.request().url.toString())) throw IOException("BoyLove 圖片網址無效")
            chain.proceed(chain.request())
        }.build()
    }
    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(
        Dispatchers.IO,
    ) {
        if (page < 1 || query.isBlank()) return@withContext MangasPage(emptyList(), false)
        if (query.length > 100) throw IOException("搜尋文字過長")
        val keyword = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        BoyLoveParser.search(fetch("$baseUrl/home/api/searchk?keyword=$keyword&type=1&pageNo=$page"))
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val url = BoyLoveParser.bookUrl(manga.url) ?: throw IOException("BoyLove 作品網址無效")
        if (!fetchDetails && !fetchChapters) return@withContext SMangaUpdate(manga.copy(), chapters)
        val html = fetch(url)
        SMangaUpdate(
            if (fetchDetails) BoyLoveParser.details(html, manga) else manga.copy(),
            if (fetchChapters) BoyLoveParser.chapters(html) else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = BoyLoveParser.chapterUrl(chapter.url) ?: throw IOException("BoyLove 章節網址無效")
        BoyLoveParser.images(fetch(url)).mapIndexed { i, image -> Page(i, url, image) }
    }
    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl?.takeIf(BoyLoveParser::imageUrlAllowed) ?: throw IOException("BoyLove 圖片網址無效"),
        headers,
    )

    private fun fetch(url: String): String {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "boylove.cc" || uri.userInfo != null || uri.port !in listOf(-1, 443) ||
            uri.fragment != null
        ) {
            throw IOException("BoyLove 網址無效")
        }
        val response = MainActivity.proxy(url, null, "")
        if (response.statusCode != 200 || response.mimeType !in setOf("text/html", "application/json", "text/plain")) {
            response.data?.close()
            throw IOException("無法連線 BoyLove，或網站要求驗證")
        }
        return response.data.use { input ->
            input.readBytes().also {
                if (it.size >
                    2_000_000
                ) {
                    throw IOException("BoyLove 回應過大")
                }
            }.toString(Charsets.UTF_8)
        }
    }
    companion object {
        const val ID = 764992021307L
    }
}

internal object BoyLoveParser {
    const val ORIGIN = "https://boylove.cc"
    private val json = Json { ignoreUnknownKeys = true }
    private val dataExpression = Regex("""let\s+data\s*=\s*JSON\.parse\(\s*(\"(?:\\.|[^\"\\])*\")\s*\)\s*;""")
    private fun url(raw: String, path: Regex): String? = runCatching {
        val u = URI(ORIGIN).resolve(raw)
        if (u.scheme != "https" || u.host != "boylove.cc" || u.userInfo != null || u.port !in listOf(-1, 443) ||
            u.query != null ||
            u.fragment != null ||
            !path.matches(u.path)
        ) {
            null
        } else {
            ORIGIN + u.path.trimEnd('/')
        }
    }.getOrNull()
    fun bookUrl(raw: String) = url(raw, Regex("^/home/book/index/id/\\d+/?$"))
    fun chapterUrl(raw: String) = url(raw, Regex("^/home/book/capter/id/\\d+/?$"))
    fun imageUrlAllowed(raw: String): Boolean = runCatching {
        val u = URI(raw)
        u.scheme == "https" && (
            u.host == "boylove.cc" || u.host?.endsWith(
                ".boylove.cc",
            ) == true
            ) && u.userInfo == null &&
            u.port in listOf(-1, 443) &&
            u.fragment == null
    }.getOrDefault(false)
    private fun image(raw: String?): String? = runCatching {
        if (raw.isNullOrBlank()) null else URI(ORIGIN).resolve(raw).toASCIIString().takeIf(::imageUrlAllowed)
    }.getOrNull()
    private fun JsonObject.value(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun search(body: String): MangasPage {
        val root = runCatching {
            json.parseToJsonElement(body) as JsonObject
        }.getOrElse { throw IOException("BoyLove 搜尋資料無效", it) }
        val result = root["result"] as? JsonObject ?: throw IOException("BoyLove 搜尋資料無效")
        val list = result["list"] as? JsonArray ?: throw IOException("BoyLove 搜尋資料無效")
        val items = list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.value("id")?.takeIf { it.matches(Regex("^\\d+$")) } ?: return@mapNotNull null
            val title = obj.value("title")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            SManga.create().apply {
                this.url = "$ORIGIN/home/book/index/id/$id"
                this.title = title
                thumbnail_url =
                    image(obj.value("image")?.ifBlank { obj.value("cover").orEmpty() })
            }
        }.distinctBy { it.url }
        return MangasPage(items, result.value("lastPage") == "false")
    }
    fun details(html: String, manga: SManga): SManga {
        val doc = Jsoup.parse(html, ORIGIN)
        return manga.copy().apply {
            description = doc.selectFirst("span.detail-text")?.text()?.trim()?.ifBlank { null } ?: description
            author =
                doc.select("p.data").firstOrNull {
                    it.text().contains("作者")
                }?.selectFirst("a")?.text()?.trim()?.ifBlank { null }
                    ?: author
            initialized = true
        }
    }
    fun chapters(html: String): List<SChapter> {
        val list = dataExpression.findAll(html).firstNotNullOfOrNull { match ->
            runCatching {
                val encoded = (json.parseToJsonElement(match.groupValues[1]) as JsonPrimitive).content
                (json.parseToJsonElement(encoded) as JsonObject)["list"] as? JsonArray
            }.getOrNull()
        } ?: throw IOException("找不到 BoyLove 公開章節")
        val chapters = list.mapNotNull { value ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val id = obj.value("id")?.takeIf { it.matches(Regex("^\\d+$")) } ?: return@mapNotNull null
            if (obj.value("isvip") != "0") return@mapNotNull null
            SChapter.create().apply {
                this.url = "$ORIGIN/home/book/capter/id/$id"
                name =
                    obj.value("title")?.trim()?.ifBlank { null } ?: "章節"
            }
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("找不到免登入可閱讀的章節")
        return chapters.asReversed()
    }
    fun images(html: String): List<String> {
        val elements = Jsoup.parse(html, ORIGIN).select(".reader-cartoon-chapter .reader-cartoon-image img")
        if (elements.isEmpty()) throw IOException("此章節未提供公開圖片或需要登入")
        if (elements.size > 3000) throw IOException("章節圖片過多")
        return elements.map { image(it.attr("data-original")) ?: throw IOException("BoyLove 圖片網址無效") }
    }
}
