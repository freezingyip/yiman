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
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class BaoziSource : HttpSource() {
    override val id = ID
    override val name = "包子漫畫"
    override val lang = "zh"
    override val baseUrl = ORIGIN
    override val supportsLatest = false

    override fun getMangaUrl(manga: SManga): String =
        BaoziSourceParser.bookUrl(URI(baseUrl).resolve(manga.url).toString()) ?: throw IOException("包子漫畫作品網址無效")
    override fun getChapterUrl(chapter: SChapter): String =
        BaoziSourceParser.chapterUrl(URI(baseUrl).resolve(chapter.url).toString()) ?: throw IOException("包子漫畫章節網址無效")

    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (url.scheme != "https" || url.port != 443 || !BaoziSourceParser.isImageHost(url.host)) {
                throw IOException("包子漫畫圖片網址無效")
            }
            chain.proceed(chain.request())
        }.build()
    }

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
        .add("Referer", "https://www.twmanga.com/")

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        if (page != 1) return@withContext MangasPage(emptyList(), false)
        val url = "$ORIGIN/"
        MangasPage(BaoziPopularParser.parse(fetch(url), url), false)
    }
    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(
        Dispatchers.IO,
    ) {
        if (page != 1 || query.isBlank()) return@withContext MangasPage(emptyList(), false)
        if (query.length > 100) throw IOException("搜尋文字過長")
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val url = "$ORIGIN/search?q=$q"
        MangasPage(BaoziSourceParser.search(fetch(url), url), false)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val url = BaoziSourceParser.bookUrl(manga.url) ?: throw IOException("包子漫畫作品網址無效")
        if (!fetchDetails && !fetchChapters) return@withContext SMangaUpdate(manga.copy(), chapters)
        val html = fetch(url)
        val updated = if (fetchDetails) BaoziSourceParser.details(html, url, manga) else manga.copy()
        val chapterList = if (fetchChapters) BaoziSourceParser.chapters(html, url) else chapters
        SMangaUpdate(updated, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val start = BaoziSourceParser.chapterUrl(chapter.url) ?: throw IOException("包子漫畫章節網址無效")
        BaoziSourceParser.pages(start, ::fetch).mapIndexed { index, image -> Page(index, start, image) }
    }

    override fun imageRequest(page: Page): Request {
        val image = page.imageUrl?.takeIf(BaoziSourceParser::isImageUrl) ?: throw IOException("包子漫畫圖片網址無效")
        return GET(image, headers)
    }

    private fun fetch(raw: String): String {
        val url = BaoziSourceParser.allowedChapterUri(raw) ?: throw IOException("包子漫畫網址無效")
        val hosts = listOf(url.host, "www.twmanga.com", "www.twbzmg.com").distinct()
        var forbidden = false
        for (host in hosts) {
            val target = URI(url.scheme, null, host, -1, url.path, url.query, null).toASCIIString()
            val response = MainActivity.proxy(target, null, "")
            forbidden = forbidden || response.statusCode == 403
            if (response.statusCode != 200 || !response.mimeType.equals("text/html", true)) {
                response.data?.close()
                continue
            }
            return response.data.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > 2_000_000) throw IOException("包子漫畫回應過大")
                bytes.toString(Charsets.UTF_8)
            }
        }
        if (forbidden) throw IOException("包子漫畫拒絕連線（403），可在 WebView 開啟網站並完成驗證後重試")
        throw IOException("無法連線包子漫畫")
    }

    companion object {
        const val ID = 764992021302L
        private const val ORIGIN = "https://www.baozimh.com"
    }
}

