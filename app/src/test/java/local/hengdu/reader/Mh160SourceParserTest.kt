package local.hengdu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Base64

class Mh160SourceParserTest {
    @Test
    fun `keeps chapter images in order and rejects untrusted paths`() {
        fun chapter(paths: String) = """
            <script>var qTcms_S_p_id = "542725"; var qTcms_S_m_murl_e = "${Base64.getEncoder().encodeToString(
            paths.toByteArray(),
        )}";</script>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://mhpic5er.tgmhfc.uk/mh160tuku/book/1.jpg",
                "https://mhpic5er.tgmhfc.uk/mh160tuku/book/2.jpg",
            ),
            Mh160Parser.images(chapter("/mh160tuku/book/1.jpg\$qingtiandy\$/mh160tuku/book/2.jpg")),
        )
        assertThrows(IOException::class.java) { Mh160Parser.images(chapter("/mh160tuku/../other.jpg")) }
    }

    @Test
    fun `search accepts only expected source links`() {
        val html = """<div class="mh-worksbord"><div class="mh-works-title"><h4><a title="作品" href="/kanmanhua/25260/">作品</a></h4></div></div>
            <div class="mh-worksbord"><div class="mh-works-title"><h4><a title="外部" href="https://evil.example/kanmanhua/1/">外部</a></h4></div></div>"""
        assertEquals(listOf("https://www.mh160mh.com/kanmanhua/25260/"), Mh160Parser.search(html).map { it.url })
    }

    @Test
    fun `desktop and mobile chapter lists keep latest first and reject unrelated works`() {
        val book = "https://www.mh160mh.com/kanmanhua/ONEPOINT/"
        val links = """<li><a href="/kanmanhua/ONEPOINT/12280120.html">第120話</a></li>
            <li><a href="/kanmanhua/ONEPOINT/46120119.html">第119話</a></li>
            <li><a href="/kanmanhua/OTHER/1.html">其他作品</a></li>
            <li><a href="https://www.mh160mh.com.evil.test/kanmanhua/ONEPOINT/1.html">外部</a></li>"""
        for (id in listOf("mh-chapter-list-ol-0", "chapterList_ul_1")) {
            val chapters = Mh160Parser.chapters("<ul id=\"$id\">$links</ul>", book)
            assertEquals(listOf("第120話", "第119話"), chapters.map { it.name })
            assertEquals("https://www.mh160mh.com/kanmanhua/ONEPOINT/12280120.html", chapters.first().url)
        }
    }

    @Test
    fun `mobile Gone catalog resolves relative links to the same work`() {
        val html = """<ul id="chapterList_ul_1"><li><a href="/kanmanhua/Gone/85300005.html">第05话</a></li>
            <li><a href="/kanmanhua/Gone/84750001.html">第01话</a></li></ul>"""
        val chapters = Mh160Parser.chapters(html, "https://www.mh160mh.com/kanmanhua/Gone/")
        assertEquals(listOf("第05话", "第01话"), chapters.map { it.name })
        assertEquals("https://www.mh160mh.com/kanmanhua/Gone/85300005.html", chapters.first().url)
    }
}
