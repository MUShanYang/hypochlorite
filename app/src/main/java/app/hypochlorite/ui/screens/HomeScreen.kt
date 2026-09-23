@file:OptIn(ExperimentalFoundationApi::class)

package app.hypochlorite.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.Space
import app.hypochlorite.netease.Playlist
import app.hypochlorite.netease.Song
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.ExpandingSearch
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.HoverBold
import app.hypochlorite.ui.MiniIconButton
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.PlaylistRow
import app.hypochlorite.ui.SettingsIcon
import app.hypochlorite.ui.SongRow
import app.hypochlorite.ui.TogetherIcon
import app.hypochlorite.ui.slice
import app.hypochlorite.ui.homeHeaderUi
import app.hypochlorite.ui.homeLibraryUi
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.sections.MiniBar
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(uiState: State<HomeState>, vm: HypochloriteViewModel) {
    val searchOpen by remember(uiState) { uiState.slice { it.searchOpen } }
    val space by remember(uiState) { uiState.slice { it.space } }
    val pager = rememberPagerState(initialPage = space.ordinal, pageCount = { 3 })
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
        if (searchOpen) {
            closeSearch()
        } else {
            vm.setSearchOpen(true)
        }
    }

    LaunchedEffect(searchOpen) {
        if (!searchOpen) {
            searchFocused = false
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    BackHandler(enabled = searchOpen || searchFocused) { closeSearch() }

    // 顶栏 ExpandingSearch 提到 AnimatedContent 外：home→search 时 SEARCH chip 原地拉长，
    // 关闭时再缩回；AnimatedContent 只换下方内容（页签/结果 ↔ 空间页签/列表）。
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(32)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(
            uiState = uiState,
            vm = vm,
            onToggleSearch = toggleSearch,
            onCloseSearch = closeSearch,
            searchFocusRequester = searchFocusRequester,
            onSearchFocus = { searchFocused = it },
        )
        Hairline(Modifier.padding(horizontal = 14.dp))
        AnimatedContent(
            targetState = searchOpen,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            transitionSpec = {
                if (targetState) {
                    (fadeIn(tween(240)) + slideInHorizontally(tween(260)) { it / 10 }) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally(tween(200)) { -it / 12 })
                } else {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(240)) { -it / 12 }) togetherWith
                        (fadeOut(tween(160)) + slideOutHorizontally(tween(180)) { it / 10 })
                }
            },
            label = "homeSearch",
        ) { open ->
            // 子树必须吃满 AnimatedContent 的有界高度，避免 HorizontalPager+weight 在部分机型上无限高崩溃。
            Box(Modifier.fillMaxSize()) {
                if (open) {
                    SearchSurface(
                        uiState = uiState,
                        vm = vm,
                        focusRequester = searchFocusRequester,
                        onFocus = { searchFocused = it },
                        onClose = closeSearch,
                        includeHeader = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    HomeLibrary(uiState, vm, pager)
                }
            }
        }
        MiniBar(uiState, vm)
    }
}

@Composable
private fun HomeLibrary(uiState: State<HomeState>, vm: HypochloriteViewModel, pager: PagerState) {
    val state by remember(uiState) { uiState.slice(HomeState::homeLibraryUi) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SpaceTabs(
            selected = pager.currentPage,
            onSelect = { page ->
                scope.launch { pager.animateScrollToPage(page) }
            },
        )
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            when (page) {
                0 -> PlaylistFlow(state.liked, state.loading, state.loggedIn, vm)
                1 -> PlaylistFlow(state.mine, state.loading, state.loggedIn, vm)
                else -> DailyFlow(
                    playlists = state.dailyPlaylists,
                    songs = state.dailySongs,
                    loading = state.loading,
                    loggedIn = state.loggedIn,
                    currentId = state.currentId,
                    likedIds = state.likedSongIds,
                    inRoom = state.inRoom,
                    vm = vm,
                )
            }
        }
    }
}

@Composable
private fun Header(
    uiState: State<HomeState>,
    vm: HypochloriteViewModel,
    onToggleSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    searchFocusRequester: FocusRequester,
    onSearchFocus: (Boolean) -> Unit,
) {
    val state by remember(uiState) { uiState.slice(HomeState::homeHeaderUi) }
    val colors = LocalHypochloriteColors.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (state.searchOpen) 8.dp else 14.dp,
                end = if (state.searchOpen) 8.dp else 14.dp,
                top = if (state.searchOpen) 10.dp else 16.dp,
                bottom = if (state.searchOpen) 8.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧：关闭时 Logo+标题；打开时返回键。与 ExpandingSearch 同生命周期，避免整页硬切。
        AnimatedContent(
            targetState = state.searchOpen,
            transitionSpec = {
                (fadeIn(tween(180)) togetherWith fadeOut(tween(120)))
            },
            label = "homeHeaderLeading",
        ) { open ->
            if (open) {
                MiniIconButton(onClick = onCloseSearch) {
                    BackArrowIcon(size = 18.dp)
                }
            } else {
                Row(
                    Modifier
                        .clickableNoRipple { onToggleSearch() }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoText("Hypochlorite", bold = true, size = 22)
                }
            }
        }
        ExpandingSearch(
            open = state.searchOpen,
            query = state.searchQuery,
            onQueryChange = vm::setSearchQuery,
            onOpen = { vm.setSearchOpen(true) },
            onSearch = {
                keyboardController?.hide()
                vm.search()
            },
            focusRequester = searchFocusRequester,
            onFocus = onSearchFocus,
            hint = if (state.searchOpen) "搜索" else "",
            onClear = if (state.searchOpen) {
                { vm.setSearchQuery("") }
            } else {
                null
            },
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = if (state.searchOpen) 0.dp else 12.dp,
                    end = if (state.searchOpen) 2.dp else 6.dp,
                ),
        )
        // 在房间里时这个图标常亮，点进去能看到房间和成员
        MiniIconButton(onClick = { vm.openListen() }) {
            TogetherIcon(
                color = if (state.connected) colors.accent else colors.text,
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
private fun PlaylistFlow(items: List<Playlist>, loading: Boolean, loggedIn: Boolean, vm: HypochloriteViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
    ) {
        if (items.isEmpty()) {
            item {
                val hint = when {
                    loading -> "正在同步歌单…"
                    !loggedIn -> "未登录"
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
private fun DailyFlow(
    playlists: List<Playlist>,
    songs: List<Song>,
    loading: Boolean,
    loggedIn: Boolean,
    currentId: String?,
    likedIds: Set<String>,
    inRoom: Boolean,
    vm: HypochloriteViewModel,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
    ) {
        if (playlists.isEmpty() && songs.isEmpty()) {
            item {
                MonoText(
                    if (loading) "正在获取今日推荐…" else if (!loggedIn) "登录后获取个性化每日推荐" else "今日暂无推荐数据",
                    muted = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        itemsIndexed(playlists, key = { i, pl -> "p-${pl.id}-$i" }) { i, pl ->
            PlaylistRow(
                playlist = pl,
                onClick = { vm.openPlaylist(pl) },
                index = i,
                modifier = Modifier.animateItem(),
            )
        }
        itemsIndexed(songs, key = { i, s -> "s-${s.id}-$i" }) { i, song ->
            SongRow(
                song = song,
                onClick = { vm.playAll(songs, i) },
                onLongPress = { vm.listenPushSong(song) },
                pushOnClick = inRoom,
                on = currentId == song.id,
                isLiked = likedIds.contains(song.id),
                index = i,
                modifier = Modifier.animateItem(),
            )
        }
    }
}
