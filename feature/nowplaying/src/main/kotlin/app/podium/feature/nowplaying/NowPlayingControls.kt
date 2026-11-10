package app.podium.feature.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.State
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.podium.core.designsystem.component.ProgressBar
import app.podium.core.designsystem.component.SquiggleProgress
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.component.pressFeedback
import app.podium.core.designsystem.component.rememberProgressTick
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.DisplayStyle
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.shell.EdgeCorner
import app.podium.core.designsystem.tonal.ContinuousShape
import app.podium.core.designsystem.tonal.LocalAlbumTone
import app.podium.core.designsystem.tonal.SurfaceLevel
import app.podium.core.designsystem.tonal.tonalSurface
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.MediaKind
import app.podium.player.api.PlaybackStatus

/*
 * Now Playing's composition and controls (D-101, D-103), the same on every theme: the cover flat
 * and leading, its facts beside its lower edge; the song's title, artist and album reading down
 * from the cover's edge; the seek bar with the times beneath; the transport as one group of keys;
 * the secondary actions in one tray, a hairline between each, in the Wheel's order. Each theme paints
 * its own material on the same parts: Carbon's solid keys, Glass's clear glass catching the cover's
 * light, the instrument's outlines.
 */

/** The side margin: the display's edge margin (D-103). The cover, the text and the controls line up on it. */
private val Gutter = Spacing.edge

/**
 * Between Now Playing's groups (D-103): the cover, the song, the seek bar, the keys and the tray
 * read the same distance apart, measured between what's drawn (a line of text's ink, the
 * playhead, a key's edge), not between the boxes that hold them.
 */
private val GroupGap = Spacing.l

/**
 * How far the tray has slid away (D-108): 0 while it is up, 1 once it is gone. One spring drives both
 * the tray and the content above it, so they move as one piece.
 */
@Composable
internal fun rememberTrayAway(p: NowPlayingParts): State<Float> {
    val motion = PodiumTheme.motion
    return animateFloatAsState(
        if (p.trayHidden) 1f else 0f,
        if (motion.reduced) tween(0) else spring(dampingRatio = 0.92f, stiffness = 420f),
        label = "trayAway",
    )
}

/**
 * Everything above the tray relaxes down into the space the tray leaves (D-109), by half of it, so it
 * settles in the middle of the taller area instead of dropping to the floor — and draws back up when
 * the tray returns. Read in the draw phase: the whole move costs no recomposition and no relayout, so
 * the cover is never resized frame by frame.
 */
internal fun Modifier.relaxWithTray(away: State<Float>, freed: Dp): Modifier =
    graphicsLayer { translationY = away.value * freed.toPx() / 2f }

/** The tray's own height, and the gap above it: the space it leaves when it goes. */
internal val TrayHeight = 48.dp
internal val TrayHeightCompact = 44.dp

/** Stacked Now Playing: cover and status, then the song, the seek bar, the keys, the tray. */
@Composable
internal fun StackedPortrait(p: NowPlayingParts) {
    val away = rememberTrayAway(p)
    // The screen's insets already hold the edge margin above (under the title bar) and below.
    Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
        Column(Modifier.weight(1f).relaxWithTray(away, GroupGap + TrayHeight)) {
        // The cover as large as the column allows, sitting on the song's title; its facts beside its
        // lower edge, like a caption.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val travel = artTravel(rowWidth = maxWidth, startInset = Gutter)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                BoxWithConstraints(Modifier.weight(0.72f).fillMaxHeight().zIndex(1f), contentAlignment = Alignment.BottomStart) {
                    // Breathing from its lower corner on the gutter, the cover stays lined up with the
                    // text and keeps its distance from the title.
                    ArtworkStage(p, maxWidth, maxHeight, travel, origin = TransformOrigin(0f, 1f))
                }
                Spacer(Modifier.width(Spacing.m))
                StatusColumn(p, Modifier.weight(0.28f).padding(bottom = Spacing.xxs))
            }
        }
        Spacer(Modifier.height(GroupGap - TitleInkInset))
        TrackText(p, Alignment.Start, TextAlign.Start)
        Spacer(Modifier.height(GroupGap - TextInkInset - PlayheadInset))
        NowPlayingProgress(p)
        Spacer(Modifier.height(Spacing.xxs))
        TrackSubtitle(p.item, Alignment.CenterHorizontally, TextAlign.Center)
        Spacer(Modifier.height(GroupGap - TextInkInset))
        NowPlayingTransport(p, Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.height(GroupGap))
        NowPlayingActions(p, away = away)
    }
}

