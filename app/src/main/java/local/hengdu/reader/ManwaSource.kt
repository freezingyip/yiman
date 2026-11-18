package local.hengdu.reader

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

/** Public search and chapters, based on the app's existing manwa.js source adapter. */
class ManwaSource : HttpSource() {
    override val id = ID
    override val name = "漫蛙"
    override val lang = "zh"
    override val baseUrl = ORIGIN
    override fun getHomeUrl() = "$ORIGIN/search?keyword=one&page=1"
    override fun getMangaUrl(manga: SManga): String = ManwaParser.bookUrl(manga.url) ?: throw IOException("漫蛙作品網址無效")
    override fun getChapterUrl(chapter: SChapter): String =
        ManwaParser.chapterUrl(chapter.url) ?: throw IOException("漫蛙章節網址無效")
    override val supportsLatest = false

    private val websiteClient: OkHttpClient by lazy {
        network.client.newBuilder().apply {
            interceptors().removeAll { it is CloudflareInterceptor }
        }.addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (url.scheme != "https" || url.host != "manwa.me" || url.port != 443) throw IOException("漫蛙網址無效")
            chain.proceed(chain.request())
        }.build()
    }

    override val client: OkHttpClient by lazy {
        // Reuse the legacy source's bounded fetch and image decoding for the site's
        // publicly served encoded images, including cached/offline downloads.
        network.client.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            if (!ManwaParser.isImageUrl(request.url.toString())) throw IOException("漫蛙圖片網址無效")
            val response = MainActivity.proxy(request.url.toString(), null, "")
            if (response.statusCode != 200 || !response.mimeType.startsWith("image/")) {
                response.data?.close()
                throw IOException("無法載入漫蛙圖片")
            }
            val bytes = response.data.use { it.readBytes() }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody(response.mimeType.toMediaTypeOrNull())).build()
        }.build()
    }

    override fun headersBuilder() = Headers.Builder()
        .add(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        )
        .add("Referer", "$ORIGIN/")

    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(
        Dispatchers.IO,
    ) {
        if (page < 1 || query.isBlank()) return@withContext MangasPage(emptyList(), false)
        if (query.length > 100) throw IOException("搜尋文字過長")
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        val html = fetch("$ORIGIN/search?keyword=$q&page=$page")
        MangasPage(ManwaParser.search(html), ManwaParser.hasNextPage(html))
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val url = ManwaParser.bookUrl(manga.url) ?: throw IOException("漫蛙作品網址無效")
        if (!fetchDetails && !fetchChapters) return@withContext SMangaUpdate(manga.copy(), chapters)
        val html = fetch(url)
        SMangaUpdate(
            if (fetchDetails) ManwaParser.details(html, manga) else manga.copy(),
            if (fetchChapters) ManwaParser.chapters(html) else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = ManwaParser.chapterUrl(chapter.url) ?: throw IOException("漫蛙章節網址無效")
        ManwaParser.images(fetch(url)).mapIndexed { index, image -> Page(index, url, image) }
    }

    override fun imageRequest(page: Page): Request {
        val image = page.imageUrl?.takeIf(ManwaParser::isImageUrl) ?: throw IOException("漫蛙圖片網址無效")
        return GET(image, headers)
    }

    private fun fetch(url: String): String {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "manwa.me" || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) || uri.fragment != null
        ) {
            throw IOException("漫蛙網址無效")
        }
        return websiteClient.newCall(GET(url, headers)).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    if (response.code ==
                        403
                    ) {
                        "請在 WebView 中完成漫蛙網站驗證後重試"
                    } else {
                        "無法連線漫蛙"
                    },
                )
            }
            val body = response.body ?: throw IOException("漫蛙回應為空")
            if (body.contentType()?.type != "text" ||
                body.contentType()?.subtype != "html"
            ) {
                throw IOException("漫蛙回應格式無效")
            }
            if (body.contentLength() > 2_000_000) throw IOException("漫蛙回應過大")
            val bytes = body.bytes()
            if (bytes.size > 2_000_000) throw IOException("漫蛙回應過大")
            bytes.toString(Charsets.UTF_8)
        }
    }

    companion object {
        const val ID = 764992021304L
        private const val ORIGIN = "https://manwa.me"
    }
}

