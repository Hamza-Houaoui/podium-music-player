package app.podium.radio

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.DetailHeader
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.KeyboardAction
import app.podium.core.designsystem.component.KeyboardAwayOnLeave
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LoadingRow
import app.podium.core.designsystem.component.LoadingScreen
import app.podium.core.designsystem.component.LocalMiniature
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.PaperTextField
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.ArtworkRef
import app.podium.player.api.PlaybackController
import app.podium.player.api.TrackCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Places inside Podium's Online Radio hierarchy.
 */
sealed interface RadioPlace {
    val title: String

    data object Menu : RadioPlace {
        override val title: String = "Radio"
    }

    data object CoverFlow : RadioPlace {
        override val title: String = "Cover Flow"
    }

    data class Category(val category: RadioCategory) : RadioPlace {
        override val title: String = category.label
    }

    data object Search : RadioPlace {
        override val title: String = "Search radio"
    }

    data object AddCustom : RadioPlace {
        override val title: String = "Custom station"
    }

    data class EditStation(val stationId: String) : RadioPlace {
        override val title: String = "Edit station"
    }

    companion object {
        fun encode(place: RadioPlace): String = when (place) {
            Menu -> "Menu"
            CoverFlow -> "CoverFlow"
            is Category -> "Category:${place.category.name}"
            Search -> "Search"
            AddCustom -> "AddCustom"
            is EditStation -> "Edit:${place.stationId}"
        }

        fun decode(raw: String): RadioPlace? = when {
            raw == "Menu" -> Menu
            raw == "CoverFlow" -> CoverFlow
            raw == "Search" -> Search
            raw == "AddCustom" -> AddCustom
            raw.startsWith("Category:") -> {
                val name = raw.removePrefix("Category:")
                RadioCategory.entries.firstOrNull { it.name == name }?.let(::Category)
            }
            raw.startsWith("Edit:") -> EditStation(raw.removePrefix("Edit:"))
            else -> null
        }
    }
}

@Composable
fun RadioScreen(
    place: RadioPlace,
    repository: RadioRepository,
    catalog: TrackCatalog,
    controller: PlaybackController,
    navigate: (RadioPlace) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onBack: () -> Unit,
) {
    when (place) {
        RadioPlace.Menu -> RadioMenuScreen(
            repository = repository,
            catalog = catalog,
            controller = controller,
            navigate = navigate,
            onOpenNowPlaying = onOpenNowPlaying,
        )
        RadioPlace.CoverFlow -> RadioCoverFlowScreen(
            repository = repository,
            catalog = catalog,
            controller = controller,
            navigate = navigate,
            onOpenNowPlaying = onOpenNowPlaying,
        )
        is RadioPlace.Category -> RadioCategoryScreen(
            category = place.category,
            repository = repository,
            catalog = catalog,
            controller = controller,
            navigate = navigate,
            onOpenNowPlaying = onOpenNowPlaying,
        )
        RadioPlace.Search -> RadioSearchScreen(
            repository = repository,
            catalog = catalog,
            controller = controller,
            navigate = navigate,
            onOpenNowPlaying = onOpenNowPlaying,
        )
        RadioPlace.AddCustom -> RadioAddCustomScreen(
            repository = repository,
            catalog = catalog,
            controller = controller,
            onSaved = {
                onBack()
                onOpenNowPlaying()
            },
        )
        is RadioPlace.EditStation -> RadioEditStationScreen(
            stationId = place.stationId,
            repository = repository,
            catalog = catalog,
            onDone = onBack,
        )
    }
}

private sealed interface RadioMenuRow {
    val key: String
    data class CoverFlow(val count: Int) : RadioMenuRow {
        override val key: String = "cover-flow"
    }
    data class CategoryItem(val category: RadioCategory, val value: String? = null) : RadioMenuRow {
        override val key: String = "cat:${category.name}"
    }
    data object Search : RadioMenuRow {
        override val key: String = "search"
    }
    data object AddCustom : RadioMenuRow {
        override val key: String = "add-custom"
    }
    data class Section(val title: String) : RadioMenuRow {
        override val key: String = "section:$title"
    }
    data class Recent(val station: RadioStation) : RadioMenuRow {
        override val key: String = "recent:${station.id}"
    }
}