internal object BaoziSourceParser {
    private val chapterDomains =
        setOf("baozimh.com", "baozimh.org", "baozimh.net", "baoziac.com", "bzmgcn.com", "twmanga.com", "twbzmg.com")
    private val imageDomains = chapterDomains + setOf("baozicdn.com", "bzcdn.net", "6wm.top")
    private val bookPath = Regex("^/comic/[^/]+/?$")
    private val chapterPath = Regex("^/comic/chapter/([a-zA-Z0-9_-]+)/(\\d+)_(\\d+)(?:_(\\d+))?\\.html$")
    private val slugPattern = Regex("^[a-zA-Z0-9_-]+$")
    private val digits = Regex("^\\d+$")
    private val comicId = Regex("comic_id=([^&\"'<>\\s]+)", RegexOption.IGNORE_CASE)

    private fun matchesDomain(host: String, domains: Set<String>): Boolean {
        val normalized = host.lowercase()
        return domains.any { normalized == it || normalized.endsWith(".$it") }
    }

    fun isImageHost(host: String) = matchesDomain(host, imageDomains)

    fun allowedChapterUri(raw: String): URI? = checkedUri(raw, chapterDomains)
    private fun checkedUri(raw: String, domains: Set<String>): URI? = runCatching {
        val u = URI(raw)
        u.takeIf {
            it.scheme == "https" && it.host != null && matchesDomain(it.host, domains) &&
                it.userInfo == null && (it.port == -1 || it.port == 443) && it.fragment == null
        }
    }.getOrNull()

    fun isImageUrl(raw: String) = checkedUri(raw, imageDomains) != null

    private fun resolve(base: String, raw: String): String? = runCatching {
        URI(base).resolve(raw).toASCIIString()
    }.getOrNull()

    fun bookUrl(raw: String): String? = allowedChapterUri(raw)?.let { uri ->
        uri.toASCIIString().takeIf { uri.query == null && bookPath.matches(uri.path) }
    }

    private fun query(uri: URI): Map<String, String> = (uri.rawQuery ?: "").split('&').mapNotNull {
        val parts = it.split('=', limit = 2)
        if (parts.size != 2) {
            null
        } else {
            runCatching {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }.getOrNull()
        }
    }.toMap()

    fun chapterUrl(raw: String): String? {
        val uri = allowedChapterUri(raw) ?: return null
        if (chapterPath.matches(uri.path) && uri.query == null) return uri.toASCIIString()
        if (uri.path != "/user/page_direct") return null
        val q = query(uri)
        val slug = q["comic_id"]?.takeIf(slugPattern::matches) ?: return null
        val a = q["section_slot"]?.takeIf(digits::matches) ?: return null
        val b = q["chapter_slot"]?.takeIf(digits::matches) ?: return null
        return URI("https", null, uri.host, -1, "/comic/chapter/$slug/${a}_$b.html", null, null).toASCIIString()
    }

    private fun chapterKey(raw: String): String? {
        val uri = allowedChapterUri(raw) ?: return null
        val match = chapterPath.matchEntire(uri.path) ?: return null
        return "${match.groupValues[1]}/${match.groupValues[2]}_${match.groupValues[3]}"
    }

