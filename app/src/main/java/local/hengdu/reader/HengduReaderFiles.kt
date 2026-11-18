package local.hengdu.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Holds temporary pages only for the lifetime of a bridged reader session. */
object HengduReaderFiles {
    const val SESSION_EXTRA = "hengdu_reader_session"
    const val ONLINE_URL_EXTRA = "hengdu_online_url"
    const val ONLINE_TOKEN_EXTRA = "hengdu_online_session"
    private const val PREFS = "hengdu_reader_sessions"
    private const val MAPPINGS = "chapters"
    private const val MAX_AGE = 24L * 60 * 60 * 1000
    private val lock = Any()
    private val active = mutableSetOf<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val validToken = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private var callbacksRegistered = false

    private fun root(context: Context) = File(context.cacheDir, "hengdu-mihon-pages")
    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun mappings(context: Context) = JSONObject(preferences(context).getString(MAPPINGS, "{}") ?: "{}")

    /** Moves completed transfer pages to a unique app-private session directory. */
    fun prepare(context: Context, source: File): String = synchronized(lock) {
        val directory = root(context)
        check(directory.isDirectory || directory.mkdirs())
        val now = System.currentTimeMillis()
        context.cacheDir.listFiles()?.forEach { item ->
            val suffix = item.name.removePrefix("mihon-transfer-")
            if (item != source && item.isDirectory && item.name.startsWith("mihon-transfer-") &&
                validToken.matches(suffix) && now - item.lastModified() > MAX_AGE
            ) {
                item.deleteRecursively()
            }
        }
        directory.listFiles()?.forEach { item ->
            if (item.isDirectory && validToken.matches(item.name) && item.name !in active &&
                now - item.lastModified() > MAX_AGE
            ) {
                item.deleteRecursively()
            }
        }
        val token = UUID.randomUUID().toString()
        check(source.renameTo(File(directory, token))) { "無法準備閱讀圖片" }
        active.add(token)
        token
    }

    fun register(activity: Activity, chapterId: Long, chapterUrl: String, token: String) {
        require(validToken.matches(token))
        synchronized(lock) {
            val map = mappings(activity)
            map.put(chapterId.toString(), JSONObject().put("url", chapterUrl).put("session", token))
            preferences(activity).edit().putString(MAPPINGS, map.toString()).apply()
        }
        ensureCallbacks(activity)
    }

    fun registerOnline(context: Context) = ensureCallbacks(context)

    /** Returns only directories created by this bridge, never a local manga directory. */
    fun resolve(context: Context, chapterId: Long, chapterUrl: String): File? = synchronized(lock) {
        val item = mappings(context).optJSONObject(chapterId.toString()) ?: return null
        if (item.optString("url") != chapterUrl) return null
        val token = item.optString("session")
        if (!validToken.matches(token)) return null
        val directory = File(root(context), token)
        if (!directory.isDirectory) return null
        active.add(token)
        ensureCallbacks(context)
        directory
    }

    fun release(context: Context, chapterId: Long?, token: String) {
        if (!validToken.matches(token)) return
        val app = context.applicationContext
        synchronized(lock) {
            active.remove(token)
            if (chapterId != null) {
                val map = mappings(app)
                if (map.optJSONObject(chapterId.toString())?.optString("session") == token) {
                    map.remove(chapterId.toString())
                    preferences(app).edit().putString(MAPPINGS, map.toString()).apply()
                }
            }
        }
        worker.execute { File(root(app), token).deleteRecursively() }
    }

    private fun ensureCallbacks(context: Context) {
        val app = context.applicationContext as Application
        val register = {
            synchronized(lock) {
                if (!callbacksRegistered) {
                    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                        override fun onActivityStarted(activity: Activity) = Unit
                        override fun onActivityResumed(activity: Activity) = Unit
                        override fun onActivityPaused(activity: Activity) = Unit
                        override fun onActivityStopped(activity: Activity) = Unit
                        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                        override fun onActivityDestroyed(activity: Activity) {
                            if (activity !is ReaderActivity) return
                            if (activity.isFinishing && !activity.isChangingConfigurations) {
                                activity.intent?.getStringExtra(SESSION_EXTRA)?.let { token ->
                                    val chapter = activity.intent?.getLongExtra("chapter", -1L)?.takeIf { it > 0 }
                                    release(activity, chapter, token)
                                }
                                val url = activity.intent?.getStringExtra(ONLINE_URL_EXTRA)
                                val token = activity.intent?.getStringExtra(ONLINE_TOKEN_EXTRA)
                                if (url != null &&
                                    token != null
                                ) {
                                    HengduOnlineGateway.removeChapter(activity, url, token)
                                }
                            }
                        }
                    })
                    callbacksRegistered = true
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) register() else Handler(Looper.getMainLooper()).post(register)
    }
}
