package app.podium.feature.library

import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.artwork.ArtworkImage
import app.podium.core.designsystem.component.DetailHeader
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LoadingScreen
import app.podium.core.designsystem.component.LocalMiniatureFocusKey
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalRowPadding
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.component.illuminated
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.Track
import app.podium.player.api.FavoritesRepository
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Play [tracks] from [index], as a context labelled [label] ("Continuing from …"). */
typealias PlayTracks = (tracks: List<Track>, index: Int, label: String) -> Unit

@Composable
internal fun rememberTracks(repository: LibraryRepository): Pair<List<Track>, Boolean> {
    val state by repository.songs.collectAsStateWithLifecycle()
    return (state as? LibraryState.Ready)?.tracks.orEmpty() to (state is LibraryState.Loading)
}

/** A value from the library, once it has loaded (null while loading). */
internal class Loaded<T>(val value: T)

@Composable
internal fun <T> rememberLoaded(vararg keys: Any?, initial: Loaded<T>? = null, flow: () -> Flow<T>): Loaded<T>? {
    val loaded = remember(*keys) { flow().map { Loaded(it) } }
    return loaded.collectAsStateWithLifecycle(initialValue = initial).value
}

/**
 * Cover Flow's last order, so opening it again (or coming back from an album) shows the covers at
 * once while the library is read and ordered afresh off the main thread.
 */
private object CoverFlowMemo {
    @Volatile
    private var last: Pair<java.lang.ref.WeakReference<LibraryRepository>, List<LibraryAlbum>>? = null

    fun get(repository: LibraryRepository): List<LibraryAlbum>? = last?.takeIf { it.first.get() === repository }?.second

    fun put(repository: LibraryRepository, albums: List<LibraryAlbum>) {
        last = java.lang.ref.WeakReference(repository) to albums
    }
}

@Composable
internal fun EmptyState(symbol: PodiumSymbol, title: String, message: String) {
    val insets = LocalScreenInsets.current
    Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
        MessageState(symbol, title, message)
    }
}

/**
 * Why a library list is empty, with the next step: music access is missing, or there's no music
 * in the folders Podium reads.
 */
internal fun noMusicMessage(accessMissing: Boolean): String =
    if (accessMissing) "Allow music access from Home, and the music on this phone appears here."
    else "No music found on this phone. Add some songs, or pick other folders in Settings."

@Composable
internal fun noMusic(repository: LibraryRepository): String {
    val actions by repository.pendingActions.collectAsStateWithLifecycle()
    return noMusicMessage(actions.isNotEmpty())
}

/**
 * Long-press menu for a song, shared by every song list: Play next, Add to Up Next, Add to playlist
 * (D-106), then any [more] of the list's own.
 */
@Composable
internal fun songMenu(onPlayNext: (Track) -> Unit, onAddToQueue: (Track) -> Unit, more: List<MenuAction> = emptyList()): (Track) -> Unit {
    val overlay = LocalOverlayHost.current
    val picker = LocalPlaylistPicker.current
    val editor = LocalSongEditorHost.current
    return { track ->
        overlay.show(
            MenuSpec(
                track.title,
                buildList {
                    add(MenuAction("Play next") { onPlayNext(track) })
                    add(MenuAction("Add to Up Next") { onAddToQueue(track) })
                    if (picker != null) add(MenuAction("Add to playlist") { picker.show(overlay, listOf(track.id)) })
                    if (editor != null && track.source.sourceId.value == "local") {
                        add(MenuAction("Edit song info") { editor.editSong(track) })
                    }
                    addAll(more)
                },
            ),
        )
    }
}

/** Albums, A to Z. */
@Composable
fun AlbumsScreen(repository: LibraryRepository, onOpen: (AlbumId) -> Unit) {
    val loaded = rememberLoaded(repository) { repository.albums() }
    val albums = loaded?.value.orEmpty()
    val focus = rememberFocusListState("albums")
    val overlay = LocalOverlayHost.current
    val editor = LocalSongEditorHost.current
    val showAlbumMenu: (Int) -> Unit = { index ->
        val album = albums.getOrNull(index)
        if (album != null) {
            overlay.show(
                MenuSpec(
                    album.title,
                    buildList {
                        add(MenuAction("Open album") { onOpen(album.id) })
                        if (editor != null && album.id.sourceId.value == "local") {
                            add(MenuAction("Edit album info") { editor.editAlbum(album) })
                        }
                    },
                ),
            )
        }
    }
    ListInputEffect(focus, onActivate = { albums.getOrNull(it)?.let { a -> onOpen(a.id) } }, onLongPress = showAlbumMenu)
    when {
        loaded == null -> LoadingScreen()
        albums.isEmpty() -> EmptyState(PodiumSymbol.Album, "No albums yet", noMusic(repository))
        else -> FocusList(
            items = albums,
            state = focus,
            key = { it.id.value },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = { onOpen(albums[it].id) },
            onLongPress = showAlbumMenu,
            preview = { MenuPreview.Artwork(listOfNotNull(it.artworkUri)) },
            modifier = Modifier.fillMaxSize(),
        ) { album, _, focused ->
            TrackRow(album.title, album.artist, focused, album.artworkUri, trailing = album.year?.toString())
        }
    }
}

