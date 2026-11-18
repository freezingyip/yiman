package local.hengdu.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import local.hengdu.reader.bridge.OfflineChapterTransfer
import mihon.app.di.appGraph
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Experimental chapter entry point, exposed only to reader.local. */
class HengduOfflineEntry(private val activity: Activity, private val view: WebView) {
    private val transfer = OfflineChapterTransfer(activity).apply { attach(view) }
    private val main = Handler(Looper.getMainLooper())
    private var opening = false
    private var previewOpening = false

    @JavascriptInterface
    fun isMihonPreviewAvailable(): Boolean = eu.kanade.tachiyomi.BuildConfig.DEBUG

    @JavascriptInterface
    fun copyLegacyChapter(bookUrl: String, chapterUrl: String) {
        activity.runOnUiThread {
            if (!eu.kanade.tachiyomi.BuildConfig.DEBUG || activity.isFinishing || activity.isDestroyed ||
                !trusted()
            ) {
                return@runOnUiThread
            }
            if (opening || previewOpening || cacheRequest != null) {
                reportLegacyCopy("已有章節正在處理，請稍候再試。")
                return@runOnUiThread
            }
            opening = true
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                var staged: File? = null
                try {
                    require(
                        ManhuarenSourceParser.bookUrl(
                            bookUrl,
                        ) == bookUrl && ManhuarenNativeChapter.supports(chapterUrl),
                    )
                    val graph = activity.appGraph
                    val manga = graph.testMangaRepository.getMangaByUrlAndSourceId(
                        bookUrl,
                        HengduLibrarySnapshot.MANHUAREN_SOURCE_ID,
                    )
                        ?: error("請先開啟 Mihon 書架測試，匯入漫畫人收藏。")
                    val chapter = graph.testChapterRepository.getChapterByUrlAndMangaId(chapterUrl, manga.id)
                        ?: error("測試書架沒有這一話，暫時無法複製；原有緩存仍可在原書架閱讀。")
                    val root = HengduLegacyOfflineCopy.root(activity.noBackupFilesDir)
                    val existing =
                        withContext(Dispatchers.IO) { HengduLegacyOfflineCopy.resolve(root, bookUrl, chapterUrl) }
                    if (existing != null) {
                        reportLegacyCopy("《${manga.title}》${chapter.name}已有可供測試的離線副本。")
                        return@launch
                    }
                    val destination = File(activity.cacheDir, "mihon-transfer-${UUID.randomUUID()}")
                    val result = suspendCancellableCoroutine<OfflineChapterTransfer.Result> { continuation ->
                        transfer.start(
                            chapterUrl,
                            destination,
                            object : OfflineChapterTransfer.Callback {
                                override fun onSuccess(result: OfflineChapterTransfer.Result) {
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            result,
                                        )
                                    } else {
                                        result.directory.deleteRecursively()
                                    }
                                }
                                override fun onFailure(message: String) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            IllegalStateException(message),
                                        )
                                    }
                                }
                            },
                        )
                    }
                    staged = result.directory
                    withContext(Dispatchers.IO) {
                        HengduLegacyOfflineCopy.publish(root, result.directory, bookUrl, chapterUrl)
                    }
                    reportLegacyCopy("已複製《${manga.title}》${chapter.name}；可在 Mihon 測試書架作離線測試，尚不顯示為下載。")
                } catch (e: Exception) {
                    reportLegacyCopy(e.message ?: "離線副本複製失敗，原有緩存仍保留。")
                } finally {
                    staged?.let { withContext(Dispatchers.IO) { it.deleteRecursively() } }
                    opening = false
                }
            }
        }
    }

    private fun reportLegacyCopy(message: String) {
        if (!activity.isFinishing && !activity.isDestroyed && trusted()) {
            view.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('hengdu-copy-status',{detail:${JSONObject.quote(message)}}))",
                null,
            )
        }
    }

    @JavascriptInterface
    fun openMihonPreview() {
        activity.runOnUiThread {
            if (!eu.kanade.tachiyomi.BuildConfig.DEBUG || activity.isFinishing || activity.isDestroyed ||
                !trusted() || previewOpening || opening || cacheRequest != null
            ) {
                return@runOnUiThread
            }
            previewOpening = true
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                try {
                    val result = HengduLibraryMigration.importFromWebView(activity, view)
                    if (!activity.isFinishing && !activity.isDestroyed && trusted()) {
                        activity.startActivity(
                            Intent(activity, HengduMihonPreviewActivity::class.java)
                                .putExtra("imported", result.imported + result.alreadyImported)
                                .putExtra("unsupported", result.unsupported)
                                .putExtra("awaitingCatalog", result.awaitingCatalog)
                                .putExtra("preservedNative", result.preservedNative),
                        )
                    }
                } catch (e: Exception) {
                    if (!activity.isFinishing && !activity.isDestroyed && trusted()) {
                        view.evaluateJavascript("window.dispatchEvent(new CustomEvent('hengdu-preview-error'))", null)
                    }
                } finally {
                    previewOpening = false
                }
            }
        }
    }

    @JavascriptInterface
    fun openReaderSettings() {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed && trusted()) {
                activity.startActivity(Intent(activity, HengduReaderSettingsActivity::class.java))
            }
        }
    }

    @JavascriptInterface
    fun setCatalog(bookUrl: String, catalog: String) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed &&
                trusted()
            ) {
                HengduCatalog.set(activity, bookUrl, catalog)
            }
        }
    }

    @JavascriptInterface
    fun retryPending() {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed && trusted()) HengduProgress.deliver(activity, view)
        }
    }
    private var pending: OnlineRequest? = null
    private var cacheRequest: CacheRequest? = null
    private data class CacheRequest(
        val token: String,
        val bookUrl: String,
        val bookTitle: String,
        val chapterUrl: String,
        val chapterTitle: String,
        val page: Int,
        val handoffToken: String?,
    )
    private val cacheTimeout = Runnable {
        val request = cacheRequest
        if (request != null) {
            cacheRequest = null
            if (request.handoffToken != null &&
                request.handoffToken != HengduChapterHandoff.currentToken()
            ) {
                return@Runnable
            }
            val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (connectivity.activeNetwork == null) {
                report("讀取緩存逾時，請重試。")
            } else {
                openOnline(request.bookUrl, request.bookTitle, request.chapterUrl, request.chapterTitle, request.page)
            }
        }
    }
    private data class OnlineRequest(
        val token: String,
        val bookUrl: String,
        val bookTitle: String,
        val chapterUrl: String,
        val chapterTitle: String,
        val page: Int,
        val handoffToken: String?,
    )
    private val timeout = Runnable {
        if (pending != null) {
            pending = null
            opening = false
            report("取得線上章節逾時。")
        }
    }

    /** Native timeout also runs while WebView's background timers are throttled. */
    @JavascriptInterface
    fun openAuto(bookUrl: String, bookTitle: String, chapterUrl: String, chapterTitle: String, initialPage: Int) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || !trusted() || opening ||
                cacheRequest != null
            ) {
                return@runOnUiThread
            }
            if (bookUrl.length !in 1..4096 || chapterUrl.length !in 1..4096 || bookTitle.length > 1000 ||
                chapterTitle.length > 1000 ||
                initialPage < 0
            ) {
                report("章節資料無效。")
                return@runOnUiThread
            }
            val request =
                CacheRequest(
                    UUID.randomUUID().toString(),
                    bookUrl,
                    bookTitle,
                    chapterUrl,
                    chapterTitle,
                    initialPage,
                    HengduChapterHandoff.currentToken(),
                )
            cacheRequest = request
            val token = JSONObject.quote(request.token)
            val chapter = JSONObject.quote(chapterUrl)
            view.evaluateJavascript(
                "(async function(){try{const c=await getCached($chapter);HengduOfflineEntry.cacheResult($token,!!c)}catch(e){HengduOfflineEntry.cacheResult($token,false)}})()",
                null,
            )
            main.postDelayed(cacheTimeout, 8000)
        }
    }

    @JavascriptInterface
    fun cacheResult(token: String, cached: Boolean) {
        activity.runOnUiThread {
            val request = cacheRequest?.takeIf { it.token == token } ?: return@runOnUiThread
            cacheRequest = null
            main.removeCallbacks(cacheTimeout)
            if (request.handoffToken != null &&
                request.handoffToken != HengduChapterHandoff.currentToken()
            ) {
                return@runOnUiThread
            }
            if (cached) {
                open(request.bookUrl, request.bookTitle, request.chapterUrl, request.chapterTitle, request.page)
            } else {
                openOnline(request.bookUrl, request.bookTitle, request.chapterUrl, request.chapterTitle, request.page)
            }
        }
    }

    @JavascriptInterface
    fun open(bookUrl: String, bookTitle: String, chapterUrl: String, chapterTitle: String, initialPage: Int) {
        begin(bookUrl, bookTitle, chapterUrl, chapterTitle, initialPage, false)
    }

    @JavascriptInterface
    fun openOnline(bookUrl: String, bookTitle: String, chapterUrl: String, chapterTitle: String, initialPage: Int) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || !trusted() || opening) {
                if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "HengduHandoff",
                        "openOnline rejected: finishing=${activity.isFinishing} destroyed=${activity.isDestroyed} trusted=${trusted()} opening=$opening",
                    )
                }
                return@runOnUiThread
            }
            if (bookUrl.length !in 1..4096 || chapterUrl.length !in 1..4096 || bookTitle.length > 1000 ||
                chapterTitle.length > 1000 ||
                initialPage < 0
            ) {
                report("章節資料無效。")
                return@runOnUiThread
            }
            opening = true
            val request =
                OnlineRequest(
                    UUID.randomUUID().toString(),
                    bookUrl,
                    bookTitle,
                    chapterUrl,
                    chapterTitle,
                    initialPage,
                    HengduChapterHandoff.currentToken(),
                )
            pending = request
            if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                android.util.Log.d(
                    "HengduHandoff",
                    "manifest requested handoff=${request.handoffToken != null}",
                )
            }
            val token = JSONObject.quote(request.token)
            val chapter = JSONObject.quote(chapterUrl)
            val book = JSONObject.quote(bookUrl)
            main.postDelayed(timeout, 120000)
            if (request.handoffToken != null && activity is MainActivity &&
                ManhuarenNativeChapter.supports(chapterUrl)
            ) {
                ManhuarenNativeChapter.fetch(activity, chapterUrl) { result ->
                    result.fold({ manifest(request.token, it) }, { manifestError(request.token) })
                }
                return@runOnUiThread
            }
            view.evaluateJavascript(
                "(async function(){try{let d=await onlineFetchChapter($chapter);" +
                    "if(!activeBook||activeBook.url!==$book){HengduOfflineEntry.manifestError($token);return}" +
                    "HengduOfflineEntry.manifest($token,JSON.stringify(d))}catch(e){HengduOfflineEntry.manifestError($token)}})()",
                null,
            )
        }
    }

    @JavascriptInterface
    fun manifest(token: String, json: String) {
        activity.runOnUiThread {
            val request = pending?.takeIf { it.token == token } ?: return@runOnUiThread
            if (eu.kanade.tachiyomi.BuildConfig.DEBUG) android.util.Log.d("HengduHandoff", "manifest received")
            pending = null
            main.removeCallbacks(timeout)
            try {
                check(!activity.isFinishing && !activity.isDestroyed && trusted())
                require(json.length <= 2_000_000)
                val data = JSONObject(json)
                val array = data.getJSONArray("images")
                require(array.length() in 1..1000)
                val images = List(array.length()) { array.getString(it) }
                val title = request.chapterTitle.ifBlank { data.optString("title", "漫畫章節") }
                val next = if (data.isNull("nextChapter")) null else data.optString("nextChapter", null)
                val info = HengduNativeReader.ChapterInfo(
                    request.bookUrl,
                    request.bookTitle,
                    request.chapterUrl,
                    title,
                    request.page,
                    images.size,
                    next,
                )
                HengduNativeReader.openOnline(activity, info, images, {
                    opening = false
                    HengduChapterHandoff.opened()
                }, { message ->
                    opening =
                        false
                    report(message)
                }, request.handoffToken)
            } catch (e: Exception) {
                opening = false
                report("無法載入線上章節，請檢查網絡或來源。")
            }
        }
    }

    @JavascriptInterface
    fun manifestError(token: String) {
        activity.runOnUiThread {
            if (pending?.token != token) return@runOnUiThread
            pending = null
            main.removeCallbacks(timeout)
            opening = false
            report("無法載入線上章節，請檢查網絡或來源。")
        }
    }

    private fun begin(
        bookUrl: String,
        bookTitle: String,
        chapterUrl: String,
        chapterTitle: String,
        initialPage: Int,
        online: Boolean,
    ) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || !trusted() || opening) return@runOnUiThread
            if (bookUrl.length !in 1..4096 || chapterUrl.length !in 1..4096 ||
                bookTitle.length > 1000 || chapterTitle.length > 1000 || initialPage < 0
            ) {
                report("章節資料無效。")
                return@runOnUiThread
            }
            opening = true
            val handoffToken = HengduChapterHandoff.currentToken()
            val dir = File(activity.cacheDir, "mihon-transfer-${UUID.randomUUID()}")
            val callback = object : OfflineChapterTransfer.Callback {
                override fun onSuccess(result: OfflineChapterTransfer.Result) {
                    if (activity.isFinishing || activity.isDestroyed || !trusted()) {
                        result.directory.deleteRecursively()
                        opening = false
                        HengduChapterHandoff.failed("無法載入已緩存章節，請重試。")
                        return
                    }
                    val info = HengduNativeReader.ChapterInfo(
                        bookUrl,
                        bookTitle,
                        chapterUrl,
                        chapterTitle.ifBlank {
                            result.title
                        },
                        initialPage,
                        result.pageCount,
                        result.nextChapter,
                    )
                    HengduNativeReader.open(activity, info, result.directory, {
                        result.directory.deleteRecursively()
                        opening = false
                        HengduChapterHandoff.opened()
                    }, { message ->
                        result.directory.deleteRecursively()
                        opening = false
                        report(message)
                    }, handoffToken)
                }

                override fun onFailure(message: String) {
                    opening = false
                    report(message)
                }
            }
            if (online) transfer.startOnline(chapterUrl, dir, callback) else transfer.start(chapterUrl, dir, callback)
        }
    }

    fun cancel() {
        cacheRequest = null
        main.removeCallbacks(cacheTimeout)
        pending = null
        opening = false
        main.removeCallbacks(timeout)
        transfer.detach()
    }

    private fun trusted(): Boolean {
        val uri = Uri.parse(view.url ?: "")
        return uri.scheme == "https" && uri.host == "reader.local" && (uri.path == "/" || uri.path == "/index.html")
    }

    private fun report(message: String) {
        if (eu.kanade.tachiyomi.BuildConfig.DEBUG) android.util.Log.d("HengduHandoff", "entry failed: $message")
        HengduChapterHandoff.failed(message)
        if (!activity.isFinishing && !activity.isDestroyed && trusted()) {
            view.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('hengdu-native-error',{detail:${JSONObject.quote(message)}}))",
                null,
            )
        }
    }
}
