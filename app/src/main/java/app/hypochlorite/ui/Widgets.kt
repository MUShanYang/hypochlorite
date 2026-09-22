package app.hypochlorite.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.ui.text.style.TextAlign
import app.hypochlorite.player.PlayMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.math.abs
import kotlin.math.hypot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hypochlorite.netease.Playlist
import app.hypochlorite.netease.Song
import app.hypochlorite.player.Lyrics
import app.hypochlorite.ui.theme.BodyStyle
import app.hypochlorite.ui.theme.HeavyBold
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import app.hypochlorite.ui.theme.Warn
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

fun fmtTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val sec = (ms / 1000) % 60
    val min = ms / 60000
    return String.format(java.util.Locale.US, "%d:%02d", min, sec)
}

/**
 * 切歌时右侧总时长跟左侧进度一样 scramble。
 *
 * seek / 拖进度条不会触发：ProgressLine 的 [trackKey] 只跟歌曲走，
 * 进度跳转不会改 key，总时长本身也不会变。
 */
fun scrambleDurationLabel(): Boolean = true

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    bold: Boolean = false,
    muted: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    size: Int = if (bold) 19 else 16,
    marquee: Boolean = false,
) {
    val textMod = if (marquee) {
        modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = 1500,
            repeatDelayMillis = 1500,
            velocity = 30.dp,
        )
    } else modifier

    val colors = LocalHypochloriteColors.current
    val targetColor = when {
        muted -> colors.muted
        color != Color.Unspecified -> color
        else -> colors.text
    }

    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = textMod,
        style = BodyStyle.copy(
            color = targetColor,
            fontWeight = if (bold) HeavyBold else FontWeight.Normal,
            fontSize = size.sp,
            lineHeight = (size * 1.25f).sp,
        ),
        maxLines = maxLines,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
        softWrap = maxLines != 1 && !marquee,
    )
}

/**
 * 搜索命中的那一段用主题色标出来。没匹配上时和普通正文一样。
 */
@Composable
fun HighlightText(
    text: String,
    query: String,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    muted: Boolean = false,
    size: Int = 14,
    maxLines: Int = 1,
    marquee: Boolean = false,
) {
    val colors = LocalHypochloriteColors.current
    val base = if (muted) colors.muted else colors.text
    val accent = colors.accent
    val annotated = remember(text, query, accent) {
        buildAnnotatedString {
            appendHighlighted(text, query, accent)
        }
    }
    val textMod = if (marquee) {
        modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = 1500,
            repeatDelayMillis = 1500,
            velocity = 30.dp,
        )
    } else modifier
    BasicText(
        text = annotated,
        modifier = textMod,
        style = BodyStyle.copy(
            color = base,
            fontWeight = if (bold) HeavyBold else FontWeight.Normal,
            fontSize = size.sp,
            lineHeight = (size * 1.25f).sp,
        ),
        maxLines = maxLines,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
        softWrap = maxLines != 1 && !marquee,
    )
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendHighlighted(
    text: String,
    query: String,
    accent: Color,
) {
    val q = query.trim()
    if (q.isEmpty() || q.length > text.length) {
        append(text)
        return
    }
    var i = 0
    while (i < text.length) {
        val hit = indexOfIgnoreCase(text, q, i)
        if (hit < 0) {
            append(text.substring(i))
            return
        }
        if (hit > i) append(text.substring(i, hit))
        withStyle(SpanStyle(color = accent)) {
            append(text.substring(hit, hit + q.length))
        }
        i = hit + q.length
    }
}

private fun indexOfIgnoreCase(text: String, query: String, from: Int): Int {
    if (query.isEmpty() || from > text.length - query.length) return -1
    var i = from
    val last = text.length - query.length
    while (i <= last) {
        if (text.regionMatches(i, query, 0, query.length, ignoreCase = true)) return i
        i++
    }
    return -1
}

@Composable
fun HoverBold(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    on: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    size: Int = 16,
    marquee: Boolean = false,
    padV: Int = 4,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    MonoText(
        text = text,
        modifier = modifier
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = padV.dp),
        color = color,
        bold = on || pressed,
        maxLines = maxLines,
        size = size,
        marquee = marquee,
    )
}

/**
 * 底栏长按跳转用的行高估算。36dp 封面 + 上下各 6dp = 48。
 *
 * 刻意不用 `layoutInfo` 实测 —— 目标行多半还在视口外没被布局，那时拿不到值。
 * 这个列表行字号固定、单行不换行、高度是常量，估算足够把行对到画面中间偏上。
 */
