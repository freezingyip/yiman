package local.hengdu.reader

import android.content.Context
import android.webkit.WebView
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Keeps progress until the original WebView acknowledges the update. */
object HengduProgress {
    data class CatalogInfo(
        val bookUrl: String,
        val chapterUrl: String,
        val nextChapterUrl: String?,
        val catalog: HengduCatalog.Snapshot,
    )
    private const val PREFS = "hengdu_mihon_progress"
    private const val MAPPINGS = "mappings"
    private const val PENDING = "pending"
    private const val NEXT = "next"
    private val lock = Any()

    fun discardChapterRequest(context: Context) = synchronized(lock) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(NEXT).apply()
    }

    @JvmStatic
    fun register(
        context: Context,
        chapterId: Long,
        bookUrl: String,
        chapterUrl: String,
        title: String,
        pageCount: Int,
        localUrl: String,
        nextChapterUrl: String?,
    ) {
        require(
            chapterId > 0 && bookUrl.isNotBlank() && chapterUrl.isNotBlank() && pageCount > 0 && localUrl.isNotBlank(),
        )
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mappings = JSONObject(prefs.getString(MAPPINGS, "{}") ?: "{}")
            mappings.put(
                chapterId.toString(),
                JSONObject()
                    .put("bookUrl", bookUrl)
                    .put("chapterUrl", chapterUrl)
                    .put("chapterTitle", title)
                    .put("localUrl", localUrl)
                    .put("nextChapterUrl", nextChapterUrl)
                    .put("pageCount", pageCount),
            )
            prefs.edit().putString(MAPPINGS, mappings.toString()).apply()
        }
        HengduCatalog.observe(context, bookUrl, chapterUrl, nextChapterUrl)
    }

    fun requestNext(context: Context, chapterId: Long): Boolean = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val info =
            JSONObject(prefs.getString(MAPPINGS, "{}") ?: "{}").optJSONObject(chapterId.toString()) ?: return false
        val next = info.optString("nextChapterUrl")
        if (next.isBlank() || next == info.optString("chapterUrl")) return false
        val request = JSONObject().put("version", UUID.randomUUID().toString())
            .put("bookUrl", info.getString("bookUrl")).put("chapterUrl", next)
        prefs.edit().putString(NEXT, request.toString()).apply()
        true
    }

    fun catalog(context: Context, chapterId: Long): CatalogInfo? = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val info =
            JSONObject(prefs.getString(MAPPINGS, "{}") ?: "{}").optJSONObject(chapterId.toString()) ?: return null
        val bookUrl = info.optString("bookUrl")
        val chapterUrl = info.optString("chapterUrl")
        val catalog = HengduCatalog.get(context, bookUrl) ?: return null
        if (catalog.chapters.none { it.url == chapterUrl }) return null
        CatalogInfo(
            bookUrl,
            chapterUrl,
            info.optString("nextChapterUrl").takeIf {
                it.isNotBlank() && it != "null"
            },
            catalog,
        )
    }

    fun previous(context: Context, chapterId: Long): HengduCatalog.Chapter? {
        val info = catalog(context, chapterId) ?: return null
        return HengduCatalog.previous(info.catalog, info.chapterUrl, info.nextChapterUrl)
    }

    fun requestChapter(context: Context, chapterId: Long, chapterUrl: String): Boolean = synchronized(lock) {
        val info = catalog(context, chapterId) ?: return false
        if (chapterUrl == info.chapterUrl || info.catalog.chapters.none { it.url == chapterUrl }) return false
        val request = JSONObject().put("version", UUID.randomUUID().toString())
            .put("bookUrl", info.bookUrl).put("chapterUrl", chapterUrl).put("resume", true)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(NEXT, request.toString()).apply()
        true
    }

    fun record(context: Context, page: ReaderPage) {
        if (page is InsertPage || page.status is Page.State.Error) return
        val chapter = page.chapter
        val pageCount = chapter.pages?.size ?: return
        if (page.index !in 0 until pageCount) return
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mappings = JSONObject(prefs.getString(MAPPINGS, "{}") ?: "{}")
            val id = chapter.chapter.id.toString()
            val info = mappings.optJSONObject(id) ?: return
            if (info.optString("localUrl") != chapter.chapter.url || info.optInt("pageCount") != pageCount) return
            val pending = JSONObject(prefs.getString(PENDING, "{}") ?: "{}")
            val alreadyComplete = pending.optJSONObject(id)?.optBoolean("complete") == true
            pending.put(
                id,
                JSONObject()
                    .put("version", UUID.randomUUID().toString())
                    .put("bookUrl", info.getString("bookUrl"))
                    .put("chapterUrl", info.getString("chapterUrl"))
                    .put("chapterTitle", info.optString("chapterTitle"))
                    .put("page", page.index)
                    .put("complete", alreadyComplete || page.index == pageCount - 1)
                    .put("updated", System.currentTimeMillis()),
            )
            prefs.edit().putString(PENDING, pending.toString()).apply()
        }
    }

    @JvmStatic
    fun deliver(context: Context, view: WebView) {
        val (snapshot, next) = synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            JSONObject(prefs.getString(PENDING, "{}") ?: "{}") to prefs.getString(NEXT, null)?.let(::JSONObject)
        }
        if (snapshot.length() == 0 && next == null) return
        val events = snapshot.keys().asSequence().mapNotNull { snapshot.optJSONObject(it) }
            .sortedBy { it.optLong("updated") }.toList()
        val json = JSONArray(events).toString()
        val nextJson = next?.toString() ?: "null"
        val dispatcher = try {
            context.assets.open("hengdu-progress-delivery.js").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return
        }
        val script = "$dispatcher\n;window.hengduDeliverNativeBatch($json,$nextJson)"
        view.evaluateJavascript(script) { result ->
            if (eu.kanade.tachiyomi.BuildConfig.DEBUG &&
                next != null
            ) {
                android.util.Log.d("HengduHandoff", "delivery result=$result")
            }
            val ack = try {
                JSONObject(result ?: return@evaluateJavascript)
            } catch (_: Exception) {
                return@evaluateJavascript
            }
            synchronized(lock) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val pending = JSONObject(prefs.getString(PENDING, "{}") ?: "{}")
                if (ack.optBoolean("progress")) {
                    snapshot.keys().forEach { key ->
                        if (pending.optJSONObject(key)?.optString("version") ==
                            snapshot.optJSONObject(key)?.optString("version")
                        ) {
                            pending.remove(key)
                        }
                    }
                }
                val editor = prefs.edit().putString(PENDING, pending.toString())
                if (next != null && ack.optBoolean("chapter") &&
                    prefs.getString(NEXT, null)?.let(::JSONObject)?.optString("version") == next.optString("version")
                ) {
                    editor.remove(NEXT)
                }
                editor.apply()
            }
            if (next != null && !ack.optBoolean("chapter") && HengduChapterHandoff.isPending()) {
                HengduChapterHandoff.failed("無法切換章節，請返回章節列表後重試。")
            }
        }
    }
}