/**
 * Nostalgic Now Playing (D-109): the original's own screen. The cover beside its facts, the song
 * centred beneath it, the seek bar, and — where the transport keys would be — the vinyl's capsule of
 * dots moving with the music. No playback keys at all: the Wheel plays, pauses and skips, as it did on
 * the thing this is modelled on. The tray still slides away with the setting on (D-108), and
 * everything relaxes down into its place.
 */
@Composable
internal fun NostalgicPortrait(p: NowPlayingParts) {
    val away = rememberTrayAway(p)
    // The content keeps the wider margin the screen had before D-103; the tray keeps the display's own
    // edge margin, as every tray does, so it stays concentric with the display's corners.
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.weight(1f).padding(horizontal = NostalgicGutter).relaxWithTray(away, Spacing.s + TrayHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The cover at the left with its facts beside it, both centred in what the rest leaves.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val travel = artTravel(rowWidth = maxWidth, startInset = NostalgicGutter)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    // Wider than the screen gave it before D-103 (0.62): with no transport beneath, the
                    // cover is what the extra room should go to, as it did on the thing this follows.
                    BoxWithConstraints(Modifier.weight(0.70f).fillMaxHeight().zIndex(1f), contentAlignment = Alignment.CenterStart) {
                        ArtworkStage(p, maxWidth, maxHeight, travel)
                    }
                    Spacer(Modifier.width(Spacing.m))
                    StatusColumn(p, Modifier.weight(0.30f))
                }
            }
            Spacer(Modifier.height(Spacing.m))
            TrackText(p, Alignment.CenterHorizontally, TextAlign.Center)
            Spacer(Modifier.height(Spacing.s + Spacing.xxs))
            NowPlayingProgress(p)
            Spacer(Modifier.height(Spacing.xxs))
            TrackSubtitle(p.item, Alignment.CenterHorizontally, TextAlign.Center)
            Spacer(Modifier.height(Spacing.s))
            DotVisualiser(
                p.controller,
                p.pulse,
                playing = p.playing,
                modifier = Modifier.fillMaxWidth(NostalgicVisualiserWidth).height(NostalgicVisualiserHeight).testTag(VisualiserTestTag),
                height = NostalgicVisualiserHeight,
            )
        }
        Spacer(Modifier.height(Spacing.s))
        Box(Modifier.padding(horizontal = Spacing.edge)) { NowPlayingActions(p, away = away) }
    }
}

/** The visualiser standing in for the transport, for the tests that check the nostalgic screen has one. */
internal const val VisualiserTestTag = "nowPlayingVisualiser"

/** The nostalgic layout keeps the wider margin the screen had before D-103 brought everything to the edge. */
private val NostalgicGutter = Spacing.xl

/** The visualiser in the transport's place: the vinyl's own capsule, a little inset from the margins. */
private const val NostalgicVisualiserWidth = 0.88f
private val NostalgicVisualiserHeight = 40.dp

internal enum class ProgressBarStyle(val label: String) {
    WAVEFORM("Waveform bars"),
    WAVE("Squiggle wave"),
    NEON("Glowing neon"),
    RETRO("Retro LCD blocks"),
    DOTS("Dotted matrix"),
    TUNER("Radio tuner dial"),
}

/**
 * The seek bar and the times under it; the volume bar in its place while the Wheel turns the volume;
 * "Loading" while the song first loads. One fixed height, so nothing below ever moves.
 */