val SongRowHeight: Dp = 48.dp

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    index: Int = 0,
    isLiked: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    highlight: String = "",
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val shouldAnimate = index < 12
    val offsetX = remember(song.id) { Animatable(if (shouldAnimate) -32f else 0f) }
    val alpha = remember(song.id) { Animatable(if (shouldAnimate) 0f else 1f) }

    if (shouldAnimate) {
        LaunchedEffect(song.id) {
            val stagger = (index * 20).toLong()
            if (stagger > 0) {
                delay(stagger)
            }
            launch {
                alpha.animateTo(1f, tween(160))
            }
            offsetX.animateTo(0f, tween(200, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)))
        }
    }

    // 详情页切歌同款的 NEXT 横幅：点中这一行时在行内扫过
    val scope = rememberCoroutineScope()
    val bannerOffset = remember(song.id) { Animatable(0f) }
    val bannerDrift = remember(song.id) { Animatable(0f) }
    var bannerJob by remember(song.id) { mutableStateOf<Job?>(null) }
    var bannerOn by remember(song.id) { mutableStateOf(false) }
    var bannerPrefix by remember(song.id) { mutableStateOf("") }
    var bannerSongName by remember(song.id) { mutableStateOf("") }

    // 横幅是否还在「盖上来」的阶段。行尾的 [播放中] 在这段时间里必须一直藏着 —— 见下面 graphicsLayer
    var bannerCovering by remember(song.id) { mutableStateOf(false) }

    // 长按推歌的反馈色。点一下是 NEXT(取色白)，长按是 PUSH(琥珀)，两种意图别混
    var bannerIsPush by remember(song.id) { mutableStateOf(false) }

    /**
     * 扫一次横幅。[prefix] 与 [name] 分别对应粗体前缀与歌曲名，[amber] 决定底色走 banner 还是琥珀。
     *
     * 抽成函数是因为点击和长按走的是两条路（长按不能触发 onClick），
     * 但视觉必须一模一样 —— 复制一份迟早会改歪一边。
     */
    fun sweepBanner(prefix: String, name: String, amber: Boolean) {
        bannerPrefix = prefix
        bannerSongName = name
        bannerIsPush = amber
        // 必须和实际操作同帧置位：晚一帧 [播放中] 就会先亮一下，
        // 而那一帧恰好是横幅还没擦过来、最露馅的时候。
        bannerCovering = true
        bannerJob?.cancel()
        bannerJob = scope.launch {
            try {
                bannerOn = true
                bannerOffset.snapTo(-1f)
                bannerDrift.snapTo(-16f)
                launch {
                    bannerDrift.animateTo(16f, tween(760, easing = LinearEasing))
                }
                bannerOffset.animateTo(0f, tween(280, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)))
                delay(240)
                // 盖满了，可以让 [播放中] 出来 —— 此刻它仍被横幅压着，等横幅退场自然露出来
                bannerCovering = false
                bannerOffset.animateTo(1f, tween(240, easing = FastOutLinearInEasing))
            } finally {
                bannerOn = false
                bannerCovering = false
            }
        }
    }

    // 长按检测。不能用 clickable(onLongClick = ...)：它和 onClick 是互斥的，
    // 而且长按判定后 Compose 就不再派发 onClick —— 我们需要的是「点击照常，长按额外触发」。
    // 所以这里在底层自己判：按下后等 longPressTimeout，期间手指没动、没抬起 → 长按成立。
    val longPressModifier = if (onLongPress == null) {
        Modifier
    } else {
        Modifier.pointerInput(song.id) {
            val longPressMs = viewConfiguration.longPressTimeoutMillis
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val held = withTimeoutOrNull(longPressMs) {
                    while (true) {
                        val e = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                        // 手指动了 = 用户在滚列表，不是长按
                        if ((e.position - e.previousPosition).getDistance() > slop) return@withTimeoutOrNull false
                        // 抬手了 = 普通点击，交给 clickable 处理
                        if (!e.pressed) return@withTimeoutOrNull false
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (held == null) {
                    sweepBanner("PUSH »", song.name, amber = true)
                    onLongPress()
                    // 关键：把这次手势「消费」掉，否则松手时 clickable 仍会收到 UP 并当成一次点击，
                    // 表现为「长按推歌之后又播了一遍」。consumed 会顺着 pointer 事件的 pass 传出去，
                    // 让同一条链路上的 clickable 检测到「已被别人处理」而放弃。
                    //
                    // 这里要用 `e.positionChange()` 先把位移读掉再 `consumePositionChange()` ——
                    // clickable 判的是「位置变化有没有被消费」，直接 consume() 只标记 downChange，拦不住它。
                    down.consume()
                    // 等手指真的抬起来再消费一次（此时才有要消费的位移变化）
                    withTimeoutOrNull(longPressMs) {
                        while (true) {
                            val e = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            e.positionChange()
                            e.consumePositionChange()
                            if (!e.pressed) break
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = offsetX.value
                this.alpha = alpha.value
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(longPressModifier)
                .clickable(interactionSource = interaction, indication = null) {
                    sweepBanner("NEXT »", song.name, amber = false)
                    onClick()
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(song.cover, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            val colors = LocalHypochloriteColors.current
            Column(Modifier.weight(1f)) {
                val mark = highlight.isNotBlank() && !on
                if (mark) {
                    HighlightText(
                        text = song.name,
                        query = highlight,
                        bold = pressed,
                        maxLines = 1,
                        marquee = true,
                        size = 14,
                    )
                } else {
                    MonoText(
                        text = song.name,
                        bold = on || pressed,
                        color = if (on) colors.accent else Color.Unspecified,
                        maxLines = 1,
                        marquee = true,
                        size = 14,
                    )
                }
                val artist = song.artists.joinToString(" / ").ifEmpty { song.album }
                if (artist.isNotEmpty()) {
                    if (mark) {
                        HighlightText(
                            text = artist,
                            query = highlight,
                            muted = true,
                            maxLines = 1,
                            marquee = true,
                            size = 11,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    } else {
                        MonoText(
                            text = artist,
                            muted = !on,
                            color = if (on) Color.Unspecified else colors.muted,
                            maxLines = 1,
                            marquee = true,
                            size = 11,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            if (on) {
                Spacer(Modifier.width(8.dp))
                MonoText(
                    "[播放中]",
                    color = colors.accent,
                    size = 13,
                    bold = true,
                    // 横幅从左往右擦入，行尾这个标记要等横幅把它盖住、盖满之后才该存在。
                    // 覆盖阶段整段藏掉，否则开头几百毫秒它会孤零零留在右侧没被盖到的地方 —— 露馅。
                    modifier = Modifier.graphicsLayer {
                        // 外层有同名的入场 alpha(Animatable)，必须用 this.alpha 指 layer 作用域
                        this.alpha = if (bannerCovering) 0f else 1f
                    },
                )
            }
        }

        if (bannerOn) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationX = bannerOffset.value * size.width
                        clip = true
                    }
                    .background(if (bannerIsPush) Warn else LocalHypochloriteColors.current.banner),
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            translationX = -bannerOffset.value * size.width + bannerDrift.value.dp.toPx()
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // 字色 = bannerInk：白天取色模式是同色相的暗墨（取色改暗，比横幅底暗两档才压得住），
                        // 深色模式近白，纯黑白主题压横幅对比色。
                        // 不用 onBanner 自适应：取色时 banner 明度被 clamp 在 L* 40~65，
                        // 正好横跨 onColor 的翻转点，换个封面字色就在黑白之间跳。
                        // PUSH »（一起听推歌）铺的是琥珀底，那条仍然必须黑字。
                        val textColor = if (bannerIsPush) Color.Black else LocalHypochloriteColors.current.bannerInk
                        androidx.compose.foundation.text.BasicText(
                            text = bannerPrefix,
                            style = BodyStyle.copy(
                                color = textColor,
                                fontWeight = HeavyBold,
                                fontSize = 20.sp,
                                letterSpacing = 2.sp,
                            ),
                        )
                        androidx.compose.foundation.text.BasicText(
                            text = bannerSongName,
                            style = BodyStyle.copy(
                                color = textColor,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                            ),
                            // 单行不换行（用户 2026-09-19 晚：换行移除）；容器定高
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    caption: String? = null,
    highlight: String = "",
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val shouldAnimate = index < 12
    val offsetX = remember(playlist.id) { Animatable(if (shouldAnimate) -32f else 0f) }
    val alpha = remember(playlist.id) { Animatable(if (shouldAnimate) 0f else 1f) }

    if (shouldAnimate) {
        LaunchedEffect(playlist.id) {
            val stagger = (index * 20).toLong()
            if (stagger > 0) {
                delay(stagger)
            }
            launch {
                alpha.animateTo(1f, tween(160))
            }
            offsetX.animateTo(0f, tween(200, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)))
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = offsetX.value
                this.alpha = alpha.value
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(playlist.cover, modifier = Modifier.size(54.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (highlight.isNotBlank()) {
                HighlightText(
                    text = playlist.name,
                    query = highlight,
                    bold = pressed,
                    maxLines = 1,
                    marquee = true,
                    size = 15,
                )
            } else {
                MonoText(
                    text = playlist.name,
                    bold = pressed,
                    maxLines = 1,
                    marquee = true,
                    size = 15,
                )
            }
            val detail = caption ?: if (playlist.trackCount > 0) "${playlist.trackCount} 首" else ""
            if (detail.isNotEmpty()) {
                if (highlight.isNotBlank()) {
                    HighlightText(
                        text = detail,
                        query = highlight,
                        muted = true,
                        maxLines = 1,
                        marquee = true,
                        size = 12,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                } else {
                    MonoText(
                        text = detail,
                        muted = true,
                        size = 12,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun RoamChevrons(
    dir: Int,
    height: Dp = 14.dp,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val ink = if (color == Color.Unspecified) LocalHypochloriteColors.current.text else color
    val count = 3
    val chevronWidth = 6.dp
    val spacing = 2.dp
    val strokeWidth = 1.5.dp
    val totalWidth = chevronWidth + (chevronWidth * 0.75f + spacing) * (count - 1)

    Canvas(modifier = modifier.size(width = totalWidth, height = height)) {
        val h = size.height
        val sw = strokeWidth.toPx()
        val cw = chevronWidth.toPx()
        val sp = spacing.toPx()

        for (i in 0 until count) {
            val xOffset = if (dir > 0) {
                i * (cw * 0.75f + sp)
            } else {
                (count - 1 - i) * (cw * 0.75f + sp)
            }

            val path = Path().apply {
                if (dir > 0) {
                    moveTo(xOffset, 1f)
                    lineTo(xOffset + cw, h / 2f)
                    lineTo(xOffset, h - 1f)
                } else {
                    moveTo(xOffset + cw, 1f)
                    lineTo(xOffset, h / 2f)
                    lineTo(xOffset + cw, h - 1f)
                }
            }
            drawPath(
                path = path,
                color = ink,
                style = Stroke(
                    width = sw,
                    cap = StrokeCap.Square,
                    join = StrokeJoin.Miter,
                ),
            )
        }
    }
}

@Composable
fun PlayIcon(
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else colors.text
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.15f, h * 0.12f)
            lineTo(w * 0.9f, h * 0.5f)
            lineTo(w * 0.15f, h * 0.88f)
            close()
        }
        drawPath(path, color = c)
    }
}

@Composable
fun PauseIcon(
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else colors.text
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barW = w * 0.28f
        drawRect(
            color = c,
            topLeft = Offset(w * 0.14f, h * 0.12f),
            size = Size(barW, h * 0.76f),
        )
        drawRect(
            color = c,
            topLeft = Offset(w * 0.58f, h * 0.12f),
            size = Size(barW, h * 0.76f),
        )
    }
}

@Composable
fun PrevIcon(
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else colors.text
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRect(
            color = c,
            topLeft = Offset(w * 0.1f, h * 0.14f),
            size = Size(w * 0.16f, h * 0.72f),
        )
        val tri = Path().apply {
            moveTo(w * 0.9f, h * 0.14f)
            lineTo(w * 0.35f, h * 0.5f)
            lineTo(w * 0.9f, h * 0.86f)
            close()
        }
        drawPath(tri, color = c)
    }
}

@Composable
fun NextIcon(
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else colors.text
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val tri = Path().apply {
            moveTo(w * 0.1f, h * 0.14f)
            lineTo(w * 0.65f, h * 0.5f)
            lineTo(w * 0.1f, h * 0.86f)
            close()
        }
        drawPath(tri, color = c)
        drawRect(
            color = c,
            topLeft = Offset(w * 0.74f, h * 0.14f),
            size = Size(w * 0.16f, h * 0.72f),
        )
    }
}

@Composable
fun MiniIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(6.dp)
            .graphicsLayer {
                alpha = if (pressed) 0.5f else 1f
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    val colors = LocalHypochloriteColors.current
    val lineAlpha = if (colors.isLight) 0.12f else 0.35f
    Box(modifier.fillMaxWidth().height(1.dp).background(colors.text.copy(alpha = lineAlpha)))
}

@Composable
fun VerticalLyric(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    // 默认跟歌名同一套取色（titleInk）。直接把 `Color.Unspecified` 丢给 BasicText，
    // 它会退回 LocalContentColor（默认黑）—— 于是深色模式下竖排歌词是黑的，看不见。
    // 参数默认值读不到 CompositionLocal，所以只能在函数体里补这一步。
    val ink = if (color == Color.Unspecified) LocalHypochloriteColors.current.titleInk else color
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label = "lyric",
    ) { t ->
        val cols = Lyrics.columns(t, maxPerCol = 8, maxCols = 3)
        if (cols.isEmpty()) {
            Box(Modifier.size(1.dp))
        } else {
            val maxLen = cols.maxOfOrNull { it.length } ?: 0
            val (fontSize, lineHeight) = when {
                maxLen <= 6 -> 15.sp to 18.sp
                maxLen <= 8 -> 14.sp to 17.sp
                else -> 13.sp to 15.5.sp
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                cols.forEach { col ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        for (ch in col) {
                            androidx.compose.foundation.text.BasicText(
                                text = ch.toString(),
                                style = BodyStyle.copy(
                                    color = ink,
                                    fontSize = fontSize,
                                    lineHeight = lineHeight,
                                    fontWeight = FontWeight.Normal,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Search,
    onGo: () -> Unit = {},
    focused: Boolean = false,
    onFocused: (Boolean) -> Unit = {},
) {
    val colors = LocalHypochloriteColors.current
    val borderColor = if (focused) colors.text else colors.text.copy(alpha = if (colors.isLight) 0.25f else 0.45f)
    Box(
        modifier
            .fillMaxWidth()
            .border(width = if (focused) 2.dp else 1.dp, color = borderColor, shape = androidx.compose.ui.graphics.RectangleShape)
            .padding(top = 3.dp, bottom = if (focused) 2.dp else 3.dp),
    ) {
        if (value.isEmpty()) {
            MonoText(placeholder, muted = true, modifier = Modifier.padding(vertical = 4.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            textStyle = BodyStyle.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onSearch = { onGo() }, onDone = { onGo() }),
            onTextLayout = {},
            decorationBox = { inner ->
                Box {
                    inner()
                }
            },
        )
    }
}

private val EinkLightBlueFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.15f, 0.30f, 0.05f, 0f, 40f,
            0.20f, 0.50f, 0.15f, 0f, 70f,
            0.30f, 0.60f, 0.30f, 0f, 120f,
            0f,    0f,    0f,    1f, 0f,
        )
    )
)

private val EinkLightRedFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.35f, 0.60f, 0.20f, 0f, 110f,
            0.15f, 0.35f, 0.10f, 0f, 45f,
            0.15f, 0.35f, 0.10f, 0f, 45f,
            0f,    0f,    0f,    1f, 0f,
        )
    )
)

private val EinkGrayscaleFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToSaturation(0f) }
)

private fun Drawable.toSafeBitmap(): Bitmap {
    if (this is BitmapDrawable && this.bitmap != null) return this.bitmap
    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}

@Composable
fun Cover(
    url: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val borderAlpha = if (colors.isLight) 0.15f else 0.45f
    Box(
        modifier = modifier
            .background(colors.cover)
            .border(1.dp, colors.text.copy(alpha = borderAlpha)),
    ) {
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize().background(LocalHypochloriteColors.current.cover))
        }
    }
}

/**
 * 角标拼「口」字时的几何常数 —— [CornerAccents] 与取色方块的**缩放下限共用这一份**。
 *
 * 分成两份写就会漂移：改了臂长忘了改方块，方块立刻跑出角框（或者缩成看不见的一点）。
 * 用户对这块的要求很具体（2026-09-18）：「将取色方块缩小的大小改成被角框住的小方块 要留间隔」。
 */
private const val CORNER_ARM_DP = 20f
private const val CORNER_ARM_FRAME_FRACTION = 0.45f
private const val CORNER_STROKE_DP = 2.5f

/** 取色方块与角内沿之间让出的间隔（单边）。 */
private const val CORNER_BOX_GAP_DP = 2f

/**
 * 角标单条臂的长度：20dp；容器太小时不超过边长的 [CORNER_ARM_FRAME_FRACTION]。
 *
 * 32dp 的底栏缩略图会吃到后面那条分支（`32 * 0.45 = 14.4dp`），200dp 大封面走 20dp。
 */
private fun Density.cornerArmLenPx(framePx: Float): Float =
    minOf(CORNER_ARM_DP.dp.toPx(), framePx * CORNER_ARM_FRAME_FRACTION)

/**
 * 取色方块**缩小的终点边长** —— 就是「被四角框住的那块小方块」。
 *
 * `p = 1` 时四条臂围成一个边长 = [cornerArmLenPx] 的正方形；线宽 [CORNER_STROKE_DP] 以路径为中心
 * 向两侧各摊一半，所以能放东西的**内净空** = 边长 − 线宽。方块再从内沿往回收
 * [CORNER_BOX_GAP_DP] 就是用户要的「间隔」。
 *
 * ⚠️ 返回的是**边长（px）**，调用方除以容器边长才是 `bgScale` 的终点。
 * 200dp：`20 − 2.5 − 4 = 13.5dp`（角框 20dp，四周各留 ~2.6dp 净空）。
 */
private fun Density.cornerBoxSidePx(framePx: Float): Float {
    val inner = cornerArmLenPx(framePx) - CORNER_STROKE_DP.dp.toPx()
    return (inner - CORNER_BOX_GAP_DP.dp.toPx() * 2f).coerceAtLeast(0f)
}

/**
 * 极简硬朗的四角准星标（对角线渲染）。
 *
 * 一次只画**一组对角**，每切一首歌换一组：
 * - [diagonal] = 0f → ┌ 左上 + ┘ 右下
 * - [diagonal] = 1f → ┐ 右上 + └ 左下
 *
 * 两组都严格对角，`p` 推到 1 时两条臂各自覆盖矩形的一整条边，严丝合缝拼成「口」字。
 * **绝不能只换一个角** —— 落到相邻两角就只剩两条平行边，合不拢。
 *
 * 方位用 Float 而不是 Boolean，就是为了让**换方位本身也能有动画**：中间值会让两个角
 * 沿着画面上下边缘横移，配合 [morph]，读作
 * 「角合拢成一根细线 → 细线滑到另一组对角 → 再展开成角」。
 *
 * @param color 角标颜色（通常取自封面提取的主题色）
 * @param scale 整体缩放比例
 * @param offsetDp 额外向外弹出的位移量
 * @param assemblyProgress 方形拼合进度 (0f = **静止形态**：L 角扣在封面外角上, 1f = 汇聚中心拼合成闭合正方形「口」)
 * @param diagonal 方位插值（0f = 左上/右下，1f = 右上/左下，中间值为横移过程）
 * @param morph 形态：1f = 完整的 L 角，0f = 只剩一根水平细线。默认恒定 1f，
 *   只有换方位那一下才由调用方驱动。
 */
@Composable
fun CornerAccents(
    color: Color,
    scale: Float = 1.0f,
    offsetDp: Float = 0f,
    assemblyProgress: Float = 0f,
    diagonal: Float = 0f,
    morph: Float = 1f,
    modifier: Modifier = Modifier,
) {
    if (scale <= 0.01f) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val strokeWidth = CORNER_STROKE_DP.dp.toPx() * scale
        // 臂长与取色方块的缩放下限共用 [cornerArmLenPx] —— 两处公式必须一致，
        // 否则「口」字的大小和方块的大小会对不上（方块跑出角框）。
        val armLen = cornerArmLenPx(w) * scale
        val gap = (3.dp.toPx() + offsetDp.dp.toPx()) * scale

        val cx = w / 2f
        val cy = h / 2f
        val halfBox = armLen / 2f

        val p = assemblyProgress.coerceIn(0f, 1f)

        // **p = 0 就是角标的静止形态，必须画。**
        // p=0 时顶点落在画面外 [-gap, -gap]，两条臂朝内伸出 —— 视觉上就是扣在封面
        // 左上 / 右下外角上的那对 L 角（`verify_corner_swing.py` 打表：200dp 时顶点
        // (-3,-3) / (203,203)，臂长全长）。这里曾经有一句 `if (p <= 0.001f) return@Canvas`
        // 「整组不画」，结果静止状态下角标整对消失（用户报的就是「角直接消失了」）
        // ——**不要再加回来**。真正需要兜底的不是 p=0，而是换位中途被打断后停在
        // p=0.45 + morph=0 的那种半成品，那个由调用方三处 `snapTo` 复位解决。

        /**
         * 形态由 [morph] 单独控制，**不跟 [p] 挂钩**。
         *
         * 早先的写法是「按 p 判断，p < 0.6 只画垂直臂」，结果收拢和炸开这两段里角有六成
         * 时间长得像一根竖线，用户一眼就问「角怎么变成竖线了」。形态只在换方位那一下才变。
         *
         * 留下的那根线是**水平**的 —— 跟横移方向一致，看着才像「线滑过去」。
         */
        val morphRaw = morph.coerceIn(0f, 1f)
        val shapeT = morphRaw * morphRaw * (3f - 2f * morphRaw) // smoothstep，收放带一点力度
        val vArm = armLen * shapeT

        // 这里曾经有一层「比角标粗 2dp 的描边」用来把角标从同色底上抠出来，
        // 固定黑色 → 深色模式下角标是亮色，外面那圈就是一条看得见的黑边（用户明确要求去掉）。
        // **不要再加回来**：角标就是干干净净一根取色线，压在同色的取色方块上会暂时看不见，
        // 这是接受的取舍 —— 方块缩小/放大那两段本来就是过渡，眼睛跟的是角标拼合与炸开。

        // 角 1 在上侧（左或右），角 2 是它的对角（必在另一侧的下方）。
        // 用 ±1 表示方位，两条臂统一朝「内侧」延伸，于是组 A 与组 B 各自成立，不必分开写。
        // ax 由 [diagonal] 连续映射到 [-1, 1]：取端点值时就是原来的两组对角，
        // 中间值代表「正在横移」的那一帧 —— 这是换方位动画的唯一来源。
        val ax = diagonal.coerceIn(0f, 1f) * 2f - 1f
        val bx = -ax

        // 角 1：上侧。开放点在画面外，目标点是中心矩形的上左/上右顶点。
        // 开放点跟着 ax 连续移动（±1 时正好回落到 -gap / w+gap，与旧实现等价），
        // 所以夹在中间时两个角是「沿上下边缘平移」，不会瞬移换边。
        val aOpenX = cx + ax * (w / 2f + gap)
        val aX = aOpenX + (cx + ax * halfBox - aOpenX) * p
        val aY = -gap + (cy - halfBox + gap) * p
        // 水平臂全程都在（它就是 morph 归零时留下的那根细线），垂直臂按 morph 生长
        drawLine(color, Offset(aX, aY), Offset(aX - ax * armLen, aY), strokeWidth, StrokeCap.Square)
        if (vArm > 0.5f) {
            drawLine(color, Offset(aX, aY), Offset(aX, aY + vArm), strokeWidth, StrokeCap.Square)
        }

        // 角 2：下侧的对角。p == 1f 时两条臂正好接上角 1 的两条，四个端点两两重合拼成「口」字
        val bOpenX = cx + bx * (w / 2f + gap)
        val bX = bOpenX + (cx + bx * halfBox - bOpenX) * p
        val bY = h + gap + (cy + halfBox - h - gap) * p
        // 角 2 同理：水平臂恒在，垂直臂按 morph 生长
        drawLine(color, Offset(bX, bY), Offset(bX - bx * armLen, bY), strokeWidth, StrokeCap.Square)
        if (vArm > 0.5f) {
            drawLine(color, Offset(bX, bY), Offset(bX, bY - vArm), strokeWidth, StrokeCap.Square)
        }
    }
}

/**
 * 歌曲封面动力学切歌画框：
 * 1. 判断是点击上一首（dir < 0）还是下一首（dir > 0）；
 * 2. 使用取色背景方块沿方向滑入；
 * 3. 滑入后背景方块先缩到**被四角框住的那块小方块**（留间隔，不会缩到看不见，见 [shrinkScale]）；
 * 4. 角标随后向中心迅速收拢，拼合为正方形「口」字 —— 正好框住那块小方块；
 * 5. 若新封面或音频正在加载，持续保持「口 + 小方块」定格在中心（作为加载态）；
 * 6. 等封面与音频加载就绪后：正方形裂开，角标向四周炸开归位（顺手换另一组对角），
 *    **同时**背景方块从角框里放大铺满；新封面从中心钻洞出来。
 */
@Composable
fun KineticCoverFrame(
    coverUrl: String?,
    songId: String?,
    direction: Int,
    transitionSeq: Long,
    accentColor: Color,
    modifier: Modifier = Modifier,
    playSecondHalfOnEnter: Boolean = false,
    isAudioReady: Boolean = true,
) {
    val context = LocalContext.current
    val colors = LocalHypochloriteColors.current
    /**
     * 取色方块 / 角标的**当前颜色**。
     *
     * 切歌后取色是异步算出来的（`themeJob` 走封面下载 + 直方图 + 派生），所以传进来的
     * [accentColor] 是**跳变**的：算完的那一帧方块和角标会整体换色，硬得能看见。
     * 这里统一做一次颜色过渡，让新配色淡进来（用户要求「给角添加颜色过渡」）。
     *
     * 方块与角标必须共用这**一个**动画色 —— 角标就压在同色的方块上，只动一个的话
     * 过渡期间会出现「角标已经是新色、底下的方块还是旧色」的错帧。
     */
    val accent by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(420, easing = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)),
        label = "coverAccent",
    )
    // 取色方块上原本有一条 2dp 描边（`accent.onColor()`，按底色明度取反色）用作擦除时的切边，
    // 用户 2026-09-18 要求连那条线一起去掉 → 这里不再算 `frameLine`。要加回来先征得同意。
    val currentAudioReady by rememberUpdatedState(isAudioReady)

    var displayedCover by remember { mutableStateOf(coverUrl) }
    var incomingCover by remember { mutableStateOf<String?>(null) }
    var showCover by remember { mutableStateOf(true) }

    val bgSlideOffset = remember { Animatable(0f) }
    val bgScale = remember { Animatable(1f) }
    val bgAlpha = remember { Animatable(0f) }
    val cornerAssembly = remember { Animatable(0f) }
    val cornerAlpha = remember { Animatable(1f) }
    val bgHoleProgress = remember { Animatable(0f) }

    /**
     * 容器的实测边长（px），由下面那个 `onSizeChanged` 写入。
     *
     * 「取色方块缩到多大」不看容器尺寸就没法算 —— 角标的臂长在窄容器里会被 `w * 0.45` 截断。
     * 动画都是布局完成之后才跑的，所以这里读到的一定是真实尺寸；没量到就先按 0 处理（= 不退让）。
     */
    var frameWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    /**
     * `bgScale` 的**缩放下限** = 那块「被四角框住的小方块」占容器的比例。
     *
     * 用户 2026-09-18：「将取色方块缩小的大小改成被角框住的小方块 要留间隔」——
     * 原来的缩小阶段是 `bgScale 1 → 0`，色块在中心直接缩没了，角标拼出来的「口」里空空如也。
     * 现在缩到角框的内净空再让开一点间隔就停住（几何见 [cornerBoxSidePx]）。
     */
    fun shrinkScale(): Float {
        val w = frameWidthPx.toFloat()
        if (w <= 0f) return 0f
        return (density.cornerBoxSidePx(w) / w).coerceIn(0f, 1f)
    }

    /**
     * 角标当前方位：0f = ┌ 左上 + ┘ 右下，1f = ┐ 右上 + └ 左下。
     *
     * 必须是会动的 [Animatable]，不能像原来那样由 `transitionSeq % 2` 直接算出来 ——
     * 那样换方位是硬切，效果就是「角忽然换了方向，一点过渡都没有」。
     */
    val diagAnim = remember { Animatable(if (transitionSeq % 2L != 0L) 1f else 0f) }

    /**
     * 角的形态：1f = 完整的 L 角，0f = 只剩一根水平细线。
     *
     * 平时恒为 1f —— **角在收拢 / 炸开这两段里必须一直是完整的角**，只有换方位那一下才变。
     * 每次动画开始时都要 `snapTo(1f)` 复位，否则上次被打断留下的形态会带到下一次。
     */
    val cornerMorph = remember { Animatable(1f) }

    var hasEntered by remember { mutableStateOf(false) }
    var lastHandledSeq by remember { mutableStateOf(transitionSeq) }

    /**
     * 角标炸开归位，顺手把**方位**也换掉。
     *
     * 拆三段，因为「换方位」必须发生在**画面内**：
     * 1. 角合拢成一根水平细线，同时 `p` 退到 0.45（这一段仍在画面里）；
     * 2. 细线沿上下边缘横移到另一组对角 —— 这就是「滑动到指定处」；
     * 3. 在新位置**张开成完整的角**，同时继续滑出画面。
     *
     * 第 3 步的 `cornerMorph` 回到 1f 是**必须的**：少了它，动画收了尾画面上还留着一根线
     * （用户报的就是「动画都结束了还是竖线，没有变成角」）。
     *
     * 顺带的好处：`p` 归零时角已经落在画面外，下一次收拢正好从**新方位**滑回来，接得上。
     *
     * @param growBlockTo 不为 null 时，取色方块**从细线开始横移那一刻起**就动，
     *   但**前 200ms（细线横移那一段）只轻轻长一点**，等细线落位、角开始张开的最后 170ms 才冲满。
     *   传 `1.0f` 就是「线在滑、角在开，方块同时在长大」；传 null 则完全不碰色块。
     */
    suspend fun swingDiagonal(growBlockTo: Float? = null) {
        coroutineScope {
            // 1. 角合拢成一根水平细线，`p` 退到 0.45（这一段仍在画面里）
            coroutineScope {
                launch {
                    cornerMorph.animateTo(0f, tween(130, easing = CubicBezierEasing(0.4f, 0f, 0.4f, 1f)))
                }
                cornerAssembly.animateTo(0.45f, tween(150, easing = CubicBezierEasing(0.3f, 0f, 0.35f, 1f)))
            }

            // 2. 色块的放大**从细线开始横移的这一刻起跑**（用户 2026-09-19 00:0x：
            //    「不是等到线运动完后方块才放大」），**但前 200ms 只轻轻长一点**。
            //
            //    为什么前段必须压住：色块和角标是**同一个颜色**，细线在横移那一段离画面中心
            //    约 0.61×半边长；方块只要长过那个半径，整条线就被同色的方块吃掉 ——
            //    用户 2026-09-19：「怎么到末尾 那个线还没有变角的时候 感觉被东西挡住了」。
            //    所以横移期间方块只从「角框内的小方块」长到 2.2 倍（远小于细线所在半径，
            //    **线全程看得见**），细线一落位就在最后 170ms 冲满。
            //
            //    为什么最后一段不会被挡：臂是**往画面外**跑（顶点最终落在 -gap），方块是
            //    **往画面内**长，用 (0.4, 0, 0.6, 1) 这条近线性的缓动时方块的边始终追不上臂，
            //    直到终点才把角吃掉 —— 那时候擦除已经开始了，看不出来。
            //
            //    演进史（别再绕回去）：
            //      23:4x  角整段演完 → delay(40) → 方块放大   ← 用户：「角在放大的时候方块也要跟着变大」
            //      23:5x  方块挂在**第三段**（张开成角）        ← 用户：「不是等到线运动完后方块才放大」
            //      00:0x  方块挂在**第二段**，370ms 匀速长满     ← 用户：「线还没有变角的时候 感觉被东西挡住了」
            //      现在   挂在**第二段**起跑，但前 200ms 只到 2.2 倍，后 170ms 才冲满
            val target = growBlockTo
            if (target != null) {
                val hover = bgScale.value * 2.2f
                launch {
                    bgScale.animateTo(
                        targetValue = target,
                        animationSpec = keyframes {
                            durationMillis = 370
                            hover at 200 with LinearEasing
                            target at 370 with CubicBezierEasing(0.4f, 0f, 0.6f, 1f)
                        },
                    )
                }
            }

            // 细线沿上下边缘横移到另一组对角 —— 这就是「滑动到指定处」
            diagAnim.animateTo(
                targetValue = if (diagAnim.value < 0.5f) 1f else 0f,
                animationSpec = tween(200, easing = CubicBezierEasing(0.35f, 0f, 0.25f, 1f)),
            )

            // 3. 在新位置**张开成完整的角**，同时继续滑出画面
            coroutineScope {
                launch {
                    cornerMorph.animateTo(1f, tween(160, easing = CubicBezierEasing(0.3f, 0f, 0.4f, 1f)))
                }
                cornerAssembly.animateTo(0.0f, tween(170, easing = CubicBezierEasing(0.3f, 0f, 0.5f, 1f)))
            }
        }
    }

    // ⚠️ 这里曾经有一个 `runCornerOnly()`：给底栏「用户自己点歌」用，**只演角标 + 后半段色块**。
    // 两个理由把它删了：
    //   1. 用户 2026-09-18：「我手动切歌的怎么没有那个取色方块」→ 色块必须有；
    //   2. 紧接着：「底栏的那个动画的前与后你搞反了」→ 只留后半段 **等于把「遮盖」那半丢了**，
    //      「擦除方向与遮盖方向相反」在底栏根本没有对照物。
    // 现在底栏（手动 / 静默）与详情页跑的是**同一套完整顺序**：
    //   色块按方向滑入（遮盖）→ 缩小到中心 → 角标拼「口」字 → 定格 → 角标炸开 + 换方位
    //   → 色块放大铺满 → 挖洞擦除（朝滑入的相反一侧）露出新封面。实现见 [runFullTransition]。

    /**
     * 详情页「进场」用的后半段：`cornerAssembly` 已经是 1（角标停在中心「口」字，
     * 里面是缩到最小的取色方块），从这里接着往下演 —— 炸开归位（**不换方位**）→
     * 色块放大铺满 → 挖洞擦除。
     */
    suspend fun runSecondHalf() {
        delay(60) // 稍作微调与详情页卡片滑入对齐
        displayedCover = coverUrl
        incomingCover = coverUrl
        showCover = false

        bgSlideOffset.snapTo(0f)
        bgScale.snapTo(shrinkScale()) // 起手 = 被角框住的小方块，不是 0（缩没了「口」里就空着）
        bgAlpha.snapTo(1f)
        cornerAssembly.snapTo(1f)
        cornerAlpha.snapTo(1f)
        cornerMorph.snapTo(1f) // 形态必须复位：上一次换位被打断会留下「线」的形态
        bgHoleProgress.snapTo(0f)

        // 1. 角标从拼合的「口」字炸开、滑回封面外角的静止形态（`cornerMorph` 保持 1f：
        //    进场不做「合拢成细线」那一套）；**同时**取色方块从角框里放大铺满 ——
        //    角在动的时候方块就在长大，两段同时起跑、时长同为 220ms
        //    （用户 2026-09-19：「不是等到线运动完后方块才放大」）。
        //    ⚠️ 这里曾经被我改成过「角先走完 → delay(40) → 方块再放大」的错位时序，
        //    是因为把用户的一句「不想动」读成了「不要改这里」；下一句他就把这条否掉了。
        //    **两个 suspend 调用不要串起来，必须包在同一个 `coroutineScope` 里并行。**
        //    ⚠️ **进场不换方位** —— 打开详情页时用户并没有切歌，顺手把对角换掉是莫名的一下
        //    （用户 2026-09-18：「在打开的时候不用播放角切换位置的动画」）。
        //    换方位只在**真的换歌**时演：`swingDiagonal()` 由 runFullTransition 调用。
        coroutineScope {
            launch {
                bgScale.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(220, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)),
                )
            }
            cornerAssembly.animateTo(0.0f, tween(220, easing = CubicBezierEasing(0.2f, 0.0f, 0.1f, 1.0f)))
        }

        // 2. 背景完全铺满后，将新封面以 100% 原始尺寸静态放置于底层（不缩放、不放大）
        showCover = true

        // 3. 然后在背景挖洞（从中心向四周钻洞开孔，镂空露出底层静态封面，~240ms）
        bgHoleProgress.animateTo(1.0f, tween(240, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)))

        bgAlpha.snapTo(0f)
        bgHoleProgress.snapTo(0f)
        incomingCover = null
    }

    suspend fun runFullTransition(dir: Int) {
        incomingCover = coverUrl
        showCover = true

        // 初始状态重置：取色背景从封面边缘根据方向准备滑入
        val startOffset = if (dir < 0) -1f else 1f
        bgSlideOffset.snapTo(startOffset)
        bgScale.snapTo(1f)
        bgAlpha.snapTo(1f)
        cornerAssembly.snapTo(0f)
        cornerAlpha.snapTo(1f)
        cornerMorph.snapTo(1f) // 形态必须复位：上一次换位被打断会留下「线」的形态
        bgHoleProgress.snapTo(0f)

        // 1. 取色背景根据切歌方向滑入封面方块 (200ms)
        bgSlideOffset.animateTo(0f, tween(200, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)))

        // 封面滑入完成后，旧封面已被遮盖，隐藏底层旧封面
        showCover = false
        delay(40)

        // 2. 缩小阶段（极具力量感的错位时序）：
        // ① 背景方块先完成缩小动画（1.0f -> 角框内净空，~180ms）
        //    ⚠️ 终点**不是 0** —— 缩到「被四角框住的那块小方块」就停住（见 [shrinkScale]）。
        //    缩到 0 的话角标拼出来的「口」里什么都没有，用户明确要求留一块小方块 + 间隔。
        bgScale.animateTo(shrinkScale(), tween(180, easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)))
        delay(40) // 确保背景已缩到角框内，角标才开始收拢

        // ② 背景缩到位后，角标才跟上迅速向中心收拢拼合成正方形「口」字（0.0f -> 1.0f，~180ms）
        cornerAssembly.animateTo(1.0f, tween(180, easing = CubicBezierEasing(0.2f, 0.0f, 0.1f, 1.0f)))

        // 3. 定格等待加载阶段：
        // 彻底停在「角标围成的口 + 中间那块小方块」的定格状态，并发预加载封面与等待音频流加载就绪
        val targetCover = coverUrl ?: incomingCover
        if (!targetCover.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val req = ImageRequest.Builder(context)
                        .data(targetCover)
                        .allowHardware(true)
                        .build()
                    context.imageLoader.execute(req)
                }
            }
        }
        val waitStartTime = System.currentTimeMillis()
        while (!currentAudioReady && System.currentTimeMillis() - waitStartTime < 3500) {
            delay(40)
        }
        delay(80) // 稍作顿挫留白

        // 4. 放大与挖洞阶段（后半段）：
        // ① 正方形裂开，角标炸开归位 + 在画面内换到另一组对角；**同一时刻**取色方块从角框里
        //    放大铺满（`growBlockTo = 1f` 走 swingDiagonal 的第三段，见那里的说明）。
        //    这里曾经是「角演完 → delay(40) → 色块再放大」，用户 2026-09-18 指出方向不对：
        //    「角在放大的时候取色方块也跟着放大」—— 两件事必须同时发生。
        swingDiagonal(growBlockTo = 1.0f)

        // ② 背景已铺满，底层就位新封面（100% 原始大小静态渲染，绝不放大缩放）
        displayedCover = incomingCover ?: coverUrl
        showCover = true

        // ③ 然后在背景挖洞（从中心向四周钻洞开孔，镂空露出底层静态封面，~240ms）
        bgHoleProgress.animateTo(1.0f, tween(240, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)))

        bgAlpha.snapTo(0f)
        bgHoleProgress.snapTo(0f)
        incomingCover = null
    }

    LaunchedEffect(transitionSeq) {
        // 首帧 onSizeChanged 还没到时 shrinkScale() 是 0，后半段会从「缩没了」起跑，
        // 看起来像切封面动画只演了一半。等量到边长再开演。
        if (frameWidthPx <= 0) {
            snapshotFlow { frameWidthPx }.first { it > 0 }
        }
        if (!hasEntered) {
            hasEntered = true
            lastHandledSeq = transitionSeq
            if (playSecondHalfOnEnter) {
                runSecondHalf()
            } else {
                displayedCover = coverUrl
            }
            return@LaunchedEffect
        }

        if (transitionSeq == 0L || transitionSeq == lastHandledSeq) {
            if (incomingCover == null) displayedCover = coverUrl
            return@LaunchedEffect
        }
        lastHandledSeq = transitionSeq

        // 手动点歌（`songTransitionManual`）以前走 `skipTransition = true`，跳过「滑入 + 缩小」
        // 只演后半段。用户 2026-09-18：「底栏的那个动画的前与后你搞反了」——
        // 只剩后半段等于把「遮盖」那半丢了，「擦除方向与遮盖方向相反」在底栏没有对照物。
        // 现在底栏与详情页跑**同一套完整顺序**（`runFullTransition` 自带状态复位，不用在外面补）。
        runFullTransition(direction)
    }

    LaunchedEffect(coverUrl) {
        if (incomingCover == null && bgAlpha.value <= 0.01f) {
            displayedCover = coverUrl
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // 核心封面与背景区域：裁剪于封面方块内部
        Box(
            Modifier
                .fillMaxSize()
                // 量一下容器边长，供 shrinkScale() 算「被角框住的小方块」的缩放下限。
                // 动画都在布局之后才跑，所以这里读到的一定是真实尺寸。
                .onSizeChanged { frameWidthPx = it.width }
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            // 底层：封面（始终以 100% 原始大小静态渲染，绝不缩放放大，通过背景挖洞逐渐显露）
            if (showCover && !displayedCover.isNullOrEmpty()) {
                Cover(
                    url = displayedCover,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 取色背景层：覆盖在封面之上
            // 切歌阶段：根据 dir 从封面边缘滑入 (bgSlideOffset: ±1f -> 0f)
            // 缩小阶段：bgScale 从 1f -> 角框内净空（**不是 0**，要留一块被角框住的小方块）
            // 展开阶段：bgScale 从角框内净空 -> 1f，与角标炸开同起同落
            // 挖洞阶段：在背景中心挖洞钻开 (bgHoleProgress: 0f -> 1f)
            if (bgAlpha.value > 0f && bgScale.value > 0.001f) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = bgSlideOffset.value * size.width
                            alpha = bgAlpha.value
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@Canvas

                    val scale = bgScale.value.coerceIn(0f, 1f)
                    val holeP = bgHoleProgress.value.coerceIn(0f, 1f)

                    if (scale < 1.0f && holeP <= 0.001f) {
                        // 缩放阶段（前半段缩小 / 后半段放大）：以中心为基准按比例缩放背景块
                        val cx = w / 2f
                        val cy = h / 2f
                        val halfW = (w / 2f) * scale
                        val halfH = (h / 2f) * scale
                        val bgRect = Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
                        drawRect(
                            color = accent,
                            topLeft = Offset(bgRect.left, bgRect.top),
                            size = Size(bgRect.width, bgRect.height),
                        )
                    } else if (holeP <= 0.001f) {
                        // 背景完全放大铺满封面。
                        // 取色方块本身**不描边** —— 它是一块纯色，四周加线就成了「一个带框的方块」，
                        // 和这个 app 其它地方一样只在真正需要分辨的边界上画线。
                        drawRect(color = accent)
                    } else if (holeP < 1.0f) {
                        // 卷帘开窗：色块**朝它滑进来的相反方向穿出去** ——
                        // 「擦除方向与遮盖方向相反」= 从哪边进来，就**不从哪边**退出去。
                        //   isNext（色块自右滑入、往左推进）→ 余块贴左、继续往左走，新封面自右侧推出
                        //   !isNext（色块自左滑入、往右推进）→ 余块贴右、继续往右走，新封面自左侧推出
                        // ⚠️ 这里曾经是「原路退回」（从哪边进来就从哪边退出去），用户明确否掉：
                        // 「擦除的时候与遮盖时候的动画方向相反」—— 他说的「方向」是**哪一侧**，
                        // 进来和出去落在同一侧就是「同一个方向」。**别再改回原路退回。**
                        val isNext = direction >= 0
                        val edgeX = if (isNext) w * (1f - holeP) else w * holeP

                        val bgRect = if (isNext) {
                            Rect(0f, 0f, edgeX, h)
                        } else {
                            Rect(edgeX, 0f, w, h)
                        }

                        // 绘制剩余取色背景块
                        if (bgRect.width > 0f) {
                            drawRect(
                                color = accent,
                                topLeft = Offset(bgRect.left, bgRect.top),
                                size = Size(bgRect.width, bgRect.height),
                            )
                        }

                        // ⚠️ 这里**不再画切边竖线**（原本是一条 2dp 取色线 + 它外侧的黑阴影）。
                        // 用户 2026-09-18：「滑动的时候不知道为什么会有个竖线在那边能不能移除了」
                        // —— 32dp 缩略图上那条线尤其像渲染残留。擦除现在是一块纯色直接退场。
                        // **不要再以「切边才有厚度」为由加回来**；要加得先问，因为这是明确否掉过的。
                    }
                }
            }
        }

        // 顶层：角标 / 拼合正方形「口」
        // 由 cornerAssembly 负责在边缘折角与中心正方形「口」之间连续插值
        if (cornerAlpha.value > 0f) {
            CornerAccents(
                // 与取色方块共用同一个动画色：角标随新配色一起淡过来
                color = accent,
                scale = 1.0f,
                offsetDp = 0f,
                assemblyProgress = cornerAssembly.value,
                // 方位由 swingDiagonal 在**画面内**动画着换（角先合拢成细线，细线横移过去再展开），
                // 不再是按切歌序号硬切。
                diagonal = diagAnim.value,
                // 形态平时恒为 1f（完整 L 角），只有换方位那一下才降到 0f
                morph = cornerMorph.value,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = cornerAlpha.value
                    },
            )
        }
    }
}

