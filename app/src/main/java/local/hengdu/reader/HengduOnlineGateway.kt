package local.hengdu.reader

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stores only the page manifest for active bridged reader sessions in app-private cache. */
object HengduOnlineGateway {
    private data class Chapter(val token: String, val images: List<String>)
    private const val PREFS = "hengdu_online_gateway"
    private const val USER_AGENT = "mh160_user_agent"
    private const val MAX_AGE = 24L * 60 * 60 * 1000
    private const val MAX_MANIFEST_BYTES = 2_000_000
    private val chapters = ConcurrentHashMap<String, Chapter>()
    private val lock = Any()

    @JvmStatic fun attach(activity: MainActivity) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(USER_AGENT, activity.sourceUserAgent()).apply()
        cleanupStale(activity)
    }

    @JvmStatic fun detach(activity: MainActivity) = Unit

    fun registerChapter(context: Context, chapterUrl: String, images: List<String>): String {
        require(chapterUrl.isNotBlank() && images.size in 1..1000 && images.all { validate(it) != null })
        val token = UUID.randomUUID().toString()
        val data = JSONObject().put(
            "url",
            chapterUrl,
        ).put("token", token).put("images", JSONArray(images)).toString().toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_MANIFEST_BYTES)
        synchronized(lock) {
            val root = root(context)
            check(root.isDirectory || root.mkdirs())
            val file = AtomicFile(manifest(context, chapterUrl))
            val output = file.startWrite()
            try {
                output.write(data)
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
            chapters[chapterUrl] = Chapter(token, images.toList())
        }
        return token
    }

    fun hasChapter(context: Context, chapterUrl: String): Boolean = chapter(context, chapterUrl) != null
    fun pages(context: Context, chapterUrl: String): List<String>? = chapter(context, chapterUrl)?.images

    private fun chapter(context: Context, chapterUrl: String): Chapter? = synchronized(lock) {
        chapters[chapterUrl]?.let { return it }
        try {
            val file = manifest(context, chapterUrl)
            val data = AtomicFile(file).openRead().use { input ->
                if (input.channel.size() !in 1..MAX_MANIFEST_BYTES.toLong()) return null
                JSONObject(input.bufferedReader(Charsets.UTF_8).readText())
            }
            if (data.getString("url") != chapterUrl) return null
            val token = data.getString("token")
            if (runCatching { UUID.fromString(token).toString() }.getOrNull() != token) return null
            val array = data.getJSONArray("images")
            if (array.length() !in 1..1000) return null
            val images = List(array.length()) { array.getString(it) }
            if (images.any { validate(it) == null }) return null
            Chapter(token, images).also { chapters[chapterUrl] = it }
        } catch (_: Exception) {
            null
        }
    }

    fun removeChapter(context: Context, chapterUrl: String, token: String) = synchronized(lock) {
        if (chapter(context, chapterUrl)?.token != token) return
        chapters.remove(chapterUrl)
        AtomicFile(manifest(context, chapterUrl)).delete()
    }

    fun fetchImage(context: Context, image: String): android.webkit.WebResourceResponse {
        val uri = validate(image) ?: error("圖片來源無效")
        val agent = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(USER_AGENT, null)
            ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
        val result = MainActivity.proxy(uri.getQueryParameter("url"), uri.getQueryParameter("ref"), agent)
        if (result.statusCode != 200 || !result.mimeType.orEmpty().startsWith("image/", true)) {
            result.data?.close()
            error("圖片載入失敗，請檢查網絡或來源")
        }
        return result
    }

    private fun root(context: Context) = File(context.cacheDir, "hengdu-online-manifests")
    private fun manifest(context: Context, chapterUrl: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(chapterUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(root(context), name + ".json")
    }

    private fun cleanupStale(context: Context) = synchronized(lock) {
        val activeNames = chapters.keys.map { manifest(context, it).name }.toSet()
        val cutoff = System.currentTimeMillis() - MAX_AGE
        root(context).listFiles()?.filter { it.isFile && it.name !in activeNames && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun validate(image: String): Uri? {
        if (image.length !in 1..16384 || !image.startsWith("/proxy?")) return null
        val uri = Uri.parse("https://reader.local$image")
        if (uri.scheme != "https" || uri.host != "reader.local" || uri.path != "/proxy" ||
            uri.fragment != null
        ) {
            return null
        }
        if (uri.getQueryParameter("url").isNullOrBlank()) return null
        return uri
    }
}