@Composable
internal fun NowPlayingProgress(p: NowPlayingParts) {
    val colors = PodiumTheme.colors
    val motion = PodiumTheme.motion
    val controller = p.controller
    val duration = p.item.durationMs ?: 0L
    val live = p.item.kind == MediaKind.LIVE
    val shown = when {
        p.showVolume -> ProgressShows.Volume
        p.snapshot.status == PlaybackStatus.Loading -> ProgressShows.Loading
        else -> ProgressShows.Song
    }
    AnimatedContent(
        targetState = shown,
        transitionSpec = { fadeIn(motion.fadeFast()) togetherWith fadeOut(motion.fadeFast()) },
        modifier = Modifier.fillMaxWidth().height(ProgressBlock),
        contentAlignment = Alignment.Center,
        label = "nowPlayingProgress",
    ) { state ->
        when (state) {
            ProgressShows.Volume -> Row(
                Modifier.fillMaxSize().semantics { contentDescription = "Volume" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(PodiumSymbol.VolumeDown, colors.labelSecondary, size = 18.dp, weight = 600)
                ProgressBar({ p.volumeFraction }, running = false, modifier = Modifier.weight(1f).padding(horizontal = Spacing.s))
                Symbol(PodiumSymbol.VolumeUp, colors.labelSecondary, size = 18.dp, weight = 600)
            }
            ProgressShows.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingBar() }
            ProgressShows.Song -> Column(Modifier.fillMaxSize()) {
                val position = {
                    when {
                        live -> 1f
                        duration > 0 -> (p.scrubTarget ?: controller.positionMs()).toFloat() / duration
                        else -> 0f
                    }
                }
                val seekable = p.snapshot.controls.seek && duration > 0 && !live
                val playing = p.snapshot.status == PlaybackStatus.Playing
                val onSeek: (Float) -> Unit = { f -> controller.seekTo((f * duration).toLong().coerceIn(0, duration)) }
                when (p.progressStyle) {
                    ProgressBarStyle.WAVEFORM -> WaveformProgress(
                        progress = position,
                        playing = playing,
                        live = live,
                        seekable = seekable,
                        emphasized = p.mode == WheelMode.Scrub,
                        seed = p.item.uid.value.hashCode(),
                        onSeek = onSeek,
                    )
                    ProgressBarStyle.WAVE -> if (colors.style == DisplayStyle.INDUSTRIAL) {
                        SeekRule(p, position, seekable, duration)
                    } else {
                        SquiggleProgress(
                            progress = position,
                            playing = playing,
                            seekable = seekable,
                            emphasized = p.mode == WheelMode.Scrub,
                            onSeek = onSeek,
                        )
                    }
                    ProgressBarStyle.NEON -> NeonProgress(
                        progress = position,
                        playing = playing,
                        seekable = seekable,
                        emphasized = p.mode == WheelMode.Scrub,
                        onSeek = onSeek,
                    )
                    ProgressBarStyle.RETRO -> RetroProgress(
                        progress = position,
                        seekable = seekable,
                        emphasized = p.mode == WheelMode.Scrub,
                        onSeek = onSeek,
                    )
                    ProgressBarStyle.DOTS -> DotsProgress(
                        progress = position,
                        playing = playing,
                        seekable = seekable,
                        emphasized = p.mode == WheelMode.Scrub,
                        onSeek = onSeek,
                    )
                    ProgressBarStyle.TUNER -> TunerProgress(
                        progress = position,
                        seekable = seekable,
                        emphasized = p.mode == WheelMode.Scrub,
                        onSeek = onSeek,
                    )
                }
                NowPlayingTimes(p)
            }
        }
    }
}

