package local.hengdu.reader

import eu.kanade.tachiyomi.util.chapter.getChapterToResume
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import kotlin.time.Instant

class HengduResumeTest {
    @Test
    fun `resumes chapter one after reading chapter thirty even at the first page`() {
        val chapter1 = Chapter.create().copy(id = 110, chapterNumber = 1.0)
        val chapter2 = Chapter.create().copy(id = 101, chapterNumber = 2.0)
        val chapter30 = Chapter.create().copy(id = 30, chapterNumber = 30.0, lastPageRead = 4)
        val history = listOf(
            History.create().copy(chapterId = chapter1.id, readAt = Instant.fromEpochMilliseconds(3000)),
            History.create().copy(chapterId = chapter30.id, readAt = Instant.fromEpochMilliseconds(1000)),
        )
        listOf(chapter30, chapter2, chapter1)
            .getChapterToResume(history, sortDescending = false, inferLegacyProgress = false) shouldBe chapter1
        val resumed = chapter1.copy(read = true, lastPageRead = 4)
        listOf(chapter30, chapter2, resumed)
            .getChapterToResume(history, sortDescending = false, inferLegacyProgress = false) shouldBe resumed
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
        val chapterList = listOf(first, third)
        chapterList.getChapterToResume(noHistory, sortDescending = false, inferLegacyProgress = true) shouldBe third
        chapterList.getChapterToResume(noHistory, sortDescending = false, inferLegacyProgress = false) shouldBe first
        listOf(first, third, otherStarted)
            .getChapterToResume(noHistory, sortDescending = false, inferLegacyProgress = true) shouldBe first
    }

    @Test
    fun `a filtered latest chapter defers to native next unread selection`() {
        val earlier = Chapter.create().copy(id = 1, lastPageRead = 2)
        val completed = Chapter.create().copy(id = 2, lastPageRead = 9, read = true)
        val unstarted = Chapter.create().copy(id = 3)
        val latest = listOf(
            History.create().copy(chapterId = earlier.id, readAt = Instant.fromEpochMilliseconds(1000)),
            History.create().copy(chapterId = completed.id, readAt = Instant.fromEpochMilliseconds(2000)),
        )
        listOf(completed, earlier, unstarted)
            .getChapterToResume(latest, sortDescending = true, inferLegacyProgress = false) shouldBe completed
        listOf(earlier, unstarted)
            .getChapterToResume(latest, sortDescending = true, inferLegacyProgress = false) shouldBe unstarted
        listOf(completed).getChapterToResume(emptyList(), sortDescending = false, inferLegacyProgress = false) shouldBe
            null
    }
}