private sealed interface AlbumRow {
    data object Header : AlbumRow
    data class Song(val track: Track, val index: Int) : AlbumRow
}

/** One album: its cover and details, then its songs in disc and track order. */
@Composable
fun AlbumScreen(
    repository: LibraryRepository,
    albumId: AlbumId,
    onPlay: PlayTracks,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
) {
    val loaded = rememberLoaded(repository, albumId) { repository.album(albumId) }
    val detail = loaded?.value
    val album = detail?.album
    val songs = detail?.tracks.orEmpty()
    val focus = rememberFocusListState("album:${albumId.value}")
    val rows = remember(songs) { listOf(AlbumRow.Header) + songs.mapIndexed { i, t -> AlbumRow.Song(t, i) } }
    val overlay = LocalOverlayHost.current
    val picker = LocalPlaylistPicker.current
    val editor = LocalSongEditorHost.current
    val menu = songMenu(
        onPlayNext,
        onAddToQueue,
        more = buildList {
            if (picker != null && songs.size > 1) {
                add(MenuAction("Add album to playlist") { picker.show(overlay, songs.map { it.id }) })
            }
            if (editor != null && album != null && album.id.sourceId.value == "local") {
                add(MenuAction("Edit album info") { editor.editAlbum(album) })
            }
        },
    )
    val activate: (Int) -> Unit = { i -> (rows.getOrNull(i) as? AlbumRow.Song)?.let { album?.let { a -> onPlay(songs, it.index, a.title) } } }
    ListInputEffect(focus, onActivate = activate, onLongPress = { i -> (rows.getOrNull(i) as? AlbumRow.Song)?.let { menu(it.track) } })
    when {
        loaded == null -> LoadingScreen()
        album == null -> EmptyState(PodiumSymbol.Album, "This album isn't here anymore", "Its songs may have been removed. Go back to Albums.")
        else -> FocusList(
            items = rows,
            state = focus,
            key = { if (it is AlbumRow.Song) it.track.id.value else "header" },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = activate,
            onLongPress = { i -> (rows[i] as? AlbumRow.Song)?.let { menu(it.track) } },
            focusable = { it is AlbumRow.Song },
            preview = { MenuPreview.Artwork(listOfNotNull(album.artworkUri)) },
            modifier = Modifier.fillMaxSize(),
        ) { row, _, focused ->
            when (row) {
                AlbumRow.Header -> DetailHeader(album.artworkUri, album.title, album.artist, listOfNotNull(songCount(album.trackCount), album.year?.toString()))
                is AlbumRow.Song -> TrackRow(
                    title = row.track.title,
                    subtitle = row.track.artistDisplay,
                    focused = focused,
                    artworkUri = null,
                    trailing = formatDuration(row.track.durationMs),
                    number = row.track.trackNumber ?: (row.index + 1),
                )
            }
        }
    }
}

private fun albumCount(n: Int) = if (n == 1) "1 album" else "$n albums"

