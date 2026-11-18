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
import java.util.Base64

class Mh160Source : HttpSource() {
    override val id = ID
    override val name = "漫畫160"
    override val lang = "zh"
    override val baseUrl = "$ORIGIN/statics/searchelxt1e1.aspx?key=one"
    override val supportsLatest = false

    override fun getMangaUrl(manga: SManga): String = Mh160Parser.bookUrl(manga.url) ?: throw IOException("漫畫160作品網址無效")
    override fun getChapterUrl(chapter: SChapter): String =
        Mh160Parser.chapterUrl(chapter.url) ?: throw IOException("漫畫160章節網址無效")
    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addNetworkInterceptor { chain ->
            if (!Mh160Parser.imageUrl(chain.request().url.toString())) throw IOException("漫畫160圖片網址無效")
            chain.proceed(chain.request())
        }.build()
    }
    override fun headersBuilder() = Headers.Builder().add("User-Agent", UA).add("Referer", "$ORIGIN/")
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
            Mh160Parser.search(
                fetch("$ORIGIN/statics/searchelxt1e1.aspx?key=${URLEncoder.encode(query.trim(), "UTF-8")}"),
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
        val url = Mh160Parser.bookUrl(manga.url) ?: throw IOException("漫畫160作品網址無效")
        if (!fetchDetails && !fetchChapters) return@withContext SMangaUpdate(manga.copy(), chapters)
        val html = fetch(url)
        SMangaUpdate(
            if (fetchDetails) Mh160Parser.details(html, manga) else manga.copy(),
            if (fetchChapters) Mh160Parser.chapters(html, url) else chapters,
        )
    }
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = Mh160Parser.chapterUrl(chapter.url) ?: throw IOException("漫畫160章節網址無效")
        Mh160Parser.images(fetch(url)).mapIndexed { index, image -> Page(index, url, image) }
    }
    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl?.takeIf(Mh160Parser::imageUrl) ?: throw IOException("漫畫160圖片網址無效"),
        headers,
    )
    private fun fetch(url: String): String {
        val u = URI(url)
        if (u.scheme != "https" || u.host != "www.mh160mh.com" || u.userInfo != null ||
            (u.port != -1 && u.port != 443)
        ) {
            throw IOException("漫畫160網址無效")
        }
        val response = MainActivity.proxy(url, null, UA)
        if (response.statusCode != 200 || !response.mimeType.equals("text/html", true)) {
            response.data?.close()
            throw IOException(if (response.statusCode == 403) "請在 WebView 中完成漫畫160網站驗證後重試" else "無法連接漫畫160")
        }
        val body = response.data.use { it.readBytes() }
        if (body.size > 2_000_000) throw IOException("漫畫160回應過大")
        val text = body.toString(Charsets.UTF_8)
        if (Jsoup.parse(
                text,
            ).title().contains(
                Regex("just a moment|security verification", RegexOption.IGNORE_CASE),
            )
        ) {
            throw IOException("請在 WebView 中完成漫畫160網站驗證後重試")
        }
        return text
    }
    companion object {
        const val ID = 764992021309L
        private const val ORIGIN = "https://www.mh160mh.com"
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
    }
}