/**
 * 切歌时让一块内容错位入场：按 [index] 依次延迟、淡入、上浮。
 *
 * 每块单看都很小，但几块叠起来，整段切歌就多了一层「内容重新落下来」的节奏。
 * 只有背景在动的话，动画会显得没有作者。
 *
 * [resetKey] 变了就重来（通常传歌曲 id）。用 graphicsLayer 的 lambda 版求值，只走图层阶段不重组。
 */
@Composable
fun StaggerIn(
    resetKey: Any?,
    index: Int,
    modifier: Modifier = Modifier,
    stepMs: Int = 34,
    durationMs: Int = 260,
    riseDp: Int = 5,
    content: @Composable () -> Unit,
) {
    val anim = remember(resetKey) { Animatable(0f) }
    LaunchedEffect(resetKey, index) {
        anim.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = durationMs,
                delayMillis = index * stepMs,
                easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f),
            ),
        )
    }
    Box(
        modifier.graphicsLayer {
            val v = anim.value
            this.alpha = v
            translationY = (1f - v) * riseDp.dp.toPx()
        },
    ) { content() }
}

private val SCRAMBLE_CHARS = "0123456789#%&?$@!*+~<>/\\".toCharArray()

@Composable
fun ScrambleTimeText(
    text: String,
    triggerKey: Any?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Int = 12,
    muted: Boolean = true,
    color: Color = Color.Unspecified,
    bold: Boolean = false,
) {
    var isScrambling by remember { mutableStateOf(false) }
    var scrambleText by remember { mutableStateOf("") }
    var lastKey by remember { mutableStateOf<Any?>(null) }
    var lastRenderedText by remember { mutableStateOf(text) }
    var previousText by remember { mutableStateOf("") }
    var triggerSeq by remember { mutableIntStateOf(0) }

    if (triggerKey != null) {
        if (lastKey == null) {
            lastKey = triggerKey
            lastRenderedText = text
        } else if (lastKey != triggerKey) {
            previousText = lastRenderedText
            lastKey = triggerKey
            triggerSeq++
        } else if (!isScrambling && enabled) {
            lastRenderedText = text
        }
    }

    LaunchedEffect(triggerSeq, enabled) {
        if (!enabled || triggerSeq == 0) {
            isScrambling = false
            return@LaunchedEffect
        }

        val startStr = if (previousText.isNotEmpty()) previousText else text
        try {
            isScrambling = true

            // 1. 先逐字变成 /
            val slashChars = startStr.toCharArray()
            for (i in slashChars.indices) {
                slashChars[i] = '/'
                scrambleText = String(slashChars)
                delay(40)
            }

            // 2. 然后变成随机字符
            val targetLen = text.length.coerceAtLeast(1)
            repeat(5) {
                val randChars = CharArray(targetLen) {
                    SCRAMBLE_CHARS[kotlin.random.Random.nextInt(SCRAMBLE_CHARS.size)]
                }
                scrambleText = String(randChars)
                delay(35)
            }

            // 3. 然后再逐字变成正常的字
            val len = text.length.coerceAtLeast(1)
            val currentChars = CharArray(len) {
                SCRAMBLE_CHARS[kotlin.random.Random.nextInt(SCRAMBLE_CHARS.size)]
            }
            for (i in 0 until len) {
                val currentTarget = text
                val charToSet = if (i < currentTarget.length) currentTarget[i] else '0'
                currentChars[i] = charToSet
                for (j in (i + 1) until len) {
                    currentChars[j] = SCRAMBLE_CHARS[kotlin.random.Random.nextInt(SCRAMBLE_CHARS.size)]
                }
                scrambleText = String(currentChars)
                delay(40)
            }
        } finally {
            isScrambling = false
        }
    }

    val textToRender = if (isScrambling && enabled) scrambleText else text
    MonoText(
        text = textToRender,
        modifier = modifier,
        color = color,
        bold = bold,
        muted = muted,
        size = size,
    )
}

