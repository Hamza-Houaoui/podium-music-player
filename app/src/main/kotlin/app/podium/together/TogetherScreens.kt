package app.podium.together

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.KeyboardAction
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LoadingScreen
import app.podium.core.designsystem.component.LocalMiniature
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.PodiumTextField
import app.podium.core.designsystem.component.SectionHeader
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.tonal.ContinuousShape
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.Track
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface TogetherRow {
    val key: String
    val focusable: Boolean get() = true

    data object StartHosting : TogetherRow { override val key = "start-hosting" }
    data class Nearby(val session: NearbySession) : TogetherRow {
        override val key = "nearby:${session.host}:${session.port}"
    }
    data object JoinByAddress : TogetherRow { override val key = "join-address" }
    data class Header(val title: String) : TogetherRow {
        override val key = "header:$title"
        override val focusable = false
    }
    data class Info(val id: String, val label: String, val value: String? = null) : TogetherRow {
        override val key = "info:$id"
    }
    data class Note(val id: String, val text: String) : TogetherRow {
        override val key = "note:$id"
    }
    data object ToggleGuestsControl : TogetherRow { override val key = "toggle-guests-control" }
    data object FindSongs : TogetherRow { override val key = "find-songs" }
    data object EndSession : TogetherRow { override val key = "end-session" }
    data object Resync : TogetherRow { override val key = "resync" }
    data object ToggleListenHere : TogetherRow { override val key = "toggle-listen-here" }
    data object HostPlayPause : TogetherRow { override val key = "host-play-pause" }
    data object HostNext : TogetherRow { override val key = "host-next" }
    data object HostPrevious : TogetherRow { override val key = "host-previous" }
    data object NowPlayingHost : TogetherRow { override val key = "now-playing-host" }
    data class GuestNowPlaying(val track: Track, val playing: Boolean) : TogetherRow {
        override val key = "guest-now:${track.id.value}"
    }
    data class QueueSong(val index: Int, val track: Track) : TogetherRow {
        override val key = "queue:$index:${track.id.value}"
    }
    data object LeaveSession : TogetherRow { override val key = "leave-session" }
}

/**
 * Listen Together main screen inside Podium's virtual screen.
 * Adapts dynamically to [TogetherState.Idle], [TogetherState.Joining], [TogetherState.Hosting],
 * and [TogetherState.Guest], controlled entirely by the Click Wheel.
 */
