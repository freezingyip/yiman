package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.File

/** Reads pages already staged by the trusted Hengdu WebView. */
internal class HengduPageLoader(private val directory: File) : PageLoader() {
    override var isLocal = true

    override suspend fun getPages(): List<ReaderPage> = directory.listFiles().orEmpty()
        .filter { it.isFile }
        .sortedBy { it.name }
        .mapIndexed { index, file ->
            ReaderPage(index).apply {
                stream = { file.inputStream() }
                status = Page.State.Ready
            }
        }
}
