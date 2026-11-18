package local.hengdu.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HengduLegacyOfflineCopyTest {
    @TempDir lateinit var temp: File
    private val book = "https://www.manhuaren.com/manhua-example/"
    private val chapter = "https://www.manhuaren.com/m123/"

    @Test
    fun `copies verified pages without changing the source and rejects wrong identity`() {
        val source = File(temp, "stage").apply { mkdir() }
        val first = File(source, "00000001.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = File(source, "00000002.png").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
        val root = File(temp, "copies")
        HengduLegacyOfflineCopy.publish(root, source, book, chapter) shouldBe true
        HengduLegacyOfflineCopy.publish(root, source, book, chapter) shouldBe true
        root.listFiles()!!.single().listFiles()!!.size shouldBe 1
        val result = requireNotNull(HengduLegacyOfflineCopy.resolve(root, book, chapter))
        result.listFiles()!!.sortedBy { it.name }.map { it.readBytes().toList() } shouldBe
            listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5, 6, 7))
        first.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
        second.readBytes().toList() shouldBe listOf<Byte>(4, 5, 6, 7)
        HengduLegacyOfflineCopy.resolve(root, book, "https://www.manhuaren.com/m124/") shouldBe null
        HengduLegacyOfflineCopy.resolve(root, "https://other.example/manhua-example/", chapter) shouldBe null
    }

    @Test
    fun `ignores partial and corrupted copies`() {
        val source = File(temp, "stage").apply { mkdir() }
        File(source, "00000001.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val root = File(temp, "copies")
        HengduLegacyOfflineCopy.publish(root, source, book, chapter)
        val pages = requireNotNull(HengduLegacyOfflineCopy.resolve(root, book, chapter))
        File(pages, "00000001.jpg").writeBytes(byteArrayOf(3, 2, 1))
        HengduLegacyOfflineCopy.resolve(root, book, chapter) shouldBe null
        File(pages.parentFile, "manifest.properties").delete()
        HengduLegacyOfflineCopy.resolve(root, book, chapter) shouldBe null
    }
}
