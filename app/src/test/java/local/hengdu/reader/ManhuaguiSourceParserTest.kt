package local.hengdu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ManhuaguiSourceParserTest {
    @Test
    fun `search and rank reject foreign hosts and duplicate books`() {
        val html = """
            <li class="cf"><div class="book-cover"><a href="/comic/123/" title="測試漫畫">
              <img data-src="https://cover.mhgui.com/123.jpg"></a></div></li>
            <li class="cf"><div class="book-cover"><a href="/comic/123/" title="重複"></a></div></li>
            <li class="cf"><div class="book-cover">
              <a href="https://manhuagui.com.evil.test/comic/456/" title="外部"></a></div></li>
        """.trimIndent()
        val results = ManhuaguiParser.search(html)
        assertEquals(listOf("測試漫畫"), results.map { it.title })
        assertEquals("https://cover.mhgui.com/123.jpg", results.single().thumbnail_url)
        assertEquals(
            listOf("排行"),
            ManhuaguiParser.rank("""<div class="rank-title"><h5><a href="/comic/123/">排行</a></h5></div>""")
                .map { it.title },
        )
        assertNull(ManhuaguiParser.bookUrl("https://www.manhuagui.com.evil.test/comic/123/"))
    }

    @Test
    fun `decompresses chapter listing and returns newest first`() {
        val state = "DwEwlgbgBAxgNgQwM5ILwCIYAsEAcAuApgE4C0cYS+6AfMAK5x0V0JRbGEBmGA9DAHsAtmBi8AjACYAzBMk" +
            "A6LPiFx0UfGHxxCGQDTeUwI5RtYLwR1eLYJbYdufQSLFTZ48YuWr1m7XtdHzZiaWvIzm4BA0QA==="
        val chapters = ManhuaguiParser.chapters(
            """<input id="__VIEWSTATE" value="$state">""",
            "https://www.manhuagui.com/comic/123/",
        )
        assertEquals(listOf("第12話", "第11話"), chapters.map { it.name })
        assertEquals("https://www.manhuagui.com/comic/123/12.html", chapters.first().url)
        assertThrows(IOException::class.java) {
            ManhuaguiParser.chapters("<html></html>", "https://www.manhuagui.com/comic/123/")
        }
    }

    @Test
    fun `separates volumes and extras from the chapter number sequence`() {
        val html = """
            <h4>單話</h4><div class="chapter-page"></div><div class="chapter-list"><ul>
              <li><a title="第748回 我的報恩" href="/comic/123/748.html"></a></li>
            </ul></div>
            <h4>单行本</h4><div class="chapter-page"></div><div class="chapter-list"><ul>
              <li><a title="第23卷" href="/comic/123/23.html"></a></li>
            </ul></div>
            <h4>番外篇</h4><div class="chapter-list"><ul>
              <li><a title="1000話紀念番外篇" href="/comic/123/1000.html"></a></li>
            </ul></div>
        """.trimIndent()
        val chapters = ManhuaguiParser.chapters(html, "https://www.manhuagui.com/comic/123/")
        assertEquals(-1f, chapters.single { it.name == "第748回 我的報恩" }.chapter_number)
        assertEquals(-2f, chapters.single { it.name == "第23卷" }.chapter_number)
        assertEquals(-2f, chapters.single { it.name == "1000話紀念番外篇" }.chapter_number)
        assertTrue(ManhuaguiChapterNumbering.hasMixedVolumesAndChapters(chapters.map { it.name }))
        assertFalse(ManhuaguiChapterNumbering.hasMixedVolumesAndChapters(listOf("第2話", "第1話")))
        assertFalse(ManhuaguiChapterNumbering.hasMixedVolumesAndChapters(listOf("第2卷", "第1卷")))
    }

    @Test
    fun `decompresses valid lz strings and rejects invalid data`() {
        assertEquals("Hello world!", ManhuaguiParser.unlz("BIUwNmD2AEDukCcwBMCEQ==="))
        assertThrows(IOException::class.java) { ManhuaguiParser.unlz("*") }
        assertThrows(IOException::class.java) { ManhuaguiParser.unlz("A".repeat(200001)) }
    }

    @Test
    fun `unpacks image list without executing source scripts`() {
        val packed = """}('0({"bookId":123,"chapterId":12,"images":["/book/a.jpg","/book/中文.png"],""" +
            """"sl":{"e":42,"m":"x+y/=="}}).1(',2,2,'E4UwhgJiwD4A6gJIDsCWAXIA')"""
        assertEquals(
            listOf(
                "https://i.hamreus.com/book/a.jpg?e=42&m=x%2By%2F%3D%3D",
                "https://i.hamreus.com/book/%E4%B8%AD%E6%96%87.png?e=42&m=x%2By%2F%3D%3D",
            ),
            ManhuaguiParser.images(packed, "https://www.manhuagui.com/comic/123/12.html"),
        )
        assertThrows(IOException::class.java) {
            ManhuaguiParser.images(packed, "https://www.manhuagui.com/comic/123/13.html")
        }
        assertThrows(IOException::class.java) {
            ManhuaguiParser.images(
                packed.replace("/book/a.jpg", "/book/../secret"),
                "https://www.manhuagui.com/comic/123/12.html",
            )
        }
    }

    @Test
    fun `supports original image format and rejects malicious image paths`() {
        val packed = """}('0({"bid":123,"cid":12,"path":"/book/","files":["first.jpg","second.png"],""" +
            """"sl":{"e":42,"m":"token"}}).1(',2,2,'JYWw5gIghgLlA+AHATgUwJIDtgyA')"""
        assertEquals(
            listOf(
                "https://i.hamreus.com/book/first.jpg?e=42&m=token",
                "https://i.hamreus.com/book/second.png?e=42&m=token",
            ),
            ManhuaguiParser.images(packed, "https://www.manhuagui.com/comic/123/12.html"),
        )
        assertThrows(IOException::class.java) {
            ManhuaguiParser.images(
                packed.replace("first.jpg", "../first.jpg"),
                "https://www.manhuagui.com/comic/123/12.html",
            )
        }
    }
}