@Composable
fun ProgressLine(
    progress: Float,
    positionMs: Long = 0,
    durationMs: Long = 0,
    compact: Boolean = false,
    showTime: Boolean = !compact,
    modifier: Modifier = Modifier,
    trackKey: Any? = null,
    onSeek: ((Float) -> Unit)? = null,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragP by remember { mutableFloatStateOf(0f) }
    val live = progress.coerceIn(0f, 1f)

    // 走针位置用 Animatable：切歌时目标值会从旧进度砸到 0，这时给一段更长的「抽气」时间，
    // 让它像被吸回去而不是硬跳；平时跟随只 140ms。
    // 值在 Canvas 的 draw 阶段读，所以这里不会每帧重组。
    val fill = remember { Animatable(live) }
    LaunchedEffect(live, dragging) {
        val target = if (dragging) dragP else live
        val collapsing = fill.value - target > 0.05f
        fill.animateTo(
            targetValue = target,
            animationSpec = tween(
                durationMillis = when {
                    dragging -> 0
                    collapsing -> 320
                    else -> 140
                },
                easing = FastOutSlowInEasing,
            ),
        )
    }
    val posShown = if (dragging && durationMs > 0) (durationMs * dragP).toLong() else positionMs
    val barHeight = if (compact) 6.dp else 18.dp

    Column(modifier.fillMaxWidth()) {
        if (!compact && showTime) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                ScrambleTimeText(
                    text = fmtTime(posShown),
                    triggerKey = trackKey,
                    enabled = !dragging,
                    size = 12,
                    muted = true,
                )
                Spacer(Modifier.weight(1f))
                ScrambleTimeText(
                    text = fmtTime(durationMs),
                    triggerKey = if (scrambleDurationLabel()) trackKey else null,
                    enabled = !dragging && scrambleDurationLabel(),
                    size = 12,
                    muted = true,
                )
            }
        }
        val colors = LocalHypochloriteColors.current
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .then(
                    if (onSeek != null) {
                        Modifier.pointerInput(onSeek, durationMs) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                dragging = true
                                dragP = (down.position.x / w).coerceIn(0f, 1f)
                                onSeek(dragP)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.first()
                                    if (!change.pressed) {
                                        onSeek(dragP)
                                        dragging = false
                                        break
                                    }
                                    dragP = (change.position.x / w).coerceIn(0f, 1f)
                                    onSeek(dragP)
                                    change.consume()
                                }
                            }
                        }
                    } else Modifier,
                ),
        ) {
            val y = size.height / 2f
            val p = if (dragging) dragP else fill.value
            val thumb = if (compact) 4.dp.toPx() else 7.dp.toPx()
            val trackAlpha = if (colors.isLight) 0.15f else 0.35f
            drawLine(colors.text.copy(alpha = trackAlpha), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            val filled = (size.width * p).coerceIn(0f, size.width)
            if (filled > 0f) {
                drawLine(colors.text, Offset(0f, y), Offset(filled, y), strokeWidth = (if (compact) 1.5.dp else 2.dp).toPx())
            }
            val tx = (filled - thumb / 2f).coerceIn(0f, size.width - thumb)
            drawRect(colors.text, topLeft = Offset(tx, y - thumb / 2f), size = Size(thumb, thumb))
        }
        if (compact && showTime) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                ScrambleTimeText(
                    text = fmtTime(posShown),
                    triggerKey = trackKey,
                    enabled = !dragging,
                    size = 11,
                    muted = true,
                )
                Spacer(Modifier.weight(1f))
                ScrambleTimeText(
                    text = fmtTime(durationMs),
                    triggerKey = if (scrambleDurationLabel()) trackKey else null,
                    enabled = !dragging && scrambleDurationLabel(),
                    size = 11,
                    muted = true,
                )
            }
        }
    }
}

