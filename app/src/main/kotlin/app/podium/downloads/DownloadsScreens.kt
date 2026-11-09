package app.podium.downloads

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.player.api.PlaybackController
import app.podium.player.api.TrackCatalog

private sealed interface DownloadsRow {
    val key: String
    data object CoverFlow : DownloadsRow { override val key = "cover-flow" }
    data object PlayAll : DownloadsRow { override val key = "play-all" }
    data object Shuffle : DownloadsRow { override val key = "shuffle" }
    data class SmartToggle(val enabled: Boolean) : DownloadsRow { override val key = "smart-toggle" }
    data class RetryFailed(val count: Int) : DownloadsRow { override val key = "retry-failed" }
    data object FindSongs : DownloadsRow { override val key = "find-songs" }
    data class Section(val title: String) : DownloadsRow { override val key = "sec:$title" }
    data class Item(val entry: DownloadEntry) : DownloadsRow { override val key = "item:${entry.track.id.value}" }
    data object RemoveAll : DownloadsRow { override val key = "remove-all" }
}

@Composable
fun DownloadsScreen(
    downloads: OfflineDownloadManager,
    catalog: TrackCatalog,
    controller: PlaybackController,
    onOpenNowPlaying: () -> Unit,
    onFindSongs: () -> Unit,
    onCoverFlow: () -> Unit = {},
) {
    val allEntries by downloads.allEntries.collectAsStateWithLifecycle()
    val completed by downloads.completedTracks.collectAsStateWithLifecycle()
    val smartEnabled by downloads.smartDownloadsEnabled.collectAsStateWithLifecycle()
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val playingId = snapshot.item?.trackId
    val overlay = LocalOverlayHost.current
    val haptics = rememberPodiumHaptics()

    val failedCount = remember(allEntries) { allEntries.count { it.status == DownloadStatus.FAILED } }
    val activeEntries = remember(allEntries) {
        allEntries.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.FAILED }
    }
    val completedEntries = remember(allEntries) {
        allEntries.filter { it.status == DownloadStatus.COMPLETED }
    }

    val rows = remember(allEntries, completed.size, smartEnabled, failedCount) {
        buildList {
            add(DownloadsRow.CoverFlow)
            if (completed.isNotEmpty()) {
                add(DownloadsRow.PlayAll)
                if (completed.size > 1) add(DownloadsRow.Shuffle)
            }
            add(DownloadsRow.SmartToggle(smartEnabled))
            if (failedCount > 0) {
                add(DownloadsRow.RetryFailed(failedCount))
            }
            if (allEntries.isEmpty()) {
                add(DownloadsRow.Section("No offline songs yet. Download songs, albums, or playlists from Online, or turn on Smart downloads."))
                add(DownloadsRow.FindSongs)
            } else {
                if (activeEntries.isNotEmpty()) {
                    add(DownloadsRow.Section("In progress"))
                    activeEntries.forEach { add(DownloadsRow.Item(it)) }
                }
                if (completedEntries.isNotEmpty()) {
                    val totalMb = completedEntries.sumOf { it.bytes } / (1024.0 * 1024.0)
                    val sizeLabel = if (totalMb >= 1.0) " · %.1f MB".format(totalMb) else ""
                    add(DownloadsRow.Section("${completedEntries.size} downloaded$sizeLabel"))
                    completedEntries.forEach { add(DownloadsRow.Item(it)) }
                }
                add(DownloadsRow.RemoveAll)
            }
        }
    }

    val focus = rememberFocusListState("downloads")
    val activate: (Int) -> Unit = { index ->
        when (val row = rows.getOrNull(index)) {
            DownloadsRow.CoverFlow -> onCoverFlow()
            DownloadsRow.PlayAll -> if (completed.isNotEmpty()) {
                catalog.remember(completed)
                controller.playContext(completed.map { it.id }, 0, "Downloads")
                onOpenNowPlaying()
            }
            DownloadsRow.Shuffle -> if (completed.isNotEmpty()) {
                catalog.remember(completed)
                controller.playContext(completed.map { it.id }, completed.indices.random(), "Downloads", shuffle = true)
                onOpenNowPlaying()
            }
            is DownloadsRow.SmartToggle -> {
                haptics.toggle(on = !smartEnabled)
                downloads.setSmartDownloads(!smartEnabled)
            }
            is DownloadsRow.RetryFailed -> {
                haptics.confirm()
                downloads.retryFailed()
            }
            DownloadsRow.FindSongs -> onFindSongs()
            is DownloadsRow.Item -> {
                val entry = row.entry
                when (entry.status) {
                    DownloadStatus.COMPLETED -> {
                        val playIdx = completed.indexOfFirst { it.id == entry.track.id }.coerceAtLeast(0)
                        catalog.remember(completed)
                        controller.playContext(completed.map { it.id }, playIdx, "Downloads")
                        onOpenNowPlaying()
                    }
                    DownloadStatus.FAILED -> {
                        haptics.confirm()
                        downloads.download(entry.track, smart = entry.smart)
                    }
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        overlay.show(
                            MenuSpec(
                                entry.track.title,
                                listOf(
                                    MenuAction("Cancel download") {
                                        downloads.remove(entry.track.id)
                                    },
                                ),
                            ),
                        )
                    }
                }
            }
            DownloadsRow.RemoveAll -> {
                overlay.show(
                    MenuSpec(
                        "Remove all downloads?",
                        listOf(
                            MenuAction("Remove all") {
                                downloads.removeAll()
                            },
                        ),
                    ),
                )
            }
            else -> Unit
        }
    }

    val longPress: (Int) -> Unit = { index ->
        val row = rows.getOrNull(index)
        if (row is DownloadsRow.Item) {
            val entry = row.entry
            val track = entry.track
            overlay.show(
                MenuSpec(
                    track.title,
                    buildList {
                        if (entry.status == DownloadStatus.COMPLETED) {
                            add(
                                MenuAction("Play next") {
                                    catalog.remember(listOf(track))
                                    controller.playNext(listOf(track.id))
                                },
                            )
                            add(
                                MenuAction("Add to Up Next") {
                                    catalog.remember(listOf(track))
                                    controller.addToQueue(listOf(track.id))
                                },
                            )
                        }
                        if (entry.status == DownloadStatus.FAILED) {
                            add(
                                MenuAction("Retry download") {
                                    downloads.download(track, smart = entry.smart)
                                },
                            )
                        }
                        add(
                            MenuAction("Remove download") {
                                downloads.remove(track.id)
                            },
                        )
                    },
                ),
            )
        }
    }

    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)

    val covers = remember(completed) { completed.mapNotNull { it.artwork?.uri }.distinct().take(10) }

    FocusList(
        items = rows,
        state = focus,
        key = { it.key },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        focusable = { it !is DownloadsRow.Section },
        preview = { row ->
            when (row) {
                is DownloadsRow.Item -> row.entry.track.artwork?.uri?.let { MenuPreview.Artwork(listOf(it)) }
                    ?: MenuPreview.Instrument
                else -> if (covers.isNotEmpty()) MenuPreview.Carousel(covers) else MenuPreview.Instrument
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            DownloadsRow.CoverFlow -> MenuRow("Cover Flow", focused, value = completed.size.takeIf { it > 0 }?.toString())
            DownloadsRow.PlayAll -> MenuRow("Play", focused, leading = PodiumSymbol.Play, showChevron = false)
            DownloadsRow.Shuffle -> MenuRow("Shuffle", focused, leading = PodiumSymbol.Shuffle, showChevron = false)
            is DownloadsRow.SmartToggle -> MenuRow(
                label = "Smart downloads",
                focused = focused,
                value = if (row.enabled) "On" else "Off",
                showChevron = false,
            )
            is DownloadsRow.RetryFailed -> MenuRow(
                label = "Retry failed downloads",
                focused = focused,
                value = row.count.toString(),
                showChevron = false,
            )
            DownloadsRow.FindSongs -> MenuRow("Find songs online", focused, leading = PodiumSymbol.Search)
            is DownloadsRow.Section -> PodiumText(
                text = row.title,
                style = PodiumTheme.type.caption,
                color = PodiumTheme.colors.labelSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = 6.dp)
                    .semantics { heading() },
            )
            is DownloadsRow.Item -> {
                val entry = row.entry
                val track = entry.track
                val subtitle = when (entry.status) {
                    DownloadStatus.DOWNLOADING -> "${track.artistDisplay} · Downloading ${entry.progress}%"
                    DownloadStatus.QUEUED -> "${track.artistDisplay} · Queued"
                    DownloadStatus.FAILED -> "${track.artistDisplay} · Failed (press to retry)"
                    DownloadStatus.COMPLETED -> listOfNotNull(track.artistDisplay.takeIf { it.isNotBlank() }, track.album?.title).joinToString(" · ")
                }
                val trailing = when (entry.status) {
                    DownloadStatus.DOWNLOADING -> "${entry.progress}%"
                    DownloadStatus.QUEUED -> "…"
                    DownloadStatus.FAILED -> "!"
                    DownloadStatus.COMPLETED -> formatDuration(track.durationMs)
                }
                TrackRow(
                    title = track.title,
                    subtitle = subtitle,
                    focused = focused,
                    artworkUri = track.artwork?.uri,
                    trailing = trailing,
                    note = if (entry.smart && entry.status == DownloadStatus.COMPLETED) "Smart download" else null,
                    active = track.id == playingId,
                )
            }
            DownloadsRow.RemoveAll -> MenuRow("Remove all downloads", focused, showChevron = false)
        }
    }
}

