package local.hengdu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class BaoziSourceParserTest {
    private val book = "https://www.baozimh.com/comic/example"
    private val first = "https://www.baozimh.com/comic/chapter/example/0_1.html"

    @Test
    fun `search keeps trusted works and rejects foreign covers and links`() {
        val html = """
            <a class="comics-card__poster" href="/comic/example" title="測試漫畫">
              <img data-src="https://img.baozicdn.com/cover.jpg">
            </a>
            <a class="comics-card__poster" href="/comic/example" title="duplicate"></a>
            <a class="comics-card__poster" href="https://baozimh.com.evil.test/comic/evil" title="fake"></a>
            <a class="comics-card__poster" href="/comic/second" title="second">
              <img src="https://evil.test/image.jpg">
            </a>
        """.trimIndent()
        val results = BaoziSourceParser.search(html, book)
        assertEquals(listOf("測試漫畫", "second"), results.map { it.title })
        assertEquals("https://img.baozicdn.com/cover.jpg", results.first().thumbnail_url)
        assertNull(results.last().thumbnail_url)
    }

    @Test
    fun `search extracts the real cover from nested AMP image tags`() {
        val html = """
            <a class="comics-card__poster" href="/comic/example" title="測試漫畫">
              <amp-img src="https://static-tw.baozimh.com/cover/example.jpg?w=285&amp;h=375">
                <amp-img placeholder src="https://static-tw.baozimh.com/cover/default_cover.png"></amp-img>
              </amp-img>
            </a>
        """.trimIndent()
        assertEquals(
            "https://static-tw.baozimh.com/cover/example.jpg?w=285&h=375",
            BaoziSourceParser.search(html, book).single().thumbnail_url,
        )
    }

    @Test
    fun `catalog normalizes direct links and returns newest first`() {
        val html = """
            <a href="/user/page_direct?comic_id=example&amp;section_slot=0&amp;chapter_slot=1"><span>第 1 話</span></a>
            <a href="/comic/chapter/example/0_2.html"><span>第 2 話</span></a>
            <a href="https://evil.test/comic/chapter/example/0_3.html">外部</a>
            <a href="/comic/chapter/example/0_2.html">duplicate</a>
        """.trimIndent()
        val chapters = BaoziSourceParser.chapters(html, book)
        assertEquals(listOf("第 2 話", "第 1 話"), chapters.map { it.name })
        assertEquals(first, chapters.last().url)
    }

    @Test
    fun `page parser follows same chapter only and keeps image order`() {
        val second = "https://www.baozimh.com/comic/chapter/example/0_1_2.html"
        val data = mapOf(
            first to """<img class="chapter-img" src="https://img.baozicdn.com/1.jpg">
                <div class="next_chapter"><a href="/comic/chapter/example/0_1_2.html">next</a></div>""",
            second to """<amp-img class="chapter-img" data-src="https://img.baozicdn.com/2.jpg"></amp-img>
                <div class="next_chapter"><a href="/comic/chapter/example/0_2.html">next chapter</a></div>""",
        )
        assertEquals(
            listOf("https://img.baozicdn.com/1.jpg", "https://img.baozicdn.com/2.jpg"),
            BaoziSourceParser.pages(first) { data.getValue(it) },
        )
    }

    @Test
    fun `chapter parses current AMP images and stops before the next chapter`() {
        val page = """
            <ul class="comic-contain">
              <amp-img id="chapter-img-0-0" class="comic-contain__item" data-src="https://s2.bzcdn.net/scomic/example/0/998/1.jpg"></amp-img>
              <amp-img id="chapter-img-0-1" class="comic-contain__item" data-src="https://s2.bzcdn.net/scomic/example/0/998/2.jpg"></amp-img>
            </ul>
            <div class="next_chapter"><a href="/comic/chapter/example/0_2.html">next chapter</a></div>
            <amp-img src="https://static-tw.baozimh.com/cover/recommendation.jpg"></amp-img>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://s2.bzcdn.net/scomic/example/0/998/1.jpg",
                "https://s2.bzcdn.net/scomic/example/0/998/2.jpg",
            ),
            BaoziSourceParser.pages(first) { page },
        )
        assertThrows(IOException::class.java) {
            BaoziSourceParser.pages(first) {
                page.replace("https://s2.bzcdn.net/scomic/example/0/998/2.jpg", "https://evil.test/2.jpg")
            }
        }
    }

    @Test
    fun `broken and looping chapter pages fail rather than silently truncate`() {
        assertThrows(IOException::class.java) { BaoziSourceParser.pages(first) { "<html></html>" } }
        assertThrows(IOException::class.java) {
            BaoziSourceParser.pages(first) {
                """<img class="chapter-img" src="https://img.baozicdn.com/1.jpg">
                    <div class="next_chapter"><a href="/comic/chapter/example/0_1.html">loop</a></div>"""
            }
        }
    }

    @Test
    fun `URLs reject insecure and lookalike hosts`() {
        assertNull(BaoziSourceParser.chapterUrl("https://baozimh.com.evil.test/comic/chapter/example/0_1.html"))
        assertNull(BaoziSourceParser.chapterUrl("http://www.baozimh.com/comic/chapter/example/0_1.html"))
        assertNull(BaoziSourceParser.bookUrl("https://evil.test/comic/example"))
    }
}