@Composable
private fun RadioMenuScreen(
    repository: RadioRepository,
    catalog: TrackCatalog,
    controller: PlaybackController,
    navigate: (RadioPlace) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val favorites by repository.favorites.collectAsStateWithLifecycle()
    val recents by repository.recents.collectAsStateWithLifecycle()
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val playingId = snapshot.item?.trackId
    val overlay = LocalOverlayHost.current
    val haptics = rememberPodiumHaptics()
    val favCovers = remember(favorites) { favorites.mapNotNull { it.toPodiumTrack().artwork?.uri }.distinct().take(10) }

    val rows = remember(favorites.size, recents) {
        buildList {
            add(RadioMenuRow.CoverFlow(favorites.size))
            add(RadioMenuRow.CategoryItem(RadioCategory.TUNISIA, "Tunisian FM"))
            add(RadioMenuRow.CategoryItem(RadioCategory.FAVORITES, favorites.size.takeIf { it > 0 }?.toString()))
            add(RadioMenuRow.CategoryItem(RadioCategory.TOP_WORLD, "Worldwide"))
            add(RadioMenuRow.CategoryItem(RadioCategory.NEWS))
            add(RadioMenuRow.CategoryItem(RadioCategory.MUSIC))
            add(RadioMenuRow.CategoryItem(RadioCategory.QURAN))
            add(RadioMenuRow.CategoryItem(RadioCategory.CULTURE))
            add(RadioMenuRow.CategoryItem(RadioCategory.SPORTS))
            add(RadioMenuRow.Search)
            add(RadioMenuRow.AddCustom)
            if (recents.isNotEmpty()) {
                add(RadioMenuRow.Section("Recently played"))
                recents.take(12).forEach { add(RadioMenuRow.Recent(it)) }
            }
        }
    }

    val focus = rememberFocusListState("radio-menu")
    val activate: (Int) -> Unit = { index ->
        when (val row = rows.getOrNull(index)) {
            is RadioMenuRow.CoverFlow -> navigate(RadioPlace.CoverFlow)
            is RadioMenuRow.CategoryItem -> navigate(RadioPlace.Category(row.category))
            RadioMenuRow.Search -> navigate(RadioPlace.Search)
            RadioMenuRow.AddCustom -> navigate(RadioPlace.AddCustom)
            is RadioMenuRow.Recent -> {
                playStations(listOf(row.station), 0, "Radio", repository, catalog, controller, onOpenNowPlaying)
            }
            else -> Unit
        }
    }
    val longPress: (Int) -> Unit = { index ->
        val row = rows.getOrNull(index)
        if (row is RadioMenuRow.Recent) {
            showStationMenu(overlay, row.station, repository, catalog, controller, haptics, navigate)
        }
    }

    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)

    FocusList(
        items = rows,
        state = focus,
        key = { it.key },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        focusable = { it !is RadioMenuRow.Section },
        preview = { row ->
            when (row) {
                is RadioMenuRow.CoverFlow -> if (favCovers.isNotEmpty()) MenuPreview.Carousel(favCovers) else MenuPreview.Instrument
                is RadioMenuRow.Recent -> row.station.toPodiumTrack().artwork?.uri?.let { MenuPreview.Artwork(listOf(it)) }
                    ?: MenuPreview.Instrument
                else -> MenuPreview.Instrument
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            is RadioMenuRow.CoverFlow -> MenuRow(
                label = "Cover Flow",
                focused = focused,
                value = row.count.takeIf { it > 0 }?.toString(),
            )
            is RadioMenuRow.CategoryItem -> MenuRow(
                label = row.category.label,
                focused = focused,
                value = row.value,
                leading = if (row.category == RadioCategory.FAVORITES) PodiumSymbol.Favorite else PodiumSymbol.Speaker,
            )
            RadioMenuRow.Search -> MenuRow("Search stations", focused, leading = PodiumSymbol.Search)
            RadioMenuRow.AddCustom -> MenuRow("Add custom station", focused, leading = PodiumSymbol.Queue)
            is RadioMenuRow.Section -> RadioSectionLabel(row.title)
            is RadioMenuRow.Recent -> {
                val track = row.station.toPodiumTrack()
                val isFav = favorites.any { it.id == row.station.id }
                TrackRow(
                    title = row.station.name,
                    subtitle = row.station.subtitle,
                    focused = focused,
                    artworkUri = track.artwork?.uri,
                    trailing = if (isFav) "★" else row.station.bitrate.takeIf { it > 0 }?.let { "${it}k" },
                    active = track.id == playingId,
                )
            }
        }
    }
}