@Composable
fun ListenTogetherScreen(
    together: TogetherSession,
    controller: PlaybackController,
    onJoinSession: (host: String, port: Int, name: String) -> Unit,
    onJoinByAddress: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onFindSongs: () -> Unit,
) {
    val state by together.state.collectAsStateWithLifecycle()
    val nearby by together.nearby.collectAsStateWithLifecycle()
    val notice by together.notice.collectAsStateWithLifecycle()
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val overlay = LocalOverlayHost.current
    val haptics = rememberPodiumHaptics()
    val miniature = LocalMiniature.current

    // Run mDNS discovery while on the Idle screen
    DisposableEffect(state is TogetherState.Idle, miniature) {
        if (!miniature && state is TogetherState.Idle) {
            together.startDiscovery()
        }
        onDispose {
            if (!miniature) together.stopDiscovery()
        }
    }

    // Auto-clear transient notices after 3.5s
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_500)
            together.clearNotice()
        }
    }

    val currentState = state
    if (currentState is TogetherState.Joining) {
        LoadingScreen()
        return
    }

    val rows = remember(currentState, nearby, notice, snapshot.item?.trackId, snapshot.status) {
        buildList {
            notice?.let { add(TogetherRow.Note("notice", it)) }
            when (currentState) {
                TogetherState.Idle -> {
                    add(TogetherRow.StartHosting)
                    if (nearby.isNotEmpty()) {
                        add(TogetherRow.Header("Nearby"))
                        nearby.forEach { add(TogetherRow.Nearby(it)) }
                    }
                    add(TogetherRow.JoinByAddress)
                    add(
                        TogetherRow.Note(
                            "about",
                            if (nearby.isEmpty()) {
                                "Searching your Wi-Fi or hotspot for nearby sessions… Host a session or join one to listen in sync."
                            } else {
                                "Select a nearby session to enter its 4-digit code, or start your own."
                            },
                        ),
                    )
                }

                is TogetherState.Hosting -> {
                    add(TogetherRow.Info("code", "Session code", currentState.code.chunked(1).joinToString(" ")))
                    add(TogetherRow.Info("address", "Address", currentState.address))
                    if (snapshot.item != null) {
                        add(TogetherRow.NowPlayingHost)
                    }
                    add(TogetherRow.ToggleGuestsControl)
                    add(TogetherRow.FindSongs)
                    add(TogetherRow.Header("Listeners (${currentState.guests.size})"))
                    if (currentState.guests.isEmpty()) {
                        add(TogetherRow.Note("waiting", "Friends on the same Wi-Fi can pick your Podium and enter code ${currentState.code}."))
                    } else {
                        currentState.guests.forEach { guest ->
                            add(TogetherRow.Info("guest:${guest.id}", guest.name, "Connected"))
                        }
                    }
                    add(TogetherRow.EndSession)
                }

                is TogetherState.Guest -> {
                    add(
                        TogetherRow.Info(
                            "host",
                            currentState.hostName,
                            "${currentState.peerCount} listening",
                        ),
                    )
                    currentState.hostSong?.let { track ->
                        add(TogetherRow.GuestNowPlaying(track, currentState.hostPlaying))
                    } ?: add(TogetherRow.Note("idle-host", "${currentState.hostName} hasn't started a song yet."))

                    if (!currentState.inSync && currentState.listenHere) {
                        add(TogetherRow.Resync)
                    }
                    add(TogetherRow.ToggleListenHere)
                    if (currentState.canControl) {
                        add(TogetherRow.HostPlayPause)
                        add(TogetherRow.HostNext)
                        add(TogetherRow.HostPrevious)
                    }
                    add(TogetherRow.FindSongs)
                    if (currentState.hostQueue.isNotEmpty()) {
                        add(TogetherRow.Header("Up next (${currentState.hostQueue.size})"))
                        currentState.hostQueue.forEachIndexed { idx, track ->
                            add(TogetherRow.QueueSong(idx, track))
                        }
                    }
                    add(TogetherRow.LeaveSession)
                }

                is TogetherState.Joining -> {}
            }
        }
    }

    val focusKey = when (currentState) {
        TogetherState.Idle -> "listen-together:idle"
        is TogetherState.Hosting -> "listen-together:hosting"
        is TogetherState.Guest -> "listen-together:guest"
        is TogetherState.Joining -> "listen-together:joining"
    }
    val focus = rememberFocusListState(focusKey)

    val activate: (Int) -> Unit = { index ->
        when (val row = rows.getOrNull(index)) {
            TogetherRow.StartHosting -> {
                haptics.confirm()
                together.startHosting()
            }
            is TogetherRow.Nearby -> {
                haptics.navigate(forward = true)
                onJoinSession(row.session.host, row.session.port, row.session.displayName)
            }
            TogetherRow.JoinByAddress -> {
                haptics.navigate(forward = true)
                onJoinByAddress()
            }
            TogetherRow.NowPlayingHost -> {
                haptics.navigate(forward = true)
                onOpenNowPlaying()
            }
            TogetherRow.ToggleGuestsControl -> {
                val h = currentState as? TogetherState.Hosting
                if (h != null) {
                    haptics.toggle(on = !h.guestsControl)
                    together.setGuestsControl(!h.guestsControl)
                }
            }
            TogetherRow.FindSongs -> {
                haptics.navigate(forward = true)
                onFindSongs()
            }
            TogetherRow.EndSession -> {
                val h = currentState as? TogetherState.Hosting
                if (h != null && h.guests.isNotEmpty()) {
                    overlay.show(
                        MenuSpec(
                            "End session for ${h.guests.size} listener${if (h.guests.size == 1) "" else "s"}?",
                            listOf(
                                MenuAction("End session") { together.stop() },
                                MenuAction("Keep hosting") {},
                            ),
                        ),
                    )
                } else {
                    haptics.confirm()
                    together.stop()
                }
            }
            is TogetherRow.GuestNowPlaying -> {
                val g = currentState as? TogetherState.Guest
                if (g != null && !g.inSync && g.listenHere) {
                    haptics.confirm()
                    together.resync()
                } else {
                    haptics.navigate(forward = true)
                    onOpenNowPlaying()
                }
            }
            TogetherRow.Resync -> {
                haptics.confirm()
                together.resync()
            }
            TogetherRow.ToggleListenHere -> {
                val g = currentState as? TogetherState.Guest
                if (g != null) {
                    haptics.toggle(on = !g.listenHere)
                    together.setListenHere(!g.listenHere)
                }
            }
            TogetherRow.HostPlayPause -> {
                val g = currentState as? TogetherState.Guest
                if (g != null) {
                    haptics.confirm()
                    together.control(if (g.hostPlaying) "pause" else "play")
                }
            }
            TogetherRow.HostNext -> {
                haptics.confirm()
                together.control("next")
            }
            TogetherRow.HostPrevious -> {
                haptics.confirm()
                together.control("previous")
            }
            is TogetherRow.QueueSong -> {
                overlay.show(
                    MenuSpec(
                        row.track.title,
                        listOf(
                            MenuAction("Play next in session") { together.suggest(row.track, next = true) },
                            MenuAction("Add again to end of queue") { together.suggest(row.track, next = false) },
                        ),
                    ),
                )
            }
            TogetherRow.LeaveSession -> {
                haptics.confirm()
                together.stop()
            }
            is TogetherRow.Info, is TogetherRow.Note, is TogetherRow.Header, null -> {
                haptics.reject()
            }
        }
    }

    ListInputEffect(focus, onActivate = activate)

    FocusList(
        items = rows,
        state = focus,
        key = { it.key },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        focusable = { it.focusable },
        preview = { row ->
            when (row) {
                is TogetherRow.GuestNowPlaying -> row.track.artwork?.uri?.let { MenuPreview.Artwork(listOf(it)) } ?: MenuPreview.Instrument
                is TogetherRow.QueueSong -> row.track.artwork?.uri?.let { MenuPreview.Artwork(listOf(it)) } ?: MenuPreview.Instrument
                TogetherRow.NowPlayingHost -> snapshot.item?.artworkUri?.let { MenuPreview.Artwork(listOf(it)) } ?: MenuPreview.Instrument
                else -> MenuPreview.Instrument
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            TogetherRow.StartHosting -> MenuRow(
                label = "Start a session",
                focused = focused,
                leading = PodiumSymbol.Headphones,
            )
            is TogetherRow.Nearby -> MenuRow(
                label = row.session.displayName,
                focused = focused,
                value = "Join",
                leading = PodiumSymbol.Person,
            )
            TogetherRow.JoinByAddress -> MenuRow(
                label = "Join with an address",
                focused = focused,
            )
            is TogetherRow.Header -> SectionHeader(row.title)
            is TogetherRow.Info -> MenuRow(
                label = row.label,
                focused = focused,
                value = row.value,
                showChevron = false,
            )
            is TogetherRow.Note -> MenuRow(
                label = row.text,
                focused = focused,
                enabled = false,
                showChevron = false,
            )
            TogetherRow.NowPlayingHost -> {
                val item = snapshot.item
                if (item != null) {
                    val isPlaying = snapshot.status is PlaybackStatus.Playing || snapshot.intent == PlayIntent.PLAY
                    TrackRow(
                        title = item.title,
                        subtitle = "Now playing · ${item.artistDisplay}",
                        focused = focused,
                        artworkUri = item.artworkUri,
                        trailing = formatDuration(item.durationMs),
                        active = isPlaying,
                    )
                }
            }
            TogetherRow.ToggleGuestsControl -> {
                val h = currentState as? TogetherState.Hosting
                MenuRow(
                    label = "Guests can control",
                    focused = focused,
                    value = if (h?.guestsControl == true) "On" else "Off",
                    showChevron = false,
                )
            }
            TogetherRow.FindSongs -> MenuRow(
                label = if (currentState is TogetherState.Guest) "Add songs to session" else "Find songs to play",
                focused = focused,
                leading = PodiumSymbol.Search,
            )
            TogetherRow.EndSession -> MenuRow(
                label = "End session",
                focused = focused,
                showChevron = false,
            )
            is TogetherRow.GuestNowPlaying -> TrackRow(
                title = row.track.title,
                subtitle = row.track.artistDisplay,
                focused = focused,
                artworkUri = row.track.artwork?.uri,
                trailing = formatDuration(row.track.durationMs),
                active = row.playing,
            )
            TogetherRow.Resync -> MenuRow(
                label = "Resync with host",
                focused = focused,
                value = "Out of step",
                showChevron = false,
            )
            TogetherRow.ToggleListenHere -> {
                val g = currentState as? TogetherState.Guest
                MenuRow(
                    label = "Play on this Podium",
                    focused = focused,
                    value = if (g?.listenHere == true) "On" else "Remote only",
                    showChevron = false,
                )
            }
            TogetherRow.HostPlayPause -> {
                val g = currentState as? TogetherState.Guest
                MenuRow(
                    label = if (g?.hostPlaying == true) "Pause session" else "Resume session",
                    focused = focused,
                    showChevron = false,
                )
            }
            TogetherRow.HostNext -> MenuRow(
                label = "Skip to next song",
                focused = focused,
                showChevron = false,
            )
            TogetherRow.HostPrevious -> MenuRow(
                label = "Previous song",
                focused = focused,
                showChevron = false,
            )
            is TogetherRow.QueueSong -> TrackRow(
                title = row.track.title,
                subtitle = row.track.artistDisplay,
                focused = focused,
                artworkUri = row.track.artwork?.uri,
                trailing = formatDuration(row.track.durationMs),
            )
            TogetherRow.LeaveSession -> MenuRow(
                label = "Leave session",
                focused = focused,
                showChevron = false,
            )
        }
    }
}

