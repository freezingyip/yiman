package eu.kanade.tachiyomi.ui.download

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.SelectAll
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

data object DownloadedTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(
            index = 1u,
            title = stringResource(MR.strings.label_downloaded),
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Download),
        )

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(DownloadQueueScreen)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = metroViewModel<DownloadedViewModel>()
        val downloads by viewModel.state.collectAsStateWithLifecycle()
        var pendingDelete by remember { mutableStateOf<List<DownloadedManga>?>(null) }
        var selectionMode by rememberSaveable { mutableStateOf(false) }
        var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        val allIds = downloads.orEmpty().flatMap { it.chapters }.map { it.id }.toSet()
        val allSelected = allIds.isNotEmpty() && selectedIds.containsAll(allIds)
        fun clearSelection() {
            selectionMode = false
            selectedIds = emptySet()
        }
        fun toggleChapter(id: Long) {
            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        }
        LaunchedEffect(downloads) {
            if (downloads != null) selectedIds = selectedIds.intersect(allIds)
        }
        BackHandler(enabled = selectionMode) { clearSelection() }
        var sortExpanded by remember { mutableStateOf(false) }
        var reverseManga by rememberSaveable { mutableStateOf(false) }
        var reverseChapters by rememberSaveable { mutableStateOf(false) }
        val sortedDownloads = downloads.orEmpty().let { if (reverseManga) it.asReversed() else it }

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Text(
                            if (selectionMode) {
                                "${selectedIds.size} ${stringResource(
                                    MR.strings.selected,
                                )}"
                            } else {
                                stringResource(MR.strings.label_downloaded)
                            },
                        )
                    },
                    isActionMode = selectionMode,
                    onCancelActionMode = ::clearSelection,
                    actions = {
                        if (selectionMode) {
                            TextButton(onClick = { selectedIds = if (allSelected) emptySet() else allIds }) {
                                Text(
                                    stringResource(
                                        if (allSelected) {
                                            MR.strings.downloaded_deselect_all
                                        } else {
                                            MR.strings.action_select_all
                                        },
                                    ),
                                )
                            }
                            IconButton(
                                enabled = selectedIds.isNotEmpty(),
                                onClick = {
                                    pendingDelete = downloads.orEmpty().mapNotNull { group ->
                                        val chapters = group.chapters.filter { it.id in selectedIds }
                                        if (chapters.isEmpty()) null else group.copy(chapters = chapters)
                                    }
                                },
                            ) {
                                Icon(
                                    MaterialSymbols.Rounded.Delete,
                                    contentDescription = stringResource(MR.strings.action_delete),
                                )
                            }
                        } else {
                            IconButton(enabled = allIds.isNotEmpty(), onClick = { selectionMode = true }) {
                                Icon(
                                    MaterialSymbols.Rounded.SelectAll,
                                    contentDescription = stringResource(MR.strings.downloaded_select),
                                )
                            }
                            IconButton(onClick = { sortExpanded = true }) {
                                Icon(
                                    MaterialSymbols.AutoMirroredRounded.Sort,
                                    contentDescription = stringResource(MR.strings.action_sort),
                                )
                            }
                            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                                DropdownMenuItem(
                                    text = {
                                        val direction = if (reverseManga) {
                                            MR.strings.action_desc
                                        } else {
                                            MR.strings.action_asc
                                        }
                                        Text(
                                            "${stringResource(
                                                MR.strings.action_sort_alpha,
                                            )}: ${stringResource(direction)}",
                                        )
                                    },
                                    onClick = {
                                        reverseManga = !reverseManga
                                        sortExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        val direction = if (reverseChapters) {
                                            MR.strings.downloaded_sort_reverse
                                        } else {
                                            MR.strings.downloaded_sort_original
                                        }
                                        val orderLabel = stringResource(MR.strings.downloaded_sort_chapter_order)
                                        Text("$orderLabel: ${stringResource(direction)}")
                                    },
                                    onClick = {
                                        reverseChapters = !reverseChapters
                                        sortExpanded = false
                                    },
                                )
                            }
                            TextButton(onClick = { navigator.push(DownloadQueueScreen) }) {
                                Text(stringResource(MR.strings.label_download_queue))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (downloads == null) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (downloads?.isEmpty() == true) {
                EmptyScreen(stringResource(MR.strings.information_no_downloads), modifier = Modifier.padding(padding))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    sortedDownloads.forEach { group ->
                        item(key = "manga-${group.manga.id}") {
                            val groupIds = group.chapters.map { it.id }.toSet()
                            val selectedCount = groupIds.count { it in selectedIds }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (selectionMode) {
                                        selectedIds =
                                            if (selectedCount ==
                                                groupIds.size
                                            ) {
                                                selectedIds - groupIds
                                            } else {
                                                selectedIds + groupIds
                                            }
                                    } else {
                                        navigator.push(MangaScreen(group.manga.id))
                                    }
                                }.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (selectionMode) {
                                    TriStateCheckbox(
                                        state = when (selectedCount) {
                                            0 -> ToggleableState.Off
                                            groupIds.size -> ToggleableState.On
                                            else -> ToggleableState.Indeterminate
                                        },
                                        onClick = null,
                                    )
                                }
                                Column {
                                    Text(group.manga.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${group.chapters.size} ${stringResource(MR.strings.downloaded_chapters)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        (if (reverseChapters) group.chapters.asReversed() else group.chapters).forEach { chapter ->
                            item(key = "chapter-${chapter.id}") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().combinedClickable(
                                        onClick = {
                                            if (selectionMode) {
                                                toggleChapter(chapter.id)
                                            } else {
                                                context.startActivity(
                                                    ReaderActivity.newIntent(context, group.manga.id, chapter.id),
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            if (!selectionMode) selectionMode = true
                                            toggleChapter(chapter.id)
                                        },
                                    ).padding(start = 24.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    if (selectionMode) {
                                        Checkbox(checked = chapter.id in selectedIds, onCheckedChange = null)
                                    }
                                    Text(
                                        chapter.name,
                                        modifier = Modifier.weight(1f),
                                        color = if (chapter.read) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (!selectionMode) {
                                        IconButton(onClick = {
                                            pendingDelete =
                                                listOf(group.copy(chapters = listOf(chapter)))
                                        }) {
                                            Icon(
                                                MaterialSymbols.Rounded.Delete,
                                                contentDescription = stringResource(MR.strings.action_delete),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "divider-${group.manga.id}") { HorizontalDivider() }
                    }
                }
            }
        }
        pendingDelete?.let { groups ->
            val count = groups.sumOf { it.chapters.size }
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(MR.strings.action_delete)) },
                text = {
                    Text(
                        if (count == 1) {
                            "${groups.first().manga.title} — ${groups.first().chapters.first().name}"
                        } else {
                            stringResource(MR.strings.downloaded_delete_selected, count)
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.delete(groups)
                        pendingDelete = null
                        clearSelection()
                    }) { Text(stringResource(MR.strings.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }
}
