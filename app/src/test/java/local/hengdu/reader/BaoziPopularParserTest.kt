package local.hengdu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class BaoziPopularParserTest {
    @Test
    fun `popular lists only ranking in website order`() {
        val html = """
            <div class="index-rank index-recommend-items">
              <div class="catalog-title">熱門漫畫</div>
              <a class="comics-card__poster" href="/comic/first" title="第一本"></a>
              <a class="comics-card__poster" href="/comic/second" title="第二本"></a>
              <a class="comics-card__poster" href="https://evil.test/comic/third" title="外部"></a>
            </div>
            <div><a class="comics-card__poster" href="/comic/other" title="其他推薦"></a></div>
        """.trimIndent()
        val result = BaoziPopularParser.parse(html, "https://www.baozimh.com/")
        assertEquals(listOf("第一本", "第二本"), result.map { it.title })
    }

    @Test
    fun `missing ranking reports source error`() {
        assertThrows(IOException::class.java) { BaoziPopularParser.parse("<html></html>", "https://www.baozimh.com/") }
    }
}
