package local.hengdu.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HengduLibrarySnapshotTest {
    @Test
    fun `invalid chapter defers the whole catalog but duplicate chapter does not`() {
        fun snapshot(entries: String) = HengduLibrarySnapshot.parse(
            """{
              "library":{"https://www.manhuagui.com/comic/123/":{
                "url":"https://www.manhuagui.com/comic/123/", "source":"漫畫櫃", "title":"作品"
              }},
              "catalogs":{"https://www.manhuagui.com/comic/123/":[$entries]},
              "reading":{}, "saved":{}, "history":{}
            }
            """.trimIndent(),
        ).books.single()
        val first = """{"url":"https://www.manhuagui.com/comic/123/1.html","title":"第一話"}"""
        val duplicate = """{"url":"https://www.manhuagui.com/comic/123/1.html","title":""}"""
        val missingTitle = """{"url":"https://www.manhuagui.com/comic/123/2.html","title":""}"""
        val otherBook = """{"url":"https://www.manhuagui.com/comic/999/2.html","title":"錯誤"}"""
        val valid = snapshot("$first,$duplicate")
        valid.catalogValid shouldBe true
        valid.chapters.size shouldBe 1
        HengduLibraryImportPlan.decide(false, false, valid.catalogValid && valid.chapters.isNotEmpty()) shouldBe
            HengduLibraryImportPlan.Action.Import
        listOf(missingTitle, otherBook, "null").forEach { invalid ->
            val incomplete = snapshot("$first,$invalid")
            incomplete.chapters.size shouldBe 1
            incomplete.catalogValid shouldBe false
            HengduLibraryImportPlan.decide(
                false,
                false,
                incomplete.catalogValid && incomplete.chapters.isNotEmpty(),
            ) shouldBe
                HengduLibraryImportPlan.Action.AwaitCatalog
        }
    }

    @Test
    fun `imports manhuagui chapter progress only for the matching book`() {
        val snapshot = HengduLibrarySnapshot.parse(
            """{
              "library": {
                "https://www.manhuagui.com/comic/123/": {
                  "url":"https://www.manhuagui.com/comic/123/", "source":"漫畫櫃", "title":"作品",
                  "cover":"/proxy?url=https%3A%2F%2Fi.hamreus.com%2Fcover.jpg"
                },
                "https://www.manhuagui.com/comic/456/": {
                  "url":"https://www.manhuagui.com/comic/456/", "source":"漫畫櫃", "title":"另一作品",
                  "cover":"https://hamreus.com.evil.example/cover.jpg"
                },
                "https://www.manhuagui.com.evil.example/comic/123/": {
                  "url":"https://www.manhuagui.com.evil.example/comic/123/", "source":"漫畫櫃", "title":"fake"
                }
              },
              "catalogs": {
                "https://www.manhuagui.com/comic/123/": [
                  {"url":"https://www.manhuagui.com/comic/123/1.html", "title":"第一話"},
                  {"url":"https://www.manhuagui.com/comic/123/2.html", "title":"第二話"},
                  {"url":"https://www.manhuagui.com/comic/456/3.html", "title":"另一作品的章節"},
                  {"url":"https://www.manhuagui.com.evil.example/comic/123/4.html", "title":"不可信"}
                ],
                "https://www.manhuagui.com/comic/456/": [
                  {"url":"https://www.manhuagui.com/comic/456/3.html", "title":"第一話"}
                ]
              },
              "reading": {"https://www.manhuagui.com/comic/123/1.html":{"page":4,"complete":true}},
              "saved": {},
              "history": {
                "https://www.manhuagui.com/comic/123/": {
                  "chapterUrl":"https://www.manhuagui.com/comic/123/2.html", "page":7, "updated":2000
                },
                "https://www.manhuagui.com/comic/456/": {
                  "chapterUrl":"https://www.manhuagui.com/comic/123/2.html", "page":8, "updated":3000
                }
              }
            }
            """.trimIndent(),
        )
        snapshot.skipped shouldBe 1
        val first = snapshot.books[0]
        first.sourceId shouldBe ManhuaguiSource.ID
        first.cover shouldBe "https://i.hamreus.com/cover.jpg"
        first.chapters.map { it.url } shouldBe
            listOf("https://www.manhuagui.com/comic/123/1.html", "https://www.manhuagui.com/comic/123/2.html")
        first.chapters.map { it.page } shouldBe listOf(4, 7)
        first.chapters.map { it.read } shouldBe listOf(true, false)
        (first.chapters[0].order > first.chapters[1].order) shouldBe true
        first.history shouldBe HengduLibrarySnapshot.History("https://www.manhuagui.com/comic/123/2.html", 7, 2000)
        snapshot.books[1].cover shouldBe null
        snapshot.books[1].history shouldBe null
        snapshot.books[1].chapters.single().page shouldBe 0
    }

    @Test
    fun `imports baozi favorites with validated chapters and legacy reading state`() {
        val snapshot = HengduLibrarySnapshot.parse(
            """{
              "library": {
                "https://www.baozimh.com/comic/example": {
                  "url":"https://www.baozimh.com/comic/example", "source":"包子漫畫", "title":"漫畫",
                  "cover":"/proxy?url=https%3A%2F%2Fimg.baozicdn.com%2Fcover.jpg"
                },
                "https://www.twmanga.com/comic/old": {
                  "url":"https://www.twmanga.com/comic/old", "title":"舊版收藏"
                },
                "https://baozimh.com.evil.example/comic/fake": {
                  "url":"https://baozimh.com.evil.example/comic/fake", "source":"包子漫畫", "title":"fake"
                }
              },
              "catalogs": {"https://www.baozimh.com/comic/example": [
                {"url":"https://www.baozimh.com/comic/chapter/example/0_1.html", "title":"第1話"},
                {"url":"https://www.baozimh.com/comic/chapter/example/0_2.html", "title":"第2話"},
                {"url":"https://evil.test/comic/chapter/example/0_3.html", "title":"不可信"}
              ]},
              "reading": {"https://www.baozimh.com/comic/chapter/example/0_1.html":{"page":4,"complete":true}},
              "saved": {"https://www.baozimh.com/comic/chapter/example/0_2.html":2},
              "history": {"https://www.baozimh.com/comic/example": {
                "chapterUrl":"https://www.baozimh.com/comic/chapter/example/0_2.html", "page":2, "updated":2000
              }}
            }
            """.trimIndent(),
        )
        snapshot.skipped shouldBe 1
        snapshot.books.size shouldBe 2
        val book = snapshot.books.first()
        book.sourceId shouldBe HengduLibrarySnapshot.BAOZI_SOURCE_ID
        book.cover shouldBe "https://img.baozicdn.com/cover.jpg"
        book.chapters.map { it.page } shouldBe listOf(4, 2)
        book.chapters.map { it.read } shouldBe listOf(true, false)
        book.history shouldBe
            HengduLibrarySnapshot.History("https://www.baozimh.com/comic/chapter/example/0_2.html", 2, 2000)
        (book.chapters[0].order > book.chapters[1].order) shouldBe true
    }

    @Test
    fun `imports favorites and chapter progress while skipping unsupported sources`() {
        val snapshot = HengduLibrarySnapshot.parse(
            """{
              "library": {
                "https://www.manhuaren.com/manhua-blue-lock/": {
                  "url": "https://www.manhuaren.com/manhua-blue-lock/", "source": "漫畫人", "title": "BLUE LOCK",
                  "updated": 1000, "cover": "/proxy?url=https%3A%2F%2Fa.cdndm5.com%2Fcover.jpg"
                },
                "https://other.example/book": {"url": "https://other.example/book", "source": "其他", "title": "Other"}
              },
              "catalogs": {"https://www.manhuaren.com/manhua-blue-lock/": [
                {"url": "https://www.manhuaren.com/m1/", "title": "第1話"},
                {"url": "https://www.manhuaren.com/m2/", "title": "第2話"},
                {"url": "https://www.manhuaren.com/m2/", "title": "重複"},
                {"url": "https://evil.example/m3/", "title": "不明來源"}
              ]},
              "reading": {"https://www.manhuaren.com/m1/": {"page": 8, "complete": true}},
              "saved": {"https://www.manhuaren.com/m2/": 3},
              "history": {"https://www.manhuaren.com/manhua-blue-lock/": {
                "chapterUrl": "https://www.manhuaren.com/m2/", "page": 3, "updated": 2000
              }}
            }
            """.trimIndent(),
        )
        snapshot.skipped shouldBe 1
        val book = snapshot.books.single()
        book.title shouldBe "BLUE LOCK"
        book.cover shouldBe "https://a.cdndm5.com/cover.jpg"
        book.added shouldBe 1000
        book.chapters.map { it.url } shouldBe listOf("https://www.manhuaren.com/m1/", "https://www.manhuaren.com/m2/")
        book.chapters.map { it.page } shouldBe listOf(8, 3)
        book.chapters.map { it.read } shouldBe listOf(true, false)
        book.history shouldBe HengduLibrarySnapshot.History("https://www.manhuaren.com/m2/", 3, 2000)
        (book.chapters[0].order > book.chapters[1].order) shouldBe true
    }

    @Test
    fun `rejects incomplete snapshot instead of interpreting it as an empty library`() {
        assertThrows<IllegalStateException> { HengduLibrarySnapshot.parse("""{"library":{}}""") }
    }

    @Test
    fun `rejects deceptive urls and untrusted covers`() {
        val snapshot = HengduLibrarySnapshot.parse(
            """{
              "library": {
                "https://www.manhuaren.com/manhua-ok/": {
                  "url":"https://www.manhuaren.com/manhua-ok/", "source":"漫畫人", "title":"ok",
                  "cover":"/proxy?url=https%3A%2F%2Fcdndm5.com.evil.example%2Fcover.jpg"
                },
                "https://www.manhuaren.com.evil.example/manhua-bad/": {
                  "url":"https://www.manhuaren.com.evil.example/manhua-bad/", "source":"漫畫人", "title":"bad"
                }
              }, "catalogs":{}, "reading":{}, "saved":{}, "history":{}
            }
            """.trimIndent(),
        )
        snapshot.books.single().cover shouldBe null
        snapshot.skipped shouldBe 1
    }

    @Test
    fun `last history page is used only when chapter progress is absent`() {
        val snapshot = HengduLibrarySnapshot.parse(
            """{
              "library":{"https://www.manhuaren.com/manhua-ok/":{
                "url":"https://www.manhuaren.com/manhua-ok/", "source":"漫畫人", "title":"ok"
              }},
              "catalogs":{"https://www.manhuaren.com/manhua-ok/":[
                {"url":"https://www.manhuaren.com/m1/","title":"1"},
                {"url":"https://www.manhuaren.com/m2/","title":"2"}
              ]},
              "reading":{}, "saved":{},
              "history":{"https://www.manhuaren.com/manhua-ok/":{
                "chapterUrl":"https://www.manhuaren.com/m2/","page":9,"updated":2000
              }}
            }
            """.trimIndent(),
        )
        snapshot.books.single().chapters.map { it.page } shouldBe listOf(0, 9)
    }
}
