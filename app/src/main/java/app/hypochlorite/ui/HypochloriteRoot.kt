@file:OptIn(ExperimentalFoundationApi::class)

package app.hypochlorite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.Route
import app.hypochlorite.Space
import app.hypochlorite.ThemeMode
import app.hypochlorite.netease.Crypto
import app.hypochlorite.netease.Playlist
import app.hypochlorite.netease.Quality
import app.hypochlorite.netease.Song
import app.hypochlorite.player.GAIN_MUTE_BELOW
import app.hypochlorite.player.Lyrics
import app.hypochlorite.player.formatSampleRate
import app.hypochlorite.player.gainDbForPercent
import app.hypochlorite.ui.theme.BodyStyle
import app.hypochlorite.ui.theme.HeavyBold
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import app.hypochlorite.ui.theme.HypochloriteTheme
import app.hypochlorite.ui.theme.Warn
import app.hypochlorite.ui.theme.argbHex
import app.hypochlorite.ui.theme.hex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val ArknightsDecel = CubicBezierEasing(0.05f, 0.9f, 0.1f, 1f)

@Composable
fun HypochloriteRoot(vm: HypochloriteViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()

    // 「跟随系统」要跟着系统深色开关实时变。Manifest 里已经把 uiMode 声明为自行处理
    // （不重建 Activity），这里再盯一眼 Compose 侧的 Configuration：切一次重算一次。
    // 取色模式下这一步是幂等的 —— 配置没变时算出来的 palette 与现有一致，不会触发扩散。
    val uiMode = LocalConfiguration.current.uiMode
    LaunchedEffect(uiMode) { vm.onConfigurationChanged() }

    HypochloriteTheme(state.palette) {
        MonetBackground(
            palette = state.palette,
            reveal = state.reveal,
            coverUrl = if (state.monetEnabled) state.backdropCoverUrl else null,
            onRevealFinished = { id -> vm.finishReveal(id) },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                AnimatedContent(
                    targetState = state.route,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        // 这一层只管**真正的路由切换**（歌单 / 歌手 / 专辑 / 登录 / 设置 / 一起听）。
                        // 详情页不是路由：它是 `state.nowPlayingOpen` 驱动的覆盖层（见下面 `NowPlayingScreen`），
                        // 所以「详情页开场 / 收场」永远不会走到这里 —— 想调详情页的方向别来这里改，白改。
                        (fadeIn(tween(220)) + slideInHorizontally(tween(240)) { it / 10 }) togetherWith
                            (fadeOut(tween(160)) + slideOutHorizontally(tween(180)) { -it / 12 })
                    },
                    contentKey = {
                        when (it) {
                            is Route.PlaylistSongs -> "pl-${it.playlist.id}"
                            is Route.ArtistDetail -> "artist-${it.artistName}"
                            is Route.AlbumDetail -> "album-${it.albumName}-${it.albumId}"
                            else -> it::class.simpleName
                        }
                    },                    label = "route",
                ) { route ->
                    when (route) {
                        Route.Home -> HomeScreen(state, vm)
                        is Route.PlaylistSongs -> PlaylistScreen(route.playlist, state, vm)
                        is Route.ArtistDetail -> ArtistScreen(route.artistName, state, vm)
                        is Route.AlbumDetail -> AlbumScreen(route.albumName, route.albumId, state, vm)
                        Route.Login -> LoginScreen(state, vm)
                        Route.Config -> ConfigScreen(state, vm)
                        Route.ListenTogether -> ListenTogetherScreen(state, vm)
                        Route.NowPlaying, Route.Roam -> HomeScreen(state, vm)
                    }
                }

                if (state.nowPlayingOpen) {
                    NowPlayingScreen(state, vm)
                }

                // 播放列表（队列管理）：盖在详情页之上 —— 从详情页的控制行点队列图标唤起，
                // 详情页留在原地，收掉面板就回到详情页（网易云的层级关系）
                if (state.queueOpen) {
                    PlayQueuePanel(state, vm)
                }

                // 云盾验证页必须内嵌：它的验证结果是以 cookie 形式落在「那个浏览器」里的，
                // 甩给系统浏览器就等于把结果丢在门外（见 HypochloriteViewModel.completeLoginVerify）。
                if (state.showVerify && state.loginVerifyUrl.isNotEmpty()) {
                    LoginVerifyOverlay(state.loginVerifyUrl, vm)
                }

                if (state.roamTransitionDir != 0) {
                    RoamTransitionOverlay(
                        dir = state.roamTransitionDir,
                        onReadyToPlay = { vm.enterRoamNow() },
                        onFinished = { vm.finishRoamTransition() },
                    )
                }

                // 一起听的操作反馈。长按推歌可能发生在任意列表页，房间页看不到 —— 所以挂根节点。
                ListenToast(state.listen.toast, vm::clearListenToast)
            }
        }
    }
}