@Composable
private fun RadioCoverFlowScreen(
    repository: RadioRepository,
    catalog: TrackCatalog,
    controller: PlaybackController,
    navigate: (RadioPlace) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val favorites by repository.favorites.collectAsStateWithLifecycle()
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val playingId = if (snapshot.intent == app.podium.player.api.PlayIntent.PLAY) snapshot.item?.trackId else null
    val overlay = LocalOverlayHost.current
    val haptics = rememberPodiumHaptics()

    app.podium.core.designsystem.component.CoverFlowStage(
        items = favorites,
        stateKey = "radio-coverflow",
        keyOf = { it.id },
        titleOf = { it.name },
        subtitleOf = { it.subtitle },
        artworkOf = { it.toPodiumTrack().artwork?.uri },
        isPlayingItem = { station -> playingId != null && station.toPodiumTrack().id == playingId },
        onActivate = { _, index ->
            playStations(favorites, index, "Favorite Radio", repository, catalog, controller, onOpenNowPlaying)
        },
        onLongPress = { station, _ ->
            showStationMenu(overlay, station, repository, catalog, controller, haptics, navigate)
        },
        emptySymbol = PodiumSymbol.Favorite,
        emptyTitle = "No favorite stations yet",
        emptyMessage = "Hold touch on any radio station and choose Add to favorites to see its cover here.",
    )
}

@Composable
private fun RadioCategoryScreen(
    category: RadioCategory,
    repository: RadioRepository,
    catalog: TrackCatalog,
    controller: PlaybackController,
    navigate: (RadioPlace) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val favorites by repository.favorites.collectAsStateWithLifecycle()
    val custom by repository.customStations.collectAsStateWithLifecycle()
    val editVersion by repository.editVersion.collectAsStateWithLifecycle()
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val playingId = snapshot.item?.trackId
    val overlay = LocalOverlayHost.current
    val haptics = rememberPodiumHaptics()

    var loading by remember(category) { mutableStateOf(category != RadioCategory.FAVORITES) }
    var fetched by remember(category) { mutableStateOf<List<RadioStation>>(emptyList()) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(category, reloadTick, editVersion) {
        if (category == RadioCategory.FAVORITES) {
            loading = false
        } else {
            loading = true
            fetched = repository.loadCategory(category)
            loading = false
        }
    }

    val stations = if (category == RadioCategory.FAVORITES) {
        remember(favorites, custom) { (favorites + custom).distinctBy { it.id } }
    } else {
        fetched
    }

    if (loading && stations.isEmpty()) {
        LoadingScreen("Tuning ${category.label}…")
        return
    }

    if (stations.isEmpty()) {
        val insets = LocalScreenInsets.current
        val focus = rememberFocusListState("radio-empty-${category.name}")
        val emptyActions = if (category == RadioCategory.FAVORITES) {
            listOf("Browse Tunisian stations" to { navigate(RadioPlace.Category(RadioCategory.TUNISIA)) }, "Add custom station" to { navigate(RadioPlace.AddCustom) })
        } else {
            listOf("Try again" to { reloadTick++ })
        }
        val activate: (Int) -> Unit = { idx -> emptyActions.getOrNull(idx)?.second?.invoke() }
        ListInputEffect(focus, onActivate = activate)
        FocusList(
            items = emptyActions,
            state = focus,
            key = { it.first },
            contentPadding = insets.listPadding(),
            onActivate = activate,
            modifier = Modifier.fillMaxSize(),
        ) { item, _, focused ->
            MenuRow(item.first, focused, showChevron = category == RadioCategory.FAVORITES)
        }
        return
    }

    val focus = rememberFocusListState("radio-cat-${category.name}")
    val activate: (Int) -> Unit = { index ->
        if (index in stations.indices) {
            playStations(stations, index, category.label, repository, catalog, controller, onOpenNowPlaying)
        }
    }
    val longPress: (Int) -> Unit = { index ->
        stations.getOrNull(index)?.let { station ->
            showStationMenu(overlay, station, repository, catalog, controller, haptics, navigate)
        }
    }

    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)

    FocusList(
        items = stations,
        state = focus,
        key = { it.id },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        preview = { station ->
            station.toPodiumTrack().artwork?.uri?.let { MenuPreview.Artwork(listOf(it)) }
                ?: MenuPreview.Instrument
        },
        modifier = Modifier.fillMaxSize(),
    ) { station, _, focused ->
        val track = station.toPodiumTrack()
        val isFav = favorites.any { it.id == station.id }
        TrackRow(
            title = station.name,
            subtitle = station.subtitle,
            focused = focused,
            artworkUri = track.artwork?.uri,
            trailing = if (isFav) "★" else station.bitrate.takeIf { it > 0 }?.let { "${it}k" },
            active = track.id == playingId,
        )
    }
}