/** Theme 1: SoundCloud-style vertical audio waveform bars that fill with song progress and dance while playing. */
@Composable
private fun WaveformProgress(
    progress: () -> Float,
    playing: Boolean,
    live: Boolean,
    seekable: Boolean,
    emphasized: Boolean,
    seed: Int,
    onSeek: (Float) -> Unit,
) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val activeColor = if (colors.isTonal) tone.accent else colors.labelPrimary
    val inactiveColor = colors.labelTertiary.copy(alpha = 0.34f)
    val barCount = 42
    val baseHeights = remember(seed) {
        FloatArray(barCount) { i ->
            val h1 = sin(i * 0.55f + (seed and 0xFF) * 0.1f) * 0.32f
            val h2 = sin(i * 1.35f + ((seed shr 8) and 0xFF) * 0.17f) * 0.22f
            val envelope = sin((i.toFloat() / (barCount - 1)) * Math.PI).toFloat() * 0.25f + 0.75f
            ((0.46f + h1 + h2) * envelope).coerceIn(0.20f, 0.96f)
        }
    }
    val tick = rememberProgressTick(playing, hz = 20)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(if (!seekable) Modifier else Modifier.pointerSeek(onSeek))
            .semantics { contentDescription = "Song position" },
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        val p = progress().coerceIn(0f, 1f)
        val timeSec = if (playing) (System.nanoTime() / 1_000_000_000.0).toFloat() else 0f
        val gap = 2.5.dp.toPx()
        val totalGap = gap * (barCount - 1)
        val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(2.dp.toPx())
        val maxBarH = if (emphasized) size.height * 0.92f else size.height * 0.78f
        val centerY = size.height / 2f
        val playheadIndex = (p * (barCount - 1)).toInt()

        for (i in 0 until barCount) {
            val frac = i.toFloat() / (barCount - 1)
            val ripple = if (playing) {
                (sin(timeSec * 5.2f + i * 0.45f) * 0.14f + sin(timeSec * 8.3f - i * 0.7f) * 0.08f)
            } else 0f
            val isPlayhead = !live && i == playheadIndex
            val hFraction = (baseHeights[i] + ripple + if (isPlayhead) 0.18f else 0f).coerceIn(0.18f, 1f)
            val barH = (maxBarH * hFraction).coerceAtLeast(4.dp.toPx())
            val x = i * (barWidth + gap)
            val color = when {
                live -> {
                    val wave = if (playing) (sin(timeSec * 3.6f - i * 0.28f) * 0.25f + 0.75f) else 0.75f
                    activeColor.copy(alpha = wave.coerceIn(0.45f, 1f))
                }
                isPlayhead -> activeColor
                frac <= p -> activeColor.copy(alpha = 0.90f)
                else -> inactiveColor
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - barH / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** Theme 3: Glowing neon laser bar with a pulsing orb playhead. */
@Composable
private fun NeonProgress(
    progress: () -> Float,
    playing: Boolean,
    seekable: Boolean,
    emphasized: Boolean,
    onSeek: (Float) -> Unit,
) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val neon = if (colors.isTonal) tone.accent else colors.highlight
    val trackBg = colors.labelTertiary.copy(alpha = 0.25f)
    val tick = rememberProgressTick(playing, hz = 16)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(if (!seekable) Modifier else Modifier.pointerSeek(onSeek))
            .semantics { contentDescription = "Song position" },
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        val p = progress().coerceIn(0f, 1f)
        val cy = size.height / 2f
        val px = p * size.width
        val coreH = if (emphasized) 4.dp.toPx() else 3.dp.toPx()
        // Track background
        drawRoundRect(trackBg, Offset(0f, cy - coreH / 2f), Size(size.width, coreH), CornerRadius(coreH))
        // Outer & inner neon glow
        drawRoundRect(neon.copy(alpha = 0.22f), Offset(0f, cy - coreH * 2.2f), Size(px, coreH * 4.4f), CornerRadius(coreH * 2.2f))
        drawRoundRect(neon.copy(alpha = 0.48f), Offset(0f, cy - coreH * 1.2f), Size(px, coreH * 2.4f), CornerRadius(coreH * 1.2f))
        drawRoundRect(neon, Offset(0f, cy - coreH / 2f), Size(px, coreH), CornerRadius(coreH))
        // Glowing orb playhead
        val pulse = if (playing) (sin((System.nanoTime() / 1_000_000_000.0) * 5.0).toFloat() * 0.15f + 1f) else 1f
        val r = (if (emphasized) 7.dp.toPx() else 5.5.dp.toPx()) * pulse
        drawCircle(neon.copy(alpha = 0.30f), radius = r * 2.1f, center = Offset(px.coerceIn(r, size.width - r), cy))
        drawCircle(neon, radius = r, center = Offset(px.coerceIn(r, size.width - r), cy))
        drawCircle(Color.White.copy(alpha = 0.85f), radius = r * 0.42f, center = Offset(px.coerceIn(r, size.width - r), cy))
    }
}

