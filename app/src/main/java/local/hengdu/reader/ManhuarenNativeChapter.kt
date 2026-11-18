package local.hengdu.reader

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.Executors

/** Parses the same public chapter data as manhuaren.js without running remote scripts. */
internal object ManhuarenNativeChapter {
    private val executor = Executors.newFixedThreadPool(2)
    private val chapterPath = Regex("^/m[0-9]+/$")
    private val packed = Regex("""\}\('((?:\\.|[^'\\])*)',\s*(\d+),\s*(\d+),\s*'((?:\\.|[^'\\])*)'\.split\('\|'\)""")
    private val word = Regex("""\b\w+\b""")
    private val imageList = Regex("""\b(?:var\s+)?newImgs\s*=\s*\[([\s\S]*?)]\s*;""")
    private val imageEntry = Regex("""['\"](https://[^'\"\s]+)['\"]""")
    private val nextPath = Regex("""pushHistory\(['\"](/m\d+/)['\"]\)""")

    fun supports(raw: String): Boolean = runCatching {
        val u = URI(raw)
        u.scheme == "https" && u.host == "www.manhuaren.com" && u.userInfo == null &&
            (u.port == -1 || u.port == 443) && u.query == null && u.fragment == null && chapterPath.matches(u.path)
    }.getOrDefault(false)

    fun fetch(activity: MainActivity, chapter: String, done: (Result<String>) -> Unit) {
        executor.execute {
            done(
                runCatching {
                    require(supports(chapter))
                    val response = MainActivity.proxy(chapter, null, activity.sourceUserAgent())
                    require(response.statusCode == 200 && response.mimeType.equals("text/html", true))
                    val html = response.data.use { input ->
                        val bytes = input.readBytes()
                        require(bytes.size <= 2_000_000)
                        bytes.toString(Charsets.UTF_8)
                    }
                    parse(chapter, html).toString()
                },
            )
        }
    }

    private fun parse(chapter: String, html: String): JSONObject {
        val images = extractImages(html).map { image ->
            "/proxy?url=${Uri.encode(image)}&ref=${Uri.encode(chapter)}"
        }
        val doc = Jsoup.parse(html, chapter)
        val next = doc.select("a.view-bottom-bar-item").asSequence()
            .filter { it.text().trim() == "下一章" }
            .mapNotNull { nextPath.find(it.attr("href"))?.groupValues?.get(1) }
            .map { "https://www.manhuaren.com$it" }
            .firstOrNull { supports(it) }
        return JSONObject().put("title", doc.title().ifBlank { "漫畫人" })
            .put("images", JSONArray(images)).put("nextChapter", next ?: JSONObject.NULL)
    }

    internal fun extractImages(html: String): List<String> {
        val script = unpack(html)
        val list = imageList.find(script)?.groupValues?.get(1) ?: error("章節沒有圖片")
        val images = imageEntry.findAll(list).map { match ->
            val url = URI(match.groupValues[1])
            val host = url.host?.lowercase().orEmpty()
            require(
                url.scheme == "https" && url.userInfo == null && (url.port == -1 || url.port == 443) &&
                    (host == "cdndm5.com" || host.endsWith(".cdndm5.com")),
            )
            url.toASCIIString()
        }.toList()
        require(images.size in 1..1000)
        return images
    }

    private fun unpack(html: String): String {
        for (m in packed.findAll(html)) {
            val base = m.groupValues[2].toIntOrNull() ?: continue
            val count = m.groupValues[3].toIntOrNull() ?: continue
            if (base !in 2..62 || count !in 0..10000) continue
            fun unescape(s: String) = s.replace("\\'", "'").replace("\\\\", "\\")
            val words = unescape(m.groupValues[4]).split('|')
            fun encode(n: Int): String {
                val digit = n % base
                val char = if (digit > 35) (digit + 29).toChar() else Character.forDigit(digit, 36)
                return (if (n >= base) encode(n / base) else "") + char
            }
            val map = (0 until minOf(count, words.size)).filter { words[it].isNotEmpty() }
                .associate { encode(it) to words[it] }
            val payload = word.replace(unescape(m.groupValues[1])) { map[it.value] ?: it.value }
            if (Regex("""\bnewImgs\b""").containsMatchIn(payload)) return payload
        }
        return html
    }
}