private sealed interface RadioSearchRow {
    data object Field : RadioSearchRow
    data object Loading : RadioSearchRow
    data class Message(val text: String) : RadioSearchRow
    data class StationItem(val station: RadioStation) : RadioSearchRow
}

@Composable
private fun RadioSearchScreen(
    repository: RadioRepository,
    catalog: TrackCatalog,
    controller: PlaybackController,
    navigate: (RadioPlace) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val favorites by repository.favorites.collectAsStateWithLifecycle()
    val editVersion by repository.editVersion.collectAsStateWithLifecycle()
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val playingId = snapshot.item?.trackId
    val overlay = LocalOverlayHost.current
    val haptics = rememberPodiumHaptics()

    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var searchedOnce by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(editVersion) {
        snapshotFlow { query.trim() }.distinctUntilChanged().collectLatest { text ->
            if (text.length < 2) {
                searching = false
                searchedOnce = false
                results = RadioApi.CURATED_TUNISIA.map { repository.getStation(it.id) ?: it }
                return@collectLatest
            }
            delay(320L)
            searching = true
            results = repository.search(text)
            searching = false
            searchedOnce = true
        }
    }

    val miniature = LocalMiniature.current
    if (!miniature) {
        LaunchedEffect(Unit) { if (query.isEmpty()) fieldFocus.requestFocus() }
        KeyboardAwayOnLeave()
    }

    val rows = remember(searching, results, searchedOnce) {
        buildList {
            add(RadioSearchRow.Field)
            if (searching) {
                add(RadioSearchRow.Loading)
            } else if (results.isEmpty() && searchedOnce) {
                add(RadioSearchRow.Message("No stations found. Try another name, country, or genre."))
            } else {
                results.forEach { add(RadioSearchRow.StationItem(it)) }
            }
        }
    }

    val focus = rememberFocusListState("radio-search")
    val activate: (Int) -> Unit = { index ->
        when (val row = rows.getOrNull(index)) {
            RadioSearchRow.Field -> fieldFocus.requestFocus()
            is RadioSearchRow.StationItem -> {
                val idx = results.indexOfFirst { it.id == row.station.id }.coerceAtLeast(0)
                playStations(results, idx, "Radio search", repository, catalog, controller, onOpenNowPlaying)
            }
            else -> Unit
        }
    }
    val longPress: (Int) -> Unit = { index ->
        val row = rows.getOrNull(index)
        if (row is RadioSearchRow.StationItem) {
            showStationMenu(overlay, row.station, repository, catalog, controller, haptics, navigate)
        }
    }

    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
    LaunchedEffect(focus) {
        snapshotFlow { focus.focusMoves }.collect { if (it > 0) focusManager.clearFocus() }
    }

    FocusList(
        items = rows,
        state = focus,
        key = { row ->
            when (row) {
                RadioSearchRow.Field -> "field"
                RadioSearchRow.Loading -> "loading"
                is RadioSearchRow.Message -> "msg:${row.text}"
                is RadioSearchRow.StationItem -> "station:${row.station.id}"
            }
        },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        focusable = { it !is RadioSearchRow.Loading && it !is RadioSearchRow.Message },
        preview = { row ->
            when (row) {
                is RadioSearchRow.StationItem -> row.station.toPodiumTrack().artwork?.uri?.let { MenuPreview.Artwork(listOf(it)) }
                    ?: MenuPreview.Instrument
                else -> MenuPreview.Instrument
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            RadioSearchRow.Field -> PaperTextField(
                value = query,
                onChange = { query = it },
                focus = fieldFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Station name, country, genre",
                description = "Search radio stations",
                action = KeyboardAction.SEARCH,
            )
            RadioSearchRow.Loading -> LoadingRow("Searching stations")
            is RadioSearchRow.Message -> RadioSectionLabel(row.text)
            is RadioSearchRow.StationItem -> {
                val track = row.station.toPodiumTrack()
                val isFav = favorites.any { it.id == row.station.id }
                TrackRow(
                    title = row.station.name,
                    subtitle = row.station.subtitle,
                    focused = focused,
                    artworkUri = track.artwork?.uri,
                    trailing = if (isFav) "★" else row.station.bitrate.takeIf { it > 0 }?.let { "${it}k" },
                    active = track.id == playingId,
                )
            }
        }
    }
}

private enum class CustomStationRow { ChooseLogo, PasteLogoUrl, NameField, UrlField, PasteUrl, SavePlay }

@Composable
private fun RadioAddCustomScreen(
    repository: RadioRepository,
    catalog: TrackCatalog,
    controller: PlaybackController,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val haptics = rememberPodiumHaptics()

    var name by rememberSaveable { mutableStateOf("") }
    var streamUrl by rememberSaveable { mutableStateOf("") }
    var logoPath by rememberSaveable { mutableStateOf<String?>(null) }

    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }

    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val saved = repository.adoptLogoUri(uri)
            if (saved != null) {
                logoPath = saved
                haptics.confirm()
            } else {
                haptics.reject()
            }
        }
    }

    val miniature = LocalMiniature.current
    if (!miniature) {
        KeyboardAwayOnLeave()
    }

    val valid = name.isNotBlank() && (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))
    val rows = CustomStationRow.entries
    val focus = rememberFocusListState("radio-add-custom")

    val activate: (Int) -> Unit = { index ->
        when (rows.getOrNull(index)) {
            CustomStationRow.ChooseLogo -> {
                pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            CustomStationRow.PasteLogoUrl -> {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                if (!clip.isNullOrBlank() && (clip.startsWith("http://") || clip.startsWith("https://"))) {
                    logoPath = clip
                    haptics.confirm()
                } else {
                    haptics.reject()
                }
            }
            CustomStationRow.NameField -> nameFocus.requestFocus()
            CustomStationRow.UrlField -> urlFocus.requestFocus()
            CustomStationRow.PasteUrl -> {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                if (!clip.isNullOrBlank()) {
                    streamUrl = clip
                    haptics.confirm()
                } else {
                    haptics.reject()
                }
            }
            CustomStationRow.SavePlay -> {
                if (valid) {
                    val station = repository.addCustomStation(
                        name = name,
                        streamUrl = streamUrl,
                        customFavicon = logoPath,
                    )
                    playStations(listOf(station), 0, "Custom radio", repository, catalog, controller, onSaved)
                } else {
                    haptics.reject()
                }
            }
            null -> Unit
        }
    }

    ListInputEffect(focus, onActivate = activate)
    LaunchedEffect(focus) {
        snapshotFlow { focus.focusMoves }.collect { if (it > 0) focusManager.clearFocus() }
    }

    val previewArt = logoPath?.let { ArtworkRef(RadioStation.RADIO_SOURCE_ID, it).uri }

    FocusList(
        items = rows,
        state = focus,
        key = { it.name },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { previewArt?.let { MenuPreview.Artwork(listOf(it)) } ?: MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            CustomStationRow.ChooseLogo -> MenuRow(
                label = if (logoPath != null) "Change station logo" else "Choose logo from gallery",
                focused = focused,
                value = if (logoPath != null) "Set" else null,
                leading = PodiumSymbol.Image,
                showChevron = false,
            )
            CustomStationRow.PasteLogoUrl -> MenuRow(
                label = "Paste logo image URL",
                focused = focused,
                showChevron = false,
            )
            CustomStationRow.NameField -> PaperTextField(
                value = name,
                onChange = { name = it },
                focus = nameFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Station name (e.g. My Radio)",
                description = "Station name",
                action = KeyboardAction.DONE,
            )
            CustomStationRow.UrlField -> PaperTextField(
                value = streamUrl,
                onChange = { streamUrl = it },
                focus = urlFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Stream URL (https://…)",
                description = "Stream URL",
                action = KeyboardAction.DONE,
            )
            CustomStationRow.PasteUrl -> MenuRow(
                label = "Paste stream URL from clipboard",
                focused = focused,
                showChevron = false,
            )
            CustomStationRow.SavePlay -> MenuRow(
                label = "Save and play station",
                focused = focused,
                enabled = valid,
                leading = PodiumSymbol.Play,
            )
        }
    }
}

private enum class EditStationRow { Header, ChooseLogo, PasteLogoUrl, ClearLogo, NameField, UrlField, Save }

@Composable
private fun RadioEditStationScreen(
    stationId: String,
    repository: RadioRepository,
    catalog: TrackCatalog,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val haptics = rememberPodiumHaptics()

    val station = remember(stationId) { repository.getStation(stationId) }
    if (station == null) {
        val insets = LocalScreenInsets.current
        Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
            MessageState(PodiumSymbol.Error, "Station not found", "Go back and select a station.")
        }
        return
    }

    var name by rememberSaveable(stationId) { mutableStateOf(station.name) }
    var streamUrl by rememberSaveable(stationId) { mutableStateOf(station.streamUrl) }
    var customLogo by rememberSaveable(stationId) { mutableStateOf(station.customFavicon) }

    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }

    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val saved = repository.adoptLogoUri(uri)
            if (saved != null) {
                customLogo = saved
                haptics.confirm()
            } else {
                haptics.reject()
            }
        }
    }

    val miniature = LocalMiniature.current
    if (!miniature) {
        KeyboardAwayOnLeave()
    }

    val rows = remember(customLogo) {
        buildList {
            add(EditStationRow.Header)
            add(EditStationRow.ChooseLogo)
            add(EditStationRow.PasteLogoUrl)
            if (!customLogo.isNullOrBlank()) add(EditStationRow.ClearLogo)
            add(EditStationRow.NameField)
            add(EditStationRow.UrlField)
            add(EditStationRow.Save)
        }
    }

    val valid = name.isNotBlank() && (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))
    val focus = rememberFocusListState("radio-edit-$stationId")

    val activate: (Int) -> Unit = { index ->
        when (rows.getOrNull(index)) {
            EditStationRow.Header -> Unit
            EditStationRow.ChooseLogo -> {
                pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            EditStationRow.PasteLogoUrl -> {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                if (!clip.isNullOrBlank() && (clip.startsWith("http://") || clip.startsWith("https://"))) {
                    customLogo = clip
                    haptics.confirm()
                } else {
                    haptics.reject()
                }
            }
            EditStationRow.ClearLogo -> {
                customLogo = null
                haptics.confirm()
            }
            EditStationRow.NameField -> nameFocus.requestFocus()
            EditStationRow.UrlField -> urlFocus.requestFocus()
            EditStationRow.Save -> {
                if (valid) {
                    val updated = repository.updateStation(
                        stationId = stationId,
                        name = name,
                        streamUrl = streamUrl,
                        customFavicon = customLogo,
                    )
                    if (updated != null) {
                        catalog.remember(listOf(updated.toPodiumTrack()))
                    }
                    haptics.confirm()
                    onDone()
                } else {
                    haptics.reject()
                }
            }
            null -> Unit
        }
    }

    ListInputEffect(focus, onActivate = activate)
    LaunchedEffect(focus) {
        snapshotFlow { focus.focusMoves }.collect { if (it > 0) focusManager.clearFocus() }
    }

    val effectiveLogo = customLogo?.takeIf { it.isNotBlank() } ?: station.favicon?.takeIf { it.isNotBlank() }
    val artworkUri = effectiveLogo?.let { ArtworkRef(RadioStation.RADIO_SOURCE_ID, it).uri }

    FocusList(
        items = rows,
        state = focus,
        key = { it.name },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        focusable = { it != EditStationRow.Header },
        preview = { artworkUri?.let { MenuPreview.Artwork(listOf(it)) } ?: MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            EditStationRow.Header -> DetailHeader(
                artworkUri = artworkUri,
                title = name.ifBlank { station.name },
                subtitle = station.subtitle,
                details = listOf("Radio station"),
            )
            EditStationRow.ChooseLogo -> MenuRow(
                label = "Choose logo from gallery",
                focused = focused,
                value = if (!customLogo.isNullOrBlank()) "Custom" else null,
                leading = PodiumSymbol.Image,
                showChevron = false,
            )
            EditStationRow.PasteLogoUrl -> MenuRow(
                label = "Paste logo image URL",
                focused = focused,
                showChevron = false,
            )
            EditStationRow.ClearLogo -> MenuRow(
                label = "Reset custom logo",
                focused = focused,
                showChevron = false,
            )
            EditStationRow.NameField -> PaperTextField(
                value = name,
                onChange = { name = it },
                focus = nameFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Station name",
                description = "Station name",
                action = KeyboardAction.DONE,
            )
            EditStationRow.UrlField -> PaperTextField(
                value = streamUrl,
                onChange = { streamUrl = it },
                focus = urlFocus,
                onDone = { focusManager.clearFocus() },
                placeholder = "Stream URL (https://…)",
                description = "Stream URL",
                action = KeyboardAction.DONE,
            )
            EditStationRow.Save -> MenuRow(
                label = "Save changes",
                focused = focused,
                enabled = valid,
                leading = PodiumSymbol.Check,
                showChevron = false,
            )
        }
    }
}

