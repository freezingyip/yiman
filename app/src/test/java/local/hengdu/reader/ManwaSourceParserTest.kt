package local.hengdu.reader

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ManwaSourceParserTest {
    @Test
    fun `verification opens the protected search on the source domain`() {
        val source = ManwaSource()
        assertEquals("https://manwa.me", source.baseUrl)
        assertEquals("https://manwa.me/search?keyword=one&page=1", source.getHomeUrl())
    }

    @Test
    fun `webview uses canonical book and chapter addresses`() {
        val source = ManwaSource()
        val manga = SManga.create().apply { url = "https://manwa.me/book/456104" }
        val chapter = SChapter.create().apply { url = "https://manwa.me/chapter/123" }
        assertEquals("https://manwa.me/book/456104", source.getMangaUrl(manga))
        assertEquals("https://manwa.me/chapter/123", source.getChapterUrl(chapter))
        manga.url = "/book/456104/"
        chapter.url = "/chapter/123/"
        assertEquals("https://manwa.me/book/456104", source.getMangaUrl(manga))
        assertEquals("https://manwa.me/chapter/123", source.getChapterUrl(chapter))
        manga.url = "https://manwa.me.evil.test/book/456104"
        chapter.url = "https://manwa.me.evil.test/chapter/123"
        assertThrows(IOException::class.java) { source.getMangaUrl(manga) }
        assertThrows(IOException::class.java) { source.getChapterUrl(chapter) }
    }

    @Test
    fun `search extracts and deduplicates public books and detects next page`() {
        val html = """
            <ul class="book-list">
              <li><a href="/book/7722/"><img data-original="https://mwappimgs.cc/a.jpg"></a>
                <p class="book-list-info-title">測試作品</p></li>
              <li><a href="/book/7722"><p class="book-list-info-title">重複</p></a></li>
              <li><a href="https://manwa.me.evil.test/book/1"><p class="book-list-info-title">外部</p></a></li>
            </ul><ul class="pagination2"><li>1</li><li><a href="?page=2">下一页</a></li></ul>
        """.trimIndent()
        val results = ManwaParser.search(html)
        assertEquals(listOf("測試作品"), results.map { it.title })
        assertEquals("https://manwa.me/book/7722", results.single().url)
        assertEquals("https://mwappimgs.cc/a.jpg", results.single().thumbnail_url)
        assertTrue(ManwaParser.hasNextPage(html))
        assertFalse(ManwaParser.hasNextPage("<ul class='pagination2'><li>1</li></ul>"))
        assertNull(ManwaParser.bookUrl("https://manwa.me@evil.test/book/1"))
        assertNull(ManwaParser.bookUrl("https://manwa.me:444/book/1"))
    }

    @Test
    fun `details and chapter list are parsed without external links or duplicates`() {
        val html = """
            <div id="detail"><div class="detail-desc">作品簡介</div></div>
            <div class="detail-main-cover"><img data-original="//mwappimgs.cc/cover.jpg"></div>
            <p class="detail-main-info-author"><span class="detail-main-info-author-field">作者</span>
              <span class="detail-main-info-value">作者名</span></p>
            <ul id="detail-list-select">
              <li><a href="/chapter/11">第11話</a></li><li><a href="/chapter/12/" title="第12話">12</a></li>
              <li><a href="/chapter/12">重複</a></li><li><a href="https://evil.test/chapter/13">外部</a></li>
            </ul>
        """.trimIndent()
        val manga = ManwaParser.details(
            html,
            SManga.create().apply {
                url = "https://manwa.me/book/7722"
                title = "測試作品"
            },
        )
        assertEquals("作品簡介", manga.description)
        assertEquals("作者名", manga.author)
        assertEquals("https://mwappimgs.cc/cover.jpg", manga.thumbnail_url)
        assertEquals(listOf("第12話", "第11話"), ManwaParser.chapters(html).map { it.name })
        assertThrows(IOException::class.java) { ManwaParser.chapters("<html></html>") }
    }

    @Test
    fun `chapter images keep their order and reject untrusted links`() {
        val html = """<div id="cp_img"><img data-r-src="https://mwappimgs.cc/1.jpg?v=20220724">""" +
            """<img data-r-src="//mwappimgs.cc/2.jpg"></div>"""
        assertEquals(
            listOf("https://mwappimgs.cc/1.jpg?v=20220724", "https://mwappimgs.cc/2.jpg"),
            ManwaParser.images(html),
        )
        assertThrows(IOException::class.java) {
            ManwaParser.images(html.replace("//mwappimgs.cc/2.jpg", "//mwappimgs.cc.evil.test/2.jpg"))
        }
        assertThrows(IOException::class.java) { ManwaParser.images("<html></html>") }
        assertFalse(ManwaParser.isImageUrl("http://mwappimgs.cc/1.jpg"))
    }
}