/** Theme 4: Retro iPod / LCD segmented blocks inside a classic framed capsule. */
@Composable
private fun RetroProgress(
    progress: () -> Float,
    seekable: Boolean,
    emphasized: Boolean,
    onSeek: (Float) -> Unit,
) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val fill = if (colors.isTonal) tone.accent else colors.labelPrimary
    val empty = colors.labelTertiary.copy(alpha = 0.22f)
    val borderCol = colors.labelSecondary.copy(alpha = 0.55f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(if (!seekable) Modifier else Modifier.pointerSeek(onSeek))
            .semantics { contentDescription = "Song position" },
    ) {
        val p = progress().coerceIn(0f, 1f)
        val boxH = if (emphasized) 14.dp.toPx() else 12.dp.toPx()
        val top = (size.height - boxH) / 2f
        val cr = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        drawRoundRect(borderCol, Offset(0f, top), Size(size.width, boxH), cr, style = Stroke(1.2.dp.toPx()))
        val pad = 3.dp.toPx()
        val innerW = size.width - pad * 2
        val innerH = boxH - pad * 2
        val segments = 24
        val gap = 2.dp.toPx()
        val segW = (innerW - gap * (segments - 1)) / segments
        val litCount = (p * segments).toInt()
        for (i in 0 until segments) {
            val x = pad + i * (segW + gap)
            drawRoundRect(
                color = if (i <= litCount && p > 0f) fill else empty,
                topLeft = Offset(x, top + pad),
                size = Size(segW, innerH),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
            )
        }
    }
}

/** Theme 5: Nothing OS-inspired dotted matrix track. */
@Composable
private fun DotsProgress(
    progress: () -> Float,
    playing: Boolean,
    seekable: Boolean,
    emphasized: Boolean,
    onSeek: (Float) -> Unit,
) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val active = if (colors.isTonal) tone.accent else colors.labelPrimary
    val inactive = colors.labelTertiary.copy(alpha = 0.30f)
    val tick = rememberProgressTick(playing, hz = 12)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(if (!seekable) Modifier else Modifier.pointerSeek(onSeek))
            .semantics { contentDescription = "Song position" },
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        val p = progress().coerceIn(0f, 1f)
        val dots = 30
        val cy = size.height / 2f
        val step = size.width / (dots - 1)
        val playheadIdx = (p * (dots - 1)).toInt()
        for (i in 0 until dots) {
            val cx = i * step
            val isHead = i == playheadIdx
            val r = when {
                isHead -> if (emphasized) 5.5.dp.toPx() else 4.5.dp.toPx()
                i < playheadIdx -> 2.4.dp.toPx()
                else -> 1.8.dp.toPx()
            }
            val col = if (i <= playheadIdx) active else inactive
            if (isHead) {
                drawCircle(active.copy(alpha = 0.28f), radius = r * 1.8f, center = Offset(cx, cy))
            }
            drawCircle(col, radius = r, center = Offset(cx, cy))
        }
    }
}

/** Theme 6: Analog radio tuner scale with major/minor ticks and a glowing tuner needle. */
@Composable
private fun TunerProgress(
    progress: () -> Float,
    seekable: Boolean,
    emphasized: Boolean,
    onSeek: (Float) -> Unit,
) {
    val colors = PodiumTheme.colors
    val tickMajor = colors.labelSecondary.copy(alpha = 0.70f)
    val tickMinor = colors.labelTertiary.copy(alpha = 0.38f)
    val needle = Color(0xFFFF5238)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(if (!seekable) Modifier else Modifier.pointerSeek(onSeek))
            .semantics { contentDescription = "Song position" },
    ) {
        val p = progress().coerceIn(0f, 1f)
        val cy = size.height / 2f
        val ticks = 40
        val step = size.width / ticks
        // Center baseline
        drawLine(tickMinor, Offset(0f, cy), Offset(size.width, cy), 1.dp.toPx())
        for (i in 0..ticks) {
            val x = i * step
            val major = i % 5 == 0
            val halfH = if (major) 6.dp.toPx() else 3.dp.toPx()
            drawLine(
                color = if (major) tickMajor else tickMinor,
                start = Offset(x, cy - halfH),
                end = Offset(x, cy + halfH),
                strokeWidth = if (major) 1.4.dp.toPx() else 1.dp.toPx(),
            )
        }
        val nx = (p * size.width).coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
        val nh = if (emphasized) 12.dp.toPx() else 10.dp.toPx()
        drawRoundRect(
            color = needle.copy(alpha = 0.30f),
            topLeft = Offset(nx - 3.dp.toPx(), cy - nh),
            size = Size(6.dp.toPx(), nh * 2),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
        drawRoundRect(
            color = needle,
            topLeft = Offset(nx - 1.3.dp.toPx(), cy - nh),
            size = Size(2.6.dp.toPx(), nh * 2),
            cornerRadius = CornerRadius(1.3.dp.toPx(), 1.3.dp.toPx()),
        )
    }
}

