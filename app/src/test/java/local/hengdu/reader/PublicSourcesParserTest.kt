package local.hengdu.reader

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class PublicSourcesParserTest {
    @Test
    fun `boylove keeps only chapters explicitly marked free`() {
        val json = """{"list":[{"id":1,"title":"公開","isvip":0},""" +
            """{"id":2,"title":"付費","isvip":1},{"id":3,"title":"未知"}]}"""
        val chapters = BoyLoveParser.chapters("let data = JSON.parse(${JsonPrimitive(json)});")
        assertEquals(listOf("公開"), chapters.map { it.name })
        assertThrows(IOException::class.java) {
            BoyLoveParser.images(
                """<div class="reader-cartoon-chapter"><div class="reader-cartoon-image">""" +
                    """<img data-original="https://evil.test/a.jpg"></div></div>""",
            )
        }
    }

    @Test
    fun `18h preserves public image order and rejects other hosts`() {
        val html = """<script>Large_cgurl[0] = "https://imgstream2.com/1.jpg"; """ +
            """Large_cgurl[1] = "https://imgstream3.com/2.jpg";</script>"""
        assertEquals(listOf("https://imgstream2.com/1.jpg", "https://imgstream3.com/2.jpg"), C18hParser.images(html))
        assertThrows(IOException::class.java) { C18hParser.images(html.replace("imgstream3.com", "evil.test")) }
    }
}
