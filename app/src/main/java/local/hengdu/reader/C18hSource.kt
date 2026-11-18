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

class C18hSource : HttpSource() {
    override val id = ID
    override val name = "18h漫！"
    override val lang = "zh"
    override val baseUrl = C18hParser.ORIGIN
    override val supportsLatest = false

    override fun getMangaUrl(manga: SManga): String = C18hParser.bookUrl(manga.url) ?: throw IOException("18h 作品網址無效")
    override fun getChapterUrl(chapter: SChapter): String =
        C18hParser.bookUrl(chapter.url) ?: throw IOException("18h 章節網址無效")
    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addNetworkInterceptor { chain ->
            if (!C18hParser.imageUrlAllowed(chain.request().url.toString())) throw IOException("18h 圖片網址無效")
            chain.proceed(chain.request())
        }.build()
    }
    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
        .add("Referer", "$baseUrl/")
    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = withContext(Dispatchers.IO) {
        if (page != 1 || query.isBlank()) return@withContext MangasPage(emptyList(), false)
        if (query.length > 100) throw IOException("搜尋文字過長")
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        MangasPage(C18hParser.search(fetch("$baseUrl/zh/doujin_search/all/$q/1.html")), false)
    }
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = C18hParser.bookUrl(manga.url) ?: throw IOException("18h 作品網址無效")
        return SMangaUpdate(
            if (fetchDetails) manga.copy().apply { initialized = true } else manga.copy(),
            if (fetchChapters) {
                listOf(
                    SChapter.create().apply {
                        this.url = url
                        name = "全一話"
                    },
                )
            } else {
                chapters
            },
        )
    }
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = C18hParser.bookUrl(chapter.url) ?: throw IOException("18h 章節網址無效")
        C18hParser.images(fetch(url)).mapIndexed { i, image -> Page(i, url, image) }
    }
    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl?.takeIf(C18hParser::imageUrlAllowed) ?: throw IOException("18h 圖片網址無效"),
        headers,
    )
    private fun fetch(url: String): String {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "18h.mm-cg.com" || uri.userInfo != null || uri.port !in listOf(
                -1,
                443,
            ) ||
            uri.fragment != null
        ) {
            throw IOException("18h 網址無效")
        }
        val response = MainActivity.proxy(url, null, "")
        if (response.statusCode != 200 || response.mimeType != "text/html") {
            response.data?.close()
            throw IOException("無法連線 18h 漫，或網站要求驗證")
        }
        return response.data.use { input ->
            input.readBytes().also {
                if (it.size >
                    2_000_000
                ) {
                    throw IOException("18h 回應過大")
                }
            }.toString(Charsets.UTF_8)
        }
    }
    companion object {
        const val ID = 764992021308L
    }
}

internal object C18hParser {
    const val ORIGIN = "https://18h.mm-cg.com"
    private val path = Regex("^/(?:[\\w-]+\\.html|zh/[\\w-]+/\\d+/content\\.html)$")
    private val largeImage = Regex("""Large_cgurl\[\d+]\s*=\s*"([^"\r\n]+)"\s*;""")
    fun bookUrl(raw: String): String? = runCatching {
        val u = URI(ORIGIN).resolve(raw)
        if (u.scheme != "https" || u.host != "18h.mm-cg.com" || u.userInfo != null || u.port !in listOf(-1, 443) ||
            u.query != null ||
            u.fragment != null ||
            !path.matches(u.path)
        ) {
            null
        } else {
            ORIGIN + u.rawPath
        }
    }.getOrNull()
    fun imageUrlAllowed(raw: String): Boolean = runCatching {
        val u = URI(raw)
        u.scheme == "https" &&
            (
                u.host == "imgstream2.com" || u.host?.endsWith(
                    ".imgstream2.com",
                ) == true || u.host == "imgstream3.com" ||
                    u.host?.endsWith(".imgstream3.com") == true
                ) &&
            u.userInfo == null &&
            u.port in listOf(-1, 443) &&
            u.fragment == null
    }.getOrDefault(false)
    private fun image(raw: String?): String? = runCatching {
        if (raw.isNullOrBlank()) null else URI(ORIGIN).resolve(raw).toASCIIString().takeIf(::imageUrlAllowed)
    }.getOrNull()
    fun search(html: String): List<SManga> = Jsoup.parse(html, ORIGIN).select("div.post").mapNotNull { post ->
        val link = post.selectFirst("a[href]") ?: return@mapNotNull null
        val url = bookUrl(link.attr("href")) ?: return@mapNotNull null
        val title = post.selectFirst("h3")?.text()?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        SManga.create().apply {
            this.url = url
            this.title = title
            thumbnail_url =
                image(post.selectFirst("img")?.attr("src"))
        }
    }.distinctBy { it.url }
    fun images(html: String): List<String> {
        val result = largeImage.findAll(html).map { match ->
            image(match.groupValues[1])
                ?: throw IOException("18h 圖片網址無效")
        }.toList()
        if (result.isEmpty()) throw IOException("此章節未提供公開圖片或需要登入")
        if (result.size > 3000) throw IOException("章節圖片過多")
        return result
    }
}
