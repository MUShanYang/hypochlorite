package app.hypochlorite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hypochlorite.netease.LyricLine
import app.hypochlorite.player.Lyrics
import app.hypochlorite.ui.theme.BodyStyle
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import app.hypochlorite.ui.theme.onColor
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// Independent line translations, then a left-origin highlight.
private val LyricEase = CubicBezierEasing(0.30f, 0f, 0.12f, 1f)
private val ScrollEase = CubicBezierEasing(0.40f, 0f, 0.12f, 1f)
// Short holds: start moving immediately, no dead-then-whip.
private val SoftLyricEase = CubicBezierEasing(0.22f, 0.10f, 0.28f, 1f)
private val SoftScrollEase = CubicBezierEasing(0.28f, 0.10f, 0.30f, 1f)
private const val SweepInMs = 620
private const val SweepOutMs = 550
private const val EmphasisMs = 400
private const val ScrollMs = 580
private const val LineDelayMs = 50
private const val MaxDelayedLines = 8

private fun mixEase(short: Easing, authored: Easing, mix: Float): Easing {
    val u = mix.coerceIn(0f, 1f)
    if (u <= 0f) return short
    if (u >= 1f) return authored
    return Easing { t -> short.transform(t) * (1f - u) + authored.transform(t) * u }
}

private fun lyricEase(durationMs: Int, baseMs: Int): Easing =
    mixEase(SoftLyricEase, LyricEase, Lyrics.switchEaseMix(durationMs, baseMs))

private fun scrollEase(durationMs: Int, baseMs: Int = ScrollMs): Easing =
    mixEase(SoftScrollEase, ScrollEase, Lyrics.switchEaseMix(durationMs, baseMs))

private fun scrollFraction(
    elapsedMs: Float,
    delayMs: Int = 0,
    scrollMs: Int = ScrollMs,
    ease: Easing = ScrollEase,
): Float {
    val span = scrollMs.coerceAtLeast(1)
    return ease.transform(((elapsedMs - delayMs) / span).coerceIn(0f, 1f))
}

/**
 * 歌词视口上下两头淡出：上头溶进波形，下头溶进歌名。
 * 曲线用 [Lyrics.edgeReveal]（贴边才散），避免线性一刀切出一块空带。
 */
private fun Modifier.fadeLyricEdges(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = size.height
            if (h <= 2f) return@drawWithContent
            val band = max(56.dp.toPx(), h * 0.12f).coerceAtMost(h * 0.22f)
            if (band <= 1f || band * 2f >= h) return@drawWithContent
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = TopEdgeStops,
                    startY = 0f,
                    endY = band,
                ),
                size = Size(size.width, band),
                blendMode = BlendMode.DstIn,
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = BottomEdgeStops,
                    startY = h - band,
                    endY = h,
                ),
                topLeft = Offset(0f, h - band),
                size = Size(size.width, band),
                blendMode = BlendMode.DstIn,
            )
        }

/** DstIn 遮罩停点：从视口边往里走，alpha 跟着 [Lyrics.edgeReveal]。 */
private fun edgeMaskStops(inwardFromStart: Boolean): Array<Pair<Float, Color>> {
    val last = 6
    return Array(last + 1) { i ->
        val u = i / last.toFloat()
        val reveal = Lyrics.edgeReveal(if (inwardFromStart) u else 1f - u)
        u to Color.White.copy(alpha = reveal)
    }
}

private val TopEdgeStops = edgeMaskStops(inwardFromStart = true)
private val BottomEdgeStops = edgeMaskStops(inwardFromStart = false)

