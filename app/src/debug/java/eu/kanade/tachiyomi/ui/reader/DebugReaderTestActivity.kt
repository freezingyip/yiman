package eu.kanade.tachiyomi.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import java.io.File

/** Isolated smoke test, present only in the temporary debug build. */
class DebugReaderTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                val graph = metroGraph<AppGraph>()
                val dual = intent.getBooleanExtra("dual", false)
                val wide = intent.getBooleanExtra("wide", false)
                val bookName = if (wide) {
                    "Wide pager prototype"
                } else if (dual) {
                    "Dual pager prototype"
                } else {
                    "Test Manga"
                }
                if (dual) graph.basePreferences.highQualityRenderer.set(false)
                val base = File(getExternalFilesDir(null), "reader-smoke-test").apply { mkdirs() }
                val chapterDir = File(base, "local/$bookName/Chapter 1").apply { mkdirs() }
                withContext(Dispatchers.IO) {
                    for (page in 1..3) {
                        val bitmap = Bitmap.createBitmap(
                            if (wide &&
                                page == 2
                            ) {
                                1200
                            } else {
                                800
                            },
                            if (wide && page == 2) 800 else 1200,
                            Bitmap.Config.ARGB_8888,
                        )
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(if (page % 2 == 0) Color.rgb(245, 235, 220) else Color.WHITE)
                        val paint = Paint().apply {
                            color = Color.BLACK
                            textSize = 90f
                            isAntiAlias = true
                        }
                        canvas.drawText("Test page $page", 100f, 250f, paint)
                        File(chapterDir, "%03d.png".format(page)).outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
                graph.storagePreferences.baseStorageDirectory.set(Uri.fromFile(base).toString())
                val manga = graph.testMangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = LocalSource.ID,
                            url = bookName,
                            title = bookName,
                            initialized = true,
                            viewerFlags = if (dual) 1L else 0L,
                        ),
                    ),
                ).single()
                val chapter = Chapter.create().copy(
                    mangaId = manga.id,
                    url = "$bookName/Chapter 1",
                    name = "Chapter 1",
                    chapterNumber = 1.0,
                )
                val stored = graph.testChapterRepository.updateFromRemote(emptyList(), listOf(chapter), emptyList())
                val chapterId = stored.firstOrNull()?.id
                    ?: graph.testChapterRepository.getChapterByUrlAndMangaId(chapter.url, manga.id)!!.id
                Log.i("ReaderSmokeTest", "Launching manga=${manga.id} chapter=$chapterId")
                startActivity(
                    ReaderActivity.newIntent(this@DebugReaderTestActivity, manga.id, chapterId)
                        .putExtra("hengdu_experimental_dual", intent.getBooleanExtra("dual", false))
                        .putExtra("page_index", intent.getIntExtra("page", 0)),
                )
                finish()
            } catch (e: Exception) {
                Log.e("ReaderSmokeTest", "Smoke test failed", e)
            }
        }
    }
}