/** The instrument's flat rule, tapped or dragged to seek. */
@Composable
private fun SeekRule(p: NowPlayingParts, position: () -> Float, seekable: Boolean, duration: Long) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(
                if (!seekable) Modifier else Modifier.pointerSeek { f -> p.controller.seekTo((f * duration).toLong().coerceIn(0, duration)) },
            )
            .semantics { contentDescription = "Song position" },
        contentAlignment = Alignment.Center,
    ) {
        ProgressBar(position, running = p.snapshot.status == PlaybackStatus.Playing, emphasized = p.mode == WheelMode.Scrub)
    }
}

/** Tap or drag along it to seek to that fraction of the song. */
@Composable
private fun Modifier.pointerSeek(seek: (Float) -> Unit): Modifier {
    val current by rememberUpdatedState(seek)
    return pointerInput(Unit) { detectTapGestures { o -> current((o.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> current((change.position.x / size.width).coerceIn(0f, 1f)) } }
}

private enum class ProgressShows { Song, Volume, Loading }

/** The seek bar's block: the bar's 32 dp and the times' 16 dp line, nothing else. */
private val ProgressBlock = 48.dp

/** From the top of the title's line to its ink (the cap height's top). */
private val TitleInkInset = 6.dp

/** From the ink of a line of text to the bottom of its line (the baseline's descent). */
private val TextInkInset = 4.dp

/** From the top of the seek bar's 32 dp to its playhead's top. */
private val PlayheadInset = 7.dp

/** Elapsed at the left, time left at the right, under the bar's ends. Its own scope: it ticks, nothing else does. */
@Composable
private fun NowPlayingTimes(p: NowPlayingParts) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val duration = p.item.durationMs ?: 0L
    val tick = rememberProgressTick(p.snapshot.status == PlaybackStatus.Playing, hz = 4)
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        @Suppress("UNUSED_EXPRESSION") tick
        val position = p.scrubTarget ?: p.controller.positionMs()
        val style = type.caption.copy(color = colors.labelSecondary)
        BasicText(formatDuration(position) ?: "0:00", style = style)
        Spacer(Modifier.weight(1f))
        BasicText(
            // A live station has no end: it says so where the time left would be (D-90).
            if (p.item.kind == MediaKind.LIVE) "Live" else "−" + (formatDuration((duration - position).coerceAtLeast(0)) ?: "0:00"),
            style = style.copy(textAlign = TextAlign.End),
        )
    }
}

/**
 * The transport: previous and next as two keys with the orb between them (D-107), their outer corners
 * round and their inner corners curving away from it. The orb is the play key, and it carries the
 * state in its shape instead of a glyph: round while the music plays, a settled pebble when it is
 * stopped. On Glass the keys are clear glass, lit by the cover; on Carbon they are solid, and the orb
 * is ivory. [compact] for short screens.
 */
@Composable
internal fun NowPlayingTransport(p: NowPlayingParts, modifier: Modifier = Modifier, compact: Boolean = false) {
    val orb = if (compact) OrbCompact else OrbDiameter
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val scale = (maxWidth / orb).coerceAtMost(1f)
        OrbKey(p, orb * scale)
    }
}

/** A key's housing in the display's material. The orb beside them brings its own (D-107). */
@Composable
private fun Modifier.keyHousing(shape: Shape, pressed: () -> Boolean): Modifier {
    val colors = PodiumTheme.colors
    return when (colors.style) {
        DisplayStyle.TONAL -> tonalSurface(shape, SurfaceLevel.High, pressed = pressed)
        DisplayStyle.GLASS -> glass(GlassMaterial.Control, shape, pressed = pressed())
        DisplayStyle.INDUSTRIAL -> border(1.dp, colors.separator, shape)
            .background(if (pressed()) colors.labelPrimary.copy(alpha = 0.08f) else Color.Transparent, shape)
    }
}