internal object ManwaParser {
    private const val ORIGIN = "https://manwa.me"
    private val bookPath = Regex("^/book/\\d+/?$")
    private val chapterPath = Regex("^/chapter/\\d+/?$")

    fun bookUrl(raw: String): String? = checked(raw, bookPath)
    fun chapterUrl(raw: String): String? = checked(raw, chapterPath)

    private fun checked(raw: String, pattern: Regex): String? = runCatching {
        val url = URI(ORIGIN).resolve(raw)
        if (url.scheme != "https" || url.host != "manwa.me" || url.userInfo != null ||
            (url.port != -1 && url.port != 443) || url.query != null || url.fragment != null ||
            !pattern.matches(url.path)
        ) {
            return null
        }
        // A trailing slash is optional on the site; canonicalize for deduplication.
        "$ORIGIN${url.path.trimEnd('/')}"
    }.getOrNull()

    fun isImageUrl(raw: String): Boolean = runCatching {
        val u = URI(raw)
        u.scheme == "https" && u.host == "mwappimgs.cc" && u.userInfo == null &&
            (u.port == -1 || u.port == 443) && u.fragment == null
    }.getOrDefault(false)

    private fun imageUrl(raw: String?): String? = runCatching {
        if (raw.isNullOrBlank()) return null
        URI(ORIGIN).resolve(raw).toASCIIString().takeIf(::isImageUrl)
    }.getOrNull()

    fun search(html: String): List<SManga> = Jsoup.parse(html, ORIGIN).select("ul.book-list > li")
        .mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val url = bookUrl(link.attr("href")) ?: return@mapNotNull null
            val title =
                item.selectFirst(".book-list-info-title")?.text()?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
            val image = item.selectFirst("img")
            SManga.create().apply {
                this.url = url
                this.title = title
                thumbnail_url =
                    imageUrl(image?.attr("data-original").orEmpty().ifBlank { image?.attr("src").orEmpty() })
            }
        }.distinctBy { it.url }

    fun hasNextPage(html: String): Boolean = Jsoup.parse(html, ORIGIN)
        .select("ul.pagination2 > li").last()?.text()?.trim() in setOf("下一页", "下一頁")

    fun details(html: String, manga: SManga): SManga {
        val doc = Jsoup.parse(html, ORIGIN)
        return manga.copy().apply {
            description = doc.selectFirst("#detail .detail-desc")?.text()?.trim()?.ifBlank { null } ?: description
            author = doc.select("p.detail-main-info-author").firstNotNullOfOrNull { field ->
                if (field.selectFirst(".detail-main-info-author-field")?.text()?.contains("作者") != true) {
                    null
                } else {
                    field.selectFirst(".detail-main-info-value")?.text()?.trim()?.ifBlank { null }
                }
            } ?: author
            val image = doc.selectFirst(".detail-main-cover img")
            thumbnail_url =
                imageUrl(image?.attr("data-original").orEmpty().ifBlank { image?.attr("src").orEmpty() })
                    ?: thumbnail_url
            initialized = true
        }
    }

    fun chapters(html: String): List<SChapter> {
        val chapters = Jsoup.parse(html, ORIGIN).select("#detail-list-select a[href]")
            .mapNotNull { link ->
                val url = chapterUrl(link.attr("href")) ?: return@mapNotNull null
                val title =
                    link.attr("title").ifBlank(link::text).trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                SChapter.create().apply {
                    this.url = url
                    name = title
                }
            }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("找不到公開章節；作品可能需要登入或來源格式已改變")
        return chapters.asReversed()
    }

    fun images(html: String): List<String> {
        val imgs = Jsoup.parse(html, ORIGIN).select("#cp_img img[data-r-src]")
        if (imgs.isEmpty()) throw IOException("此章節沒有公開圖片，可能需要登入或暫時不可用")
        if (imgs.size > 3000) throw IOException("章節圖片數量過多")
        return imgs.map { imageUrl(it.attr("data-r-src")) ?: throw IOException("漫蛙圖片來源未受支援") }
    }
}