@Composable
private fun RadioSectionLabel(text: String) {
    PodiumText(
        text = text,
        style = PodiumTheme.type.caption,
        color = PodiumTheme.colors.labelSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = 6.dp)
            .semantics { heading() },
    )
}

private fun playStations(
    stations: List<RadioStation>,
    index: Int,
    label: String,
    repository: RadioRepository,
    catalog: TrackCatalog,
    controller: PlaybackController,
    onOpenNowPlaying: () -> Unit,
) {
    if (stations.isEmpty()) return
    val safeIndex = index.coerceIn(0, stations.lastIndex)
    repository.rememberStations(stations)
    repository.addRecent(stations[safeIndex])
    val tracks = stations.map { it.toPodiumTrack() }
    catalog.remember(tracks)
    controller.playContext(tracks.map { it.id }, safeIndex, label)
    onOpenNowPlaying()
}

private fun showStationMenu(
    overlay: OverlayHost,
    station: RadioStation,
    repository: RadioRepository,
    catalog: TrackCatalog,
    controller: PlaybackController,
    haptics: app.podium.core.interaction.PodiumHaptics,
    navigate: (RadioPlace) -> Unit,
) {
    val isFav = repository.isFavorite(station.id)
    val track = station.toPodiumTrack()
    overlay.show(
        MenuSpec(
            title = station.name,
            actions = buildList {
                add(
                    MenuAction("Edit station / Set logo") {
                        repository.rememberStations(listOf(station))
                        navigate(RadioPlace.EditStation(station.id))
                    },
                )
                add(
                    MenuAction(if (isFav) "Remove from Favorites" else "Add to Favorites") {
                        haptics.favorite(on = !isFav)
                        repository.toggleFavorite(station)
                    },
                )
                add(
                    MenuAction("Play next") {
                        repository.rememberStations(listOf(station))
                        catalog.remember(listOf(track))
                        controller.playNext(listOf(track.id))
                    },
                )
                add(
                    MenuAction("Add to Up Next") {
                        repository.rememberStations(listOf(station))
                        catalog.remember(listOf(track))
                        controller.addToQueue(listOf(track.id))
                    },
                )
                if (station.isCustom) {
                    add(
                        MenuAction("Delete custom station") {
                            repository.removeCustomStation(station.id)
                        },
                    )
                }
            },
        ),
    )
}
