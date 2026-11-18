package eu.kanade.tachiyomi.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class DownloadedViewModel(
    mangaRepository: MangaRepository,
    chapterRepository: ChapterRepository,
    cache: DownloadCache,
    private val downloadManager: DownloadManager,
    private val sourceManager: SourceManager,
) : ViewModel() {
    val state = combine(
        mangaRepository.getMangaWithChaptersAsFlow(),
        cache.changes.onStart {
            cache.awaitInitialized()
        },
    ) { manga, _ ->
        val candidates = manga.filter { downloadManager.getDownloadCount(it) > 0 }
        val chaptersByManga = if (candidates.isEmpty()) {
            emptyMap()
        } else {
            chapterRepository.getChaptersByMangaIds(candidates.map { it.id })
        }
        candidates.mapNotNull { item ->
            val chapters = chaptersByManga[item.id].orEmpty()
            val downloaded = downloadManager.getDownloadedChapterIds(chapters, item)
            val selected = chapters.filter { it.id in downloaded }.sortedBy { it.sourceOrder }
            if (selected.isEmpty()) null else DownloadedManga(item, selected)
        }.sortedBy { it.manga.title.lowercase() }
    }.flowOn(Dispatchers.IO)
        .stateIn<List<DownloadedManga>?>(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    fun delete(groups: List<DownloadedManga>) {
        viewModelScope.launch {
            groups.forEach { (manga, chapters) ->
                downloadManager.deleteChaptersManually(chapters, manga, sourceManager.getOrStub(manga.source))
            }
        }
    }
}

data class DownloadedManga(val manga: Manga, val chapters: List<Chapter>)
