package local.hengdu.reader

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History

internal object HengduResume {
    fun lastRead(sourceId: Long, available: List<Chapter>, history: List<History>): Chapter? {
        if (sourceId !in
            setOf(
                HengduLibrarySnapshot.MANHUAREN_SOURCE_ID,
                HengduLibrarySnapshot.BAOZI_SOURCE_ID,
                HengduLibrarySnapshot.MANHUAGUI_SOURCE_ID,
            )
        ) {
            return null
        }
        val chapters = available.associateBy { it.id }
        val mostRecent = history.asSequence()
            .filter { it.readAt != null }
            .maxByOrNull { it.readAt!! }
        if (mostRecent != null) return chapters[mostRecent.chapterId]?.takeUnless { it.read }
        // Some legacy snapshots have a page position but no saved history. Only
        // infer the chapter when the position identifies it unambiguously.
        return available.singleOrNull { it.lastPageRead > 0 && !it.read }
    }
}