/** Artists, A to Z. */
@Composable
fun ArtistsScreen(repository: LibraryRepository, onOpen: (ArtistId) -> Unit) {
    val loaded = rememberLoaded(repository) { repository.artists() }
    val artists = loaded?.value.orEmpty()
    val focus = rememberFocusListState("artists")
    val overlay = LocalOverlayHost.current
    val editor = LocalSongEditorHost.current
    val showArtistMenu: (Int) -> Unit = { index ->
        val artist = artists.getOrNull(index)
        if (artist != null) {
            overlay.show(
                MenuSpec(
                    artist.name,
                    buildList {
                        add(MenuAction("Open artist") { onOpen(artist.id) })
                        if (editor != null && artist.id.sourceId.value == "local") {
                            add(MenuAction("Edit artist name") { editor.editArtist(artist) })
                        }
                    },
                ),
            )
        }
    }
    ListInputEffect(focus, onActivate = { artists.getOrNull(it)?.let { a -> onOpen(a.id) } }, onLongPress = showArtistMenu)
    when {
        loaded == null -> LoadingScreen()
        artists.isEmpty() -> EmptyState(PodiumSymbol.Person, "No artists yet", noMusic(repository))
        else -> FocusList(
            items = artists,
            state = focus,
            key = { it.id.value },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = { onOpen(artists[it].id) },
            onLongPress = showArtistMenu,
            preview = { a -> a.artworkUri?.let { MenuPreview.Artwork(listOf(it), round = true) } ?: MenuPreview.Artwork(a.covers) },
            modifier = Modifier.fillMaxSize(),
        ) { artist, _, focused ->
            TrackRow(
                artist.name,
                albumCount(artist.albumCount),
                focused,
                artist.artworkUri,
                trailing = artist.trackCount.toString(),
                roundArtwork = true,
            )
        }
    }
}

private sealed interface ArtistRow {
    data object Header : ArtistRow
    data object AllSongs : ArtistRow
    data class AlbumEntry(val album: LibraryAlbum) : ArtistRow
    data class SongEntry(val track: Track, val index: Int) : ArtistRow
}

