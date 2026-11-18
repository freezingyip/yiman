package local.hengdu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class ManhuarenSourceParserTest {
    @Test
    fun `search keeps valid works and rejects cross site links`() {
        val html = """
            <div class="book-list-cover">
              <a href="/manhua-blue-lock/" title="BLUE LOCK"><img data-original="https://i.cdndm5.com/cover.jpg"></a>
              <a href="/manhua-blue-lock/" title="Duplicate"></a>
              <a href="https://example.com/manhua-fake/" title="Fake"></a>
              <a href="/manhua-other/" title="Other"><img src="https://example.com/cover.jpg"></a>
            </div>
        """.trimIndent()
        val books = ManhuarenSourceParser.search(html)
        assertEquals(listOf("BLUE LOCK", "Other"), books.map { it.title })
        assertEquals("https://i.cdndm5.com/cover.jpg", books[0].thumbnail_url)
        assertEquals(null, books[1].thumbnail_url)
    }

    @Test
    fun `chapters are newest first regardless of source sort direction`() {
        val html = """
            <span class="detail-list-title-right sort-2"></span>
            <ul id="detail-list-select-1">
              <li><a href="/m12/"><span class="chapter-info-title">第12话</span></a></li>
              <li><a href="/m11/"><span class="chapter-info-title">第11话</span></a></li>
              <li><a href="https://example.com/m10/">Invalid</a></li>
            </ul>
        """.trimIndent()
        assertEquals(listOf("第12话", "第11话"), ManhuarenSourceParser.chapters(html).map { it.name })
    }

    @Test
    fun `empty or unavailable chapters do not erase an existing catalogue`() {
        assertThrows(IOException::class.java) { ManhuarenSourceParser.chapters("<html></html>") }
        assertThrows(IOException::class.java) { ManhuarenSourceParser.chapters("<!-- 已不再提供在线阅读 -->") }
    }

    @Test
    fun `chapter parser extracts only allowed image hosts`() {
        val html = "var newImgs=['https://x.cdndm5.com/1.jpg','https://cdndm5.com/2.png'];"
        assertEquals(
            listOf("https://x.cdndm5.com/1.jpg", "https://cdndm5.com/2.png"),
            ManhuarenNativeChapter.extractImages(html),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ManhuarenNativeChapter.extractImages("var newImgs=['https://cdndm5.com.evil.test/p.jpg'];")
        }
    }

    @Test
    fun `chapter parser unpacks encoded image list without executing it`() {
        val html = "}('0=[\\'https://x.cdndm5.com/1.jpg\\'];',2,1,'newImgs'.split('|')"
        assertEquals(listOf("https://x.cdndm5.com/1.jpg"), ManhuarenNativeChapter.extractImages(html))
    }
}
