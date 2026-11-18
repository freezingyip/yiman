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

// Site format reference: https://github.com/suiyuran/aidoku-zh-sources/tree/main/src/rust/zh.mycomic
class MyComicSource : HttpSource() {
    override val id = ID
    override val name = "MYCOMIC"
    override val lang = "zh"
    override val baseUrl = ORIGIN
    override val supportsLatest = false

    override fun getMangaUrl(manga: SManga): String =
        MyComicParser.bookUrl(manga.url) ?: throw IOException("MYCOMIC 作品網址無效")
    override fun getChapterUrl(chapter: SChapter): String =
        MyComicParser.chapterUrl(chapter.url) ?: throw IOException("MYCOMIC 章節網址無效")

    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addNetworkInterceptor { chain ->
            if (!MyComicParser.isAllowedUrl(chain.request().url.toString())) throw IOException("MYCOMIC 網址不受支援")
            chain.proceed(chain.request())
        }.build()
    }

    override fun headersBuilder() = Headers.Builder().add("Referer", "$ORIGIN/")
    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(
        Dispatchers.IO,
    ) {
        if (page < 1 || query.isBlank()) return@withContext MangasPage(emptyList(), false)
        if (query.length > 100) throw IOException("搜尋文字過長")
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val html = fetch("$ORIGIN/comics?q=$encoded&page=$page")
        MangasPage(MyComicParser.search(html), MyComicParser.hasNextPage(html, page))
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val url = MyComicParser.bookUrl(manga.url) ?: throw IOException("MYCOMIC 作品網址無效")
        if (!fetchDetails && !fetchChapters) return@withContext SMangaUpdate(manga.copy(), chapters)
        val html = fetch(url)
        SMangaUpdate(
            if (fetchDetails) MyComicParser.details(html, manga) else manga.copy(),
            if (fetchChapters) MyComicParser.chapters(html) else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = MyComicParser.chapterUrl(chapter.url) ?: throw IOException("MYCOMIC 章節網址無效")
        MyComicParser.images(fetch(url)).mapIndexed { index, image -> Page(index, url, image) }
    }

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl?.takeIf(MyComicParser::isImageUrl) ?: throw IOException("MYCOMIC 圖片網址無效"),
        headers,
    )

    private fun fetch(url: String): String = client.newCall(GET(url, headers)).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException(
                if (response.code ==
                    403
                ) {
                    "請在 WebView 中完成 MYCOMIC 網站驗證後重試"
                } else {
                    "無法連線 MYCOMIC（${response.code}）"
                },
            )
        }
        val body = response.body ?: throw IOException("MYCOMIC 回應為空")
        if (body.contentLength() > 4_000_000) throw IOException("MYCOMIC 回應過大")
        val data = body.bytes()
        if (data.size > 4_000_000) throw IOException("MYCOMIC 回應過大")
        val html = data.toString(Charsets.UTF_8)
        if (Jsoup.parse(
                html,
            ).title().contains(
                Regex("just a moment|attention required", RegexOption.IGNORE_CASE),
            )
        ) {
            throw IOException("請在 WebView 中完成 MYCOMIC 網站驗證後重試")
        }
        html
    }

    companion object {
        const val ID = 764992021311L
        private const val ORIGIN = "https://mycomic.com"
    }
}

internal object MyComicParser {
    private const val ORIGIN = "https://mycomic.com"
    private val bookPattern = Regex("^/(?:cn/)?comics/\\d+/?$")
    private val chapterPattern = Regex("^/(?:cn/)?chapters/\\d+/?$")

    private fun siteUrl(raw: String, pattern: Regex): String? = runCatching {
        val uri = URI(ORIGIN).resolve(raw)
        if (uri.scheme != "https" || uri.host != "mycomic.com" || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) ||
            uri.query != null ||
            !pattern.matches(uri.path)
        ) {
            return null
        }
        "$ORIGIN${uri.path.removePrefix("/cn").trimEnd('/')}"
    }.getOrNull()

    fun bookUrl(raw: String) = siteUrl(raw, bookPattern)
    fun chapterUrl(raw: String) = siteUrl(raw, chapterPattern)
    fun isImageUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" &&
            uri.host == "biccam.com" &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.fragment == null
    }.getOrDefault(false)
    fun isAllowedUrl(raw: String): Boolean = isImageUrl(raw) || runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && uri.host == "mycomic.com" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
    private fun image(
        raw: String,
    ): String? = runCatching { URI(ORIGIN).resolve(raw).toString().takeIf(::isImageUrl) }.getOrNull()

    fun search(html: String): List<SManga> = Jsoup.parse(html, ORIGIN).select(".group a[href]").mapNotNull { a ->
        val url = bookUrl(a.attr("href")) ?: return@mapNotNull null
        val img = a.selectFirst("img") ?: return@mapNotNull null
        val title = img.attr("alt").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        SManga.create().apply {
            this.url = url
            this.title = title
            thumbnail_url =
                image(img.attr("data-src").ifBlank { img.attr("src") })
        }
    }.distinctBy { it.url }

    fun hasNextPage(html: String, page: Int): Boolean =
        Jsoup.parse(html, ORIGIN).select("a[href][rel=next]").any { it.attr("href").isNotBlank() } ||
            Jsoup.parse(html, ORIGIN).select("a[href]").any { a ->
                runCatching {
                    URI(ORIGIN).resolve(a.attr("href")).query?.split('&')?.contains("page=${page + 1}") ==
                        true
                }.getOrDefault(false)
            }

    fun details(html: String, manga: SManga): SManga {
        val doc = Jsoup.parse(html, ORIGIN)
        return manga.copy().apply {
            author = doc.selectFirst("meta[name=author]")?.attr("content")?.takeIf(String::isNotBlank) ?: author
            description =
                doc.selectFirst("div[x-show=show]")?.text()?.takeIf(String::isNotBlank)
                    ?: doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf(String::isNotBlank)
                    ?: description
            thumbnail_url =
                doc.selectFirst("meta[property=og:image], meta[name=og:image]")?.attr("content")?.let(::image)
                    ?: thumbnail_url
            initialized = true
        }
    }

    private fun chapterArray(data: String): String? {
        val marker = Regex("\\bchapters\\s*:").find(data) ?: return null
        val start = data.indexOf('[', marker.range.last + 1).takeIf { it >= 0 } ?: return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until data.length) {
            val char = data[index]
            if (quoted) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '[' -> depth++
                    ']' -> if (--depth == 0) return data.substring(start, index + 1)
                }
            }
        }
        return null
    }

    fun chapters(html: String): List<SChapter> {
        val chapters = Jsoup.parse(html, ORIGIN).select("div[x-data*=chapters]").flatMap { element ->
            val array = chapterArray(element.attr("x-data"))?.let {
                runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull()
            }.orEmpty()
            array.mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                val id =
                    (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("\\d+")) }
                        ?: return@mapNotNull null
                val title =
                    (item["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                SChapter.create().apply {
                    url = "$ORIGIN/chapters/$id"
                    name = title
                }
            }
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("找不到 MYCOMIC 公開章節")
        return chapters
    }

    fun images(html: String): List<String> {
        val images = Jsoup.parse(html, ORIGIN).select("img.page").map { img ->
            image(img.attr("data-src").ifBlank { img.attr("src") }) ?: throw IOException("MYCOMIC 圖片網址不受支援")
        }
        if (images.isEmpty() || images.size > 3000) throw IOException("此章節沒有公開圖片")
        return images
    }
}
