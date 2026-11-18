package local.hengdu.reader

import android.content.Context
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.JavaScriptEngine
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class KuaikanSource : HttpSource() {
    override val id = ID
    override val name = "快看漫畫"
    override val lang = "zh"
    override val baseUrl = ORIGIN
    override val supportsLatest = false

    override fun getMangaUrl(manga: SManga): String = KuaikanParser.bookUrl(manga.url) ?: throw IOException("快看作品網址無效")
    override fun getChapterUrl(chapter: SChapter): String =
        KuaikanParser.chapterUrl(chapter.url) ?: throw IOException("快看章節網址無效")
    private val engine: JavaScriptEngine by injectLazy()
    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addNetworkInterceptor { chain ->
            if (!KuaikanParser.isImageUrl(chain.request().url.toString())) throw IOException("快看圖片網址無效")
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
        MangasPage(
            KuaikanParser.search(fetch("$ORIGIN/sou/${URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")}/")),
            false,
        )
    }
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val url = KuaikanParser.bookUrl(manga.url) ?: throw IOException("快看作品網址無效")
        if (!fetchDetails && !fetchChapters) return@withContext SMangaUpdate(manga.copy(), chapters)
        val html = fetch(url)
        val list = if (fetchChapters) KuaikanParser.chapters(extract(html, "comics")) else chapters
        val info = if (fetchDetails) runCatching { extract(html, "topicInfo") }.getOrNull() else null
        SMangaUpdate(if (info != null) KuaikanParser.details(info, manga) else manga.copy(), list)
    }
    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val url = KuaikanParser.chapterUrl(chapter.url) ?: throw IOException("快看章節網址無效")
        KuaikanParser.images(extract(fetch(url), "comicInfo")).mapIndexed { index, image -> Page(index, url, image) }
    }
    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl?.takeIf(KuaikanParser::isImageUrl) ?: throw IOException("快看圖片網址無效"),
        headers,
    )
    private suspend fun extract(html: String, key: String): String {
        val parser = Injekt.get<Context>().assets.open("kuaikan_nuxt_parser.js").bufferedReader().use { it.readText() }
        val script = "(()=>{ $parser; return JSON.stringify(extract(nuxt(${JSONObject.quote(
            html,
        )}), ${JSONObject.quote(key)})); })()"
        return try {
            engine.evaluate<String>(script)
        } catch (e: Exception) {
            throw IOException("快看資料格式已改變", e)
        }
    }
    private fun fetch(url: String): String {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "www.kuaikanmanhua.com" || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443)
        ) {
            throw IOException("快看網址無效")
        }
        val response = MainActivity.proxy(url, null, "")
        if (response.statusCode != 200 ||
            response.mimeType != "text/html"
        ) {
            response.data?.close()
            throw IOException("無法連線快看")
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
        const val ID = 764992021306L
        private const val ORIGIN = "https://www.kuaikanmanhua.com"
    }
}

internal object KuaikanParser {
    private const val ORIGIN = "https://www.kuaikanmanhua.com"
    private fun canonical(raw: String, pattern: Regex): String? = runCatching {
        val u = URI(ORIGIN).resolve(raw)
        if (u.scheme != "https" || u.host != "www.kuaikanmanhua.com" || u.userInfo != null ||
            (u.port != -1 && u.port != 443) ||
            u.query != null ||
            u.fragment != null ||
            !pattern.matches(u.path)
        ) {
            return null
        }
        "$ORIGIN${u.path.trimEnd('/')}/"
    }.getOrNull()
    fun bookUrl(raw: String) = canonical(raw, Regex("^/web/topic/\\d+/?$"))
    fun chapterUrl(raw: String) = canonical(raw, Regex("^/webs/comic-next/\\d+/?$"))
    fun isImageUrl(raw: String): Boolean = runCatching {
        val u = URI(raw)
        val h = u.host ?: return false
        u.scheme == "https" && u.userInfo == null && (u.port == -1 || u.port == 443) && u.fragment == null &&
            listOf("kkmh.com", "kuaikanmanhua.com", "v3mh.com").any { h == it || h.endsWith(".$it") }
    }.getOrDefault(false)
    private fun image(
        raw: String,
    ): String? = runCatching { URI(ORIGIN).resolve(raw).toString().takeIf(::isImageUrl) }.getOrNull()
    private fun json(
        raw: String,
    ): JsonElement = try {
        Json.parseToJsonElement(raw)
    } catch (e: Exception) {
        throw IOException("快看資料格式無效", e)
    }
    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.flag(
        key: String,
    ): Boolean = when (string(key).lowercase()) {
        "true", "1" -> true
        else -> false
    }
    private fun JsonObject.confirmedFree(): Boolean = listOf("locked", "need_vip", "is_pay_comic").all {
        string(it).lowercase() in setOf("false", "0")
    } && !flag("is_vip_exclusive")
    fun search(
        html: String,
    ): List<SManga> = Jsoup.parse(html, ORIGIN).select("a[href^=/web/topic/][title]").mapNotNull { a ->
        val url = bookUrl(a.attr("href")) ?: return@mapNotNull null
        val title = a.attr("title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        val cover = a.selectFirst("img")
        SManga.create().apply {
            this.url = url
            this.title = title
            thumbnail_url =
                image(cover?.attr("data-src").orEmpty().ifBlank { cover?.attr("src").orEmpty() })
        }
    }.distinctBy { it.url }
    fun chapters(raw: String): List<SChapter> {
        val array = json(raw) as? JsonArray ?: throw IOException("快看章節資料無效")
        val list = array.mapNotNull { item ->
            val x = item as? JsonObject ?: return@mapNotNull null
            if (!x.confirmedFree()) return@mapNotNull null
            val id = x.string("id").takeIf { it.matches(Regex("^\\d+$")) } ?: return@mapNotNull null
            val title = x.string("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            SChapter.create().apply {
                url = "$ORIGIN/webs/comic-next/$id/"
                name = title
            }
        }.distinctBy { it.url }
        if (list.isEmpty()) throw IOException("此作品沒有提供可免登入閱讀的章節")
        return list.asReversed()
    }
    fun details(raw: String, manga: SManga): SManga {
        val x = json(raw) as? JsonObject ?: return manga.copy()
        return manga.copy().apply {
            description = x.string("description").takeIf(String::isNotBlank) ?: description
            author = (x["user"] as? JsonObject)?.string("nickname")?.takeIf(String::isNotBlank) ?: author
            thumbnail_url =
                image(x.string("cover_image_url").ifBlank { x.string("vertical_image_url") }) ?: thumbnail_url
            initialized = true
        }
    }
    fun images(raw: String): List<String> {
        val x = json(raw) as? JsonObject ?: throw IOException("此章節沒有公開圖片")
        if (!x.confirmedFree()) throw IOException("此章節需要登入、付費或未標明免費狀態")
        val list = x["comicImages"] as? JsonArray ?: throw IOException("此章節沒有公開圖片")
        if (list.isEmpty() || list.size > 3000) throw IOException("此章節沒有公開圖片")
        return list.map { image((it as? JsonObject)?.string("url").orEmpty()) ?: throw IOException("快看圖片網址不受支援") }
    }
}
