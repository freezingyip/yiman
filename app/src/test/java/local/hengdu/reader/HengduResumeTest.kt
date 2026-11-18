package local.hengdu.reader

import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import kotlin.time.Instant

class HengduResumeTest {
    @Test
    fun `continues most recent available chapter even if earlier chapters remain unread`() {
        val chapter2 = Chapter.create().copy(id = 2)
        val chapter18 = Chapter.create().copy(id = 18, lastPageRead = 0)
        val history = listOf(History.create().copy(chapterId = 18, readAt = Instant.fromEpochMilliseconds(2000)))
        HengduResume.lastRead(HengduLibrarySnapshot.MANHUAREN_SOURCE_ID, listOf(chapter2, chapter18), history) shouldBe
            chapter18
        HengduResume.lastRead(HengduLibrarySnapshot.BAOZI_SOURCE_ID, listOf(chapter2, chapter18), history) shouldBe
            chapter18
        HengduResume.lastRead(HengduLibrarySnapshot.MANHUAGUI_SOURCE_ID, listOf(chapter2, chapter18), history) shouldBe
            chapter18
        HengduResume.lastRead(999, listOf(chapter2, chapter18), history) shouldBe null
        HengduResume.lastRead(HengduLibrarySnapshot.MANHUAREN_SOURCE_ID, listOf(chapter2), history) shouldBe null
    }

    @Test
    fun `unknown chapter numbers are not treated as duplicates`() {
        val chapter1 = Chapter.create().copy(id = 1, chapterNumber = -1.0)
        val chapter2 = Chapter.create().copy(id = 2, chapterNumber = -1.0)
        val sameNumber = Chapter.create().copy(id = 3, chapterNumber = 1.0)
        val sameNumberAgain = Chapter.create().copy(id = 4, chapterNumber = 1.0)
        val chapters = listOf(chapter1, chapter2, sameNumber, sameNumberAgain)
        chapters.removeDuplicates(chapter1, preserveUnnumbered = true).map { it.id } shouldBe listOf(1, 2, 3)
        chapters.removeDuplicates(chapter1).map { it.id } shouldBe listOf(1, 3)
    }

    @Test
    fun `resumes a uniquely started chapter if migrated history is absent`() {
        val first = Chapter.create().copy(id = 1)
        val third = Chapter.create().copy(id = 3, lastPageRead = 3)
        val otherStarted = Chapter.create().copy(id = 4, lastPageRead = 1)
        val noHistory = emptyList<History>()
        val source = HengduLibrarySnapshot.MANHUAREN_SOURCE_ID
        HengduResume.lastRead(source, listOf(first, third), noHistory) shouldBe third
        HengduResume.lastRead(source, listOf(first, third, otherStarted), noHistory) shouldBe null
        val newer = listOf(History.create().copy(chapterId = first.id, readAt = Instant.fromEpochMilliseconds(3000)))
        HengduResume.lastRead(source, listOf(first, third), newer) shouldBe first
    }

    @Test
    fun `completed or filtered latest chapter defers to native next unread selection`() {
        val source = HengduLibrarySnapshot.MANHUAREN_SOURCE_ID
        val earlier = Chapter.create().copy(id = 1, lastPageRead = 2)
        val completed = Chapter.create().copy(id = 2, lastPageRead = 9, read = true)
        val latest = listOf(
            History.create().copy(chapterId = earlier.id, readAt = Instant.fromEpochMilliseconds(1000)),
            History.create().copy(chapterId = completed.id, readAt = Instant.fromEpochMilliseconds(2000)),
        )
        HengduResume.lastRead(source, listOf(earlier, completed), latest) shouldBe null
        HengduResume.lastRead(source, listOf(earlier), latest) shouldBe null
        HengduResume.lastRead(source, listOf(completed), emptyList()) shouldBe null
    }
}