@Composable
private fun RoamTransitionOverlay(
    dir: Int,
    onReadyToPlay: () -> Unit,
    onFinished: () -> Unit,
) {
    BackHandler { /* consume back during transition */ }

    // dir > 0（底栏右滑）：从左边席卷进来，再从右边穿出去。
    // 必须跟手势预览（从左往右铺满）同一边；从右边进来会感觉整段反了。
    val enterBg = if (dir > 0) -1f else 1f
    val exitBg = -enterBg
    val bgOffset = remember(dir) { Animatable(enterBg) }

    DisposableEffect(dir) {
        onDispose {
            onFinished()
        }
    }

    LaunchedEffect(dir) {
        bgOffset.snapTo(enterBg)

        // 1. 背景作为遮罩从屏幕边缘席卷滑入 (280ms)
        bgOffset.animateTo(0f, tween(280, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)))

        // 2. 后台启动切页加载并在中心停留
        onReadyToPlay()
        delay(260)

        // 3. 背景遮罩继续席卷滑出屏幕，切除并带走文字 (260ms)
        bgOffset.animateTo(exitBg, tween(260, easing = FastOutLinearInEasing))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        // 背景容器：自身携带 clip = true 作为动态遮罩，背景扫到哪里，哪里才能看到内部文字
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = bgOffset.value * size.width
                    clip = true
                }
                .background(LocalHypochloriteColors.current.banner),
        ) {
            // 文字容器：反向抵消背景自身位移（静态锚定屏幕中央，不跟随时空漂移）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX = -bgOffset.value * size.width
                    },
            ) {
                val onBanner = LocalHypochloriteColors.current.onBanner
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (dir < 0) {
                        BasicText(
                            text = "<<<  ",
                            style = BodyStyle.copy(
                                color = onBanner.copy(alpha = 0.65f),
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp,
                            ),
                        )
                    }
                    BasicText(
                        text = "ROAM",
                        style = BodyStyle.copy(
                            color = onBanner,
                            fontWeight = HeavyBold, // 超粗体
                            fontSize = 44.sp, // 偏大超粗
                            letterSpacing = 5.sp,
                        ),
                    )
                    BasicText(
                        text = "  漫游",
                        style = BodyStyle.copy(
                            color = onBanner,
                            fontWeight = FontWeight.Normal, // 细体
                            fontSize = 16.sp,
                            letterSpacing = 2.sp,
                        ),
                    )
                    if (dir > 0) {
                        BasicText(
                            text = "  >>>",
                            style = BodyStyle.copy(
                                color = onBanner.copy(alpha = 0.65f),
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp,
                            ),
                        )
                    }
                }
                BasicText(
                    text = "// 心动漫游 · 智能探索",
                    style = BodyStyle.copy(
                        color = onBanner.copy(alpha = 0.65f),
                        fontWeight = FontWeight.Normal, // 细体
                        fontSize = 13.sp,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(state: HomeState, vm: HypochloriteViewModel) {
    val pager = rememberPagerState(initialPage = state.space.ordinal, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(pager.currentPage) {
        vm.setSpace(Space.entries[pager.currentPage])
    }
    var searchFocused by remember { mutableStateOf(false) }

    val closeSearch: () -> Unit = {
        vm.setSearchOpen(false)
        searchFocused = false
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    val toggleSearch: () -> Unit = {
        if (state.searchOpen) {
            closeSearch()
        } else {
            vm.setSearchOpen(true)
        }
    }

    LaunchedEffect(state.searchOpen) {
        if (state.searchOpen) {
            delay(260)
            runCatching { searchFocusRequester.requestFocus() }
        } else {
            searchFocused = false
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    BackHandler(enabled = state.searchOpen || searchFocused) { closeSearch() }

    val searchShown = state.searchOpen

    Column(Modifier.fillMaxSize()) {
        Header(
            state,
            vm,
            onToggleSearch = toggleSearch,
            searchFocusRequester = searchFocusRequester,
            onSearchFocus = { searchFocused = it },
        )
        Hairline(Modifier.padding(horizontal = 14.dp))
        if (searchShown && state.searchMsg.isNotEmpty()) {
            val colors = LocalHypochloriteColors.current
            MonoText(
                state.searchMsg,
                color = if (state.searchMsg.contains("没有") || state.searchMsg.contains("输入")) colors.warning else colors.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        SpaceTabs(
            selected = pager.currentPage,
            onSelect = { page ->
                scope.launch { pager.animateScrollToPage(page) }
            },
        )
        if (searchShown && (state.searchPlaylists.isNotEmpty() || state.searchSongs.isNotEmpty() || state.searchMsg.isNotEmpty())) {
            SearchResults(state, vm, Modifier.weight(1f))
        } else {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    0 -> PlaylistFlow(state.liked, state.loading, state.loggedIn, vm)
                    1 -> PlaylistFlow(state.mine, state.loading, state.loggedIn, vm)
                    else -> DailyFlow(state, vm)
                }
            }
        }
        MiniBar(state, vm)
    }
}

@Composable
private fun Header(
    state: HomeState,
    vm: HypochloriteViewModel,
    onToggleSearch: () -> Unit,
    searchFocusRequester: FocusRequester,
    onSearchFocus: (Boolean) -> Unit,
) {
    val colors = LocalHypochloriteColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clickableNoRipple { onToggleSearch() }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoMark()
            Spacer(Modifier.width(8.dp))
            MonoText("Hypochlorite", bold = true, size = 22)
        }
        ExpandingSearch(
            open = state.searchOpen,
            query = state.searchQuery,
            onQueryChange = vm::setSearchQuery,
            onOpen = { vm.setSearchOpen(true) },
            onSearch = { vm.search() },
            focusRequester = searchFocusRequester,
            onFocus = onSearchFocus,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 6.dp),
        )
        // 在房间里时这个图标常亮，点进去能看到房间和成员
        MiniIconButton(onClick = { vm.openListen() }) {
            TogetherIcon(
                color = if (state.listen.connected) colors.accent else colors.text,
                size = 17.dp,
            )
        }
        Spacer(Modifier.width(6.dp))
        MiniIconButton(onClick = { vm.openConfig() }) {
            SettingsIcon(color = colors.muted, size = 16.dp)
        }
    }
}

@Composable
private fun SpaceTabs(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("[收藏]", "[歌单]", "[日推]")
    val colors = LocalHypochloriteColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        labels.forEachIndexed { i, label ->
            HoverBold(
                text = label,
                onClick = { onSelect(i) },
                color = if (i == selected) colors.text else colors.muted,
                on = i == selected,
            )
        }
    }
}

@Composable
private fun SearchResults(state: HomeState, vm: HypochloriteViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    ListJumpEffect(
        listState = listState,
        target = state.pendingListJump,
        seq = state.listJumpSeq,
        itemHeight = SongRowHeight,
        onDone = { vm.clearListJump() },
    )
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
    ) {
        if (state.searchPlaylists.isNotEmpty()) {
            item { MonoText("[相关歌单]", muted = true, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
            itemsIndexed(state.searchPlaylists, key = { i, pl -> "sp-${pl.id}-$i" }) { i, pl ->
                PlaylistRow(
                    playlist = pl,
                    onClick = { vm.openPlaylist(pl) },
                    index = i,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (state.searchSongs.isNotEmpty()) {
            item { MonoText("[单曲列表]", muted = true, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)) }
            itemsIndexed(state.searchSongs, key = { i, song -> "ss-${song.id}-$i" }) { i, song ->
                SongRow(
                    song = song,
                    onClick = { vm.playSong(song) },
                    onLongPress = { vm.listenPushSong(song) },
                    on = state.player.current?.id == song.id,
                    isLiked = state.likedSongIds.contains(song.id),
                    index = i,
                )
            }
        }
    }
}

@Composable
private fun PlaylistFlow(items: List<Playlist>, loading: Boolean, loggedIn: Boolean, vm: HypochloriteViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
    ) {
        if (items.isEmpty()) {
            item {
                val hint = when {
                    loading -> "正在同步歌单…"
                    !loggedIn -> "未登录，右上角设置里可登录账号"
                    else -> "歌单为空，点右上角搜索图标添加歌曲"
                }
                MonoText(hint, muted = true, modifier = Modifier.padding(top = 12.dp))
            }
        }
        itemsIndexed(items, key = { i, pl -> "${pl.id}-$i" }) { i, pl ->
            PlaylistRow(
                playlist = pl,
                onClick = { vm.openPlaylist(pl) },
                index = i,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun DailyFlow(state: HomeState, vm: HypochloriteViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
    ) {
        if (state.dailyPlaylists.isEmpty() && state.dailySongs.isEmpty()) {
            item {
                MonoText(
                    if (state.loading) "正在获取今日推荐…" else if (!state.loggedIn) "登录后获取个性化每日推荐" else "今日暂无推荐数据",
                    muted = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        itemsIndexed(state.dailyPlaylists, key = { i, pl -> "p-${pl.id}-$i" }) { i, pl ->
            PlaylistRow(
                playlist = pl,
                onClick = { vm.openPlaylist(pl) },
                index = i,
                modifier = Modifier.animateItem(),
            )
        }
        itemsIndexed(state.dailySongs, key = { i, s -> "s-${s.id}-$i" }) { i, song ->
            SongRow(
                song = song,
                onClick = { vm.playAll(state.dailySongs, i) },
                onLongPress = { vm.listenPushSong(song) },
                on = state.player.current?.id == song.id,
                isLiked = state.likedSongIds.contains(song.id),
                index = i,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun MiniBar(state: HomeState, vm: HypochloriteViewModel) {
    val song = state.player.current
    val lyric = state.player.lyricLines.getOrNull(state.player.lyricIndex)?.text.orEmpty()
    val artist = song?.artists?.joinToString(" / ").orEmpty()
    val progress = if (state.player.durationMs > 0) {
        (state.player.positionMs.toFloat() / state.player.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
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
    val isTransitioning = state.roamTransitionDir != 0

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

    LaunchedEffect(state.songTransitionSeq) {
        if (!miniBannerPrimed) {
            miniBannerPrimed = true
            return@LaunchedEffect
        }
        // 用户自己点歌 / 点下一首上一首 → 底栏不出这条横幅。
        // 缩略图那张取色方块动画在**手动点歌时照样演**（用户 2026-09-18 明确要求），
        // 但这条**滑动的歌名横幅**只在静默换歌（自动续播、一起听同步、漫游）时才出 ——
        // 用户手动点歌时正在看着列表，再飘一条横幅过来是多余的。
        if (state.songTransitionManual) return@LaunchedEffect
        if (state.songTransitionSeq == 0L || song == null) return@LaunchedEffect
        val isNext = state.songTransitionDir > 0
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

    LaunchedEffect(state.roamTransitionDir) {
        if (state.roamTransitionDir == 0) {
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(colors.text.copy(alpha = if (colors.isLight) 0.12f else 0.25f)),
            ) {
                if (progress > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction = progress)
                            .fillMaxHeight()
                            .background(colors.text),
                    )
                }
            }
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
                        .pointerInput(state.roamTransitionDir) {
                            awaitEachGesture {
                                if (state.roamTransitionDir != 0) return@awaitEachGesture
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
                    val isAudioReady = (state.player.current?.id == song?.id) &&
                        (state.player.playable != null || state.player.playing || state.player.error != null)
                    KineticCoverFrame(
                        coverUrl = song?.cover?.takeIf { it.isNotEmpty() } ?: state.backdropCoverUrl,
                        songId = song?.id,
                        direction = state.songTransitionDir,
                        transitionSeq = state.songTransitionSeq,
                        accentColor = state.palette.banner,
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
                                if (state.player.roam) {
                                    MonoText("[漫游] ", color = colors.accent, size = 16, bold = true)
                                }
                                MonoText(song?.name ?: "未在播放", maxLines = 1, marquee = true, modifier = Modifier.weight(1f, fill = false), size = 14)
                            }
                        }
                        val sub = when {
                            !state.player.error.isNullOrEmpty() -> state.player.error!!
                            lyric.isNotEmpty() -> lyric
                            artist.isNotEmpty() -> artist
                            else -> ""
                        }
                        if (sub.isNotEmpty()) {
                            // resetKey 只跟歌曲 id：副标题内容跟着歌词每句在变，不能被它反复触发入场
                            StaggerIn(
                                resetKey = song?.id,
                                index = 1,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MonoText(
                                    sub,
                                    maxLines = 1,
                                    marquee = true,
                                    muted = state.player.error.isNullOrEmpty(),
                                    color = if (!state.player.error.isNullOrEmpty()) colors.warning else colors.muted,
                                    size = 11,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                // 控制按钮：暂停/播放 与 下一首 (图标式)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MiniIconButton(onClick = { vm.toggle() }) {
                        if (state.player.playing) {
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
                val currentDir = if (isTransitioning) state.roamTransitionDir else swipeDir
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
private fun PlaylistScreen(pl: Playlist, state: HomeState, vm: HypochloriteViewModel) {
    BackHandler { vm.back() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clickableNoRipple { vm.back() }
                    .padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrowIcon(size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            MonoText(pl.name, maxLines = 1, marquee = true)
        }
        Hairline(Modifier.padding(horizontal = 14.dp))
        val listState = rememberLazyListState()
        // 底栏长按跳到本列表里的当前歌（见 vm.revealCurrentInList）
        ListJumpEffect(
            listState = listState,
            target = state.pendingListJump,
            seq = state.listJumpSeq,
            itemHeight = SongRowHeight,
            onDone = { vm.clearListJump() },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!pl.cover.isNullOrEmpty()) {
                        Cover(pl.cover, modifier = Modifier.size(72.dp))
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        MonoText(pl.name, bold = true, size = 22, maxLines = 2)
                        if (pl.trackCount > 0) {
                            MonoText("${pl.trackCount} 首歌曲", muted = true, size = 13, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            if (state.loading) item { MonoText("正在获取歌曲列表…", muted = true, modifier = Modifier.padding(top = 8.dp)) }
            itemsIndexed(state.playlistSongs, key = { i, s -> "${s.id}-$i" }) { i, song ->
                SongRow(
                    song = song,
                    onClick = { vm.playAll(state.playlistSongs, i) },
                    onLongPress = { vm.listenPushSong(song) },
                    on = state.player.current?.id == song.id,
                    isLiked = state.likedSongIds.contains(song.id),
                    index = i,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        MiniBar(state, vm)
    }
}

@Composable
private fun ArtistScreen(artistName: String, state: HomeState, vm: HypochloriteViewModel) {
    BackHandler { vm.back() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clickableNoRipple { vm.back() }
                    .padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrowIcon(size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            MonoText(artistName, maxLines = 1, marquee = true)
        }
        Hairline(Modifier.padding(horizontal = 14.dp))
        val listState = rememberLazyListState()
        ListJumpEffect(
            listState = listState,
            target = state.pendingListJump,
            seq = state.listJumpSeq,
            itemHeight = SongRowHeight,
            onDone = { vm.clearListJump() },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cover = state.artistCover ?: state.artistSongs.firstOrNull()?.cover
                    if (!cover.isNullOrEmpty()) {
                        Cover(cover, modifier = Modifier.size(72.dp))
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        MonoText(artistName, bold = true, size = 24)
                        if (state.artistSongs.isNotEmpty()) {
                            val countText = if (state.artistTotal > state.artistSongs.size) {
                                "${state.artistSongs.size} / ${state.artistTotal} 首歌曲"
                            } else {
                                "${state.artistSongs.size} 首歌曲"
                            }
                            MonoText(countText, muted = true, size = 13, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            if (state.artistLoading) {
                item { MonoText("…", muted = true) }
            } else if (state.artistSongs.isEmpty()) {
                item { MonoText("暂无歌曲", muted = true) }
            }
            itemsIndexed(state.artistSongs, key = { i, s -> "${s.id}-$i" }) { i, song ->
                SongRow(
                    song = song,
                    onClick = { vm.playAll(state.artistSongs, i) },
                    onLongPress = { vm.listenPushSong(song) },
                    on = state.player.current?.id == song.id,
                    isLiked = state.likedSongIds.contains(song.id),
                    index = i,
                    modifier = Modifier.animateItem(),
                )
            }
            if (state.artistHasMore) {
                item {
                    LaunchedEffect(state.artistSongs.size) {
                        vm.loadMoreArtistSongs()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.artistLoadingMore) {
                            MonoText("加载更多中…", muted = true, size = 13)
                        } else {
                            HoverBold(
                                text = "[点击加载更多歌曲 (${state.artistSongs.size}/${state.artistTotal})]",
                                onClick = { vm.loadMoreArtistSongs() },
                                size = 13,
                            )
                        }
                    }
                }
            }
        }
        MiniBar(state, vm)
    }
}

@Composable
private fun AlbumScreen(albumName: String, albumId: String?, state: HomeState, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
    BackHandler { vm.back() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clickableNoRipple { vm.back() }
                    .padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrowIcon(size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            MonoText(albumName, maxLines = 1, marquee = true)
        }
        Hairline(Modifier.padding(horizontal = 14.dp))
        val listState = rememberLazyListState()
        ListJumpEffect(
            listState = listState,
            target = state.pendingListJump,
            seq = state.listJumpSeq,
            itemHeight = SongRowHeight,
            onDone = { vm.clearListJump() },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cover = state.album?.cover ?: state.albumSongs.firstOrNull()?.cover
                    if (!cover.isNullOrEmpty()) {
                        Cover(cover, modifier = Modifier.size(72.dp))
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        MonoText(state.album?.name ?: albumName, bold = true, size = 22, maxLines = 2)
                        val artist = state.album?.artistName.orEmpty()
                        if (artist.isNotEmpty()) {
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MonoText("歌手: ", muted = true, size = 13)
                                HoverBold(
                                    text = artist,
                                    onClick = { vm.openArtist(artist) },
                                    color = colors.muted,
                                    size = 13,
                                )
                            }
                        }
                        if (state.albumSongs.isNotEmpty()) {
                            MonoText("${state.albumSongs.size} 首歌曲", muted = true, size = 13, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
            if (state.albumLoading) {
                item { MonoText("…", muted = true) }
            } else if (state.albumSongs.isEmpty()) {
                item { MonoText("暂无歌曲", muted = true) }
            }
            itemsIndexed(state.albumSongs, key = { i, s -> "${s.id}-$i" }) { i, song ->
                SongRow(
                    song = song,
                    onClick = { vm.playAll(state.albumSongs, i) },
                    onLongPress = { vm.listenPushSong(song) },
                    on = state.player.current?.id == song.id,
                    isLiked = state.likedSongIds.contains(song.id),
                    index = i,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        MiniBar(state, vm)
    }
}

@Composable
private fun NowPlayingScreen(state: HomeState, vm: HypochloriteViewModel) {
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
    val dragOffsetY = remember { Animatable(screenHeightPx) }
    var isDismissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        dragOffsetY.animateTo(
            targetValue = 0f,
            animationSpec = tween(320, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)),
        )
    }

    fun dismissCard() {
        if (isDismissing) return
        isDismissing = true
        scope.launch {
            dragOffsetY.animateTo(
                targetValue = screenHeightPx,
                animationSpec = tween(240, easing = FastOutLinearInEasing),
            )
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
    val idx = state.player.lyricIndex
    val current = state.player.lyricLines.getOrNull(idx)
    val lyricSplit = remember(current?.text) {
        if (current != null) Lyrics.splitLyric(current.text) else Lyrics.LyricSplit(null, null)
    }
    val left = idx >= 0 && idx % 2 == 0
    val progress = if (state.player.durationMs > 0) state.player.positionMs.toFloat() / state.player.durationMs else 0f

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
            scope.launch {
                val next = (dragOffsetY.value + delta).coerceAtLeast(0f)
                dragOffsetY.snapTo(next)
            }
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

    val dismissProgress = (dragOffsetY.value / screenHeightPx).coerceIn(0f, 1f)
    val scrimAlpha = (1f - dismissProgress) * 0.65f

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalHypochloriteColors.current.scrim.copy(alpha = scrimAlpha))
                .clickableNoRipple { dismissCard() },
        )

        val coverUrl = state.backdropCoverUrl ?: song?.cover?.takeIf { it.isNotEmpty() }
        val coverVisible = state.monetEnabled && !coverUrl.isNullOrEmpty()
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragOffsetY.value
                    val scale = (1f - dismissProgress * 0.08f).coerceIn(0.92f, 1f)
                    scaleX = scale
                    scaleY = scale
                    val hasOffset = dragOffsetY.value > 0f
                    clip = hasOffset
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (hasOffset) (dismissProgress * 16).dp else 0.dp,
                        bottomEnd = if (hasOffset) (dismissProgress * 16).dp else 0.dp,
                    )
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (dragOffsetY.value > dismissThreshold || velocity > 800f) {
                            dismissCard()
                        } else {
                            scope.launch {
                                dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
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
            val onLyricsPage = showFullLyrics && hasLyrics
            // 封面一直留在组合里：在歌词页切歌时完整动画仍在跑，切回封面才不会只剩后半段。
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = if (onLyricsPage) 0f else 1f },
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
                            if (lyricSplit.vertical != null && left) {
                                VerticalLyric(lyricSplit.vertical, Modifier.padding(end = 12.dp))
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
                                    if (hasLyrics) Modifier.clickableNoRipple { showFullLyrics = true } else Modifier,
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
                            if (lyricSplit.vertical != null && !left) {
                                VerticalLyric(lyricSplit.vertical, Modifier.padding(start = 12.dp))
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
                        if (lyricSplit.horizontal != null) {
                            AnimatedContent(
                                targetState = lyricSplit.horizontal,
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
                    }
            }
            if (onLyricsPage) {
                ScrollingLyricView(
                    lines = state.player.lyricLines,
                    currentIndex = state.player.lyricIndex,
                    modifier = Modifier.fillMaxSize(),
                    onSeek = { vm.seekMs(it) },
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
        val durMs = if (state.player.durationMs > 0) state.player.durationMs else (curSong?.durationMs ?: 0L)
        val activeSongId = song?.id ?: curSong?.id

        ProgressLine(
            progress = progress,
            positionMs = state.player.positionMs,
            durationMs = durMs,
            trackKey = "${activeSongId ?: ""}_${state.songTransitionSeq}",
            onSeek = { vm.seekFraction(it) },
        )

        val isLiked = curSong != null && (
            state.likedSongIds.contains(curSong.id) ||
            state.playlistSongs.any { it.id == curSong.id && ((state.route as? Route.PlaylistSongs)?.playlist?.specialType == 5 || (state.route as? Route.PlaylistSongs)?.playlist?.name?.contains("喜欢") == true) }
        )

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

/**
 * 播放列表（队列管理）面板。
 *
 * 从详情页控制行的队列图标唤起：从底部滑上来、盖在详情页上，收掉就回到详情页。
 * 两个页签 —— 队列（点按跳转 / 删除 / 长按拖动排序 / 清空）和历史（最近播过的 200 首）。
 *
 * 队列行的「正在播放」永远高亮，顺序变了（拖动 / 删除）跟手即时反映 ——
 * 数据源就是 [PlayerSnapshot.queue]，没有任何本地副本可失步。
 */
@Composable
private fun PlayQueuePanel(state: HomeState, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val scope = rememberCoroutineScope()
    val slideY = remember { Animatable(screenHeightPx) }
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        slideY.animateTo(
            targetValue = 0f,
            animationSpec = tween(300, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)),
        )
    }

    fun close() {
        if (isClosing) return
        isClosing = true
        scope.launch {
            slideY.animateTo(
                targetValue = screenHeightPx,
                animationSpec = tween(220, easing = FastOutLinearInEasing),
            )
            vm.closeQueue()
        }
    }

    // 面板在详情页之后组合 → 这里的 BackHandler 优先吃掉返回，先收面板再收详情页
    BackHandler(enabled = !isClosing) { close() }

    var tab by remember { mutableIntStateOf(0) }   // 0 = 队列，1 = 历史
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(2600)
            confirmClear = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = 0.4f))
            .clickableNoRipple { close() },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .graphicsLayer { translationY = slideY.value }
                .background(colors.background)
                .clickableNoRipple { /* 挡住穿透，别把点按漏给遮罩 */ },
        ) {
            // ---- 顶栏：返回 + 标题 + 数量 + 清空 ----
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clickableNoRipple { close() }
                        .padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackArrowIcon(size = 18.dp)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    MonoText("播放列表", bold = true, size = 19, maxLines = 1)
                    MonoText(
                        when (tab) {
                            0 -> "${state.player.queue.size} 首在队列"
                            else -> "最近 ${state.player.history.size} 首"
                        },
                        muted = true,
                        size = 12,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (tab == 0 && state.player.queue.isNotEmpty()) {
                    MonoText(
                        text = if (confirmClear) "确认清空?" else "清空",
                        size = 13,
                        color = colors.warning,
                        modifier = Modifier
                            .clickableNoRipple {
                                if (confirmClear) {
                                    vm.queueClear()
                                    confirmClear = false
                                } else {
                                    confirmClear = true
                                }
                            }
                            .padding(8.dp),
                    )
                }
            }
            Hairline(Modifier.padding(horizontal = 14.dp))

            // ---- 页签：队列 / 历史 ----
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                QueueTab("队列", tab == 0) { tab = 0 }
                Spacer(Modifier.width(22.dp))
                QueueTab("历史", tab == 1) { tab = 1 }
            }
            Hairline(Modifier.padding(horizontal = 14.dp))

            when (tab) {
                0 -> QueueTabList(state, vm)
                else -> HistoryTabList(state, vm)
            }
        }
    }
}

@Composable
private fun QueueTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalHypochloriteColors.current
    Column(Modifier.clickableNoRipple(onClick)) {
        MonoText(
            text = label,
            bold = selected,
            size = 15,
            color = if (selected) colors.text else colors.muted,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        Box(
            Modifier
                .width(22.dp)
                .height(2.dp)
                .background(if (selected) colors.text else Color.Transparent),
        )
    }
}

/**
 * 队列页签。行高定死 [SongRowHeight]，长按拖动换位：
 * 手指划过多少行就实时和谁交换（拖动计数每次整行跳变时立刻 commit），
 * 松手即定。拖动中的那一行浮起来（投影 + 跟手位移）。
 */
@Composable
private fun ColumnScope.QueueTabList(state: HomeState, vm: HypochloriteViewModel) {
    val density = LocalDensity.current
    val rowHpx = with(density) { SongRowHeight.toPx() }
    val queue = state.player.queue
    val listState = rememberLazyListState()

    var dragIdx by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }

    if (queue.isEmpty()) {
        MonoText("队列是空的", muted = true, modifier = Modifier.padding(start = 14.dp, top = 18.dp))
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .pointerInput(queue.size) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { off ->
                        if (queue.isEmpty()) return@detectDragGesturesAfterLongPress
                        val scrolled = listState.firstVisibleItemIndex * rowHpx +
                            listState.firstVisibleItemScrollOffset
                        dragIdx = ((scrolled + off.y) / rowHpx).toInt().coerceIn(0, queue.lastIndex)
                        dragY = 0f
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val from = dragIdx ?: return@detectDragGesturesAfterLongPress
                        dragY += drag.y
                        val shift = (dragY / rowHpx).toInt()
                        if (shift != 0) {
                            val to = (from + shift).coerceIn(0, queue.lastIndex)
                            if (to != from) {
                                vm.queueMove(from, to)
                                dragIdx = to
                                dragY -= shift * rowHpx
                            }
                        }
                    },
                    onDragEnd = { dragIdx = null; dragY = 0f },
                    onDragCancel = { dragIdx = null; dragY = 0f },
                )
            },
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        itemsIndexed(queue, key = { i, s -> "q-${s.id}-$i" }) { i, song ->
            val isCurrent = i == state.player.index
            Box(
                Modifier
                    .height(SongRowHeight)
                    .fillMaxWidth()
                    .zIndex(if (dragIdx == i) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragIdx == i) dragY else 0f
                        shadowElevation = if (dragIdx == i) 24f else 0f
                    },
            ) {
                QueueRow(
                    song = song,
                    index = i,
                    isCurrent = isCurrent,
                    onClick = { vm.queueJumpAt(i) },
                    onRemove = { vm.queueRemoveAt(i) },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalHypochloriteColors.current
    Row(
        Modifier
            .fillMaxSize()
            .background(if (isCurrent) colors.text.copy(alpha = 0.06f) else Color.Transparent)
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(
            text = if (isCurrent) "NOW" else "%02d".format(index + 1),
            size = 12,
            color = if (isCurrent) colors.accent else colors.muted,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            MonoText(
                text = song.name,
                bold = isCurrent,
                size = if (isCurrent) 16 else 15,
                maxLines = 1,
                marquee = isCurrent,
                color = if (isCurrent) colors.text else colors.text.copy(alpha = 0.82f),
            )
            MonoText(
                text = song.artists.joinToString(" / "),
                muted = true,
                size = 12,
                maxLines = 1,
            )
        }
        MiniIconButton(onClick = onRemove) {
            // 一条斜杠加一条反向斜杠的小 ×
            Canvas(Modifier.size(12.dp)) {
                val sw = 1.6.dp.toPx()
                drawLine(colors.muted, Offset(0f, 0f), Offset(size.width, size.height), sw, StrokeCap.Square)
                drawLine(colors.muted, Offset(size.width, 0f), Offset(0f, size.height), sw, StrokeCap.Square)
            }
        }
    }
}

/** 历史页签：最近播过的歌，点一下加回队列并播放。 */
@Composable
private fun ColumnScope.HistoryTabList(state: HomeState, vm: HypochloriteViewModel) {
    val history = state.player.history
    if (history.isEmpty()) {
        MonoText("还没有播放历史", muted = true, modifier = Modifier.padding(start = 14.dp, top = 18.dp))
        return
    }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        itemsIndexed(history.asReversed(), key = { i, s -> "h-${s.id}-$i" }) { _, song ->
            Row(
                Modifier
                    .height(SongRowHeight)
                    .fillMaxWidth()
                    .clickableNoRipple { vm.playSong(song) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    MonoText(song.name, size = 15, maxLines = 1)
                    MonoText(song.artists.joinToString(" / "), muted = true, size = 12, maxLines = 1)
                }
            }
        }
    }
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

/**
 * 一起听的一次性反馈条。
 *
 * 挂根节点而不是房间页 —— 长按推歌的位置是任意歌曲列表，那里离开房间页很远，
 * 反馈必须跟着用户走。视觉沿用等宽 + 方框，和列表行的横幅区分开（横幅扫过，这个是从下浮起）。
 */
@Composable
private fun ListenToast(text: String?, onDone: () -> Unit) {
    if (text.isNullOrEmpty()) return
    var shown by remember(text) { mutableStateOf(false) }
    LaunchedEffect(text) {
        shown = true
        delay(1800)
        shown = false
        delay(200)
        onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        MonoText(
            text = text,
            color = LocalHypochloriteColors.current.onBanner,
            size = 15,
            bold = true,
            maxLines = 1,
            modifier = Modifier
                .graphicsLayer {
                    val v = if (shown) 1f else 0f
                    this.alpha = v
                    translationY = (1f - v) * 12.dp.toPx()
                }
                .background(LocalHypochloriteColors.current.banner)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ListenTogetherScreen(state: HomeState, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
    val lt = state.listen
    val room = lt.room
    BackHandler { vm.back() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clickableNoRipple { vm.back() }
                    .padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrowIcon(size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            MonoText("一起听")
            Spacer(Modifier.weight(1f))
            if (lt.syncing) MonoText("同步中…", muted = true, size = 12)
        }
        Hairline()

        if (room == null) {
            // ---------------------------------------------------- 未进房
            MonoText("和另一个人听同一首歌", modifier = Modifier.padding(top = 20.dp), bold = true, size = 20)
            if (!state.loggedIn) {
                MonoText(
                    "一起听要挂在你的网易云账号上，先去登录。",
                    color = Warn,
                    size = 13,
                    modifier = Modifier.padding(top = 16.dp),
                )
                HoverBold("账号登录", onClick = { vm.openLogin() }, modifier = Modifier.padding(top = 10.dp))
            } else {
                MonoText("创建房间", modifier = Modifier.padding(top = 28.dp))
                Hairline(Modifier.padding(top = 8.dp))
                HoverBold(
                    "> 创建房间  成为房主",
                    onClick = { vm.listenCreateRoom() },
                    modifier = Modifier.padding(top = 10.dp),
                    on = true,
                )

                MonoText("加入房间", modifier = Modifier.padding(top = 28.dp))
                Hairline(Modifier.padding(top = 8.dp))
                UnderlineField(
                    value = state.listenInput,
                    onValueChange = { vm.setListenInput(it) },
                    placeholder = "输入房间号",
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    onGo = { vm.listenJoinRoom() },
                    modifier = Modifier.padding(top = 12.dp),
                )
                HoverBold(
                    "> 加入",
                    onClick = { vm.listenJoinRoom() },
                    modifier = Modifier.padding(top = 6.dp),
                    on = state.listenInput.isNotEmpty(),
                )
            }
        } else {
            // ---------------------------------------------------- 在房间里
            Column(Modifier.padding(top = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText(if (lt.hosting) "[房主]" else "[成员]", bold = true, size = 16)
                    Spacer(Modifier.width(8.dp))
                    MonoText(
                        when {
                            !lt.connected -> "掉线了，正在重连…"
                            lt.syncing -> "同步中…"
                            else -> "已同步"
                        },
                        muted = true,
                        size = 13,
                    )
                }
                MonoText(
                    "房间号 ${room.roomId}",
                    modifier = Modifier.padding(top = 10.dp),
                    bold = true,
                    size = 22,
                )
                // 房间号是纯数字，分享链接是给另一台设备直接点开的。两个都给，
                // 因为「发给朋友」和「自己换设备接着听」是两种不同的用法。
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    HoverBold(
                        "复制房间号",
                        onClick = { vm.copyText(room.roomId, "房间号复制好了") },
                        padV = 9,
                    )
                    HoverBold(
                        "复制链接",
                        onClick = {
                            vm.copyText(
                                Crypto.ltShareUrl(room.roomId, state.player.current?.id, state.profileUserId),
                                "链接复制好了，用网易云打开就能进",
                            )
                        },
                        padV = 9,
                    )
                }
            }

            MonoText("一起听的人 ${room.memberCount}", modifier = Modifier.padding(top = 28.dp))
            Hairline(Modifier.padding(top = 8.dp))
            if (room.users.isEmpty()) {
                MonoText(
                    "房间里只有你。把房间号发出去，等人进来。",
                    muted = true,
                    size = 13,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                room.users.forEachIndexed { i, u ->
                    val me = u.userId == state.profileUserId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoText(
                            if (me) "> ${u.nickname.ifEmpty { "我" }}" else "- ${u.nickname.ifEmpty { "用户 ${u.userId.takeLast(6)}" }}",
                            bold = me || u.userId == room.ownerId,
                            maxLines = 1,
                            marquee = true,
                        )
                        Spacer(Modifier.weight(1f))
                        if (u.userId == room.ownerId) {
                            MonoText("房主", muted = true, size = 12)
                        }
                    }
                }
            }

            MonoText("房间队列", modifier = Modifier.padding(top = 28.dp))
            Hairline(Modifier.padding(top = 8.dp))
            MonoText(
                "${lt.roomQueue.size} 首",
                muted = true,
                size = 13,
                modifier = Modifier.padding(top = 10.dp),
            )
            // 房间里队列是共享的 —— 没有「推/拉」的概念了，直接把它显示出来。
            // 点一行就是切歌（走房间指令），长按是加歌，两条路都在列表页可用。
            lt.roomQueue.take(30).forEachIndexed { i, song ->
                val nowPlaying = state.player.current?.id == song.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { vm.playSong(song) }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoText(
                        (i + 1).toString().padStart(2, '0'),
                        muted = !nowPlaying,
                        size = 12,
                    )
                    Spacer(Modifier.width(12.dp))
                    MonoText(
                        song.name,
                        bold = nowPlaying,
                        color = if (nowPlaying) colors.accent else colors.text,
                        maxLines = 1,
                        marquee = true,
                        size = 14,
                        modifier = Modifier.weight(1f),
                    )
                    if (nowPlaying) {
                        Spacer(Modifier.width(8.dp))
                        MonoText("[播放中]", color = colors.accent, size = 11, bold = true)
                    }
                }
            }
            if (lt.roomQueue.size > 30) {
                MonoText(
                    "…还有 ${lt.roomQueue.size - 30} 首",
                    muted = true,
                    size = 12,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (lt.roomQueue.isEmpty()) {
                MonoText(
                    "还没歌。在任意列表里点一首就能放进来，或者长按某首歌只加不放。",
                    muted = true,
                    size = 12,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (!lt.error.isNullOrEmpty()) {
                MonoText(lt.error!!, color = Warn, size = 13, modifier = Modifier.padding(top = 20.dp))
            }

            MonoText("房间", modifier = Modifier.padding(top = 28.dp))
            Hairline(Modifier.padding(top = 8.dp))
            HoverBold(
                "退出房间",
                onClick = { vm.listenLeaveRoom() },
                color = Warn,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (lt.hosting) {
                MonoText(
                    "你是房主，你退出房间就散了，其他人会被一起断开。",
                    muted = true,
                    size = 12,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LoginScreen(state: HomeState, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
    BackHandler { vm.back() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clickableNoRipple { vm.back() }
                    .padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrowIcon(size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            MonoText("账号登录")
        }
        Hairline()

        // ---- 登录方式切换 ----
        Row(
            Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            HoverBold(
                if (state.loginTab == 0) "> 扫码登录" else "- 扫码登录",
                onClick = { vm.setLoginTab(0) },
                on = state.loginTab == 0,
                padV = 9,
            )
            HoverBold(
                if (state.loginTab == 1) "> 手机号登录" else "- 手机号登录",
                onClick = { vm.setLoginTab(1) },
                on = state.loginTab == 1,
                padV = 9,
            )
        }
        Hairline(Modifier.padding(top = 10.dp))

        if (state.loginTab == 0) {
            QrLoginPane(state, vm)
        } else {
            PhoneLoginPane(state, vm)
        }

        MonoText("高级登录方式：cookie / MUSIC_U (可选)", modifier = Modifier.padding(top = 26.dp), muted = true)
        BasicTextField(
            value = state.cookieInput,
            onValueChange = vm::setCookieInput,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(72.dp),
            textStyle = BodyStyle.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            decorationBox = { inner ->
                Column {
                    Box(Modifier.weight(1f)) { inner() }
                    Hairline()
                }
            },
        )
        Row(Modifier.padding(top = 12.dp)) {
            HoverBold("提交 Cookie 登录", onClick = { vm.submitCookie() })
        }
            if (state.loginMsg.isNotEmpty()) {
                MonoText(
                    state.loginMsg,
                    color = if (isLoginError(state.loginMsg)) Warn else colors.text,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // 云盾挡下来时给个出口：光重试没用，必须在同一个会话里过一次验证
            if (state.loginVerifyUrl.isNotEmpty()) {
                HoverBold(
                    "过一次安全验证",
                    onClick = { vm.openLoginVerify() },
                    modifier = Modifier.padding(top = 12.dp),
                    on = true,
                    padV = 9,
                )
                MonoText(
                    "在这里过一次验证就行，过了会自动回来重试登录。",
                    muted = true,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
    }
}

/**
 * 云盾人机验证页，内嵌 WebView。
 *
 * 两个关键点，少一个这页就白开了：
 * 1. **先把 app 会话的 cookie 种进去** —— 否则验证的是一个陌生会话，过完也对不上这次登录；
 * 2. **验证完把 cookie 收回来** —— 云盾的验证结果就是 cookie，待在 WebView 的 jar 里
 *    等于没验证。所以「已完成验证」要把 jar 里的东西原样交回 ViewModel。
 */
@Composable
private fun LoginVerifyOverlay(url: String, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
    BackHandler { vm.closeLoginVerify() }
    val seedCookies = remember(url) { vm.verifySeedCookies() }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText("安全验证", modifier = Modifier.weight(1f))
                HoverBold("关闭", onClick = { vm.closeLoginVerify() }, color = colors.muted, padV = 8)
            }
            Hairline()
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // **必须带 `NeteaseMusic/x.y.z`**。这个页面挂载前只判一件事：
                        // `bn()`（UA 匹配 /NeteaseMusic\/\d+.\d+.\d+/i），为假就直接
                        // 渲染「请在网易云音乐app内打开本页面」+ 转圈，滑块一次都不出现。
                        // 手机 UA 单独用是不够的 —— 详见 Crypto.VERIFY_USER_AGENT。
                        settings.userAgentString = Crypto.VERIFY_USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        seedCookies.forEach { (k, v) ->
                            // 两个域都要种：页面自己挂在 st.music.163.com 上，
                            // 但它发给后端的 getConfig / check 要带去 music.163.com 的会话
                            CookieManager.getInstance().setCookie(VERIFY_COOKIE_URL, "$k=$v")
                            CookieManager.getInstance().setCookie(VERIFY_HOST_URL, "$k=$v")
                        }
                        CookieManager.getInstance().flush()
                        webViewClient = object : WebViewClient() {
                            // 只让它留在网页里。这个页面自带一整套「唤起网易云 App」逻辑：
                            // Android 上直接 `location.href = "intent://…#Intent;scheme=orpheus;
                            // package=com.netease.cloudmusic;end"`，兜底还跳 `orpheus://openurl?url=…`、
                            // `neplay://`。WebView 里没有能接这些 scheme 的东西，整页立刻变成
                            // ERR_UNKNOWN_URL_SCHEME —— 验证页当场作废。返回 true = 我们吃掉了这一跳。
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = !isVerifyPageKept(request.url)
                        }
                        loadUrl(url)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onRelease = { it.destroy() },
            )
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HoverBold(
                    "已完成验证",
                    onClick = { vm.completeLoginVerify(harvestVerifyCookies()) },
                    on = true,
                    padV = 9,
                )
                MonoText(
                    "验证通过后点这里，会自动回到登录。",
                    muted = true,
                    size = 12,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 这次导航要不要真的加载。
 *
 * 放行 http/https，但**顺手挡掉两种「把用户带走」的跳转**：
 *
 * 1. **非 http(s) 的 scheme** —— `orpheus://` / `neplay://` / `intent://` / `itms-appss://`。
 *    WebView 没有能处理它们的 Activity，会整页变成 `ERR_UNKNOWN_URL_SCHEME`；
 * 2. **唤起 App 失败后的兜底跳转** —— 网易的唤醒逻辑等 3 秒没唤起成功，就把页面跳到
 *    `music.163.com/m/download` 或 App Store，验证页会被顶掉。
 *
 * 拦掉这些是安全的：验证本身走的是页面内的 XHR + cookie，不依赖任何跳转。
 */
private fun isVerifyPageKept(url: Uri): Boolean {
    val scheme = url.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = url.host?.lowercase().orEmpty()
    if (host.endsWith("itunes.apple.com") || host.endsWith("apps.apple.com")) return false
    return !url.path.orEmpty().contains("/m/download")
}

/** 把 WebView 的 cookie jar 原样读出来交回会话。 */
private fun harvestVerifyCookies(): String {
    val cm = CookieManager.getInstance()
    cm.flush()
    return listOf(VERIFY_COOKIE_URL, VERIFY_HOST_URL)
        .mapNotNull { cm.getCookie(it) }
        .joinToString("; ")
}

private const val VERIFY_COOKIE_URL = "https://music.163.com"
private const val VERIFY_HOST_URL = "https://st.music.163.com"

/** 登录提示里包含这些词就按错误色显示。 */
private fun isLoginError(msg: String): Boolean =
    listOf("失败", "无效", "过期", "不对", "不能为空", "错误", "风控", "拦").any { msg.contains(it) }

@Composable
private fun QrLoginPane(state: HomeState, vm: HypochloriteViewModel) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(188.dp)
            .padding(top = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state.qr != null) {
            Image(state.qr, contentDescription = "qr", modifier = Modifier.size(168.dp))
        } else {
            MonoText("扫码登录", muted = true)
        }
    }
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        HoverBold("刷新二维码", onClick = { vm.requestQr() }, on = true)
        HoverBold("退出登录", onClick = { vm.logout() }, color = LocalHypochloriteColors.current.muted)
    }
    // 二维码不是「坏」了，是它第一次轮询就被云盾挡下来（还没人扫就先 -462）。
    // 这里得说清楚，否则用户只会以为二维码一直转是卡住了。
    if (state.loginVerifyUrl.isNotEmpty()) {
        MonoText(
            "这条路也在走云盾：码还没人扫，第一次查询就被挡了。" +
                "先点上面的「过一次安全验证」，或者直接用下面的 cookie 登录。",
            color = Warn,
            size = 13,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun PhoneLoginPane(state: HomeState, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current

    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        HoverBold(
            if (state.phoneUseCaptcha) "> 验证码登录" else "- 验证码登录",
            onClick = { vm.setPhoneUseCaptcha(true) },
            on = state.phoneUseCaptcha,
            padV = 9,
        )
        HoverBold(
            if (!state.phoneUseCaptcha) "> 密码登录" else "- 密码登录",
            onClick = { vm.setPhoneUseCaptcha(false) },
            on = !state.phoneUseCaptcha,
            padV = 9,
        )
    }

    // 国家区号 + 手机号
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LoginField(
            label = "区号",
            value = "+${state.phoneCountry}",
            onValueChange = vm::setPhoneCountry,
            modifier = Modifier.width(72.dp),
            keyboardType = KeyboardType.Number,
        )
        LoginField(
            label = "手机号",
            value = state.phone,
            onValueChange = vm::setPhone,
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Phone,
            placeholder = "11 位手机号",
        )
    }

    if (state.phoneUseCaptcha) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LoginField(
                label = "短信验证码",
                value = state.phoneCaptcha,
                onValueChange = vm::setPhoneCaptcha,
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.NumberPassword,
                placeholder = "6 位数字",
            )
            HoverBold(
                text = when {
                    state.phoneSending -> "发送中…"
                    state.phoneCountdown > 0 -> "${state.phoneCountdown}s"
                    state.loginCooldown > 0 -> "${state.loginCooldown}s"
                    else -> "获取验证码"
                },
                onClick = { vm.sendSmsCode() },
                color = if (state.phoneCountdown > 0 || state.phoneSending || state.loginCooldown > 0) {
                    colors.muted
                } else {
                    colors.text
                },
                modifier = Modifier.padding(bottom = 4.dp),
                padV = 9,
            )
        }
    } else {
        LoginField(
            label = "密码",
            value = state.phonePassword,
            onValueChange = vm::setPhonePassword,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = "网易云账号密码",
        )
    }

    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        HoverBold(
            when {
                state.phoneLoggingIn -> "登录中…"
                state.loginCooldown > 0 -> "${state.loginCooldown}s"
                else -> "登录"
            },
            onClick = { vm.submitPhoneLogin() },
            color = if (state.phoneLoggingIn || state.loginCooldown > 0) colors.muted else colors.text,
            on = !state.phoneLoggingIn && state.loginCooldown == 0,
            padV = 9,
        )
        HoverBold("退出登录", onClick = { vm.logout() }, color = colors.muted, padV = 9)
    }

    if (state.loginCooldown > 0) {
        MonoText(
            if (state.loginVerifyUrl.isNotEmpty()) {
                "网易这次要的是人机验证 —— 点下面的「过一次安全验证」，过了会自动重试登录。"
            } else {
                "被拦下来了，这会儿别急着再点。连续失败会让风控记更久。"
            },
            modifier = Modifier.padding(top = 14.dp),
            muted = true,
        )
    }
}

/** 下划线式输入框，和项目里 cookie 输入框同一套视觉语言。 */
@Composable
private fun LoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = LocalHypochloriteColors.current
    Column(modifier) {
        MonoText(label, muted = true, size = 14)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(34.dp),
            textStyle = BodyStyle.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            decorationBox = { inner ->
                Column {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            MonoText(placeholder, muted = true)
                        }
                        inner()
                    }
                    Hairline()
                }
            },
        )
    }
}

/**
 * HiFi 音频输出区。
 *
 * 交互上刻意分两层：**顶层四项开关一眼可读**，每项都带一句「什么时候会有用」，
 * 用户不需要懂 Android 音频栈也能判断该不该开；**需要细调的东西收在折叠里**
 * （精确音量），避免把设置页拉成一条长长的参数表。
 *
 * 状态显示遵循「说真话」原则：开关开着但系统拒绝了，界面必须显示为「未生效」，
 * 而不是给一个好看但假的绿灯。
 */
@Composable
private fun AudioOutSection(state: HomeState, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
    val a = state.audioOut
    val hasUsb = state.usbDevices > 0

    MonoText("音频输出", modifier = Modifier.padding(top = 28.dp))
    Hairline(Modifier.padding(top = 8.dp))

    // --- USB 独占 ---
    SwitchRow(
        label = "USB 独占",
        on = a.usbExclusive,
        // 没插设备时**不禁用**：点了不是没反应，而是给出「为什么开不了」。
        // 禁用会让用户以为是坏了，这才是最容易被误读成 bug 的状态。
        onClick = { vm.setUsbExclusive(!a.usbExclusive) },
        modifier = Modifier.padding(top = 16.dp),
    )
    MonoText(
        when {
            !hasUsb && !state.usbHostSupported ->
                "这台机器没有 USB 音频，插了也认不出来。"
            !hasUsb && a.usbExclusive ->
                "设备拔了，声音回到默认出口。插上就自动接回来。"
            !hasUsb -> "还没插解码器 / 小尾巴。插上就认。"
            a.usbExclusive && state.usbExclusiveActive ->
                "声音走 ${state.usbDeviceName ?: "USB 解码器"}。"
            a.usbExclusive ->
                "认到 ${state.usbDeviceName ?: "USB 解码器"} 了，但钉不住，声音还在默认出口。"
            else -> "认到 ${state.usbDeviceName ?: "USB 解码器"} 了。点一下就能钉上去。"
        },
        color = if (a.usbExclusive && state.usbExclusiveActive) colors.text else colors.muted,
        modifier = Modifier.padding(top = 8.dp),
    )

    // --- 采样率匹配 ---
    ResampleBlock(state, vm)

    // --- 独占音频焦点 ---
    SwitchRow(
        label = "独奏模式",
        on = a.exclusiveFocus,
        onClick = { vm.setExclusiveFocus(!a.exclusiveFocus) },
        modifier = Modifier.padding(top = 18.dp),
    )

    // --- 保持唤醒 ---
    SwitchRow(
        label = "保持唤醒",
        on = a.keepAwake,
        onClick = { vm.setKeepAwake(!a.keepAwake) },
        modifier = Modifier.padding(top = 18.dp),
    )

    // --- 音量控制方式 ---
    MonoText("音量控制", modifier = Modifier.padding(top = 26.dp))
    Hairline(Modifier.padding(top = 8.dp))
    SwitchRow(
        label = "系统级音量直控",
        on = a.directVolume,
        onClick = { vm.setDirectVolume(!a.directVolume) },
        modifier = Modifier.padding(top = 14.dp),
    )

    if (a.directVolume) {
        // 直控开启时滑块失去意义（增益被强制 1.0），直接不显示，比显示一个假滑块干净
        MonoText("精确音量用不上了，现在跟着系统音量键走。", muted = true, modifier = Modifier.padding(top = 10.dp))
    } else {
        GainSlider(
            percent = a.gainPercent,
            onChange = { vm.setGain(it) },
            onCommit = { vm.commitGain() },
            modifier = Modifier.padding(top = 16.dp),
        )
    }

    if (state.hifiMsg.isNotEmpty()) {
        MonoText(state.hifiMsg, muted = true, modifier = Modifier.padding(top = 14.dp))
    }
}

/**
 * 采样率匹配状态。
 *
 * 这是整套 HiFi 选项里**唯一一个有真实技术收益**的区块，所以给它独立的视觉层级，
 * 而不是混在开关列表里当一个副标题。
 *
 * 核心逻辑：Android 的 AudioFlinger 只在「音源采样率 == 输出设备原生采样率」时
 * 不做 SRC。所以这里只报三件事 —— 音源多少、设备要多少、现在会不会被重采样。
 *
 * **拿不到数据时什么都不说**。设备没报能力列表、接口没报采样率，都不显示结论，
 * 也不用「可能」「也许」糊过去 —— 宁可不说，也不给一个可能是假的警告。
 */
@Composable
private fun ResampleBlock(state: HomeState, vm: HypochloriteViewModel) {
    MonoText("采样率匹配", modifier = Modifier.padding(top = 26.dp))
    Hairline(Modifier.padding(top = 8.dp))

    when {
        state.sourceSampleRate == null -> MonoText(
            "放一首歌就能看到。",
            muted = true,
            modifier = Modifier.padding(top = 12.dp),
        )

        state.supportedRates.isEmpty() -> MonoText(
            "音源 ${formatSampleRate(state.sourceSampleRate)} · " +
                "解码器没说自己支持哪些采样率，看不出来会不会被转。",
            muted = true,
            modifier = Modifier.padding(top = 12.dp),
        )

        state.willResample -> {
            MonoText(
                "${formatSampleRate(state.sourceSampleRate)} → " +
                    "${formatSampleRate(state.nativeSampleRate)}  正在转一次",
                modifier = Modifier.padding(top = 12.dp),
            )
            MonoText(
                "解码器支持 ${state.supportedRates.joinToString(" / ") { formatSampleRate(it) }}，" +
                    "现在的音源不在里面。换一档匹配的音质就能消掉，或者换只能吃这个采样率的解码器。",
                muted = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        else -> {
            MonoText(
                "${formatSampleRate(state.sourceSampleRate)} → " +
                    "${formatSampleRate(state.nativeSampleRate)}  直通",
                modifier = Modifier.padding(top = 12.dp),
            )
            MonoText(
                "音源和设备的采样率对上了，系统不转。这是普通 app 能摸到的最接近直通的状态。",
                muted = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 一行开关：左边标题 + 一句说明，右边状态词。整行可点，命中区够大。 */
@Composable
private fun SwitchRow(
    label: String,
    sub: String = "",
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val liveColors = LocalHypochloriteColors.current
    val tone = if (!enabled) liveColors.muted.copy(alpha = 0.55f) else liveColors.text
    Column(modifier) {
        HoverBold(
            if (on) "> $label" else "- $label",
            onClick = { if (enabled) onClick() },
            modifier = Modifier.padding(top = 2.dp),
            color = tone,
            on = on,
            padV = 8,
        )
        if (sub.isNotEmpty()) {
            MonoText(sub, muted = true, modifier = Modifier.padding(top = 2.dp), size = 14)
        }
    }
}

/**
 * 精确音量滑块。
 *
 * 没有用 Material 的 Slider —— 这个 app 全篇没有 Material 控件，插一个进来的
 * 圆角与阴影会和纯黑等宽语言打架。这里直接自绘：一条 1dp 的刻度线 + 一个
 * 方块游标，和底栏进度条的视觉是同一套。
 *
 * 拖动中只回调 [onChange]（改内存、不落盘），抬手才回调 [onCommit] ——
 * 一路拖过去会触发几十次 prefs 写入，没必要。
 */
@Composable
private fun GainSlider(
    percent: Int,
    onChange: (Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val db = gainDbForPercent(percent)
    val label = when {
        percent < GAIN_MUTE_BELOW -> "[静音]"
        db > -0.05 -> "[-0.0dB]"
        else -> "[${String.format(java.util.Locale.US, "%.1f", db)}dB]"
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText("精确音量", size = 15)
            MonoText(label, muted = percent < GAIN_MUTE_BELOW, size = 15)
        }
        Spacer(Modifier.height(10.dp))

        var width by remember { mutableIntStateOf(1) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun emit(x: Float) {
                            onChange(((x / width) * 100f).toInt().coerceIn(0, 100))
                        }
                        emit(down.position.x)
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            emit(ch.position.x)
                            // 必须消费，否则手势会被外层的 verticalScroll 抢走
                            ch.consume()
                        }
                        onCommit()
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                val cy = this.size.height / 2f
                val w = this.size.width
                val unit = 1.dp.toPx()
                // 刻度：0dB 位置（93 格）单独标出来，用户才知道哪一格是「不衰减」
                drawLine(
                    color = colors.text.copy(alpha = 0.28f),
                    start = Offset(0f, cy),
                    end = Offset(w, cy),
                    strokeWidth = unit,
                )
                val zeroX = w * (93f / 100f)
                drawLine(
                    color = colors.muted,
                    start = Offset(zeroX, cy - 5.dp.toPx()),
                    end = Offset(zeroX, cy + 5.dp.toPx()),
                    strokeWidth = unit,
                )
                drawLine(
                    color = colors.text,
                    start = Offset(0f, cy),
                    end = Offset(w * (percent / 100f), cy),
                    strokeWidth = 2.dp.toPx(),
                )
                val cx = w * (percent / 100f)
                val half = 4.dp.toPx()
                drawRect(
                    color = colors.text,
                    topLeft = Offset(cx - half, cy - 10.dp.toPx()),
                    size = Size(half * 2, 20.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MonoText("0", muted = true, size = 13)
            MonoText("93 = 不衰减", muted = true, size = 13)
            MonoText("100", muted = true, size = 13)
        }
    }
}

@Composable
private fun ConfigScreen(state: HomeState, vm: HypochloriteViewModel) {
    BackHandler { vm.back() }
    val colors = LocalHypochloriteColors.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clickableNoRipple { vm.back() }
                    .padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrowIcon(color = colors.text, size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            MonoText("设置")
        }
        Hairline()
        MonoText("音质", modifier = Modifier.padding(top = 16.dp))
        Quality.PRESETS.forEach { q ->
            val on = state.player.quality.id == q.id
            HoverBold(
                if (on) "> ${q.id}  ${q.label}" else "- ${q.id}  ${q.label}",
                onClick = { vm.setQuality(q.id) },
                modifier = Modifier.padding(top = 10.dp),
                on = on,
            )
        }
        AudioOutSection(state, vm)
        MonoText("播放", modifier = Modifier.padding(top = 28.dp))
        Hairline(Modifier.padding(top = 8.dp))
        SwitchRow(
            label = "DJ 自动接歌",
            on = state.djMix,
            onClick = { vm.setDjMix(!state.djMix) },
            modifier = Modifier.padding(top = 16.dp),
        )
        MonoText(
            if (state.djMix) {
                "开着。两首歌之间会自动对速度、按调性选接法，滤波和回响收尾，切歌没有断点。"
            } else {
                "开着之后，两首歌之间会自动对速度、按调性接过去，没有硬切。单曲循环时不接。"
            },
            muted = true,
            modifier = Modifier.padding(top = 8.dp),
        )
        MonoText("主题", modifier = Modifier.padding(top = 28.dp))
        Hairline(Modifier.padding(top = 8.dp))
        MonoText("显示模式", muted = true, size = 14, modifier = Modifier.padding(top = 12.dp))
        ThemeMode.entries.forEach { mode ->
            val on = state.themeMode == mode
            HoverBold(
                if (on) "> ${mode.label}" else "- ${mode.label}",
                onClick = { vm.setThemeMode(mode) },
                modifier = Modifier.padding(top = 10.dp),
                on = on,
            )
        }
        HoverBold(
            if (state.monetEnabled) "> 封面取色  虚化封面背景并上色"
            else if (colors.isLight) "- 封面取色  当前为纯白"
            else "- 封面取色  当前为纯黑",
            onClick = { vm.setMonetEnabled(!state.monetEnabled) },
            modifier = Modifier.padding(top = 20.dp),
            on = state.monetEnabled,
        )
        if (state.monetEnabled) {
            MonoText(
                if (state.palette.seed == null) "还没放过歌，放一首就有了。"
                else "背景 ${state.palette.background.hex()} · 封面色 ${argbHex(state.palette.seed)} · 封面虚化铺底",
                muted = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        HoverBold(
            if (state.revealEnabled) "> 切歌扩散动画  开" else "- 切歌扩散动画  关",
            onClick = { vm.setRevealEnabled(!state.revealEnabled) },
            modifier = Modifier.padding(top = 14.dp),
            on = state.revealEnabled,
        )
        MonoText("账号", modifier = Modifier.padding(top = 28.dp))
        Hairline(Modifier.padding(top = 8.dp))
        MonoText(
            if (state.loggedIn) "已登录" else "还没登录。",
            muted = true,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HoverBold("账号登录", onClick = { vm.openLogin() }, on = !state.loggedIn)
            if (state.loggedIn) {
                HoverBold("退出登录", onClick = { vm.logout() }, color = colors.muted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