@Composable
private fun keyShape(start: Dp, end: Dp): Shape =
    if (PodiumTheme.colors.style == DisplayStyle.INDUSTRIAL) RoundedCornerShape(3.dp) else ContinuousShape(start, end, end, start)

@Composable
private fun TransportKey(
    symbol: PodiumSymbol,
    label: String,
    enabled: Boolean,
    leading: Boolean,
    width: Dp,
    height: Dp,
    touched: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = PodiumTheme.colors
    val haptics = rememberPodiumHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(width, height)
            .pressFeedback(0.96f)
            .keyHousing(if (leading) keyShape(KeyOuter, KeyInner) else keyShape(KeyInner, KeyOuter), pressed = { pressed })
            .clickable(interactionSource = interaction, indication = null) {
                touched()
                if (enabled) haptics.skip(forward = !leading) else haptics.reject()
                onClick()
            }
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, if (enabled) colors.labelPrimary else colors.labelTertiary, size = 26.dp, weight = 700, filled = true)
    }
}

private val KeyGap = 10.dp
private val KeyOuter = 26.dp

/** The corner facing the orb: it curves away from the round thing between the keys (D-107). */
private val KeyInner = 20.dp
private val SideKeyWidth = 72.dp

/** The orb stands taller than the keys either side of it: it is the one thing in the group that is alive. */
private val OrbDiameter = 72.dp
private val OrbCompact = 60.dp

/**
 * The secondary actions in one tray, in the Wheel's order (shuffle, repeat, favourite, lyrics,
 * vinyl, Up Next, more), a hairline between each. An action that's on sits on a small backing in the
 * accent; in the Wheel's action mode a key slides beneath the focused action.
 */
