package local.hengdu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BaoziCatalogOrderTest {
    @Test
    fun `full catalog takes precedence over repeated latest chapters and preserves split chapters`() {
        val base = "https://www.baozimh.com/comic/example"
        val html = """
            <div class="comics-detail__info">
              <a href="/user/page_direct?comic_id=example&amp;section_slot=0&amp;chapter_slot=3">第2話（下）</a>
              <a href="/user/page_direct?comic_id=example&amp;section_slot=0&amp;chapter_slot=2">第2話（上）</a>
            </div>
            <div id="chapter-items">
              <a href="/user/page_direct?comic_id=example&amp;section_slot=0&amp;chapter_slot=0">第1話</a>
              <a href="/user/page_direct?comic_id=example&amp;section_slot=0&amp;chapter_slot=1">2024年3月20日道歉信</a>
            </div>
            <div id="chapters_other_list">
              <a href="/user/page_direct?comic_id=example&amp;section_slot=0&amp;chapter_slot=2">第2話（上）</a>
              <a href="/user/page_direct?comic_id=example&amp;section_slot=0&amp;chapter_slot=3">第2話（下）</a>
            </div>
        """.trimIndent()
        val result = BaoziSourceParser.chapters(html, base)
        assertEquals(listOf("第2話（下）", "第2話（上）", "2024年3月20日道歉信", "第1話"), result.map { it.name })
        assertEquals(
            listOf("0_3.html", "0_2.html", "0_1.html", "0_0.html"),
            result.map {
                it.url.substringAfterLast('/')
            },
        )
        assertEquals(-2f, result[2].chapter_number)
    }
}
