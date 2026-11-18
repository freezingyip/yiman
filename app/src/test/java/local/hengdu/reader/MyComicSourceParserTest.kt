package local.hengdu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class MyComicSourceParserTest {
    @Test
    fun `all built-in online sources have distinct identifiers`() {
        val ids = listOf(
            BaoziSource.ID,
            BoyLoveSource.ID,
            C18hSource.ID,
            KuaikanSource.ID,
            ManhuaguiSource.ID,
            ManhuarenSource.ID,
            ManwaSource.ID,
            Mh160Source.ID,
            MyComicSource.ID,
            YymhSource.ID,
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `normalizes real book URL and rejects other origins`() {
        assertEquals(
            "https://mycomic.com/comics/25946",
            MyComicParser.bookUrl("https://mycomic.com/comics/25946#7e22a78d6c33d8ebbf19a7b71a3f2a10"),
        )
        assertEquals("https://mycomic.com/comics/25946", MyComicParser.bookUrl("/cn/comics/25946"))
        assertNull(MyComicParser.bookUrl("https://mycomic.com.evil.example/comics/25946"))
        assertFalse(MyComicParser.isImageUrl("https://biccam.com.evil.example/chapters/713607/1.jpg"))
    }

    @Test
    fun `search extracts and deduplicates book cards`() {
        val html = """<div class="group"><a href="/comics/25946"><img alt="青之蘆葦"
            data-src="https://biccam.com/cover.jpg"></a></div>
            <div class="group"><a href="/comics/25946"><img alt="重複"></a></div>
            <div class="group"><a href="https://evil.example/comics/1"><img alt="外站"></a></div>"""
        val items = MyComicParser.search(html)
        assertEquals(1, items.size)
        assertEquals("青之蘆葦", items.single().title)
        assertEquals("https://biccam.com/cover.jpg", items.single().thumbnail_url)
    }

    @Test
    fun `reads chapter JSON without confusing a bracket inside a title`() {
        val html = """<div x-data='{ chapters: [{"id":713607,"title":"第334話 [完]"},""" +
            """{"id":713606,"title":"第333話"}], descending: true }'></div>"""
        val chapters = MyComicParser.chapters(html)
        assertEquals(listOf("第334話 [完]", "第333話"), chapters.map { it.name })
        assertEquals("https://mycomic.com/chapters/713607", chapters.first().url)
    }

    @Test
    fun `keeps chapter image order and rejects external images`() {
        val html = """<img class="page" data-src="https://biccam.com/chapters/713607/1-28dfdb.jpg">""" +
            """<img class="page" src="https://biccam.com/chapters/713607/2.jpg">"""
        assertEquals(
            listOf("https://biccam.com/chapters/713607/1-28dfdb.jpg", "https://biccam.com/chapters/713607/2.jpg"),
            MyComicParser.images(html),
        )
        assertThrows(IOException::class.java) {
            MyComicParser.images("""<img class="page" src="https://evil.example/1.jpg">""")
        }
    }
}
