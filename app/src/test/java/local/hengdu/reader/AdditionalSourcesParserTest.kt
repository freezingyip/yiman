package local.hengdu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class AdditionalSourcesParserTest {
    @Test fun `kanman search and trusted urls`() {
        val data = """{"data":[{"comic_id":123,"comic_name":"作品","cover_img":"http://image.yqmh.com/a.jpg"},""" +
            """{"comic_id":"1bad","comic_name":"錯誤"}]}"""
        val manga = KanmanParser.search(data).single()
        assertEquals("https://www.kanman.com/123/", manga.url)
        assertEquals("https://image.yqmh.com/a.jpg", manga.thumbnail_url)
        assertNull(KanmanParser.chapterUrl("https://www.kanman.com.evil.test/123/a.html"))
        assertFalse(KanmanParser.isImageUrl("https://kaimanhua.com.evil.test/a.jpg"))
    }

    @Test fun `kanman does not expose paid pages`() {
        val paid = """{"data":{"current_chapter":{"price":1,"is_vip":0,""" +
            """"chapter_img_list":["https://image.yqmh.com/a.jpg"]}}}"""
        assertThrows(IOException::class.java) { KanmanParser.images(paid) }
        val free = paid.replace("\"price\":1", "\"price\":0")
        assertEquals(listOf("https://image.yqmh.com/a.jpg"), KanmanParser.images(free))
        assertThrows(IOException::class.java) { KanmanParser.images(free.replace("\"price\":0,", "")) }
    }

    @Test fun `kuaikan filters paid chapters and pages`() {
        val chapters = """[{"id":1,"title":"第一話","locked":false,"need_vip":false,"is_pay_comic":false},""" +
            """{"id":2,"title":"付費","is_pay_comic":true},{"id":3,"title":"狀態未知"}]"""
        assertEquals(listOf("第一話"), KuaikanParser.chapters(chapters).map { it.name })
        val paid = """{"need_vip":true,"comicImages":[{"url":"https://img.kkmh.com/a.jpg"}]}"""
        assertThrows(IOException::class.java) { KuaikanParser.images(paid) }
        assertFalse(KuaikanParser.isImageUrl("https://kkmh.com.evil.test/a.jpg"))
    }
}
