package app.podium

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.DetailHeader
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.KeyboardAction
import app.podium.core.designsystem.component.KeyboardAwayOnLeave
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalMiniature
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.PaperTextField
import app.podium.core.designsystem.component.SectionHeader
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.lyrics.LrcParser
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class EditSongRow {
    Header,
    TitleLabel,
    TitleField,
    ArtistLabel,
    ArtistField,
    AlbumLabel,
    AlbumField,
    LyricsLabel,
    LyricsField,
    PasteLyrics,
    ClearLyrics,
    ResetOriginal,
    Save,
}

@Composable
fun EditLocalSongScreen(
    graph: AppGraph,
    trackId: TrackId,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val haptics = rememberPodiumHaptics()
    val scope = rememberCoroutineScope()

    val localTracks by graph.localSource.tracksState.collectAsStateWithLifecycle()
    var loadedTrack by remember(trackId) {
        mutableStateOf<Track?>(
            localTracks?.firstOrNull { it.id == trackId } ?: graph.catalog.cached(trackId),
        )
    }
    LaunchedEffect(trackId, localTracks) {
        val fromLocal = localTracks?.firstOrNull { it.id == trackId }
        if (fromLocal != null) {
            loadedTrack = fromLocal
        } else if (loadedTrack == null) {
            loadedTrack = graph.libraryStore.track(trackId)
        }
    }

    val track = loadedTrack
    if (track == null) {
        MessageState(
            PodiumSymbol.Note,
            "Song not found",
            "This song is no longer in your local library.",
        )
        return
    }

    val existingEdit = remember(trackId) { graph.localSource.getEdit(trackId) }
    var title by rememberSaveable(trackId.value) { mutableStateOf(existingEdit?.title ?: track.title) }
    var artist by rememberSaveable(trackId.value) { mutableStateOf(existingEdit?.artist ?: track.artistDisplay) }
    var album by rememberSaveable(trackId.value) { mutableStateOf(existingEdit?.album ?: track.album?.title.orEmpty()) }
    var lyrics by rememberSaveable(trackId.value) { mutableStateOf(existingEdit?.lyrics.orEmpty()) }

    val titleFocus = remember { FocusRequester() }
    val artistFocus = remember { FocusRequester() }
    val albumFocus = remember { FocusRequester() }
    val lyricsFocus = remember { FocusRequester() }

    val miniature = LocalMiniature.current
    if (!miniature) {
        KeyboardAwayOnLeave()
    }

    val hasEdit = graph.localSource.hasEdit(trackId)
    val rows = remember(lyrics.isNotBlank(), hasEdit) {
        buildList {
            add(EditSongRow.Header)
            add(EditSongRow.TitleLabel)
            add(EditSongRow.TitleField)
            add(EditSongRow.ArtistLabel)
            add(EditSongRow.ArtistField)
            add(EditSongRow.AlbumLabel)
            add(EditSongRow.AlbumField)
            add(EditSongRow.LyricsLabel)
            add(EditSongRow.LyricsField)
            add(EditSongRow.PasteLyrics)
            if (lyrics.isNotBlank()) add(EditSongRow.ClearLyrics)
            if (hasEdit) add(EditSongRow.ResetOriginal)
            add(EditSongRow.Save)
        }
    }

    val valid = title.trim().isNotEmpty() && artist.trim().isNotEmpty()
    val focus = rememberFocusListState("edit-local-song:${trackId.value}")

    val syncNow: (Track?) -> Unit = { updated ->
        if (updated != null) {
            graph.catalog.remember(listOf(updated))
        }
        scope.launch(Dispatchers.IO) {
            val all = graph.localSource.tracksState.value
            if (all != null) {
                runCatching {
                    graph.libraryStore.sync(
                        graph.localSource.descriptor.id,
                        graph.localSource.descriptor.displayName,
                        all,
                        emptyMap(),
                    )
                }
            }
        }
    }

    val activate: (Int) -> Unit = { index ->
        when (rows.getOrNull(index)) {
            EditSongRow.TitleField -> titleFocus.requestFocus()
            EditSongRow.ArtistField -> artistFocus.requestFocus()
            EditSongRow.AlbumField -> albumFocus.requestFocus()
            EditSongRow.LyricsField -> lyricsFocus.requestFocus()
            EditSongRow.PasteLyrics -> {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                if (!clip.isNullOrBlank()) {
                    lyrics = clip
                    haptics.confirm()
                } else {
                    haptics.reject()
                }
            }
            EditSongRow.ClearLyrics -> {
                lyrics = ""
                haptics.confirm()
            }
            EditSongRow.ResetOriginal -> {
                val restored = graph.localSource.clearEdit(trackId)
                syncNow(restored)
                haptics.confirm()
                onDone()
            }
            EditSongRow.Save -> {
                if (valid) {
                    val updated = graph.localSource.saveEdit(
                        trackId = trackId,
                        title = title,
                        artist = artist,
                        album = album,
                        lyrics = lyrics,
                    )
                    syncNow(updated)
                    haptics.confirm()
                    onDone()
                } else {
                    haptics.reject()
                }
            }
            else -> Unit
        }
    }

    ListInputEffect(focus, onActivate = activate)
    LaunchedEffect(focus) {
        snapshotFlow { focus.focusMoves }.collect { if (it > 0) focusManager.clearFocus() }
    }

    val lyricsStatus = remember(lyrics) {
        val trimmed = lyrics.trim()
        if (trimmed.isEmpty()) {
            null
        } else {
            val synced = LrcParser.synced(trimmed)
            if (synced != null) {
                "Synced LRC · ${synced.lines.size} lines"
            } else {
                val plainCount = trimmed.lines().count { it.isNotBlank() }
                "Plain · $plainCount lines"
            }
        }
    }

    FocusList(
        items = rows,
        state = focus,
        key = { it.name },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        focusable = {
            it != EditSongRow.Header &&
                it != EditSongRow.TitleLabel &&
                it != EditSongRow.ArtistLabel &&
                it != EditSongRow.AlbumLabel &&
                it != EditSongRow.LyricsLabel
        },
        preview = { track.artwork?.uri?.let { MenuPreview.Artwork(listOf(it)) } ?: MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            EditSongRow.Header -> DetailHeader(
                artworkUri = track.artwork?.uri,
                title = title.ifBlank { track.title },
                subtitle = artist.ifBlank { track.artistDisplay },
                details = listOfNotNull(album.takeIf { it.isNotBlank() }, lyricsStatus),
            )
            EditSongRow.TitleLabel -> SectionHeader("Song title")
            EditSongRow.TitleField -> PaperTextField(
                value = title,
                onChange = { title = it },
                focus = titleFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Song title",
                description = "Song title",
                action = KeyboardAction.DONE,
            )
            EditSongRow.ArtistLabel -> SectionHeader("Artist")
            EditSongRow.ArtistField -> PaperTextField(
                value = artist,
                onChange = { artist = it },
                focus = artistFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Artist name",
                description = "Artist name",
                action = KeyboardAction.DONE,
            )
            EditSongRow.AlbumLabel -> SectionHeader("Album")
            EditSongRow.AlbumField -> PaperTextField(
                value = album,
                onChange = { album = it },
                focus = albumFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Album title",
                description = "Album title",
                action = KeyboardAction.DONE,
            )
            EditSongRow.LyricsLabel -> SectionHeader(lyricsStatus?.let { "Lyrics ($it)" } ?: "Lyrics")
            EditSongRow.LyricsField -> PaperTextField(
                value = lyrics,
                onChange = { lyrics = it },
                focus = lyricsFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Lyrics or [mm:ss.xx] LRC",
                description = "Lyrics",
                action = KeyboardAction.DONE,
            )
            EditSongRow.PasteLyrics -> MenuRow(
                label = "Paste lyrics from clipboard",
                focused = focused,
                leading = PodiumSymbol.Lyrics,
                showChevron = false,
            )
            EditSongRow.ClearLyrics -> MenuRow(
                label = "Clear lyrics",
                focused = focused,
                showChevron = false,
            )
            EditSongRow.ResetOriginal -> MenuRow(
                label = "Reset to original file info",
                focused = focused,
                showChevron = false,
            )
            EditSongRow.Save -> MenuRow(
                label = "Save changes",
                focused = focused,
                enabled = valid,
                leading = PodiumSymbol.Check,
                showChevron = false,
            )
        }
    }
}

