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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class ManhuarenSource : HttpSource() {
    override val id = ID
    override val name = "漫畫人"
    override val lang = "zh"
    override val baseUrl = ORIGIN
    override val supportsLatest = false

    override fun getMangaUrl(manga: SManga): String =
        ManhuarenSourceParser.bookUrl(manga.url) ?: throw IOException("漫畫人作品網址無效")
    override fun getChapterUrl(chapter: SChapter): String {
        val url = URI(baseUrl).resolve(chapter.url).toString()
        return url.takeIf(ManhuarenNativeChapter::supports) ?: throw IOException("漫畫人章節網址無效")
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addNetworkInterceptor { chain ->
            val url = chain.request().url
            val host = url.host.lowercase()
            if (url.scheme != "https" || url.port != 443 ||
                (host != "www.manhuaren.com" && host != "cdndm5.com" && !host.endsWith(".cdndm5.com"))
            ) {
                throw IOException("漫畫人圖片網址無效")
            }
            chain.proceed(chain.request())
        }.build()
    }

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
        .add("Referer", "$ORIGIN/")
        .add("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        if (page != 1) {
            MangasPage(emptyList(), false)
        } else {
            MangasPage(ManhuarenSourceParser.rank(fetch("$ORIGIN/manhua-rank/")), false)
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(
        Dispatchers.IO,
    ) {
        if (page != 1 || query.isBlank()) {
            MangasPage(emptyList(), false)
        } else {
            val q = URLEncoder.encode(query, Charsets.UTF_8.name())
            MangasPage(ManhuarenSourceParser.search(fetch("$ORIGIN/search?title=$q&page=1")), false)
        }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val url = ManhuarenSourceParser.bookUrl(manga.url) ?: throw IOException("漫畫人作品網址無效")
        val html = fetch(url)
        val updated = if (fetchDetails) ManhuarenSourceParser.details(html, manga) else manga.copy()
        val chapterList = if (fetchChapters) ManhuarenSourceParser.chapters(html) else chapters
        SMangaUpdate(updated, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = chapter.url.takeIf(ManhuarenNativeChapter::supports) ?: throw IOException("漫畫人章節網址無效")
        ManhuarenNativeChapter.extractImages(fetch(url)).mapIndexed { index, image -> Page(index, url, image) }
    }

    override fun imageRequest(page: Page): Request {
        val image = page.imageUrl?.takeIf(ManhuarenSourceParser::isImageUrl) ?: throw IOException("漫畫人圖片網址無效")
        val referer = page.url.takeIf(ManhuarenNativeChapter::supports) ?: "$ORIGIN/"
        return GET(image, headers.newBuilder().set("Referer", referer).build())
    }

    private fun fetch(url: String): String {
        val response = MainActivity.proxy(url, null, "")
        if (response.statusCode != 200 || !response.mimeType.equals("text/html", true)) throw IOException("無法連線漫畫人")
        return response.data.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > 2_000_000) throw IOException("漫畫人回應過大")
            bytes.toString(Charsets.UTF_8)
        }
    }

    companion object {
        const val ID = 764992021301L
        private const val ORIGIN = "https://www.manhuaren.com"
    }
}

internal object ManhuarenSourceParser {
    private const val ORIGIN = "https://www.manhuaren.com"
    private val bookPath = Regex("^/manhua-[\\w-]+/$")
    private val unavailable = Regex("(?:已不再提供|已經不再提供|不再提供)[\\s\\S]{0,120}(?:在线阅读|線上閱讀|在线漫画|在線閱讀)")

    fun bookUrl(raw: String): String? = runCatching {
        val url = URI(ORIGIN).resolve(raw)
        url.toString().takeIf {
            url.scheme == "https" && url.host == "www.manhuaren.com" &&
                url.userInfo == null && (url.port == -1 || url.port == 443) && url.query == null &&
                url.fragment == null && bookPath.matches(url.path)
        }
    }.getOrNull()

    fun isImageUrl(raw: String): Boolean = runCatching {
        val url = URI(raw)
        val host = url.host?.lowercase().orEmpty()
        url.scheme == "https" && url.userInfo == null && (url.port == -1 || url.port == 443) &&
            (host == "cdndm5.com" || host.endsWith(".cdndm5.com"))
    }.getOrDefault(false)

    private fun imageUrl(raw: String): String? = runCatching {
        URI(ORIGIN).resolve(raw).toString().takeIf(::isImageUrl)
    }.getOrNull()

    fun search(html: String): List<SManga> = Jsoup.parse(html, ORIGIN).select(".book-list-cover a[href]")
        .mapNotNull { a ->
            val url = bookUrl(a.attr("href")) ?: return@mapNotNull null
            val img = a.selectFirst("img")
            val title = a.attr("title").ifBlank { img?.attr("alt").orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.url = url
                this.title = title
                thumbnail_url = imageUrl(img?.attr("data-original").orEmpty().ifBlank { img?.attr("src").orEmpty() })
            }
        }.distinctBy { it.url }

    fun rank(html: String): List<SManga> = Jsoup.parse(html, ORIGIN).select("#rankList_1 a[href]")
        .mapNotNull { a ->
            val url = bookUrl(a.attr("href")) ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.selectFirst(".rank-list-info-right-title")?.text() ?: a.text() }
            if (title.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img")
            SManga.create().apply {
                this.url = url
                this.title = title
                thumbnail_url = imageUrl(img?.attr("data-original").orEmpty().ifBlank { img?.attr("src").orEmpty() })
            }
        }.distinctBy { it.url }

    fun details(html: String, manga: SManga): SManga {
        val doc = Jsoup.parse(html, ORIGIN)
        return manga.copy().apply {
            description = doc.selectFirst(".detail-desc")?.text()?.ifBlank { null } ?: description
            author =
                doc.selectFirst(".detail-main-info-author")?.text()?.replace(Regex("^作者[：:]"), "")?.trim()?.ifBlank {
                    null
                }
                    ?: author
            val img = doc.selectFirst(".detail-main-cover img")
            thumbnail_url =
                imageUrl(img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }) ?: thumbnail_url
            initialized = true
        }
    }

    fun chapters(html: String): List<SChapter> {
        val doc = Jsoup.parse(html, ORIGIN)
        val desc = doc.selectFirst(".detail-list-title-right.sort-2") != null
        val ordered = doc.select("ul[id^=detail-list-select-]").flatMap { group ->
            val anchors = group.select("a[href]").toList()
            if (desc) anchors.asReversed() else anchors
        }.mapNotNull { a ->
            val url = runCatching { URI(ORIGIN).resolve(a.attr("href")).toString() }.getOrNull()
                ?.takeIf(ManhuarenNativeChapter::supports) ?: return@mapNotNull null
            val title = a.selectFirst("[class$=-info-title]")?.text() ?: a.text()
            if (title.isBlank()) return@mapNotNull null
            SChapter.create().apply {
                this.url = url
                name = title
            }
        }.distinctBy { it.url }
        if (ordered.isEmpty()) {
            if (unavailable.containsMatchIn(Jsoup.parse(html).text()) || unavailable.containsMatchIn(html)) {
                throw IOException("漫畫人已不再提供這部作品的在線閱讀。")
            }
            throw IOException("找不到可讀取的漫畫人章節。")
        }
        return ordered.asReversed()
    }
}