/** Fixed text metrics keep wrapped lines stable while emphasis animates on a layer. */
@Composable
private fun HypochloriteLyricLine(
    text: String,
    active: Boolean,
    distance: Int,
    browsing: Boolean,
    holdMs: Long,
) {
    val colors = LocalHypochloriteColors.current
    // 歌词跟歌名同一套取色（titleInk）。扫光底用这墨，字在上面压对比色。
    val fill = colors.titleInk
    val onFill = fill.onColor()
    val rest = fill
    val emphasisMs = Lyrics.switchDurationMs(holdMs, EmphasisMs)
    val emphasisEase = lyricEase(emphasisMs, EmphasisMs)
    val emphasis by animateFloatAsState(
        if (active) 1f else 0f, tween(emphasisMs, easing = emphasisEase), label = "lyricEmphasis",
    )
    val highlight = remember { Animatable(-1.01f) }
    LaunchedEffect(active, holdMs) {
        val base = if (active) SweepInMs else SweepOutMs
        val ms = Lyrics.switchDurationMs(holdMs, base)
        highlight.animateTo(
            if (active) 0f else -1.01f,
            tween(ms, easing = lyricEase(ms, base)),
        )
    }
    val wipe = highlight.value
    val blur by animateFloatAsState(
        targetValue = when {
            browsing || active -> 0f
            distance < 0 -> (-distance * 0.4f).coerceAtMost(3.2f)
            else -> (distance * 0.2f).coerceAtMost(1.8f)
        },
        animationSpec = tween(emphasisMs, easing = emphasisEase), label = "lyricBlur",
    )
    BoxWithConstraints(
        Modifier.fillMaxWidth().blur(blur.dp, BlurredEdgeTreatment.Unbounded).clipToBounds(),
    ) {
        Box(
            Modifier.matchParentSize().graphicsLayer {
                translationX = size.width * wipe
            }.background(fill),
        )
        // 左右对齐进度条：不再额外缩进。右边只留扫光放大 / 右移要用的空位，避免换行跟着动。
        val shift = 20.dp
        val textWidth = ((maxWidth - shift) / 1.15f).coerceAtLeast(1.dp)
        val textModifier = Modifier
            .padding(end = (maxWidth - textWidth).coerceAtLeast(0.dp), top = 10.dp, bottom = 10.dp)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0.5f)
                scaleX = 1f + 0.15f * emphasis
                scaleY = scaleX
                translationX = shift.toPx() * emphasis
            }
        val textStyle = BodyStyle.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            lineHeight = 27.sp,
            textAlign = TextAlign.Left,
        )
        BasicText(text = text, style = textStyle.copy(color = rest), modifier = textModifier)
        // 字色切在扫光前沿上。clip 必须按行宽（和扫光底同一条 matchParent 宽），
        // 不能按字形布局宽 —— 字比行窄时底先扫完、对比色还落在后面。
        Box(
            Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val right = size.width * Lyrics.sweepReveal(wipe)
                    clipRect(left = 0f, top = 0f, right = right, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            BasicText(text = text, style = textStyle.copy(color = onFill), modifier = textModifier)
        }
    }
}