private enum class EditAlbumRow {
    Header,
    TitleLabel,
    TitleField,
    ArtistLabel,
    ArtistField,
    Save,
}

@Composable
fun EditLocalAlbumScreen(
    graph: AppGraph,
    albumId: AlbumId,
    initialTitle: String,
    initialArtist: String,
    onDone: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val haptics = rememberPodiumHaptics()
    val scope = rememberCoroutineScope()

    var title by rememberSaveable(albumId.value) { mutableStateOf(initialTitle) }
    var artist by rememberSaveable(albumId.value) { mutableStateOf(initialArtist) }
    val titleFocus = remember { FocusRequester() }
    val artistFocus = remember { FocusRequester() }

    val miniature = LocalMiniature.current
    if (!miniature) {
        KeyboardAwayOnLeave()
    }

    val rows = remember { EditAlbumRow.entries }
    val valid = title.trim().isNotEmpty()
    val focus = rememberFocusListState("edit-local-album:${albumId.value}")

    val activate: (Int) -> Unit = { index ->
        when (rows.getOrNull(index)) {
            EditAlbumRow.TitleField -> titleFocus.requestFocus()
            EditAlbumRow.ArtistField -> artistFocus.requestFocus()
            EditAlbumRow.Save -> {
                if (valid) {
                    graph.localSource.renameAlbum(albumId, title, artist)
                    scope.launch(Dispatchers.IO) {
                        val all = graph.localSource.tracksState.value
                        if (all != null) {
                            runCatching {
                                graph.libraryStore.sync(
                                    graph.localSource.descriptor.id,
                                    graph.localSource.descriptor.displayName,
                                    all,
                                    emptyMap(),
                                )
                            }
                        }
                    }
                    haptics.confirm()
                    onDone()
                } else {
                    haptics.reject()
                }
            }
            else -> Unit
        }
    }

    ListInputEffect(focus, onActivate = activate)
    LaunchedEffect(focus) {
        snapshotFlow { focus.focusMoves }.collect { if (it > 0) focusManager.clearFocus() }
    }

    FocusList(
        items = rows,
        state = focus,
        key = { it.name },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        focusable = { it != EditAlbumRow.Header && it != EditAlbumRow.TitleLabel && it != EditAlbumRow.ArtistLabel },
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            EditAlbumRow.Header -> DetailHeader(
                artworkUri = null,
                title = title.ifBlank { initialTitle },
                subtitle = artist.ifBlank { initialArtist },
                details = listOf("Edit album"),
            )
            EditAlbumRow.TitleLabel -> SectionHeader("Album title")
            EditAlbumRow.TitleField -> PaperTextField(
                value = title,
                onChange = { title = it },
                focus = titleFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Album title",
                description = "Album title",
                action = KeyboardAction.DONE,
            )
            EditAlbumRow.ArtistLabel -> SectionHeader("Album artist")
            EditAlbumRow.ArtistField -> PaperTextField(
                value = artist,
                onChange = { artist = it },
                focus = artistFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Artist name",
                description = "Artist name",
                action = KeyboardAction.DONE,
            )
            EditAlbumRow.Save -> MenuRow(
                label = "Save changes",
                focused = focused,
                enabled = valid,
                leading = PodiumSymbol.Check,
                showChevron = false,
            )
        }
    }
}

