package local.hengdu.reader

import android.app.Activity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import java.io.File
import java.security.MessageDigest

/** Experimental adapter: opens a chapter whose pages were already copied into app-private storage. */
object HengduNativeReader {
    private val filesLock = Any()
    data class ChapterInfo(
        val bookUrl: String,
        val bookTitle: String,
        val chapterUrl: String,
        val chapterTitle: String,
        val initialPage: Int,
        val pageCount: Int,
        val nextChapterUrl: String?,
    )

    /** [sourceDir] must be a new private staging directory produced by a trusted page transfer. */
    fun open(
        activity: Activity,
        info: ChapterInfo,
        sourceDir: File,
        onOpened: () -> Unit,
        onError: (String) -> Unit,
        handoffToken: String? = null,
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var session: String? = null
            var registeredChapter: Long? = null
            try {
                require(info.bookUrl.isNotBlank() && info.chapterUrl.isNotBlank())
                require(info.pageCount in 1..1000 && info.initialPage >= 0)
                check(!activity.isFinishing && !activity.isDestroyed) { "閱讀器已關閉" }
                session = withContext(Dispatchers.IO) {
                    val allowedRoot = activity.cacheDir.canonicalFile.toPath()
                    require(sourceDir.canonicalFile.toPath().startsWith(allowedRoot) && sourceDir.isDirectory)
                    val files = sourceDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
                    require(files.size == info.pageCount)
                    require(files.all { it.length() in 1..(32L * 1024 * 1024) })
                    require(files.sumOf { it.length() } <= 500L * 1024 * 1024)
                    synchronized(filesLock) {
                        HengduReaderFiles.prepare(activity, sourceDir)
                    }
                }
                check(!activity.isFinishing && !activity.isDestroyed) { "閱讀器已關閉" }
                val (manga, chapter, localUrl) = prepareChapter(activity, info)
                val preparedSession = checkNotNull(session)
                HengduReaderFiles.register(activity, chapter.id, localUrl, preparedSession)
                registeredChapter = chapter.id
                // Mihon resumes read chapters at page 1 by default. The existing shelf's
                // page is authoritative at handoff, even after a manual progress change.
                val intent = ReaderActivity.newIntent(activity, manga.id, chapter.id)
                    .putExtra("page_index", info.initialPage.coerceAtMost(info.pageCount - 1))
                    .putExtra(HengduReaderFiles.SESSION_EXTRA, preparedSession)
                    .putExtra(
                        "hengdu_experimental_dual",
                        eu.kanade.tachiyomi.BuildConfig.DEBUG &&
                            activity.intent.getBooleanExtra("hengdu_test_dual", false),
                    )
                HengduChapterHandoff.startReader(activity, intent, handoffToken)
                session = null
                onOpened()
            } catch (e: Exception) {
                session?.let { HengduReaderFiles.release(activity, registeredChapter, it) }
                onError(e.message ?: "無法開啟原生閱讀器")
            }
        }
    }

    /** Opens a chapter whose page URLs are resolved by the trusted source adapter. */
    fun openOnline(
        activity: Activity,
        info: ChapterInfo,
        images: List<String>,
        onOpened: () -> Unit,
        onError: (String) -> Unit,
        handoffToken: String? = null,
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var localUrl: String? = null
            var session: String? = null
            try {
                require(info.bookUrl.isNotBlank() && info.chapterUrl.isNotBlank())
                require(info.pageCount in 1..1000 && info.initialPage >= 0 && images.size == info.pageCount)
                check(!activity.isFinishing && !activity.isDestroyed) { "閱讀器已關閉" }
                val (manga, chapter, url) = prepareChapter(activity, info)
                localUrl = url
                session = HengduOnlineGateway.registerChapter(activity, url, images)
                HengduReaderFiles.registerOnline(activity)
                check(!activity.isFinishing && !activity.isDestroyed) { "閱讀器已關閉" }
                val intent = ReaderActivity.newIntent(activity, manga.id, chapter.id)
                    .putExtra("page_index", info.initialPage.coerceAtMost(info.pageCount - 1))
                    .putExtra(HengduReaderFiles.ONLINE_URL_EXTRA, url)
                    .putExtra(HengduReaderFiles.ONLINE_TOKEN_EXTRA, session)
                    .putExtra(
                        "hengdu_experimental_dual",
                        eu.kanade.tachiyomi.BuildConfig.DEBUG &&
                            activity.intent.getBooleanExtra("hengdu_test_dual", false),
                    )
                HengduChapterHandoff.startReader(activity, intent, handoffToken)
                session = null
                onOpened()
            } catch (e: Exception) {
                val url = localUrl
                val token = session
                if (url != null && token != null) HengduOnlineGateway.removeChapter(activity, url, token)
                onError(e.message ?: "無法開啟原生閱讀器")
            }
        }
    }

    private suspend fun prepareChapter(activity: Activity, info: ChapterInfo): Triple<Manga, Chapter, String> {
        val graph = activity.metroGraph<AppGraph>()
        // A separate synthetic manga prevents unrelated chapters being offered as the next one.
        val bookKey = sha256(info.bookUrl + "\u0000" + info.chapterUrl)
        val localUrl = "$bookKey/${sha256(info.chapterUrl)}"
        val manga = graph.testMangaRepository.insertNetworkManga(
            listOf(
                Manga.create().copy(source = LocalSource.ID, url = bookKey, title = info.bookTitle, initialized = true),
            ),
        ).single()
        var chapter = graph.testChapterRepository.getChapterByUrlAndMangaId(localUrl, manga.id)
        if (chapter == null) {
            val candidate = Chapter.create().copy(
                mangaId = manga.id,
                url = localUrl,
                name = info.chapterTitle,
                lastPageRead = info.initialPage.coerceAtMost(info.pageCount - 1),
            )
            chapter =
                graph.testChapterRepository.updateFromRemote(emptyList(), listOf(candidate), emptyList()).firstOrNull()
                    ?: graph.testChapterRepository.getChapterByUrlAndMangaId(localUrl, manga.id)
        }
        checkNotNull(chapter)
        check(!activity.isFinishing && !activity.isDestroyed) { "閱讀器已關閉" }
        HengduProgress.register(
            activity,
            chapter.id,
            info.bookUrl,
            info.chapterUrl,
            info.chapterTitle,
            info.pageCount,
            localUrl,
            info.nextChapterUrl,
        )
        return Triple(manga, chapter, localUrl)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