internal object Mh160Parser {
    private const val ORIGIN = "https://www.mh160mh.com"
    private val bookPattern = Regex("^/kanmanhua/[a-zA-Z0-9_-]+/?$")
    private val chapterPattern = Regex("^/kanmanhua/[a-zA-Z0-9_-]+/\\d+\\.html$")
    private fun siteUrl(raw: String, pattern: Regex): String? = runCatching {
        val u = URI(ORIGIN).resolve(raw)
        if (u.scheme != "https" ||
            u.host != "www.mh160mh.com" ||
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
    fun bookUrl(raw: String) = siteUrl(raw, bookPattern)
    fun chapterUrl(raw: String) = siteUrl(raw, chapterPattern)
    fun imageUrl(raw: String): Boolean = runCatching {
        val u = URI(raw)
        u.scheme == "https" &&
            u.host in setOf("p.miyeye.cn", "mhpic5er.tgmhfc.uk", "mhpic6.tgmhfc.uk") &&
            u.userInfo == null && (u.port == -1 || u.port == 443) && u.fragment == null
    }.getOrDefault(false)
    private fun cover(
        raw: String,
    ): String? = runCatching { URI(ORIGIN).resolve(raw).toASCIIString().takeIf(::imageUrl) }.getOrNull()
    fun search(html: String): List<SManga> = Jsoup.parse(html, ORIGIN).select(".mh-worksbord").mapNotNull { item ->
        val a = item.selectFirst(".mh-works-title h4 a[href], .mh-nlook-w a[href]") ?: return@mapNotNull null
        val url = bookUrl(a.attr("href")) ?: return@mapNotNull null
        val img = item.selectFirst(".mh-nlook-w img")
        val title = a.attr("title").ifBlank { img?.attr("alt").orEmpty() }.ifBlank(a::text).trim()
        if (title.isEmpty()) return@mapNotNull null
        SManga.create().apply {
            this.url = url
            this.title = title
            thumbnail_url =
                cover(img?.attr("data-original").orEmpty().ifBlank { img?.attr("src").orEmpty() })
        }
    }.distinctBy { it.url }
    fun details(html: String, manga: SManga): SManga {
        val doc = Jsoup.parse(html, ORIGIN)
        return manga.copy().apply {
            description =
                doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifBlank { null }
                    ?: description
            author = doc.selectFirst(".works-info-tc em")?.text()?.trim()?.ifBlank { null } ?: author
            thumbnail_url = doc.selectFirst(".mh-date-bgpic img")?.attr("src")?.let(::cover) ?: thumbnail_url
            initialized = true
        }
    }
    fun chapters(html: String, book: String): List<SChapter> {
        val prefix = URI(bookUrl(book) ?: throw IOException("漫畫160作品網址無效")).path.trimEnd('/') + "/"
        val result = Jsoup.parse(
            html,
            ORIGIN,
        ).select("[id^=mh-chapter-list-ol-] a[href], [id^=chapterList_ul_] a[href]").mapNotNull { a ->
            val url = chapterUrl(a.attr("href")) ?: return@mapNotNull null
            if (!URI(url).path.startsWith(prefix)) return@mapNotNull null
            val title = a.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            SChapter.create().apply {
                this.url = url
                name = title
            }
        }.distinctBy { it.url }
        if (result.isEmpty()) throw IOException("找不到漫畫160公開章節")
        return result
    }
    private fun variable(
        html: String,
        name: String,
    ): String = Regex("\\b${Regex.escape(name)}\\s*=\\s*[\"']([^\"']*)[\"']").find(html)?.groupValues?.get(1).orEmpty()
    fun images(html: String): List<String> {
        val encoded = variable(html, "qTcms_S_m_murl_e")
        if (encoded.isEmpty() || encoded.length > 1_000_000) throw IOException("找不到漫畫160圖片資料")
        val decoded = runCatching {
            Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
        }.getOrElse { throw IOException("漫畫160圖片資料格式無效") }
        val id = variable(html, "qTcms_S_p_id").toLongOrNull()?.takeIf { it > 0 } ?: throw IOException("漫畫160章節資料格式無效")
        val host = if (id > 542724) "https://mhpic5er.tgmhfc.uk" else "https://mhpic6.tgmhfc.uk"
        val paths = decoded.split("\$qingtiandy\$")
        if (paths.size > 1000 ||
            paths.any { !it.startsWith("/mh160tuku/") || it.contains("..") || it.any { c -> c in "?#\\" } }
        ) {
            throw IOException("漫畫160圖片來源不受支援")
        }
        return paths.map { path ->
            host +
                path.split('/').mapIndexed { i, s ->
                    if (i ==
                        0
                    ) {
                        ""
                    } else {
                        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
                    }
                }.joinToString("/")
        }
    }
}