@Composable
fun ScrollingLyricView(
    lines: List<LyricLine>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
    onSeek: ((Long) -> Unit)? = null,
) {
    if (lines.isEmpty()) {
        Box(modifier.fillMaxSize())
        return
    }

    val listState = rememberLazyListState()
    val host = LocalView.current
    var viewportTop by remember { mutableFloatStateOf(Float.NaN) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var placed by remember(lines) { mutableStateOf(false) }
    val entrance = remember(lines) { Animatable(0f) }
    var scrollMs by remember(lines) { mutableIntStateOf(ScrollMs) }
    var scrollEaseMix by remember(lines) { mutableFloatStateOf(1f) }
    val clock = remember(lines) { Animatable((ScrollMs + LineDelayMs * MaxDelayedLines).toFloat()) }
    var travel by remember(lines) { mutableFloatStateOf(0f) }
    var movingIndex by remember(lines) { mutableIntStateOf(0) }
    var stagger by remember(lines) { mutableStateOf(false) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var browsing by remember(lines) { mutableStateOf(false) }

    // Let the user read freely, then resume following three seconds after their drag.
    LaunchedEffect(dragging) {
        if (dragging) browsing = true
        else if (browsing) {
            delay(3000)
            browsing = false
        }
    }
    LaunchedEffect(placed, lines) {
        if (placed) entrance.animateTo(1f, tween(400, easing = LyricEase))
    }

    LaunchedEffect(lines, currentIndex, viewportTop, viewportHeight, browsing, dragging) {
        if (viewportHeight == 0 || viewportTop.isNaN() || browsing || dragging) return@LaunchedEffect
        val index = Lyrics.scrollTarget(currentIndex, lines.size, placed) ?: return@LaunchedEffect
        val itemIndex = index + 1 // Leading spacer allows the first line to reach the anchor.
        // Preserve this app's screen-centre anchor, independent of the controls below it.
        val anchor = (host.height / 2f - viewportTop).coerceIn(0f, viewportHeight.toFloat())
        val nextScrollMs = Lyrics.switchDurationMs(Lyrics.lineHoldMs(lines, index), ScrollMs)
        val motionMs = nextScrollMs + LineDelayMs * MaxDelayedLines
        val nextEase = scrollEase(nextScrollMs)
        scrollMs = nextScrollMs
        scrollEaseMix = Lyrics.switchEaseMix(nextScrollMs, ScrollMs)
        travel = 0f
        clock.snapTo(motionMs.toFloat())
        withFrameNanos { }
        var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        if (!placed || item == null) {
            listState.scrollToItem(itemIndex, -anchor.roundToInt())
            withFrameNanos { }
            item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        }
        val measured = item ?: return@LaunchedEffect
        val delta = measured.offset + measured.size / 2f - anchor
        if (!placed) {
            listState.scrollBy(delta)
            placed = true
            movingIndex = index
            return@LaunchedEffect
        }
        if (abs(delta) < 0.5f) return@LaunchedEffect
        stagger = abs(index - movingIndex) <= 1
        movingIndex = index
        travel = delta
        clock.snapTo(0f)
        try {
            // The list scrolls once; per-row layer offsets supply the delayed follow-through.
            // No font-size animation or repeated geometry correction is needed.
            listState.scroll {
                var consumed = 0f
                clock.animateTo(motionMs.toFloat(), tween(motionMs, easing = LinearEasing)) {
                    val target = delta * scrollFraction(value, scrollMs = nextScrollMs, ease = nextEase)
                    consumed += scrollBy(target - consumed)
                }
            }
        } finally {
            travel = 0f
        }
    }

    val rowScrollEase = remember(scrollEaseMix) {
        mixEase(SoftScrollEase, ScrollEase, scrollEaseMix)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize()
            .onGloballyPositioned {
                viewportTop = it.positionInWindow().y
                viewportHeight = it.size.height
            }
            .graphicsLayer {
                alpha = entrance.value
                scaleX = 0.85f + 0.15f * entrance.value
                scaleY = scaleX
                clip = true
            }
            .fadeLyricEdges(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "lyric-pad-top") { Spacer(Modifier.fillParentMaxHeight()) }
        itemsIndexed(lines, key = { i, line -> "lyric-${line.timeMs}-$i" }) { i, line ->
            Box(
                Modifier.fillMaxWidth().graphicsLayer {
                    val delayMs = if (stagger) (i - movingIndex).coerceIn(0, MaxDelayedLines) * LineDelayMs else 0
                    translationY = travel * (
                        scrollFraction(clock.value, scrollMs = scrollMs, ease = rowScrollEase) -
                            scrollFraction(clock.value, delayMs, scrollMs, ease = rowScrollEase)
                    )
                }.clickableNoRipple { onSeek?.invoke(line.timeMs) },
            ) {
                HypochloriteLyricLine(
                    text = line.text,
                    active = i == currentIndex,
                    distance = if (currentIndex >= 0) i - currentIndex else 0,
                    browsing = browsing,
                    holdMs = Lyrics.lineHoldMs(lines, i),
                )
            }
        }
        item(key = "lyric-pad-bottom") { Spacer(Modifier.fillParentMaxHeight()) }
    }
}
