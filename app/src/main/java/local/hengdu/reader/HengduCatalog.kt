package local.hengdu.reader

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** A snapshot of the chapter list for the book currently opened in the bridge. */
object HengduCatalog {
    data class Chapter(val url: String, val title: String)
    data class Snapshot(val bookUrl: String, val chapters: List<Chapter>, val direction: Int)
    private const val PREFS = "hengdu_reader_catalog"
    private const val KEY = "current"

    private fun validUrl(value: String): Boolean {
        if (value.length !in 1..4096) return false
        val uri = Uri.parse(value)
        return uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    @Synchronized
    fun set(context: Context, bookUrl: String, json: String): Boolean {
        if (!validUrl(bookUrl) || json.length > 2_000_000) return false
        return try {
            val input = JSONArray(json)
            if (input.length() !in 1..10000) return false
            val seen = HashSet<String>()
            val chapters = JSONArray()
            for (i in 0 until input.length()) {
                val item = input.getJSONObject(i)
                val url = item.getString("url")
                val title = item.optString("title").take(1000)
                if (!validUrl(url)) return false
                if (seen.add(url)) chapters.put(JSONObject().put("url", url).put("title", title))
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getString(KEY, null)?.let { JSONObject(it) }
            val direction = if (old?.optString("bookUrl") == bookUrl &&
                old.optJSONArray("chapters")?.toString() == chapters.toString()
            ) {
                old.optInt("direction", 0).takeIf { it == -1 || it == 1 } ?: 0
            } else {
                0
            }
            val value = JSONObject().put(
                "bookUrl",
                bookUrl,
            ).put("chapters", chapters).put("direction", direction).toString()
            prefs.edit().putString(KEY, value).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun get(context: Context, bookUrl: String): Snapshot? {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val obj = JSONObject(prefs.getString(KEY, null) ?: return null)
            if (obj.optString("bookUrl") != bookUrl) return null
            val input = obj.getJSONArray("chapters")
            val chapters = List(input.length()) { i ->
                val item = input.getJSONObject(i)
                Chapter(item.getString("url"), item.optString("title"))
            }
            val direction = obj.optInt("direction", 0).takeIf { it == -1 || it == 1 } ?: 0
            Snapshot(bookUrl, chapters, direction)
        } catch (_: Exception) {
            null
        }
    }

    /** Remember an order only when the source's next link identifies an adjacent item. */
    @Synchronized
    fun observe(context: Context, bookUrl: String, current: String, next: String?) {
        if (next.isNullOrBlank()) return
        val snapshot = get(context, bookUrl) ?: return
        val currentIndex = snapshot.chapters.indexOfFirst { it.url == current }
        val nextIndex = snapshot.chapters.indexOfFirst { it.url == next }
        val direction = nextIndex - currentIndex
        if (currentIndex < 0 || nextIndex < 0 || (direction != -1 && direction != 1)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        if (obj.optString("bookUrl") ==
            bookUrl
        ) {
            prefs.edit().putString(KEY, obj.put("direction", direction).toString()).apply()
        }
    }

    fun previous(snapshot: Snapshot, current: String, next: String?): Chapter? {
        val chapters = snapshot.chapters
        val index = chapters.indexOfFirst { it.url == current }
        if (index < 0 || chapters.size < 2) return null
        val nextIndex = if (next.isNullOrBlank()) -1 else chapters.indexOfFirst { it.url == next }
        val direction = when {
            nextIndex >= 0 && nextIndex == index + 1 -> 1
            nextIndex >= 0 && nextIndex == index - 1 -> -1
            next.isNullOrBlank() -> snapshot.direction
            else -> 0
        }
        if (direction == 0) return null
        val previousIndex = index - direction
        return chapters.getOrNull(previousIndex)
    }
}