private enum class EditArtistRow {
    Header,
    NameLabel,
    NameField,
    Save,
}

@Composable
fun EditLocalArtistScreen(
    graph: AppGraph,
    artistId: ArtistId,
    initialName: String,
    onDone: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val haptics = rememberPodiumHaptics()
    val scope = rememberCoroutineScope()

    var name by rememberSaveable(artistId.value) { mutableStateOf(initialName) }
    val nameFocus = remember { FocusRequester() }

    val miniature = LocalMiniature.current
    if (!miniature) {
        KeyboardAwayOnLeave()
    }

    val rows = remember { EditArtistRow.entries }
    val valid = name.trim().isNotEmpty()
    val focus = rememberFocusListState("edit-local-artist:${artistId.value}")

    val activate: (Int) -> Unit = { index ->
        when (rows.getOrNull(index)) {
            EditArtistRow.NameField -> nameFocus.requestFocus()
            EditArtistRow.Save -> {
                if (valid) {
                    graph.localSource.renameArtist(artistId, name)
                    scope.launch(Dispatchers.IO) {
                        val all = graph.localSource.tracksState.value
                        if (all != null) {
                            runCatching {
                                graph.libraryStore.sync(
                                    graph.localSource.descriptor.id,
                                    graph.localSource.descriptor.displayName,
                                    all,
                                    emptyMap(),
                                )
                            }
                        }
                    }
                    haptics.confirm()
                    onDone()
                } else {
                    haptics.reject()
                }
            }
            else -> Unit
        }
    }

    ListInputEffect(focus, onActivate = activate)
    LaunchedEffect(focus) {
        snapshotFlow { focus.focusMoves }.collect { if (it > 0) focusManager.clearFocus() }
    }

    FocusList(
        items = rows,
        state = focus,
        key = { it.name },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        focusable = { it != EditArtistRow.Header && it != EditArtistRow.NameLabel },
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            EditArtistRow.Header -> DetailHeader(
                artworkUri = null,
                title = name.ifBlank { initialName },
                subtitle = "Local artist",
                details = listOf("Edit artist"),
                round = true,
            )
            EditArtistRow.NameLabel -> SectionHeader("Artist name")
            EditArtistRow.NameField -> PaperTextField(
                value = name,
                onChange = { name = it },
                focus = nameFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Artist name",
                description = "Artist name",
                action = KeyboardAction.DONE,
            )
            EditArtistRow.Save -> MenuRow(
                label = "Save changes",
                focused = focused,
                enabled = valid,
                leading = PodiumSymbol.Check,
                showChevron = false,
            )
        }
    }
}