@Composable
fun DownloadsCoverFlowScreen(
    downloads: OfflineDownloadManager,
    catalog: TrackCatalog,
    controller: PlaybackController,
    onOpenNowPlaying: () -> Unit,
) {
    val completed by downloads.completedTracks.collectAsStateWithLifecycle()
    val overlay = LocalOverlayHost.current
    app.podium.core.designsystem.component.CoverFlowStage(
        items = completed,
        stateKey = "downloads-coverflow",
        keyOf = { it.id.value },
        titleOf = { it.title },
        subtitleOf = { listOfNotNull(it.artistDisplay.takeIf { a -> a.isNotBlank() }, it.album?.title).joinToString(" · ") },
        artworkOf = { it.artwork?.uri },
        onActivate = { _, index ->
            if (completed.isNotEmpty()) {
                catalog.remember(completed)
                controller.playContext(completed.map { it.id }, index.coerceIn(0, completed.lastIndex), "Downloads")
                onOpenNowPlaying()
            }
        },
        onLongPress = { track, index ->
            overlay.show(
                MenuSpec(
                    track.title,
                    listOf(
                        MenuAction("Play") {
                            catalog.remember(completed)
                            controller.playContext(completed.map { it.id }, index.coerceIn(0, completed.lastIndex), "Downloads")
                            onOpenNowPlaying()
                        },
                        MenuAction("Play next") {
                            catalog.remember(listOf(track))
                            controller.playNext(listOf(track.id))
                        },
                        MenuAction("Add to Up Next") {
                            catalog.remember(listOf(track))
                            controller.addToQueue(listOf(track.id))
                        },
                        MenuAction("Remove download") {
                            downloads.remove(track.id)
                        },
                    ),
                ),
            )
        },
        emptySymbol = PodiumSymbol.Album,
        emptyTitle = "No downloads yet",
        emptyMessage = "Download songs, albums, or playlists from Online, and their covers appear here.",
    )
}

private fun formatDuration(durationMs: Long?): String? {
    val ms = durationMs ?: return null
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
