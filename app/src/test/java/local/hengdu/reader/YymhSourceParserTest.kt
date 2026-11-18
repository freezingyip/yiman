package local.hengdu.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class YymhSourceParserTest {
    @Test fun `search validates works and image hosts`() {
        val raw = """{"result":{"list":[{"id":"5635","title":"作品","image":"https://yy.yymhimg1.top/a.jpg"},""" +
            """{"id":"evil","title":"無效"}]}}"""
        val result = YymhParser.search(raw).single()
        assertEquals("https://yymh889.com/home/book/index/id/5635", result.url)
        assertEquals("https://yy.yymhimg1.top/a.jpg", result.thumbnail_url)
        assertFalse(YymhParser.imageUrl("https://yymhimg1.top.evil.test/a.jpg"))
        assertNull(YymhParser.bookUrl("https://yymh889.com.evil.test/home/book/index/id/1"))
    }

    @Test fun `only chapters explicitly offered for free with public images are listed`() {
        val raw = """{"result":{"totalPage":1,"list":[{"id":"1","title":"免費","score":"0",""" +
            """"imagelist":"https://yy.yymhimg1.top/a.jpg,https://yy.yymhimg1.top/b.jpg"},""" +
            """{"id":"2","title":"付費","score":"58"},""" +
            """{"id":"3","title":"未知","imagelist":"https://yy.yymhimg1.top/c.jpg"}]}}"""
        val page = YymhParser.chapterPage(raw, "5635", 1)
        assertEquals(listOf("免費"), page.chapters.map { it.name })
        assertEquals(
            listOf("https://yy.yymhimg1.top/a.jpg", "https://yy.yymhimg1.top/b.jpg"),
            YymhParser.images(page.items.first()),
        )
        assertFalse(YymhParser.isPublic(page.items[1]))
        assertFalse(YymhParser.isPublic(page.items[2]))
        assertNull(YymhParser.chapterParts("https://yymh889.com/home/book/capter/id/1?book=5635&page=201"))
        val bad = Json.parseToJsonElement("""{"score":"0","imagelist":"https://evil.test/a.jpg"}""").jsonObject
        assertThrows(IOException::class.java) { YymhParser.images(bad) }
    }
}