/**
 * Classic iPod 4-digit combination-lock tumbler screen (`0..9` per wheel).
 * - Rotate the Click Wheel to spin the active digit (`0..9`).
 * - Press `Center` to lock the digit and move to the next slot; on the 4th slot, `Center` joins the session.
 * - Press `Previous` / `Next` on the Click Wheel to step between the 4 digit slots.
 */
@Composable
fun JoinTogetherCodeScreen(
    host: String,
    port: Int,
    hostName: String,
    together: TogetherSession,
    onJoined: () -> Unit,
) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val insets = LocalScreenInsets.current
    val haptics = rememberPodiumHaptics()

    val digits = remember { mutableStateListOf(0, 0, 0, 0) }
    var activeSlot by remember { mutableIntStateOf(0) }

    val submitCode = {
        val code = digits.joinToString("")
        haptics.confirm()
        together.join(host = host, port = port, code = code, displayName = hostName)
        onJoined()
    }

    InputTargetEffect(WheelContext.LIST_FOCUS) { input ->
        when (input) {
            is PodiumInput.Rotate -> {
                if (input.detents != 0) {
                    val cur = digits[activeSlot]
                    val next = ((cur + input.detents) % 10 + 10) % 10
                    digits[activeSlot] = next
                    haptics.step(level = 0.5f)
                }
                true
            }
            is PodiumInput.Press -> when (input.button) {
                WheelButton.CENTER -> {
                    if (activeSlot < 3) {
                        activeSlot++
                        haptics.confirm()
                    } else {
                        submitCode()
                    }
                    true
                }
                WheelButton.NEXT -> {
                    if (activeSlot < 3) {
                        activeSlot++
                        haptics.step(level = 0.4f)
                    } else {
                        haptics.boundary()
                    }
                    true
                }
                WheelButton.PREVIOUS -> {
                    if (activeSlot > 0) {
                        activeSlot--
                        haptics.step(level = 0.4f)
                    } else {
                        haptics.boundary()
                    }
                    true
                }
                else -> false
            }
            else -> false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = insets.top + Spacing.s, bottom = insets.bottom)
            .padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PodiumText(
                text = "Enter the 4-digit code shown on $hostName",
                style = type.footnote,
                color = colors.labelSecondary,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.m))

            // 4-digit combination tumbler row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (slot in 0 until 4) {
                    val focused = slot == activeSlot
                    val value = digits[slot]
                    val prevDigit = (value + 9) % 10
                    val nextDigit = (value + 1) % 10

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(108.dp)
                            .background(
                                if (focused) colors.highlight.copy(alpha = 0.16f) else colors.canvasRaised,
                                ContinuousShape(14.dp),
                            )
                            .border(
                                width = if (focused) 2.dp else 1.dp,
                                color = if (focused) colors.highlight else colors.separator,
                                shape = ContinuousShape(14.dp),
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (activeSlot == slot) {
                                    digits[slot] = (digits[slot] + 1) % 10
                                    haptics.step(level = 0.5f)
                                } else {
                                    activeSlot = slot
                                    haptics.confirm()
                                }
                            }
                            .padding(vertical = Spacing.xs),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PodiumText(
                            text = prevDigit.toString(),
                            style = type.caption,
                            color = colors.labelTertiary.copy(alpha = if (focused) 0.75f else 0.35f),
                        )
                        PodiumText(
                            text = value.toString(),
                            style = type.largeTitle,
                            color = if (focused) colors.highlightText else colors.labelPrimary,
                        )
                        PodiumText(
                            text = nextDigit.toString(),
                            style = type.caption,
                            color = colors.labelTertiary.copy(alpha = if (focused) 0.75f else 0.35f),
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PodiumText(
                text = if (activeSlot < 3) {
                    "Turn the wheel for digit ${activeSlot + 1} · Press Center for next"
                } else {
                    "Turn the wheel for digit 4 · Press Center to join"
                },
                style = type.caption,
                color = colors.labelTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.height(Spacing.s))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(colors.highlight, ContinuousShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (activeSlot < 3) {
                            activeSlot++
                            haptics.confirm()
                        } else {
                            submitCode()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                PodiumText(
                    text = if (activeSlot < 3) "Next digit" else "Join session",
                    style = type.rowFocused,
                    color = colors.onHighlight,
                )
            }
        }
    }
}

