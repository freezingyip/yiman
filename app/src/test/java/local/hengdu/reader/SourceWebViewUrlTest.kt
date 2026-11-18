package local.hengdu.reader

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceWebViewUrlTest {
    @Test
    fun `webview urls retain saved full addresses and accept relative paths`() {
        val cases = listOf(
            Triple(
                BaoziSource(),
                "https://www.baozimh.com/comic/test",
                "https://www.baozimh.com/comic/chapter/test/1_2.html",
            ),
            Triple(ManhuarenSource(), "https://www.manhuaren.com/manhua-test/", "https://www.manhuaren.com/m1851941/"),
            Triple(ManhuaguiSource(), "https://www.manhuagui.com/comic/1/", "https://www.manhuagui.com/comic/1/2.html"),
            Triple(
                KuaikanSource(),
                "https://www.kuaikanmanhua.com/web/topic/1/",
                "https://www.kuaikanmanhua.com/webs/comic-next/2/",
            ),
            Triple(
                BoyLoveSource(),
                "https://boylove.cc/home/book/index/id/1",
                "https://boylove.cc/home/book/capter/id/2",
            ),
            Triple(
                C18hSource(),
                "https://18h.mm-cg.com/zh/test/1/content.html",
                "https://18h.mm-cg.com/zh/test/1/content.html",
            ),
            Triple(
                Mh160Source(),
                "https://www.mh160mh.com/kanmanhua/25260",
                "https://www.mh160mh.com/kanmanhua/25260/123.html",
            ),
            Triple(MyComicSource(), "https://mycomic.com/comics/25946", "https://mycomic.com/chapters/713607"),
        )
        cases.forEach { (source, book, chapter) ->
            val manga = SManga.create().apply { url = book }
            val sChapter = SChapter.create().apply { url = chapter }
            assertEquals(book, source.getMangaUrl(manga), source.name)
            assertEquals(chapter, source.getChapterUrl(sChapter), source.name)
            manga.url = java.net.URI(book).rawPath
            sChapter.url = java.net.URI(chapter).rawPath
            assertEquals(book, source.getMangaUrl(manga), "${source.name} relative book")
            assertEquals(chapter, source.getChapterUrl(sChapter), "${source.name} relative chapter")
            manga.url = "https://evil.example${java.net.URI(book).rawPath}"
            sChapter.url = "https://evil.example${java.net.URI(chapter).rawPath}"
            assertThrows(IOException::class.java, { source.getMangaUrl(manga) }, source.name)
            assertThrows(IOException::class.java, { source.getChapterUrl(sChapter) }, source.name)
        }
    }

    @Test
    fun `yymh chapter webview omits internal lookup parameters`() {
        val source: HttpSource = YymhSource()
        val manga = SManga.create().apply { url = "https://yymh889.com/home/book/index/id/1" }
        val chapter = SChapter.create().apply { url = "https://yymh889.com/home/book/capter/id/2?book=1&page=1" }
        assertEquals(manga.url, source.getMangaUrl(manga))
        assertEquals("https://yymh889.com/home/book/capter/id/2", source.getChapterUrl(chapter))
        chapter.url = "https://evil.example/home/book/capter/id/2?book=1&page=1"
        assertThrows(IOException::class.java) { source.getChapterUrl(chapter) }
    }
}
