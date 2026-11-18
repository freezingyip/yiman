package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga

internal fun List<Chapter>.getChapterToResume(
    manga: Manga,
    downloadedOnly: Boolean,
    downloadManager: DownloadManager,
    history: List<History>,
    inferLegacyProgress: Boolean,
): Chapter? = applyFilters(manga, downloadedOnly, downloadManager)
    .getChapterToResume(history, manga.sortDescending(), inferLegacyProgress)

internal fun List<ChapterList.Item>.getChapterToResume(
    manga: Manga,
    downloadedOnly: Boolean,
    history: List<History>,
    inferLegacyProgress: Boolean,
): Chapter? = applyFilters(manga, downloadedOnly)
    .map { it.chapter }
    .toList()
    .getChapterToResume(history, manga.sortDescending(), inferLegacyProgress)

internal fun List<Chapter>.getChapterToResume(
    history: List<History>,
    sortDescending: Boolean,
    inferLegacyProgress: Boolean,
): Chapter? {
    val latest = history.asSequence()
        .filter { it.readAt != null }
        .maxWithOrNull(compareBy(History::readAt).thenBy(History::id))
    if (latest != null) {
        find { it.id == latest.chapterId }?.let { return it }
    } else if (inferLegacyProgress) {
        // Some imported snapshots have a page position but no history.
        singleOrNull { it.lastPageRead > 0 && !it.read }?.let { return it }
    }
    return if (sortDescending) findLast { !it.read } else find { !it.read }
}