/**
 * Manual IP address entry screen using [PodiumTextField] (supports both Podium's WheelKeyboard
 * and the phone's keyboard + clipboard paste), then advances to [JoinTogetherCodeScreen].
 */
@Composable
fun JoinTogetherAddressScreen(
    onContinueToCode: (host: String, port: Int) -> Unit,
) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val insets = LocalScreenInsets.current
    val haptics = rememberPodiumHaptics()
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    val submit = {
        val parsed = parseHostPort(text)
        if (parsed == null) {
            problem = "Enter an IP address like 192.168.1.42 or 192.168.1.42:47312"
            haptics.reject()
        } else {
            problem = null
            haptics.confirm()
            focusManager.clearFocus()
            onContinueToCode(parsed.first, parsed.second)
        }
    }

    val paste: () -> Unit = {
        scope.launch {
            val pasted = runCatching {
                clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            }.getOrNull()
            if (pasted.isNullOrBlank()) {
                haptics.reject()
            } else {
                text = pasted.trim().take(64)
                problem = null
                haptics.confirm()
            }
        }
    }

    val miniature = LocalMiniature.current
    if (!miniature) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }

    InputTargetEffect(WheelContext.VOLUME) { input ->
        when {
            input is PodiumInput.Press && input.button == WheelButton.CENTER -> {
                submit()
                true
            }
            else -> false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = insets.top + Spacing.s, bottom = insets.bottom)
            .padding(horizontal = Spacing.xl),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(colors.canvasRaised, ContinuousShape(14.dp))
                .border(1.dp, if (problem != null) colors.critical else colors.separator, ContinuousShape(14.dp))
                .padding(horizontal = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    PodiumText("192.168.1.x", type.body, colors.labelTertiary, maxLines = 1)
                }
                PodiumTextField(
                    value = text,
                    onValueChange = {
                        text = it.take(64)
                        problem = null
                    },
                    textStyle = type.body.copy(color = colors.labelPrimary),
                    description = "Host IP address",
                    focusRequester = focusRequester,
                    action = KeyboardAction.DONE,
                    onAction = submit,
                    maxLength = 64,
                    accept = { !it.isISOControl() },
                    phoneOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    cursorColor = colors.highlightText,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(Spacing.m))
        PodiumText(
            text = problem ?: "Enter the IP address shown on the host's Listen Together screen.",
            style = type.footnote,
            color = if (problem != null) colors.critical else colors.labelSecondary,
            maxLines = 3,
        )
        Spacer(Modifier.height(Spacing.l))
        Row(Modifier.fillMaxWidth()) {
            AddressButton("Paste", primary = false, modifier = Modifier.weight(1f), onClick = paste)
            Spacer(Modifier.width(Spacing.m))
            AddressButton("Enter code", primary = true, modifier = Modifier.weight(2f), onClick = submit)
        }
    }
}

@Composable
private fun AddressButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    Box(
        modifier
            .height(48.dp)
            .background(if (primary) colors.highlight else colors.canvasRaised, ContinuousShape(14.dp))
            .then(if (primary) Modifier else Modifier.border(1.dp, colors.separator, ContinuousShape(14.dp)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PodiumText(label, type.rowFocused, if (primary) colors.onHighlight else colors.labelPrimary)
    }
}

internal fun parseHostPort(raw: String): Pair<String, Int>? {
    val clean = raw.trim()
    if (clean.isEmpty()) return null
    val colonIdx = clean.lastIndexOf(':')
    return if (colonIdx > 0 && colonIdx < clean.lastIndex) {
        val host = clean.substring(0, colonIdx).trim()
        val port = clean.substring(colonIdx + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) null else host to port
    } else {
        clean to TogetherSession.PORT
    }
}
