package app.hypochlorite.ui.sections

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.netease.LyricLine
import app.hypochlorite.player.PlayerSnapshot
import app.hypochlorite.ui.KineticCoverFrame
import app.hypochlorite.ui.MiniIconButton
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.NextIcon
import app.hypochlorite.ui.PauseIcon
import app.hypochlorite.ui.PlayIcon
import app.hypochlorite.ui.RoamChevrons
import app.hypochlorite.ui.StaggerIn
import app.hypochlorite.ui.lyricIndex
import app.hypochlorite.ui.reportMiniCoverAnchor
import app.hypochlorite.ui.theme.BodyStyle
import app.hypochlorite.ui.theme.HeavyBold
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import app.hypochlorite.ui.slice
import app.hypochlorite.ui.miniBarUi
import app.hypochlorite.ui.MiniBarUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun MiniBar(uiState: State<HomeState>, vm: HypochloriteViewModel) {
    val state by remember(uiState) { uiState.slice(HomeState::miniBarUi) }
    MiniBar(state, vm)
}

@Composable
internal fun MiniBar(state: HomeState, vm: HypochloriteViewModel) {
    val chrome = remember(state) { state.miniBarUi() }
    MiniBar(chrome, vm)
}

@Composable
private fun MiniBar(state: MiniBarUi, vm: HypochloriteViewModel) {
    MiniBarChrome(
        player = state.player,
        backdropCoverUrl = state.backdropCoverUrl,
        banner = state.banner,
        songTransitionDir = state.songTransitionDir,
        songTransitionSeq = state.songTransitionSeq,
        songTransitionManual = state.songTransitionManual,
        roamTransitionDir = state.roamTransitionDir,
        vm = vm,
    )
}

