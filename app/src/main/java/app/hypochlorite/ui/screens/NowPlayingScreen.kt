package app.hypochlorite.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.Route
import app.hypochlorite.netease.LyricLine
import app.hypochlorite.player.Lyrics
import app.hypochlorite.ui.AudioWaveLine
import app.hypochlorite.ui.CoverAnchor
import app.hypochlorite.ui.CoverBlurBackdrop
import app.hypochlorite.ui.CoverUrls
import app.hypochlorite.ui.HeartIcon
import app.hypochlorite.ui.HoverBold
import app.hypochlorite.ui.KineticCoverFrame
import app.hypochlorite.ui.MiniIconButton
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.NextIcon
import app.hypochlorite.ui.PauseIcon
import app.hypochlorite.ui.PlayIcon
import app.hypochlorite.ui.PlayModeIcon
import app.hypochlorite.ui.PrevIcon
import app.hypochlorite.ui.ProgressLine
import app.hypochlorite.ui.QueueIcon
import app.hypochlorite.ui.ScrollingLyricView
import app.hypochlorite.ui.StaggerIn
import app.hypochlorite.ui.VerticalLyric
import app.hypochlorite.ui.activityOrNull
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.coverPulse
import app.hypochlorite.ui.lyricIndex
import app.hypochlorite.ui.monetBackdrop
import app.hypochlorite.ui.playerClock
import app.hypochlorite.ui.rememberCoverPulse
import app.hypochlorite.ui.rememberRevealProgress
import app.hypochlorite.ui.reportPrimaryCoverAnchor
import app.hypochlorite.ui.theme.BodyStyle
import app.hypochlorite.ui.theme.HeavyBold
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import app.hypochlorite.ui.theme.Warn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun NowPlayingScreen(state: HomeState, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val dismissThreshold = with(density) { 130.dp.toPx() }

    /**
     * 英文原文歌词那一块的**固定高度** = 最长的一档：3 行 × 最大行高 20sp。
     *
     * 那块的字号是按句子长度分档的（12/13/14sp），行高 16/18/20sp，最多 3 行 ——
     * 取最大的一档定死，换句时高度就不再变，上面那颗封面也就不会再跟着重心上下挪。
     * 用 sp 换算而不是写死 dp：系统字号放大时一起放大，不会把字挤出去。
     */
    val lyricBlockHeight = with(density) { 60.sp.toDp() }

    val scope = rememberCoroutineScope()
    var offsetY by remember { mutableFloatStateOf(screenHeightPx) }
    var isDismissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        animate(
            initialValue = screenHeightPx,
            targetValue = 0f,
            animationSpec = tween(320, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)),
        ) { value, _ ->
            offsetY = value
        }
    }

    fun dismissCard() {
        if (isDismissing) return
        isDismissing = true
        scope.launch {
            animate(
                initialValue = offsetY,
                targetValue = screenHeightPx,
                animationSpec = tween(240, easing = FastOutLinearInEasing),
            ) { value, _ ->
                offsetY = value
            }
            vm.closeNowPlaying()
        }
    }

    BackHandler(enabled = !isDismissing) { dismissCard() }

    var showFullLyrics by remember { mutableStateOf(false) }
    val lyricsResolved = state.player.lyricsResolved
    val hasLyricLines = state.player.lyricLines.isNotEmpty()
    var lastKnownHasLyrics by remember { mutableStateOf(lyricsResolved && hasLyricLines) }
    LaunchedEffect(lyricsResolved, hasLyricLines) {
        if (!lyricsResolved) return@LaunchedEffect
        lastKnownHasLyrics = hasLyricLines
        if (!hasLyricLines) showFullLyrics = false
    }
    val hasLyrics = Lyrics.lyricsAvailable(lyricsResolved, hasLyricLines, lastKnownHasLyrics)

    // 卡面自己是不透明的（拖拽收起时要是实心卡片），所以它得自己承载背景层，
    // 否则根层的扩散波纹会被整块盖住。这里不再回调 finishReveal，交给根层统一收尾。
    val revealProgress = rememberRevealProgress(state.reveal)
    // 详情页大封面的脉动（独立时钟）
    val coverPulseScale = rememberCoverPulse(state.reveal)

    // 大封面离开时清掉锚点，波纹起点自动回落到首页底栏的缩略图
    DisposableEffect(Unit) {
        onDispose { CoverAnchor.clearPrimary() }
    }

    val song = state.player.current

    val context = LocalContext.current
    var displayedSong by remember { mutableStateOf(song) }
    var lastSongId by remember { mutableStateOf(song?.id) }
    var lastIndex by remember { mutableIntStateOf(state.player.index) }
    var manualTrigger by remember { mutableIntStateOf(0) }

    val bannerOffset = remember { Animatable(0f) }
    val bannerDrift = remember { Animatable(0f) }
    var showBanner by remember { mutableStateOf(false) }
    var bannerIsNext by remember { mutableStateOf(true) }
    var bannerSongName by remember { mutableStateOf("") }
    // 标题栏（歌名/艺人/专辑三行）的实测高度。横幅高度钉死成这个值：
    // 动画全程同一高度，不跟横幅文字走（用户 2026-09-19：高度不许中途变）。
    var titleBlockHeightPx by remember { mutableStateOf(0) }

    val draggableState = rememberDraggableState { delta ->
        if (!isDismissing) {
            offsetY = (offsetY + delta).coerceAtLeast(0f)
        }
    }

    LaunchedEffect(song?.id) {
        val nextSong = song
        val prevId = lastSongId
        val prevIdx = lastIndex
        val currIdx = state.player.index
        lastSongId = nextSong?.id
        lastIndex = currIdx

        if (nextSong != null && prevId != null && prevId != nextSong.id) {
            val isNext = state.songTransitionDir > 0

            // 提前在后台预加载下一首歌曲封面到内存缓存中
            if (!nextSong.cover.isNullOrEmpty()) {
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(CoverUrls.sized(nextSong.cover))
                        .size(256)
                        .build()
                )
            }
            val startOffset = if (isNext) -1f else 1f
            // 出场必须是 `-startOffset`（穿过去），不要改成 `startOffset`（原路退回）。
            // 2026-09-18 改成过原路退回，用户否掉：「怎么详情页的歌曲切换的动画被你改了」。
            // NEXT » 从左边进来、往右穿出去；文字漂移跟扫过同向。进场侧不要再改回右边。
            val endOffset = -startOffset
            val startDrift = if (isNext) -28f else 28f
            val endDrift = -startDrift
            bannerIsNext = isNext
            bannerSongName = nextSong.name
            try {
                showBanner = true
                bannerOffset.snapTo(startOffset)
                bannerDrift.snapTo(startDrift)
                launch {
                    bannerDrift.animateTo(endDrift, tween(760, easing = LinearEasing))
                }
                bannerOffset.animateTo(0f, tween(280, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)))
                displayedSong = nextSong
                delay(240)
                bannerOffset.animateTo(endOffset, tween(240, easing = FastOutLinearInEasing))
            } finally {
                showBanner = false
            }
            return@LaunchedEffect
        }
        manualTrigger = 0
        displayedSong = nextSong
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val p = (offsetY / screenHeightPx).coerceIn(0f, 1f)
                    drawRect(colors.scrim.copy(alpha = (1f - p) * 0.65f))
                }
                .clickableNoRipple { dismissCard() },
        )

        val coverUrl = state.backdropCoverUrl ?: song?.cover?.takeIf { it.isNotEmpty() }
        val coverVisible = state.monetEnabled && !coverUrl.isNullOrEmpty()
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = offsetY
                    val p = (offsetY / screenHeightPx).coerceIn(0f, 1f)
                    val scale = (1f - p * 0.08f).coerceIn(0.92f, 1f)
                    scaleX = scale
                    scaleY = scale
                    val hasOffset = offsetY > 0f
                    clip = hasOffset
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (hasOffset) (p * 16).dp else 0.dp,
                        bottomEnd = if (hasOffset) (p * 16).dp else 0.dp,
                    )
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (offsetY > dismissThreshold || velocity > 800f) {
                            dismissCard()
                        } else {
                            scope.launch {
                                animate(
                                    initialValue = offsetY,
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                ) { value, _ ->
                                    offsetY = value
                                }
                            }
                        }
                    },
                ),
        ) {
            CoverBlurBackdrop(
                coverUrl = if (state.monetEnabled) coverUrl else null,
                palette = state.palette,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .monetBackdrop(
                        state.palette,
                        state.reveal,
                        revealProgress,
                        LocalContext.current.activityOrNull(),
                        coverVisible,
                    )
                    .padding(horizontal = 14.dp),
            ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 32.dp, height = 4.dp)
                    .background(colors.muted.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
            )
        }
        Row(Modifier.padding(bottom = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // 这里原来是「← 返回」图标（点它 = dismissCard）。用户 2026-09-18 要求移除：
            // 「把详情页的返回图标移除」。收起详情页仍然有三种方式 —— 下滑手势、点卡片外的遮罩、
            // 系统返回键（`BackHandler(enabled = !isDismissing) { dismissCard() }`），不会有功能损失。
            // 留一个同宽的 Spacer 是为了让右边的 [查看歌词] 停在原位，不要顺手删掉。
            Spacer(Modifier.width(32.dp))
            Spacer(Modifier.weight(1f))
            if (showFullLyrics && hasLyrics) {
                HoverBold(
                    text = "[切回封面]",
                    onClick = { showFullLyrics = false },
                    color = colors.text,
                    size = 14,
                )
            } else if (hasLyrics) {
                HoverBold(
                    text = "[查看歌词]",
                    onClick = { showFullLyrics = true },
                    color = colors.muted,
                    size = 14,
                )
            } else {
                // 无歌词：不给进歌词页。占住同一行高，别让顶栏塌下去。
                Spacer(Modifier.height(22.dp))
            }
        }
        AudioWaveLine(playing = state.player.playing, audioLevel = vm::audioLevel)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val showLyrics = showFullLyrics && hasLyrics
            val lyricsProgress by animateFloatAsState(
                targetValue = if (showLyrics) 1f else 0f,
                animationSpec = tween(320, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)),
                label = "lyricsCover",
            )
            // 封面一直留在组合里：在歌词页切歌时完整动画仍在跑，切回封面才不会只剩后半段。
            // 不卸 KineticCoverFrame、不加 Offscreen：1126d7f 的互换/离屏合成在部分机型打开详情会崩。
            // 歌词 LazyColumn 首帧 scrollToItem 很贵：晚一点再挂，且等淡入近结束才允许跟滚。
            val mountFullLyrics = showLyrics || lyricsProgress > 0.25f
            val lyricsScrollReady = lyricsProgress >= 0.95f
            Column(
                Modifier
                    .align(Alignment.Center)
                    .zIndex(if (lyricsProgress < 0.5f) 1f else 0f)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = 1f - lyricsProgress
                        val scale = 1f - 0.05f * lyricsProgress
                        scaleX = scale
                        scaleY = scale
                        translationY = -12.dp.toPx() * lyricsProgress
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            if (lyricsProgress < 0.85f) {
                                NowPlayingSideLyric(
                                    vm = vm,
                                    lines = state.player.lyricLines,
                                    left = true,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            }
                        }
                        val isAudioReady = (state.player.current?.id == song?.id) &&
                            (state.player.playable != null || state.player.playing || state.player.error != null)
                        KineticCoverFrame(
                            coverUrl = song?.cover?.takeIf { it.isNotEmpty() } ?: state.backdropCoverUrl,
                            songId = song?.id,
                            direction = state.songTransitionDir,
                            transitionSeq = state.songTransitionSeq,
                            accentColor = state.palette.banner,
                            playSecondHalfOnEnter = true,
                            isAudioReady = isAudioReady,
                            modifier = Modifier
                                .size(200.dp)
                                .then(
                                    if (hasLyrics && lyricsProgress <= 0.02f) {
                                        Modifier.clickableNoRipple { showFullLyrics = true }
                                    } else {
                                        Modifier
                                    },
                                )
                                .coverPulse(coverPulseScale)
                                .reportPrimaryCoverAnchor(),
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (lyricsProgress < 0.85f) {
                                NowPlayingSideLyric(
                                    vm = vm,
                                    lines = state.player.lyricLines,
                                    left = false,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 高度**定死**（原来是 `heightIn(min = 46.dp)`，会被长句顶破：
                            // 字号按长度分 12/13/14sp、行数 1~3 行，一破，上面那颗封面就跟着
                            // 这栏的重心整体挪一截）。现在一律按最长的一档占位，换句时纹丝不动。
                            .padding(top = 20.dp)
                            .height(lyricBlockHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (lyricsProgress < 0.85f) {
                            NowPlayingEnglishLyric(
                                vm = vm,
                                lines = state.player.lyricLines,
                            )
                        }
                    }
            }
            if (mountFullLyrics) {
                NowPlayingFullLyrics(
                    vm = vm,
                    lines = state.player.lyricLines,
                    enableFollowScroll = lyricsScrollReady,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (lyricsProgress >= 0.5f) 1f else 0f)
                        .graphicsLayer {
                            alpha = lyricsProgress
                            translationY = 16.dp.toPx() * (1f - lyricsProgress)
                            val scale = 0.96f + 0.04f * lyricsProgress
                            scaleX = scale
                            scaleY = scale
                        },
                )
            }
        }
        if (!state.player.error.isNullOrEmpty()) {
            MonoText(state.player.error!!, color = Warn, modifier = Modifier.padding(bottom = 8.dp))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .padding(bottom = 8.dp),
        ) {
            // 三行按 34ms 依次落下来。displayedSong 是在横幅盖满之后才换的，
            // 所以这三行的入场正好压在「盖子合上」那一刻，节奏对得上。
            val shownSongId = displayedSong?.id
            Column(
                Modifier
                    .fillMaxWidth()
                    // 行数恒为三行（歌名 + 艺人 + 专辑，缺的那行画空行占位，见下面的 StaggerIn），
                    // 所以这栏高度对每首歌都一样。实测高度喂给下面的切歌横幅当下限：
                    // 横幅背景 = max(这栏高度, 横幅文字换行后的高度)，短歌名盖严、长歌名撑高。
                    .onSizeChanged { titleBlockHeightPx = it.height }
                    .heightIn(min = 42.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                StaggerIn(resetKey = shownSongId, index = 0, modifier = Modifier.fillMaxWidth()) {
                    MonoText(
                        text = displayedSong?.name ?: "未在播放",
                        bold = true,
                        size = 24,
                        maxLines = 1,
                        marquee = true,
                        // 白天取色模式 = 取色改暗（accent），其余 = 正文色
                        color = colors.titleInk,
                    )
                }
                val artists = displayedSong?.artists.orEmpty()
                val albumName = displayedSong?.album.orEmpty()
                // ⚠️ 下面两行的 `if` 已经去掉了 —— **这两行必须常在**。
                // 歌缺艺人 / 缺专辑时画一个只有字高、没有字形的空格占位（见 [InfoRowPlaceholder]），
                // 这栏就恒为三行高；盖在上面的切歌横幅是 `matchParentSize()` 跟着这栏走的，
                // 于是横幅、以及下面的进度条都不会再「一首歌一个高度」。
                // 别为了「空的就别画了」把 if 加回来。
                StaggerIn(resetKey = shownSongId, index = 1, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (artists.isEmpty()) {
                            InfoRowPlaceholder(size = 14)
                        } else {
                            artists.forEachIndexed { aIdx, artistName ->
                                if (aIdx > 0) {
                                    MonoText(" / ", muted = true, size = 14)
                                }
                                HoverBold(
                                    text = artistName,
                                    onClick = { vm.openArtist(artistName) },
                                    color = colors.muted,
                                    size = 14,
                                    maxLines = 1,
                                    padV = 4,
                                )
                            }
                        }
                    }
                }
                StaggerIn(resetKey = shownSongId, index = 2, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (albumName.isEmpty()) {
                            InfoRowPlaceholder(size = 13)
                        } else {
                            HoverBold(
                                text = albumName,
                                onClick = { vm.openAlbum(albumName, displayedSong?.albumId) },
                                color = colors.muted,
                                size = 13,
                                maxLines = 1,
                                marquee = true,
                                padV = 4,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (showBanner) {
                // 横幅高度钉死 = 标题栏三行的实测高度，动画全程恒定。
                // 之前是 heightIn(min=…)+内容撑高，长歌名换行会把横幅中途撑高，
                // 用户报「前部分是低的 后面才变高」—— 现在文字单行、高度不再跟内容走。
                // 没量到时回落 42.dp（titleBlockHeightPx 只在首帧可能还是 0）。
                val bannerFixedHeight = if (titleBlockHeightPx > 0) {
                    with(LocalDensity.current) { titleBlockHeightPx.toDp() }
                } else {
                    42.dp
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(bannerFixedHeight)
                        .graphicsLayer {
                            translationX = bannerOffset.value * size.width
                            clip = true
                        }
                        .background(LocalHypochloriteColors.current.banner)
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
                            // 字色 = bannerInk：白天取色是同色相的暗墨（取色改暗），深色近白，
                            // 纯黑白主题压横幅对比色（横幅是反相的深/浅灰块）
                            val bannerInk = LocalHypochloriteColors.current.bannerInk
                            BasicText(
                                text = if (bannerIsNext) "NEXT »" else "« PREV",
                                style = BodyStyle.copy(
                                    color = bannerInk,
                                    fontWeight = HeavyBold,
                                    fontSize = 24.sp,
                                    letterSpacing = 2.sp,
                                ),
                            )
                            BasicText(
                                text = bannerSongName,
                                style = BodyStyle.copy(
                                    color = bannerInk,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 14.sp,
                                ),
                                // 单行不换行、不带歌手（用户 2026-09-19 晚：换行和歌手都移除）
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        val curSong = displayedSong ?: song ?: state.player.current
        val activeSongId = song?.id ?: curSong?.id

        NowPlayingProgress(
            vm = vm,
            songDurationMs = curSong?.durationMs ?: 0L,
            trackKey = "${activeSongId ?: ""}_${state.songTransitionSeq}",
        )

        val likedPlaylist = (state.route as? Route.PlaylistSongs)?.playlist
        val isLiked = remember(curSong?.id, state.likedSongIds, likedPlaylist?.id, likedPlaylist?.specialType, likedPlaylist?.name, state.playlistSongs) {
            curSong != null && (
                state.likedSongIds.contains(curSong.id) ||
                    (
                        likedPlaylist != null &&
                            (likedPlaylist.specialType == 5 || likedPlaylist.name.contains("喜欢")) &&
                            state.playlistSongs.any { it.id == curSong.id }
                        )
                )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniIconButton(
                    onClick = {
                        manualTrigger = -1
                        vm.prev(force = true)
                    },
                ) {
                    PrevIcon(size = 16.dp)
                }
                MiniIconButton(
                    onClick = { vm.toggle() },
                ) {
                    if (state.player.playing) {
                        PauseIcon(size = 16.dp)
                    } else {
                        PlayIcon(size = 16.dp)
                    }
                }
                MiniIconButton(
                    onClick = {
                        manualTrigger = 1
                        vm.next()
                    },
                ) {
                    NextIcon(size = 16.dp)
                }
                MiniIconButton(
                    onClick = { vm.togglePlayMode() },
                ) {
                    PlayModeIcon(mode = state.player.playMode, size = 15.dp)
                }
            }
            Spacer(Modifier.weight(1f))
            MiniIconButton(
                onClick = { vm.openQueue() },
            ) {
                QueueIcon(size = 16.dp)
            }
            MiniIconButton(
                onClick = { vm.toggleLike(curSong ?: state.player.current) },
            ) {
                HeartIcon(isLiked = isLiked, size = 16.dp)
            }
        }
            }
        }
    }
}

@Composable
private fun NowPlayingSideLyric(
    vm: HypochloriteViewModel,
    lines: List<LyricLine>,
    left: Boolean,
    modifier: Modifier = Modifier,
) {
    val idx = lyricIndex(vm)
    val current = lines.getOrNull(idx) ?: return
    val onLeft = idx >= 0 && idx % 2 == 0
    if (onLeft != left) return
    val vertical = remember(current.text) { Lyrics.splitLyric(current.text).vertical } ?: return
    VerticalLyric(vertical, modifier)
}

@Composable
private fun NowPlayingEnglishLyric(
    vm: HypochloriteViewModel,
    lines: List<LyricLine>,
) {
    val idx = lyricIndex(vm)
    val current = lines.getOrNull(idx)
    val horizontal = remember(current?.text) {
        if (current != null) Lyrics.splitLyric(current.text).horizontal else null
    } ?: return
    val colors = LocalHypochloriteColors.current
    AnimatedContent(
        targetState = horizontal,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label = "en_lyric",
    ) { txt ->
        val len = txt.length
        val (fontSize, lineHeight) = when {
            len > 80 -> 12.sp to 16.sp
            len > 45 -> 13.sp to 18.sp
            else -> 14.sp to 20.sp
        }
        BasicText(
            text = txt,
            style = BodyStyle.copy(
                color = colors.titleInk,
                fontSize = fontSize,
                lineHeight = lineHeight,
                textAlign = TextAlign.Center,
            ),
            maxLines = 3,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun NowPlayingFullLyrics(
    vm: HypochloriteViewModel,
    lines: List<LyricLine>,
    modifier: Modifier = Modifier,
    enableFollowScroll: Boolean = true,
) {
    val idx = lyricIndex(vm)
    ScrollingLyricView(
        lines = lines,
        currentIndex = idx,
        modifier = modifier,
        onSeek = { vm.seekMs(it) },
        enableFollowScroll = enableFollowScroll,
    )
}

@Composable
private fun NowPlayingProgress(
    vm: HypochloriteViewModel,
    songDurationMs: Long,
    trackKey: String,
) {
    val clock = playerClock(vm)
    val durMs = if (clock.durationMs > 0) clock.durationMs else songDurationMs
    val progress = if (durMs > 0) clock.positionMs.toFloat() / durMs else 0f
    ProgressLine(
        progress = progress,
        positionMs = clock.positionMs,
        durationMs = durMs,
        trackKey = trackKey,
        onSeek = { vm.seekFraction(it) },
    )
}

/**
 * 切歌信息栏的**空行占位**：一个只有字高、没有字形的空格。
 *
 * 歌缺艺人或专辑时，那一行不能就这么不画 —— 少一行 = 这栏矮一截，
 * 而切歌横幅是 `matchParentSize()` 跟着这栏走的，横幅就会跟着矮一截，
 * 切歌时底边「咔」地跳一下。所以缺的行改成画这个占位。
 *
 * [size] 要传**同一行真名的字号**、留同样的 4dp（= `HoverBold` 的 `padV` 默认值）：
 * 高度是照着真名量出来的，将来改字号或 padV 要两边一起改。
 */
@Composable
private fun InfoRowPlaceholder(size: Int) {
    MonoText(
        text = " ",
        muted = true,
        size = size,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