@Composable
internal fun NowPlayingActions(p: NowPlayingParts, compact: Boolean = false, away: State<Float>) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val motion = PodiumTheme.motion
    val height = if (compact) TrayHeightCompact else TrayHeight
    val lensVisible = p.mode == WheelMode.Actions && !p.trayHidden
    // Hidden (D-108), it slides down off the display and fades while the rest of the screen relaxes
    // down behind it (D-109). Read while drawing: the slide costs no recomposition, and once it is out
    // its own layer takes it out of reach of a touch.
    val style = colors.style
    // At the display's foot, the edge margin in: concentric with its corners (D-103).
    val tray = if (style == DisplayStyle.INDUSTRIAL) RoundedCornerShape(2.dp) else ContinuousShape(EdgeCorner)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                val gone = away.value
                translationY = gone * (size.height + TrayExit.toPx())
                alpha = 1f - gone
            }
            .then(
                when (style) {
                    DisplayStyle.TONAL -> Modifier.tonalSurface(tray, SurfaceLevel.Low)
                    DisplayStyle.GLASS -> Modifier.glass(GlassMaterial.Control, tray)
                    DisplayStyle.INDUSTRIAL -> Modifier.border(1.dp, colors.separator, tray)
                },
            ),
    ) {
        val pad = TrayPadding
        val slot = (maxWidth - pad * 2) / Action.entries.size
        val slotPx = with(LocalDensity.current) { slot.toPx() }
        // The focused action's key, as a slot position, gliding on the lens's own spring.
        val keyAt = remember { Animatable(p.actionFocus.toFloat()) }
        LaunchedEffect(p.actionFocus) {
            if (motion.reduced) keyAt.snapTo(p.actionFocus.toFloat())
            else keyAt.animateTo(p.actionFocus.toFloat(), spring(dampingRatio = 0.86f, stiffness = 1_400f))
        }
        val padPx = with(LocalDensity.current) { pad.toPx() }
        Box(
            Modifier.fillMaxSize().drawBehind {
                // Hairlines between the actions.
                if (style != DisplayStyle.INDUSTRIAL) {
                    val line = if (style == DisplayStyle.GLASS) Color.White.copy(alpha = if (colors.isDark) 0.18f else 0.45f) else colors.labelPrimary.copy(alpha = 0.12f)
                    val inset = size.height * 0.3f
                    for (i in 1 until Action.entries.size) {
                        val x = padPx + slotPx * i
                        drawLine(line, Offset(x, inset), Offset(x, size.height - inset), 1.dp.toPx())
                    }
                } else {
                    for (i in 1 until Action.entries.size) {
                        val x = padPx + slotPx * i
                        drawLine(colors.separator, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                }
                if (!lensVisible) return@drawBehind
                // The key fills its slot and keeps the tray's padding from the tray's edges on every
                // side, its corners concentric with the tray's (D-103).
                val l = padPx + slotPx * keyAt.value
                val r = l + slotPx
                val inset = padPx
                val h = size.height - inset * 2
                val cr = CornerRadius((EdgeCorner - TrayPadding).toPx())
                when (style) {
                    DisplayStyle.TONAL -> {
                        drawRoundRect(Color.Black.copy(alpha = 0.3f), Offset(l, inset + 1.5.dp.toPx()), Size(r - l, h), cr)
                        drawRoundRect(tone.surfaceHighest, Offset(l, inset), Size(r - l, h), cr)
                    }
                    DisplayStyle.GLASS -> {
                        // A pane of stained glass.
                        drawRoundRect(colors.highlight.copy(alpha = if (colors.isDark) 0.42f else 0.24f), Offset(l, inset), Size(r - l, h), cr)
                        drawRoundRect(
                            Brush.verticalGradient(listOf(Color.White.copy(alpha = if (colors.isDark) 0.35f else 0.7f), Color.Transparent), startY = inset, endY = inset + h),
                            Offset(l, inset),
                            Size(r - l, h),
                            cr,
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                    DisplayStyle.INDUSTRIAL -> drawRect(colors.labelPrimary, Offset((l + r) / 2f - 7.dp.toPx(), size.height - 6.dp.toPx()), Size(14.dp.toPx(), 2.dp.toPx()))
                }
            },
        )
        Row(Modifier.fillMaxSize().padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically) {
            Action.entries.forEachIndexed { i, action ->
                val (symbol, label, on) = actionLook(p, action)
                val focused = lensVisible && i == p.actionFocus
                val unsupported = (action == Action.Shuffle && !p.snapshot.controls.shuffle) || (action == Action.Repeat && !p.snapshot.controls.repeat)
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (on == true && style == DisplayStyle.TONAL) {
                        Box(Modifier.size(40.dp, 30.dp).drawBehind { drawRoundRect(tone.accentContainer, cornerRadius = CornerRadius(10.dp.toPx())) })
                    }
                    ControlButton(
                        symbol,
                        label,
                        20.dp,
                        slot,
                        tint = when {
                            unsupported -> colors.labelTertiary
                            on == true && style == DisplayStyle.TONAL -> tone.accent
                            on == true && action == Action.Favorite && style == DisplayStyle.GLASS -> colors.like
                            on == true -> if (style == DisplayStyle.GLASS) colors.highlightText else colors.labelPrimary
                            focused -> colors.labelPrimary
                            else -> colors.labelSecondary
                        },
                        filled = action == Action.Favorite && on == true,
                        toggled = on,
                        height = height,
                        onClick = { p.perform(action) },
                    )
                }
            }
        }
    }
}

private val TrayPadding = 4.dp

/** How far past the display's foot the hidden tray goes, so no edge of it is left showing (D-108). */
private val TrayExit = 24.dp


/** The measured quality (audio-architecture.md §5), as a small label in the display's material beside the cover. */
@Composable
internal fun QualityChip(text: String) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val style = colors.style
    Box(
        Modifier
            .drawBehind {
                val r = CornerRadius(if (style == DisplayStyle.INDUSTRIAL) 2.dp.toPx() else size.height / 2f)
                when (style) {
                    DisplayStyle.TONAL -> drawRoundRect(tone.surfaceLow, cornerRadius = r)
                    DisplayStyle.GLASS -> drawRoundRect(Color.White.copy(alpha = if (colors.isDark) 0.1f else 0.5f), cornerRadius = r)
                    DisplayStyle.INDUSTRIAL -> Unit
                }
                drawRoundRect(
                    if (style == DisplayStyle.GLASS) Color.White.copy(alpha = if (colors.isDark) 0.22f else 0.7f) else colors.separator,
                    cornerRadius = r,
                    style = Stroke(1.dp.toPx()),
                )
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        PodiumText(text, PodiumTheme.type.footnote.copy(fontSize = 11.sp, lineHeight = 14.sp), colors.labelPrimary, maxLines = 1)
    }
}
