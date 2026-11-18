package local.hengdu.reader

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import mihon.core.migration.Migrator
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.time.Instant

/** Imports supported favorites without changing the original WebView data. */
object HengduLibraryMigration {
    data class Result(
        val imported: Int,
        val alreadyImported: Int,
        val unsupported: Int,
        val awaitingCatalog: Int,
        val preservedNative: Int,
    )

    private fun importKey(sourceId: Long, url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(
            (
                if (sourceId ==
                    HengduLibrarySnapshot.MANHUAREN_SOURCE_ID
                ) {
                    url
                } else {
                    "$sourceId\u0000$url"
                }
                ).toByteArray(Charsets.UTF_8),
        )
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    internal fun isImported(context: Context, sourceId: Long, url: String): Boolean =
        sourceId in
            setOf(
                HengduLibrarySnapshot.MANHUAREN_SOURCE_ID,
                HengduLibrarySnapshot.BAOZI_SOURCE_ID,
                HengduLibrarySnapshot.MANHUAGUI_SOURCE_ID,
            ) &&
            context.getSharedPreferences("hengdu_mihon_library_import", Context.MODE_PRIVATE)
                .getBoolean(importKey(sourceId, url), false)

    suspend fun importFromWebView(context: Context, webView: WebView): Result {
        val json = withContext(Dispatchers.Main.immediate) {
            val uri = Uri.parse(webView.url ?: "")
            require(
                uri.scheme == "https" && uri.host == "reader.local" && (uri.path == "/" || uri.path == "/index.html"),
            )
            withTimeout(10000) {
                suspendCancellableCoroutine { continuation ->
                    webView.evaluateJavascript(
                        """(function(){try{const read=(key)=>JSON.parse(localStorage.getItem(key)||'{}');return {library:read('hengdu-library-v1'),catalogs:read('hengdu-catalog-v1'),reading:read('hengdu-chapter-progress-v1'),saved:read('hengdu-progress'),history:read('hengdu-history-v1')}}catch(e){return null}})()""",
                    ) { result -> if (continuation.isActive) continuation.resume(result ?: "null") }
                }
            }
        }
        val snapshot = withContext(Dispatchers.Default) { HengduLibrarySnapshot.parse(json) }
        Migrator.await()
        context.appGraph.databaseRepository.awaitOpen()
        val mangaRepo = context.appGraph.testMangaRepository
        val chapterRepo = context.appGraph.testChapterRepository
        val historyRepo = context.appGraph.historyRepository
        val prefs = context.getSharedPreferences("hengdu_mihon_library_import", Context.MODE_PRIVATE)
        var imported = 0
        var alreadyImported = 0
        var awaitingCatalog = 0
        var preservedNative = 0
        for (book in snapshot.books) {
            val key = importKey(book.sourceId, book.url)
            val already = prefs.getBoolean(key, false)
            val old = mangaRepo.getMangaByUrlAndSourceId(book.url, book.sourceId)
            when (
                HengduLibraryImportPlan.decide(
                    already,
                    old != null,
                    book.catalogValid && book.chapters.isNotEmpty(),
                )
            ) {
                HengduLibraryImportPlan.Action.AlreadyImported -> alreadyImported++
                HengduLibraryImportPlan.Action.AwaitCatalog -> {
                    awaitingCatalog++
                    continue
                }
                HengduLibraryImportPlan.Action.PreserveNative -> {
                    preservedNative++
                    continue
                }
                HengduLibraryImportPlan.Action.Import -> Unit
            }
            val favoriteAt = Instant.fromEpochMilliseconds(book.added)
            val manga = if (already) {
                checkNotNull(old)
            } else {
                val chapters = book.chapters.map {
                    Chapter.create().copy(
                        url = it.url,
                        name = it.title,
                        sourceOrder = it.order,
                        lastPageRead = it.page,
                        read = it.read,
                    )
                }
                val inserted = mangaRepo.insertMangaWithChaptersIfAbsent(
                    Manga.create().copy(
                        source = book.sourceId,
                        url = book.url,
                        title = book.title,
                        author = book.author,
                        description = book.description,
                        thumbnailUrl = book.cover,
                        favoriteAt = favoriteAt,
                        initialized = true,
                    ),
                    chapters,
                )
                if (inserted == null) {
                    preservedNative++
                    continue
                }
                inserted
            }
            if (!already) {
                check(prefs.edit().putBoolean(key, true).commit())
                imported++
            }
            val historyKey = "history_" + key
            if (!prefs.getBoolean(historyKey, false)) {
                val latest = book.history
                // A later WebView delivery may still provide the missing history.
                if (latest != null) {
                    val priorHistory = historyRepo.getHistoryByMangaId(manga.id)
                    if (priorHistory.any { it.readAt != null }) {
                        check(prefs.edit().putBoolean(historyKey, true).commit())
                    } else {
                        val chapter = chapterRepo.getChapterByUrlAndMangaId(latest.chapterUrl, manga.id)
                        if (chapter != null) {
                            val time = Instant.fromEpochMilliseconds(latest.updated)
                            historyRepo.upsertHistory(HistoryUpdate(chapter.id, time, 0))
                            check(
                                historyRepo.getHistoryByMangaId(manga.id).any {
                                    it.chapterId == chapter.id &&
                                        it.readAt == time
                                },
                            )
                            check(prefs.edit().putBoolean(historyKey, true).commit())
                        }
                    }
                }
            }
        }
        return Result(imported, alreadyImported, snapshot.skipped, awaitingCatalog, preservedNative)
    }
}