/** One artist: all their songs, then their albums and songs. */
@Composable
fun ArtistScreen(
    repository: LibraryRepository,
    artistId: ArtistId,
    onOpenAlbum: (AlbumId) -> Unit,
    onPlay: PlayTracks,
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
) {
    val loaded = rememberLoaded(repository, artistId) { repository.artist(artistId) }
    val detail = loaded?.value
    val artist = detail?.artist
    val songs = detail?.tracks.orEmpty()
    val rows = remember(detail) {
        listOf(ArtistRow.Header, ArtistRow.AllSongs) +
            detail?.albums.orEmpty().map { ArtistRow.AlbumEntry(it) } +
            songs.mapIndexed { i, t -> ArtistRow.SongEntry(t, i) }
    }
    val focus = rememberFocusListState("artist:${artistId.value}")
    val overlay = LocalOverlayHost.current
    val editor = LocalSongEditorHost.current
    val sMenu = songMenu(
        onPlayNext,
        onAddToQueue,
        more = if (editor != null && artist != null && artist.id.sourceId.value == "local") {
            listOf(MenuAction("Edit artist name") { editor.editArtist(artist) })
        } else emptyList(),
    )
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            ArtistRow.AllSongs -> detail?.let { onPlay(it.tracks, 0, it.artist.name) }
            is ArtistRow.AlbumEntry -> onOpenAlbum(row.album.id)
            is ArtistRow.SongEntry -> detail?.let { onPlay(it.tracks, row.index, it.artist.name) }
            ArtistRow.Header, null -> Unit
        }
    }
    val longPress: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            ArtistRow.AllSongs -> if (editor != null && artist != null && artist.id.sourceId.value == "local") {
                overlay.show(
                    MenuSpec(
                        artist.name,
                        listOf(
                            MenuAction("Play all songs") { detail?.let { onPlay(it.tracks, 0, it.artist.name) } },
                            MenuAction("Edit artist name") { editor.editArtist(artist) },
                        ),
                    ),
                )
            }
            is ArtistRow.AlbumEntry -> if (editor != null && row.album.id.sourceId.value == "local") {
                overlay.show(
                    MenuSpec(
                        row.album.title,
                        listOf(
                            MenuAction("Open album") { onOpenAlbum(row.album.id) },
                            MenuAction("Edit album info") { editor.editAlbum(row.album) },
                        ),
                    ),
                )
            }
            is ArtistRow.SongEntry -> sMenu(row.track)
            ArtistRow.Header, null -> Unit
        }
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
    when {
        loaded == null -> LoadingScreen()
        artist == null -> EmptyState(PodiumSymbol.Person, "This artist isn't here anymore", "Their songs may have been removed. Go back to Artists.")
        else -> FocusList(
            items = rows,
            state = focus,
            key = {
                when (it) {
                    ArtistRow.Header -> "header"
                    ArtistRow.AllSongs -> "all"
                    is ArtistRow.AlbumEntry -> "album:${it.album.id.value}"
                    is ArtistRow.SongEntry -> "song:${it.track.id.value}"
                }
            },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = activate,
            onLongPress = longPress,
            focusable = { it !is ArtistRow.Header },
            preview = { row ->
                when (row) {
                    is ArtistRow.AlbumEntry -> MenuPreview.Artwork(listOfNotNull(row.album.artworkUri))
                    is ArtistRow.SongEntry -> MenuPreview.Artwork(listOfNotNull(row.track.artwork?.uri))
                    else -> MenuPreview.Artwork(artist.covers)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { row, _, focused ->
            when (row) {
                ArtistRow.Header -> DetailHeader(
                    artist.artworkUri,
                    artist.name,
                    albumCount(artist.albumCount),
                    listOf(songCount(artist.trackCount)),
                    round = true,
                )
                ArtistRow.AllSongs -> MenuRow("All songs", focused, leading = PodiumSymbol.PlaylistPlay, showChevron = false)
                is ArtistRow.AlbumEntry -> TrackRow(row.album.title, songCount(row.album.trackCount), focused, row.album.artworkUri, trailing = row.album.year?.toString())
                is ArtistRow.SongEntry -> TrackRow(
                    title = row.track.title,
                    subtitle = row.track.album?.title ?: row.track.artistDisplay,
                    focused = focused,
                    artworkUri = row.track.artwork?.uri,
                    trailing = formatDuration(row.track.durationMs),
                )
            }
        }
    }
}

/** The songs the listener has marked as favorites, A to Z. */
@Composable
fun FavoritesScreen(
    repository: LibraryRepository,
    favorites: FavoritesRepository,
    onPlay: PlayTracks,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
) {
    val (tracks, loading) = rememberTracks(repository)
    val favoriteIds by favorites.favorites.collectAsStateWithLifecycle()
    val songs = remember(tracks, favoriteIds) { tracks.filter { it.id in favoriteIds } }
    val focus = rememberFocusListState("favorites")
    val menu = songMenu(onPlayNext, onAddToQueue)
    ListInputEffect(focus, onActivate = { onPlay(songs, it, "Favorites") }, onLongPress = { menu(songs[it]) })
    when {
        loading -> LoadingScreen()
        songs.isEmpty() -> EmptyState(PodiumSymbol.Favorite, "No favorites yet", "On Now Playing, choose the heart or More to add a song.")
        else -> FocusList(
            items = songs,
            state = focus,
            key = { it.id.value },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = { onPlay(songs, it, "Favorites") },
            onLongPress = { menu(songs[it]) },
            preview = { MenuPreview.Artwork(listOfNotNull(it.artwork?.uri)) },
            modifier = Modifier.fillMaxSize(),
        ) { track, _, focused ->
            TrackRow(track.title, track.artistDisplay, focused, track.artwork?.uri, trailing = formatDuration(track.durationMs))
        }
    }
}

/**
 * Cover Flow (D-29): album covers as the whole screen. The centre cover faces you; its neighbours
 * stand angled to either side like a record rack, smaller and dimmer with distance. Matte around
 * the art — no reflections, no glow. The Wheel moves through it; Center opens the album; drag or
 * tap works too.
 */
@Composable
fun CoverFlowScreen(repository: LibraryRepository, onOpen: (AlbumId) -> Unit) {
    // By artist, then album (D-84), like an iPod's Cover Flow; the Albums list stays by title. Ordered
    // off the main thread, so opening and closing never wait on it; the last order shows meanwhile.
    val loaded = rememberLoaded(repository, initial = CoverFlowMemo.get(repository)?.let(::Loaded)) {
        repository.albums()
            .map { coverFlowOrder(it).also { ordered -> CoverFlowMemo.put(repository, ordered) } }
            .flowOn(Dispatchers.Default)
    }
    val loading = loaded == null
    val albums = loaded?.value.orEmpty()
    val overlay = LocalOverlayHost.current
    val editor = LocalSongEditorHost.current
    if (loading) return
    app.podium.core.designsystem.component.CoverFlowStage(
        items = albums,
        stateKey = "coverflow",
        keyOf = { it.id.value },
        titleOf = { it.title },
        subtitleOf = { it.artist },
        artworkOf = { it.artworkUri },
        onActivate = { album, _ -> onOpen(album.id) },
        onLongPress = { album, _ ->
            overlay.show(
                MenuSpec(
                    album.title,
                    buildList {
                        add(MenuAction("Open album") { onOpen(album.id) })
                        if (editor != null && album.id.sourceId.value == "local") {
                            add(MenuAction("Edit album info") { editor.editAlbum(album) })
                        }
                    },
                ),
            )
        },
        emptySymbol = PodiumSymbol.Album,
        emptyTitle = "No albums yet",
        emptyMessage = noMusic(repository),
    )
}