@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .background(Color.Red),
    )
}

@Composable
fun RowLine(text: String, onClick: () -> Unit, trailing: String? = null, onTrailing: (() -> Unit)? = null, on: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HoverBold(
            text = "- $text",
            onClick = onClick,
            modifier = Modifier.weight(1f),
        )
        if (on) {
            // selected = black weight via HoverBold pressed look: force bold
        }
        if (trailing != null && onTrailing != null) {
            HoverBold(trailing, onClick = onTrailing)
        }
    }
}

private val SearchExpandEase = CubicBezierEasing(0.05f, 0.9f, 0.1f, 1f)
private val SearchCollapsedWidth = 92.dp
private val SearchFrameHeight = 28.dp

/**
 * 四角裁切标：只画四段 L，不封边。给搜索框这类扁矩形用，
 * 封面对角准星 [CornerAccents] 是另一套几何，不要混。
 */
@Composable
fun FourCornerFrame(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    arm: Dp = 6.dp,
    stroke: Dp = 1.25.dp,
    content: @Composable () -> Unit,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color == Color.Unspecified) colors.text else color
    Box(
        modifier = modifier.drawWithContent {
            drawContent()
            val armPx = arm.toPx()
            val sw = stroke.toPx()
            val inset = sw / 2f
            val w = size.width
            val h = size.height
            if (w <= sw || h <= sw) return@drawWithContent
            fun hLine(x1: Float, x2: Float, y: Float) {
                drawLine(c, Offset(x1, y), Offset(x2, y), sw, StrokeCap.Square)
            }
            fun vLine(x: Float, y1: Float, y2: Float) {
                drawLine(c, Offset(x, y1), Offset(x, y2), sw, StrokeCap.Square)
            }
            hLine(inset, inset + armPx, inset)
            vLine(inset, inset, inset + armPx)
            hLine(w - inset, w - inset - armPx, inset)
            vLine(w - inset, inset, inset + armPx)
            hLine(inset, inset + armPx, h - inset)
            vLine(inset, h - inset, h - inset - armPx)
            hLine(w - inset, w - inset - armPx, h - inset)
            vLine(w - inset, h - inset, h - inset - armPx)
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 顶栏搜索：收起是带四角的 SEARCH，点一下从右侧向左拉长，字消失，里面空着等输入。
 */
@Composable
fun ExpandingSearch(
    open: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: () -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    hint: String = "",
    onClear: (() -> Unit)? = null,
    lockOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val density = LocalDensity.current
    val shown = open || lockOpen
    val expand by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(300, easing = SearchExpandEase),
        label = "search_expand",
    )
    var maxPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier.onSizeChanged { maxPx = it.width },
        contentAlignment = Alignment.CenterEnd,
    ) {
        val frameModifier = if (lockOpen) {
            Modifier
                .fillMaxWidth()
                .height(SearchFrameHeight)
        } else {
            val maxDp = with(density) { maxPx.toDp() }
            val start = if (maxPx == 0) SearchCollapsedWidth else SearchCollapsedWidth.coerceAtMost(maxDp)
            val frameWidth = start + (maxDp - start).coerceAtLeast(0.dp) * expand
            Modifier
                .width(if (maxPx == 0) SearchCollapsedWidth else frameWidth)
                .height(SearchFrameHeight)
        }
        val labelAlpha = (1f - expand * 1.7f).coerceIn(0f, 1f)
        val fieldAlpha = if (lockOpen) 1f else ((expand - 0.28f) / 0.72f).coerceIn(0f, 1f)
        FourCornerFrame(
            modifier = frameModifier.then(if (!shown) Modifier.clickableNoRipple(onOpen) else Modifier),
            color = if (shown) colors.text else colors.muted,
        ) {
            if (shown) {
                val clear = onClear
                val showClear = !query.isEmpty() && clear != null
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = fieldAlpha }
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocus(it.isFocused) },
                    textStyle = BodyStyle.copy(
                        color = colors.text,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                    ),
                    cursorBrush = SolidColor(colors.text),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(start = 10.dp, end = if (showClear) 30.dp else 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (query.isEmpty() && hint.isNotEmpty()) {
                                    BasicText(
                                        text = hint,
                                        style = BodyStyle.copy(
                                            color = colors.muted,
                                            fontSize = 13.sp,
                                            lineHeight = 16.sp,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                            if (clear != null && query.isNotEmpty()) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .clickableNoRipple { clear() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CrossIcon(color = colors.muted, size = 10.dp)
                                }
                            }
                        }
                    },
                )
            }
            if (!lockOpen && labelAlpha > 0.01f && (!open || query.isEmpty())) {
                BasicText(
                    text = "SEARCH",
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .graphicsLayer { alpha = labelAlpha },
                    style = BodyStyle.copy(
                        color = colors.muted,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        letterSpacing = 2.4.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun CrossIcon(
    color: Color = Color.Unspecified,
    size: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val ink = if (color == Color.Unspecified) colors.text else color
    Canvas(modifier = modifier.size(size)) {
        val sw = 1.4.dp.toPx()
        val inset = sw
        drawLine(
            ink,
            Offset(inset, inset),
            Offset(this.size.width - inset, this.size.height - inset),
            sw,
            StrokeCap.Square,
        )
        drawLine(
            ink,
            Offset(this.size.width - inset, inset),
            Offset(inset, this.size.height - inset),
            sw,
            StrokeCap.Square,
        )
    }
}

@Composable
fun SearchIcon(
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else colors.text
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = (size.toPx() * 0.12f).coerceAtLeast(1.5f)
        val radius = w * 0.32f
        val center = Offset(w * 0.40f, h * 0.40f)
        drawCircle(
            color = c,
            radius = radius,
            center = center,
            style = Stroke(width = stroke)
        )
        val start = Offset(center.x + radius * 0.707f, center.y + radius * 0.707f)
        val end = Offset(w * 0.88f, h * 0.88f)
        drawLine(
            color = c,
            start = start,
            end = end,
            strokeWidth = stroke * 1.2f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun HeartIcon(
    isLiked: Boolean,
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else if (isLiked) Color.Red else colors.muted
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.85f)
            cubicTo(w * 0.15f, h * 0.55f, 0f, h * 0.35f, 0f, h * 0.22f)
            cubicTo(0f, h * 0.08f, w * 0.18f, 0f, w * 0.36f, 0f)
            cubicTo(w * 0.43f, 0f, w * 0.47f, h * 0.06f, w * 0.5f, h * 0.14f)
            cubicTo(w * 0.53f, h * 0.06f, w * 0.57f, 0f, w * 0.64f, 0f)
            cubicTo(w * 0.82f, 0f, w, h * 0.08f, w, h * 0.22f)
            cubicTo(w, h * 0.35f, w * 0.85f, h * 0.55f, w * 0.5f, h * 0.85f)
            close()
        }
        if (isLiked) {
            drawPath(path, color = c)
        } else {
            drawPath(path, color = c, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun BackArrowIcon(
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else colors.text
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.65f, h * 0.15f)
            lineTo(w * 0.25f, h * 0.5f)
            lineTo(w * 0.65f, h * 0.85f)
        }
        drawPath(path, color = c, style = Stroke(sw, cap = StrokeCap.Square, join = StrokeJoin.Miter))
    }
}

@Composable
fun SettingsIcon(
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else colors.text
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val center = Offset(w / 2f, h / 2f)
        val stroke = (size.toPx() * 0.11f).coerceAtLeast(1.2f)
        val ringR = w * 0.27f
        val toothInner = w * 0.32f
        val toothOuter = w * 0.46f
        drawCircle(color = c, radius = ringR, center = center, style = Stroke(width = stroke))
        for (i in 0 until 8) {
            val a = (Math.PI / 4.0) * i
            val cos = kotlin.math.cos(a).toFloat()
            val sin = kotlin.math.sin(a).toFloat()
            drawLine(
                color = c,
                start = Offset(center.x + cos * toothInner, center.y + sin * toothInner),
                end = Offset(center.x + cos * toothOuter, center.y + sin * toothOuter),
                strokeWidth = stroke * 1.15f,
                cap = StrokeCap.Square,
            )
        }
    }
}

/** 播放列表图标：三条上长下短的横线。 */
@Composable
fun QueueIcon(
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else colors.text
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()
        val ys = listOf(h * 0.22f, h * 0.5f, h * 0.78f)
        val widths = listOf(w * 0.82f, w * 0.62f, w * 0.42f)
        for (i in ys.indices) {
            drawLine(
                color = c,
                start = Offset(w * 0.09f, ys[i]),
                end = Offset(w * 0.09f + widths[i], ys[i]),
                strokeWidth = sw,
                cap = StrokeCap.Square,
            )
        }
    }
}

/**
 * 一起听图标：两个并排的「人」（圆头 + 肩线），手绘风格与其余图标一致。
 * 不引 material-icons 是项目的既定约定。
 */
@Composable
fun TogetherIcon(
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else colors.text
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.5.dp.toPx()
        // 左边的人（实心：当前用户）
        drawCircle(color = c, radius = w * 0.15f, center = Offset(w * 0.33f, h * 0.33f))
        val lShoulder = Path().apply {
            moveTo(w * 0.10f, h * 0.85f)
            cubicTo(w * 0.12f, h * 0.60f, w * 0.54f, h * 0.60f, w * 0.56f, h * 0.85f)
        }
        drawPath(lShoulder, color = c, style = Stroke(sw, cap = StrokeCap.Round))
        // 右边的人：只画轮廓，表示「另一个人」
        drawCircle(
            color = c,
            radius = w * 0.12f,
            center = Offset(w * 0.70f, h * 0.36f),
            style = Stroke(sw),
        )
        val rShoulder = Path().apply {
            moveTo(w * 0.56f, h * 0.85f)
            cubicTo(w * 0.60f, h * 0.66f, w * 0.84f, h * 0.66f, w * 0.88f, h * 0.85f)
        }
        drawPath(rShoulder, color = c, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

@Composable
fun PlayModeIcon(
    mode: PlayMode,
    color: Color = Color.Unspecified,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val c = if (color != Color.Unspecified) color else colors.text
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.5.dp.toPx()
        when (mode) {
            PlayMode.SEQUENCE -> {
                val topPath = Path().apply {
                    moveTo(w * 0.2f, h * 0.38f)
                    lineTo(w * 0.2f, h * 0.3f)
                    lineTo(w * 0.75f, h * 0.3f)
                    lineTo(w * 0.75f, h * 0.45f)
                }
                drawPath(topPath, color = c, style = Stroke(sw, cap = StrokeCap.Square))
                val arrowTop = Path().apply {
                    moveTo(w * 0.65f, h * 0.35f)
                    lineTo(w * 0.75f, h * 0.47f)
                    lineTo(w * 0.85f, h * 0.35f)
                }
                drawPath(arrowTop, color = c, style = Stroke(sw, cap = StrokeCap.Square))

                val botPath = Path().apply {
                    moveTo(w * 0.8f, h * 0.62f)
                    lineTo(w * 0.8f, h * 0.7f)
                    lineTo(w * 0.25f, h * 0.7f)
                    lineTo(w * 0.25f, h * 0.55f)
                }
                drawPath(botPath, color = c, style = Stroke(sw, cap = StrokeCap.Square))
                val arrowBot = Path().apply {
                    moveTo(w * 0.35f, h * 0.65f)
                    lineTo(w * 0.25f, h * 0.53f)
                    lineTo(w * 0.15f, h * 0.65f)
                }
                drawPath(arrowBot, color = c, style = Stroke(sw, cap = StrokeCap.Square))
            }
            PlayMode.LOOP_ONE -> {
                val topPath = Path().apply {
                    moveTo(w * 0.15f, h * 0.3f)
                    lineTo(w * 0.8f, h * 0.3f)
                    lineTo(w * 0.8f, h * 0.45f)
                }
                drawPath(topPath, color = c, style = Stroke(sw, cap = StrokeCap.Square))
                val arrowTop = Path().apply {
                    moveTo(w * 0.7f, h * 0.35f)
                    lineTo(w * 0.8f, h * 0.47f)
                    lineTo(w * 0.9f, h * 0.35f)
                }
                drawPath(arrowTop, color = c, style = Stroke(sw, cap = StrokeCap.Square))

                val botPath = Path().apply {
                    moveTo(w * 0.85f, h * 0.7f)
                    lineTo(w * 0.2f, h * 0.7f)
                    lineTo(w * 0.2f, h * 0.55f)
                }
                drawPath(botPath, color = c, style = Stroke(sw, cap = StrokeCap.Square))
                val arrowBot = Path().apply {
                    moveTo(w * 0.3f, h * 0.65f)
                    lineTo(w * 0.2f, h * 0.53f)
                    lineTo(w * 0.1f, h * 0.65f)
                }
                drawPath(arrowBot, color = c, style = Stroke(sw, cap = StrokeCap.Square))

                drawLine(c, Offset(w * 0.44f, h * 0.46f), Offset(w * 0.5f, h * 0.41f), sw)
                drawLine(c, Offset(w * 0.5f, h * 0.41f), Offset(w * 0.5f, h * 0.59f), sw)
                drawLine(c, Offset(w * 0.44f, h * 0.59f), Offset(w * 0.56f, h * 0.59f), sw)
            }
            PlayMode.SHUFFLE -> {
                val p1 = Path().apply {
                    moveTo(w * 0.15f, h * 0.3f)
                    cubicTo(w * 0.45f, h * 0.3f, w * 0.55f, h * 0.7f, w * 0.8f, h * 0.7f)
                }
                drawPath(p1, color = c, style = Stroke(sw, cap = StrokeCap.Round))
                val a1 = Path().apply {
                    moveTo(w * 0.68f, h * 0.6f)
                    lineTo(w * 0.85f, h * 0.7f)
                    lineTo(w * 0.68f, h * 0.8f)
                }
                drawPath(a1, color = c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

                val p2 = Path().apply {
                    moveTo(w * 0.15f, h * 0.7f)
                    cubicTo(w * 0.45f, h * 0.7f, w * 0.55f, h * 0.3f, w * 0.8f, h * 0.3f)
                }
                drawPath(p2, color = c, style = Stroke(sw, cap = StrokeCap.Round))
                val a2 = Path().apply {
                    moveTo(w * 0.68f, h * 0.2f)
                    lineTo(w * 0.85f, h * 0.3f)
                    lineTo(w * 0.68f, h * 0.4f)
                }
                drawPath(a2, color = c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

/**
 * 底栏长按 → 滚到目标行 + 短暂高亮。
 *
 * 高度用**估算**而不是 `layoutInfo` 实测：跳转时目标行多半还没被布局出来（在视口外），
 * 实测拿不到值。字号固定、单行不换行的列表行高度是常量，估算足够精确。
 *
 * 滚动用 `scrollToItem` 而不是 `animateScrollToItem` —— 跨越几千行时逐行动画会卡死主线程。
 * 所以「段落感」交给高亮闪烁来给：两次快闪。
 *
 * [seq] 参与 key：索引没变时也要重跳一次（用户可能已经手动滚走了）。
 */
@Composable
fun ListJumpEffect(
    listState: LazyListState,
    target: Int?,
    seq: Int,
    itemHeight: Dp,
    onDone: () -> Unit,
) {
    val density = LocalDensity.current
    // 用 State<Dp> 而不是裸布尔：闪烁要连开连关两次，布尔值在重组成同一值时会被跳过
    var flash by remember { mutableStateOf(0f) }
    LaunchedEffect(target, seq) {
        if (target == null || target < 0) return@LaunchedEffect
        val px = with(density) { itemHeight.toPx() }
        val offset = (px * 0.35f).toInt()
        runCatching { listState.scrollToItem(target, scrollOffset = -offset) }
        repeat(2) {
            flash = 1f
            delay(150)
            flash = 0f
            delay(110)
        }
        onDone()
    }
    val highlight by animateFloatAsState(
        targetValue = flash,
        animationSpec = tween(if (flash > 0f) 70 else 140),
        label = "list_jump_highlight",
    )
    if (highlight > 0.001f) {
        val colors = LocalHypochloriteColors.current
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.text.copy(alpha = 0.20f * highlight)),
        )
    }
}
