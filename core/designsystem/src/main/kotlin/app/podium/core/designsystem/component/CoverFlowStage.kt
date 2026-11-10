package app.podium.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.podium.core.designsystem.artwork.ArtworkImage
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.rememberFocusListState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Shared 3D Cover Flow stage (D-29) used across Music, Downloads, Online, and Radio:
 * the centre cover faces the listener; neighbours stand angled to either side like a record rack,
 * smaller and dimmer with distance. Controlled by the Click Wheel, horizontal drag/flick, tap, and
 * long-press (or long Center press).
 */
@Composable
fun <T> CoverFlowStage(
    items: List<T>,
    stateKey: String,
    keyOf: (T) -> String,
    titleOf: (T) -> String,
    subtitleOf: (T) -> String,
    artworkOf: (T) -> String?,
    onActivate: (T, Int) -> Unit,
    onLongPress: ((T, Int) -> Unit)? = null,
    isPlayingItem: ((T) -> Boolean)? = null,
    emptySymbol: PodiumSymbol = PodiumSymbol.Album,
    emptyTitle: String = "Nothing here yet",
    emptyMessage: String = "",
) {
    val insets = LocalScreenInsets.current
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val motion = PodiumTheme.motion
    val focus = rememberFocusListState(stateKey)
    val latestItems by rememberUpdatedState(items)
    val latestActivate by rememberUpdatedState(onActivate)
    val latestLongPress by rememberUpdatedState(onLongPress)

    SideEffect { focus.itemCount = items.size }
    ListInputEffect(
        focus = focus,
        onActivate = { idx -> latestItems.getOrNull(idx)?.let { latestActivate(it, idx) } },
        onLongPress = { idx -> latestItems.getOrNull(idx)?.let { item -> latestLongPress?.invoke(item, idx) } },
    )

    if (items.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = insets.top, bottom = insets.bottom),
            contentAlignment = Alignment.Center,
        ) {
            MessageState(emptySymbol, emptyTitle, emptyMessage)
        }
        return
    }

    val position = remember(stateKey) { Animatable(focus.focusedIndex.coerceIn(0, items.lastIndex).toFloat()) }
    val scope = rememberCoroutineScope()

    LocalMiniatureFocusKey.current?.let { key ->
        LaunchedEffect(items, key) {
            val i = items.indexOfFirst { keyOf(it) == key }
            if (i >= 0) {
                focus.focus(i)
                position.snapTo(i.toFloat())
            }
        }
    }

    LaunchedEffect(focus.focusedIndex, items.size) {
        val target = focus.focusedIndex.coerceIn(0, items.lastIndex).toFloat()
        if (motion.reduced) position.snapTo(target) else position.animateTo(target, spring(dampingRatio = 0.92f, stiffness = 380f))
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom)) {
        val cover = minOf(maxWidth * 0.5f, maxHeight * 0.58f)
        val stageY = (maxHeight - cover) * 0.38f
        val spacingPx = cover.value * 0.62f
        val middle by remember { derivedStateOf { position.value.roundToInt().coerceIn(0, (latestItems.size - 1).coerceAtLeast(0)) } }
        val pxPerDp = LocalDensity.current.density

        Box(
            Modifier
                .fillMaxWidth()
                .height(cover)
                .offset(y = stageY)
                .draggable(
                    state = rememberDraggableState { dx ->
                        if (latestItems.isNotEmpty()) {
                            val next = (position.value - dx / (spacingPx * pxPerDp)).coerceIn(0f, latestItems.lastIndex.toFloat())
                            scope.launch(start = CoroutineStart.UNDISPATCHED) { position.snapTo(next) }
                        }
                    },
                    orientation = Orientation.Horizontal,
                    onDragStopped = { pixelsPerSecond ->
                        if (latestItems.isNotEmpty()) {
                            val speed = -pixelsPerSecond / (spacingPx * pxPerDp)
                            val target = (position.value + speed * COVER_FLOW_FLING_SECONDS).roundToInt().coerceIn(0, latestItems.lastIndex)
                            scope.launch { position.animateTo(target.toFloat(), spring(dampingRatio = 0.92f, stiffness = 380f), initialVelocity = speed) }
                            focus.focus(target)
                        }
                    },
                ),
        ) {
            val window = (middle - 4)..(middle + 4)
            for (i in window) {
                val item = items.getOrNull(i) ?: continue
                val title = titleOf(item)
                val sub = subtitleOf(item)
                val art = artworkOf(item)
                Box(
                    Modifier
                        .zIndex(-abs(i - middle).toFloat())
                        .align(Alignment.Center)
                        .size(cover)
                        .graphicsLayer {
                            val d = i - position.value
                            val near = d.coerceIn(-1f, 1f)
                            val x = if (abs(d) <= 1f) d * spacingPx else sign(d) * (spacingPx + (abs(d) - 1f) * cover.value * 0.24f)
                            translationX = x * density
                            rotationY = -near * 58f
                            cameraDistance = 14f * density
                            val s = 1f - 0.16f * abs(near)
                            scaleX = s
                            scaleY = s
                            alpha = (1f - (abs(d) - 2.5f).coerceAtLeast(0f)).coerceIn(0f, 1f)
                        }
                        .pointerInput(i, keyOf(item)) {
                            detectTapGestures(
                                onTap = {
                                    if (i == focus.focusedIndex) {
                                        latestItems.getOrNull(i)?.let { latestActivate(it, i) }
                                    } else {
                                        focus.focus(i)
                                    }
                                },
                                onLongPress = {
                                    focus.focus(i)
                                    latestItems.getOrNull(i)?.let { target -> latestLongPress?.invoke(target, i) }
                                },
                            )
                        }
                        .semantics { contentDescription = if (sub.isNotBlank()) "$title by $sub" else title },
                ) {
                    ArtworkImage(art, cover, fallbackText = title, modifier = Modifier.fillMaxSize())
                    if (isPlayingItem?.invoke(item) == true) {
                        CoverFlowPlayingBadge(
                            accent = Color(0xFF4CD964),
                            reduced = motion.reduced,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp),
                        )
                    }
                }
            }
        }

        val current = items.getOrNull(focus.focusedIndex.coerceIn(0, items.lastIndex))
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn(tween(if (motion.reduced) 0 else 200)) togetherWith fadeOut(tween(if (motion.reduced) 0 else 120)) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = stageY + cover + Spacing.l)
                .padding(horizontal = Spacing.xl),
            label = "coverFlowTitle",
        ) { selected ->
            if (selected != null) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PodiumText(titleOf(selected), illuminated(type.rowFocused, true), colors.labelPrimary, textAlign = TextAlign.Center)
                    val subtitle = subtitleOf(selected)
                    if (subtitle.isNotBlank()) {
                        PodiumText(subtitle, type.rowSecondary, colors.labelSecondary, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverFlowPlayingBadge(
    accent: Color,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "coverFlowPlaying")
    val bars = if (reduced) {
        listOf(0.7f, 0.45f, 0.85f)
    } else {
        listOf(
            transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(460, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar0",
            ).value,
            transition.animateFloat(
                initialValue = 0.95f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar1",
            ).value,
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.9f,
                animationSpec = infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar2",
            ).value,
        )
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.height(11.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            bars.forEach { frac ->
                Box(
                    Modifier
                        .width(2.5.dp)
                        .height((11f * frac).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
        }
        PodiumText(
            text = "Playing",
            style = PodiumTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
        )
    }
}

private const val COVER_FLOW_FLING_SECONDS = 0.15f