    fun search(html: String, base: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select("a.comics-card__poster[href]").mapNotNull { a ->
            val url = resolve(base, a.attr("href"))?.let(::bookUrl) ?: return@mapNotNull null
            val img = a.selectFirst("img, amp-img")
            val title = a.attr("title").ifBlank { img?.attr("alt").orEmpty() }.trim()
            if (title.isBlank()) return@mapNotNull null
            val cover = img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }
            SManga.create().apply {
                this.url = url
                this.title = title
                thumbnail_url = resolve(base, cover)?.takeIf(::isImageUrl)
            }
        }.distinctBy { it.url }.take(100)
    }

    fun details(html: String, base: String, manga: SManga): SManga {
        val doc = Jsoup.parse(html, base)
        return manga.copy().apply {
            description = doc.selectFirst(".comics-detail__desc")?.text()?.ifBlank { null } ?: description
            author = doc.selectFirst(".comics-detail__author")?.text()?.ifBlank { null } ?: author
            val cover = doc.selectFirst("meta[property=og:image],meta[name=og:image]")?.attr("content")
            thumbnail_url = cover?.let { resolve(base, it) }?.takeIf(::isImageUrl) ?: thumbnail_url
            initialized = true
        }
    }

    fun chapters(html: String, base: String): List<SChapter> {
        val baseUri = allowedChapterUri(base) ?: throw IOException("包子漫畫作品網址無效")
        val rawSlug = comicId.find(html)?.groupValues?.get(1) ?: baseUri.path.split('/').getOrNull(2).orEmpty()
        val slug = runCatching { URLDecoder.decode(Parser.unescapeEntities(rawSlug, false), "UTF-8") }.getOrNull()
            ?.takeIf(slugPattern::matches) ?: throw IOException("無法識別此漫畫的章節")
        val seen = HashSet<String>()
        val doc = Jsoup.parse(html, base)
        val catalog = doc.select("#chapter-items a[href], #chapters_other_list a[href]")
        val links = catalog.ifEmpty { doc.select("a[href]") }
        val result = links.mapNotNull { a ->
            val url = resolve(base, a.attr("href")) ?: return@mapNotNull null
            val uri = allowedChapterUri(url) ?: return@mapNotNull null
            val chapter = if (chapterPath.matches(uri.path)) {
                chapterUrl(url)
            } else {
                val q = query(uri)
                val section = q["section_slot"]?.takeIf(digits::matches) ?: return@mapNotNull null
                val slot = q["chapter_slot"]?.takeIf(digits::matches) ?: return@mapNotNull null
                chapterUrl(
                    URI(
                        "https",
                        null,
                        uri.host,
                        -1,
                        "/comic/chapter/$slug/${section}_$slot.html",
                        null,
                        null,
                    ).toASCIIString(),
                )
            } ?: return@mapNotNull null
            val title = (a.selectFirst("span")?.text() ?: a.text()).trim()
            if (title.isBlank() || !seen.add(chapter)) return@mapNotNull null
            SChapter.create().apply {
                this.url = chapter
                name = title
                if (Regex("^\\d{4}年\\d{1,2}月").containsMatchIn(title)) chapter_number = -2f
            }
        }.take(3000)
        if (result.isEmpty()) throw IOException("找不到可讀取的包子漫畫章節")
        return result.asReversed()
    }

    fun pages(start: String, load: (String) -> String): List<String> {
        val target = chapterUrl(start) ?: throw IOException("包子漫畫章節網址無效")
        val expected = chapterKey(target) ?: throw IOException("包子漫畫章節網址無效")
        val seenPages = HashSet<String>()
        val seenImages = LinkedHashSet<String>()
        var current = target
        repeat(100) {
            val currentUri = allowedChapterUri(current) ?: throw IOException("包子漫畫章節網址無效")
            if (!seenPages.add(currentUri.path)) throw IOException("章節分頁重複")
            val doc = Jsoup.parse(load(current), current)
            val images = doc.select(
                "img.chapter-img, amp-img.chapter-img, img.comic-contain__item[id^=chapter-img-], amp-img.comic-contain__item[id^=chapter-img-]",
            ).map { img ->
                val raw = listOf("data-src", "data-original", "data-url", "src")
                    .firstNotNullOfOrNull { img.attr(it).takeIf(String::isNotBlank) }
                    ?: throw IOException("來源網站未提供完整的漫畫圖片")
                resolve(current, raw)?.takeIf(::isImageUrl)
                    ?: throw IOException("來源圖片網址無效")
            }
            if (images.isEmpty()) throw IOException("來源網站未提供完整的漫畫圖片")
            seenImages.addAll(images)
            if (seenImages.size > 1000) throw IOException("章節圖片過多")
            val next = doc.selectFirst("div.next_chapter > a[href]")?.attr("href")
                ?.let { resolve(current, it) }?.let(::chapterUrl) ?: return seenImages.toList()
            if (chapterKey(next) != expected) return seenImages.toList()
            current = next
        }
        throw IOException("章節分頁過多")
    }
}