@Composable
private fun MiniBarChrome(
    player: PlayerSnapshot,
    backdropCoverUrl: String?,
    banner: Color,
    songTransitionDir: Int,
    songTransitionSeq: Long,
    songTransitionManual: Boolean,
    roamTransitionDir: Int,
    vm: HypochloriteViewModel,
) {
    val song = player.current
    val artist = song?.artists?.joinToString(" / ").orEmpty()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val fullDragPx = with(density) { 160.dp.toPx() }
    val touchSlopPx = with(density) { 10.dp.toPx() }
    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
    val fillProgress = remember { Animatable(0f) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var showFill by remember { mutableStateOf(false) }
    var swipeDir by remember { mutableIntStateOf(1) }
    var animJob by remember { mutableStateOf<Job?>(null) }
    val isTransitioning = roamTransitionDir != 0

    val miniBannerOffset = remember { Animatable(0f) }
    val miniBannerDrift = remember { Animatable(0f) }
    var showMiniBanner by remember { mutableStateOf(false) }
    var miniBannerIsNext by remember { mutableStateOf(true) }
    var miniBannerSongName by remember { mutableStateOf("") }

    /**
     * 「首次组合不播」守卫。
     *
     * 路由在 Home ↔ NowPlaying 之间来回时，`AnimatedContent` 的 `contentKey` 变了会把整棵
     * HomeScreen 重建一次，这个 `LaunchedEffect` 也就跟着重跑。少了守卫的话，只要当前
     * `songTransitionSeq` 非 0，**切一次页面就会把底栏这条横幅凭空重播一遍**。
     * 底栏的 `KineticCoverFrame` 一直有这个守卫（`hasEntered`），横幅这边漏了。
     */
    var miniBannerPrimed by remember { mutableStateOf(false) }

    LaunchedEffect(songTransitionSeq) {
        if (!miniBannerPrimed) {
            miniBannerPrimed = true
            return@LaunchedEffect
        }
        // 用户自己点歌 / 点下一首上一首 → 底栏不出这条横幅。
        // 缩略图那张取色方块动画在**手动点歌时照样演**（用户 2026-09-18 明确要求），
        // 但这条**滑动的歌名横幅**只在静默换歌（自动续播、一起听同步、漫游）时才出 ——
        // 用户手动点歌时正在看着列表，再飘一条横幅过来是多余的。
        if (songTransitionManual) return@LaunchedEffect
        if (songTransitionSeq == 0L || song == null) return@LaunchedEffect
        val isNext = songTransitionDir > 0
        miniBannerIsNext = isNext
        miniBannerSongName = song.name

        // NEXT » 从左边进来、往右穿出去（和列表行扫过、进漫游同一边）。
        val startOffset = if (isNext) -1f else 1f
        val endOffset = -startOffset
        val startDrift = if (isNext) -24f else 24f
        val endDrift = -startDrift

        try {
            showMiniBanner = true
            miniBannerOffset.snapTo(startOffset)
            miniBannerDrift.snapTo(startDrift)

            launch {
                miniBannerDrift.animateTo(endDrift, tween(760, easing = LinearEasing))
            }

            miniBannerOffset.animateTo(0f, tween(280, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)))
            delay(240)
            miniBannerOffset.animateTo(endOffset, tween(240, easing = FastOutLinearInEasing))
        } finally {
            showMiniBanner = false
        }
    }

    LaunchedEffect(roamTransitionDir) {
        if (roamTransitionDir == 0) {
            animJob?.cancel()
            showFill = false
            isDragging = false
            dragProgress = 0f
            fillProgress.snapTo(0f)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            val colors = LocalHypochloriteColors.current
            MiniProgressTrack(vm)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 歌曲信息栏：点击进入播放详情页，横向手势跟手滑动进入 Roam
                Row(
                    Modifier
                        .weight(1f)
                        .pointerInput(roamTransitionDir) {
                            awaitEachGesture {
                                if (roamTransitionDir != 0) return@awaitEachGesture
                                val down = awaitFirstDown()
                                animJob?.cancel()
                                var dragged = false
                                var total = 0f
                                var lastTime = down.uptimeMillis
                                var lastX = down.position.x
                                var velocityX = 0f
                                // 长按跳列表。和 clickable 的 onLongClick 同一个语义：
                                // 抬手时不触发 onClick，所以这里必须自己吃掉这次点击。
                                var longFired = false

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.first()
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y

                                    val now = change.uptimeMillis
                                    val dt = (now - lastTime).coerceAtLeast(1L)
                                    val currentX = change.position.x
                                    velocityX = ((currentX - lastX) / dt.toFloat()) * 1000f
                                    lastX = currentX
                                    lastTime = now

                                    // 按住不动到超时 → 跳列表。一旦真的拖起来就永久放弃长按，
                                    // 免得松手前手指刚好停在原地又凑满超时。
                                    if (!dragged && !longFired && !change.isConsumed &&
                                        now - down.uptimeMillis >= longPressTimeoutMs &&
                                        abs(dx) < touchSlopPx && abs(dy) < touchSlopPx
                                    ) {
                                        longFired = true
                                        vm.revealCurrentInList()
                                    }

                                    if (!change.pressed) {
                                        isDragging = false
                                        if (longFired) {
                                            // 已经跳过了，别再开播放详情页
                                        } else if (!dragged) {
                                            vm.openNowPlaying()
                                            showFill = false
                                        } else {
                                            val p = dragProgress
                                            val dir = swipeDir
                                            // 判断是否有向回拉动的手势惯性
                                            val slidingBack = (dir > 0 && velocityX < -200f) || (dir < 0 && velocityX > 200f)
                                            val flingForward = (dir > 0 && velocityX > 600f) || (dir < 0 && velocityX < -600f)

                                            if (!slidingBack && (p >= 0.45f || (p >= 0.2f && flingForward))) {
                                                // 顺势滑动达到触发标准：平滑填满剩余距离并触发 Roam
                                                animJob = scope.launch {
                                                    try {
                                                        fillProgress.snapTo(p)
                                                        val dur = ((1f - p) * 260).toInt().coerceIn(60, 260)
                                                        fillProgress.animateTo(
                                                            1f,
                                                            tween(dur, easing = FastOutSlowInEasing),
                                                        )
                                                        vm.startRoamTransition(dir)
                                                    } catch (_: Exception) {
                                                        showFill = false
                                                        fillProgress.snapTo(0f)
                                                    }
                                                }
                                            } else {
                                                // 手动滑回去或未达到阈值：收回动画，取消触发 Roam
                                                animJob = scope.launch {
                                                    fillProgress.snapTo(p)
                                                    val dur = (p * 200).toInt().coerceIn(60, 200)
                                                    fillProgress.animateTo(
                                                        0f,
                                                        tween(dur, easing = FastOutSlowInEasing),
                                                    )
                                                    showFill = false
                                                }
                                            }
                                        }
                                        break
                                    }

                                    if (dragged) {
                                        change.consume()
                                        total = dx
                                        val p = (abs(total) / fullDragPx).coerceIn(0f, 1f)
                                        swipeDir = if (total >= 0) 1 else -1
                                        isDragging = true
                                        showFill = true
                                        dragProgress = p
                                    } else if (abs(dx) > abs(dy) && abs(dx) > touchSlopPx) {
                                        dragged = true
                                        change.consume()
                                        total = dx
                                        val p = (abs(total) / fullDragPx).coerceIn(0f, 1f)
                                        swipeDir = if (total >= 0) 1 else -1
                                        isDragging = true
                                        showFill = true
                                        dragProgress = p
                                    }
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 底栏缩略图在**每次换歌**时都演完整动画（自动续播 / 一起听同步 / 漫游 / 用户自己点歌）。
                    // ⚠️ 2026-09-18 改：以前「用户自己点歌」走 `skipTransition = true`，只演后半段，
                    // 用户报「底栏的那个动画的前与后你搞反了」—— 少掉的正是「色块滑入 + 缩小」那前半段，
                    // 于是「擦除方向与遮盖方向相反」在底栏没有对照物。现在与详情页同一套完整顺序。
                    // 锚点必须保留：主题扩散的起点靠它兜底。
                    val isAudioReady = (player.current?.id == song?.id) &&
                        (player.playable != null || player.playing || player.error != null)
                    KineticCoverFrame(
                        coverUrl = song?.cover?.takeIf { it.isNotEmpty() } ?: backdropCoverUrl,
                        songId = song?.id,
                        direction = songTransitionDir,
                        transitionSeq = songTransitionSeq,
                        accentColor = banner,
                        isAudioReady = isAudioReady,
                        modifier = Modifier
                            .size(32.dp)
                            .reportMiniCoverAnchor(),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        StaggerIn(
                            resetKey = song?.id,
                            index = 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (player.roam) {
                                    MonoText("[漫游] ", color = colors.accent, size = 16, bold = true)
                                }
                                MonoText(song?.name ?: "未在播放", maxLines = 1, marquee = true, modifier = Modifier.weight(1f, fill = false), size = 14)
                            }
                        }
                        MiniBarCaption(
                            vm = vm,
                            songId = song?.id,
                            artist = artist,
                            error = player.error,
                            lyricLines = player.lyricLines,
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                // 控制按钮：暂停/播放 与 下一首 (图标式)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MiniIconButton(onClick = { vm.toggle() }) {
                        if (player.playing) {
                            PauseIcon(size = 16.dp)
                        } else {
                            PlayIcon(size = 16.dp)
                        }
                    }
                    MiniIconButton(onClick = { vm.next() }) {
                        NextIcon(size = 16.dp)
                    }
                }
            }
        }

        val effectiveShow = showFill || isTransitioning
        if (effectiveShow) {
            Box(
                Modifier
                    .matchParentSize()
                    .clipToBounds()
            ) {
                val currentDir = if (isTransitioning) roamTransitionDir else swipeDir
                val p = if (isTransitioning) 1f else if (isDragging) dragProgress else fillProgress.value.coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(p)
                        .align(if (currentDir > 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .background(LocalHypochloriteColors.current.banner)
                        .clipToBounds()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .align(if (currentDir > 0) Alignment.CenterEnd else Alignment.CenterStart)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val onBanner = LocalHypochloriteColors.current.onBanner
                        if (currentDir < 0) {
                            RoamChevrons(dir = -1, height = 14.dp, color = onBanner)
                        }
                        BasicText(
                            text = "ROAM",
                            style = BodyStyle.copy(
                                color = onBanner,
                                fontWeight = HeavyBold, // 粗体
                                fontSize = 24.sp, // 偏大超粗
                                letterSpacing = 3.sp,
                            ),
                        )
                        BasicText(
                            text = "漫游",
                            style = BodyStyle.copy(
                                color = onBanner,
                                fontWeight = FontWeight.Normal, // 细体
                                fontSize = 13.sp,
                            ),
                        )
                        if (currentDir > 0) {
                            RoamChevrons(dir = 1, height = 14.dp, color = onBanner)
                        }
                    }
                }
            }
        }

        if (showMiniBanner && !effectiveShow) {
            Box(
                Modifier
                    .matchParentSize()
                    .clipToBounds()
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            translationX = miniBannerOffset.value * size.width
                            clip = true // 背景为遮罩！
                        }
                        .background(LocalHypochloriteColors.current.banner)
                ) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                translationX = -miniBannerOffset.value * size.width + miniBannerDrift.value.dp.toPx()
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // 字色 = bannerInk：白天取色是同色相的暗墨（取色改暗），深色近白，
                            // 纯黑白主题压横幅对比色（横幅是反相的深/浅灰块）
                            val bannerInk = LocalHypochloriteColors.current.bannerInk
                            BasicText(
                                text = if (miniBannerIsNext) "NEXT »" else "« PREV",
                                style = BodyStyle.copy(
                                    color = bannerInk,
                                    fontWeight = HeavyBold, // 粗体偏大
                                    fontSize = 22.sp,
                                    letterSpacing = 2.sp,
                                ),
                            )
                            BasicText(
                                text = miniBannerSongName,
                                style = BodyStyle.copy(
                                    color = bannerInk,
                                    fontWeight = FontWeight.Normal, // 细体
                                    fontSize = 13.sp,
                                ),
                                // 单行不换行、不带歌手（用户 2026-09-19 晚）；容器定高，换行只会被裁
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniProgressTrack(vm: HypochloriteViewModel) {
    val clock = vm.clock.collectAsStateWithLifecycle()
    val colors = LocalHypochloriteColors.current
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp),
    ) {
        val now = clock.value
        val progress = if (now.durationMs > 0) {
            (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f)
        } else 0f
        drawRect(colors.text.copy(alpha = if (colors.isLight) 0.12f else 0.25f))
        drawRect(colors.text, size = size.copy(width = size.width * progress))
    }
}

@Composable
private fun MiniBarCaption(
    vm: HypochloriteViewModel,
    songId: String?,
    artist: String,
    error: String?,
    lyricLines: List<LyricLine>,
) {
    val index = lyricIndex(vm)
    val lyric = lyricLines.getOrNull(index)?.text.orEmpty()
    val sub = when {
        !error.isNullOrEmpty() -> error
        lyric.isNotEmpty() -> lyric
        artist.isNotEmpty() -> artist
        else -> ""
    }
    if (sub.isEmpty()) return
    val colors = LocalHypochloriteColors.current
    // resetKey 只跟歌曲 id：副标题内容跟着歌词每句在变，不能被它反复触发入场
    StaggerIn(
        resetKey = songId,
        index = 1,
        modifier = Modifier.fillMaxWidth(),
    ) {
        MonoText(
            sub,
            maxLines = 1,
            marquee = true,
            muted = error.isNullOrEmpty(),
            color = if (!error.isNullOrEmpty()) colors.warning else colors.muted,
            size = 11,
        )
    }
}
