package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import local.hengdu.reader.HengduOnlineGateway
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Loads only the visible and adjacent pages through the existing source proxy. */
internal class HengduOnlinePageLoader(context: Context, private val chapter: ReaderChapter) : PageLoader() {
    private val appContext = context.applicationContext
    override var isLocal = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val semaphore = Semaphore(1)
    private val directory = File(context.cacheDir, "hengdu-online/" + UUID.randomUUID())
    private lateinit var pages: List<ReaderPage>

    override suspend fun getPages(): List<ReaderPage> {
        val images = HengduOnlineGateway.pages(appContext, chapter.chapter.url) ?: error("請返回書架重新開啟章節")
        check(directory.mkdirs() || directory.isDirectory)
        activeDirectories.add(directory.absolutePath)
        pages =
            images.mapIndexed { index, image ->
                ReaderPage(index, imageUrl = image).apply {
                    this.chapter =
                        this@HengduOnlinePageLoader.chapter
                }
            }
        return pages
    }

    override suspend fun loadPage(page: ReaderPage) {
        launchOnce(page)
        pages.getOrNull(page.index + 1)?.let(::launchOnce)
        pages.getOrNull(page.index - 1)?.let(::launchOnce)
    }

    override fun retryPage(page: ReaderPage) {
        if (page.status is Page.State.Error) page.status = Page.State.Queue
        launchOnce(page)
    }

    private fun launchOnce(page: ReaderPage) {
        if (isRecycled || page.status !is Page.State.Queue) return
        synchronized(jobs) {
            if (jobs[page.index]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) { load(page) }
            jobs[page.index] = job
            job.invokeOnCompletion { jobs.remove(page.index, job) }
            job.start()
        }
    }

    private suspend fun load(page: ReaderPage) {
        try {
            semaphore.withPermit {
                if (isRecycled) return
                page.status = Page.State.DownloadImage
                val target = File(directory, "%08d.img".format(page.index))
                val temporary = File(directory, "%08d.tmp".format(page.index))
                val response = HengduOnlineGateway.fetchImage(appContext, requireNotNull(page.imageUrl))
                try {
                    response.data.use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                    check(temporary.length() > 0 && !isRecycled && temporary.renameTo(target))
                    page.stream = { target.inputStream() }
                    page.status = Page.State.Ready
                } finally {
                    temporary.delete()
                }
            }
        } catch (e: CancellationException) {
            page.status = Page.State.Queue
            throw e
        } catch (e: Throwable) {
            if (!isRecycled) page.status = Page.State.Error(e)
        }
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        jobs.clear()
        directory.deleteRecursively()
        activeDirectories.remove(directory.absolutePath)
    }

    companion object {
        private val activeDirectories = ConcurrentHashMap.newKeySet<String>()

        @JvmStatic fun cleanupStale(context: Context) {
            val root = File(context.cacheDir, "hengdu-online")
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            root.listFiles()?.filter {
                it.isDirectory && it.lastModified() < cutoff &&
                    !activeDirectories.contains(it.absolutePath)
            }
                ?.forEach { it.deleteRecursively() }
        }
    }
}
