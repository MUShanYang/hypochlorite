@file:OptIn(ExperimentalFoundationApi::class)

package app.hypochlorite.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.SearchHit
import app.hypochlorite.SearchPhase
import app.hypochlorite.SearchState
import app.hypochlorite.SearchTab
import app.hypochlorite.netease.Album
import app.hypochlorite.netease.Artist
import app.hypochlorite.netease.Playlist
import app.hypochlorite.netease.Song
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.Cover
import app.hypochlorite.ui.ExpandingSearch
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.HighlightText
import app.hypochlorite.ui.HoverBold
import app.hypochlorite.ui.MiniIconButton
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.PlayIcon
import app.hypochlorite.ui.PlaylistRow
import app.hypochlorite.ui.SettingsIcon
import app.hypochlorite.ui.SongRow
import app.hypochlorite.ui.TogetherIcon
import app.hypochlorite.ui.slice
import app.hypochlorite.ui.homeHeaderUi
import app.hypochlorite.ui.searchResultsUi
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 综合搜索。
 *
 * 页签和分区跟网易云综合搜索同一套信息架构：综合里先放最相关的一位歌手
 * （没有歌手就放第一张专辑），再按单曲、歌单、专辑、歌手分段。
 * 点「更多」或横滑进入该类的整表。外观仍是括号页签、四角搜索框和现有列表行。
 */

private const val PREVIEW_SONGS = 6
private const val PREVIEW_REST = 3

private val SearchLabels = listOf("[综合]", "[单曲]", "[歌单]", "[专辑]", "[歌手]")

@Composable
internal fun SearchSurface(
    uiState: State<HomeState>,
    vm: HypochloriteViewModel,
    focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** 首页把顶栏提到 AnimatedContent 外做 SEARCH 拉长时传 false，只渲染页签+结果。 */
    includeHeader: Boolean = true,
) {
    val pager = rememberPagerState(initialPage = 0, pageCount = { SearchTab.entries.size })
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    // 页签跟着滑动落点走。只读 currentPage 时，父组合不一定会因翻页重算，顶栏会停在旧页。
    var selectedTab by remember { mutableIntStateOf(pager.currentPage) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.targetPage }.collect { page -> selectedTab = page }
    }

    if (includeHeader) {
        LaunchedEffect(Unit) {
            delay(32)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(modifier.fillMaxSize()) {
        if (includeHeader) {
            SearchHeader(
                uiState = uiState,
                vm = vm,
                focusRequester = focusRequester,
                onFocus = onFocus,
                onClose = onClose,
                onSearch = {
                    keyboard?.hide()
                    vm.search()
                },
            )
            Hairline(Modifier.padding(horizontal = 14.dp))
        }
        SearchTabs(
            selected = selectedTab,
            onSelect = { page ->
                selectedTab = page
                scope.launch { pager.animateScrollToPage(page) }
            },
        )
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
        ) { page ->
            SearchResultsPage(uiState, vm, SearchTab.entries[page]) { tab ->
                selectedTab = tab.ordinal
                scope.launch { pager.animateScrollToPage(tab.ordinal) }
            }
        }
    }
}

@Composable
private fun SearchResultsPage(
    uiState: State<HomeState>,
    vm: HypochloriteViewModel,
    tab: SearchTab,
    onMore: (SearchTab) -> Unit,
) {
    val state by remember(uiState) { uiState.slice(HomeState::searchResultsUi) }
    val found = state.search
    val query = found.resultQuery
    val currentId = state.currentId
    val likedIds = state.likedSongIds
    val inRoom = state.inRoom
    when (tab) {
        SearchTab.All -> Comprehensive(
            found = found,
            query = query,
            currentId = currentId,
            likedIds = likedIds,
            inRoom = inRoom,
            vm = vm,
            onMore = onMore,
        )
        SearchTab.Songs -> SongResults(
            songs = found.songs,
            query = query,
            phase = found.phase,
            resultQuery = found.resultQuery,
            currentId = currentId,
            likedIds = likedIds,
            inRoom = inRoom,
            moreLoading = found.moreTab == SearchTab.Songs,
            moreFailed = found.moreErrorTab == SearchTab.Songs,
            vm = vm,
        )
        SearchTab.Playlists -> PlaylistResults(
            playlists = found.playlists,
            query = query,
            phase = found.phase,
            resultQuery = found.resultQuery,
            moreLoading = found.moreTab == SearchTab.Playlists,
            moreFailed = found.moreErrorTab == SearchTab.Playlists,
            vm = vm,
        )
        SearchTab.Albums -> AlbumResults(
            albums = found.albums,
            query = query,
            phase = found.phase,
            resultQuery = found.resultQuery,
            moreLoading = found.moreTab == SearchTab.Albums,
            moreFailed = found.moreErrorTab == SearchTab.Albums,
            vm = vm,
        )
        SearchTab.Artists -> ArtistResults(
            artists = found.artists,
            query = query,
            phase = found.phase,
            resultQuery = found.resultQuery,
            moreLoading = found.moreTab == SearchTab.Artists,
            moreFailed = found.moreErrorTab == SearchTab.Artists,
            vm = vm,
        )
    }
}

@Composable
private fun SearchHeader(
    uiState: State<HomeState>,
    vm: HypochloriteViewModel,
    focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    onClose: () -> Unit,
    onSearch: () -> Unit,
) {
    val state by remember(uiState) { uiState.slice(HomeState::homeHeaderUi) }
    val colors = LocalHypochloriteColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniIconButton(onClick = onClose) {
            BackArrowIcon(size = 18.dp)
        }
        ExpandingSearch(
            open = true,
            query = state.searchQuery,
            onQueryChange = vm::setSearchQuery,
            onOpen = {},
            onSearch = onSearch,
            focusRequester = focusRequester,
            onFocus = onFocus,
            hint = "搜索",
            onClear = { vm.setSearchQuery("") },
            lockOpen = true,
            modifier = Modifier
                .weight(1f)
                .padding(end = 2.dp),
        )
        MiniIconButton(onClick = { vm.openListen() }) {
            TogetherIcon(
                color = if (state.connected) colors.accent else colors.text,
                size = 17.dp,
            )
        }
        MiniIconButton(onClick = { vm.openConfig() }) {
            SettingsIcon(color = colors.muted, size = 16.dp)
        }
    }
}

@Composable
private fun SearchTabs(selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalHypochloriteColors.current
    val scroll = rememberScrollState()
    val intoView = remember { List(SearchLabels.size) { BringIntoViewRequester() } }
    LaunchedEffect(selected) {
        intoView.getOrNull(selected)?.bringIntoView()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SearchLabels.forEachIndexed { index, label ->
            HoverBold(
                text = label,
                onClick = { onSelect(index) },
                color = if (index == selected) colors.text else colors.muted,
                on = index == selected,
                modifier = Modifier.bringIntoViewRequester(intoView[index]),
            )
        }
    }
}

@Composable
private fun Comprehensive(
    found: SearchState,
    query: String,
    currentId: String?,
    likedIds: Set<String>,
    inRoom: Boolean,
    vm: HypochloriteViewModel,
    onMore: (SearchTab) -> Unit,
) {
    val songs = found.songs
    val playlists = found.playlists
    val albums = found.albums
    val artists = found.artists
    val trimmed = query.trim()
    val hasItems = songs.items.isNotEmpty() || playlists.items.isNotEmpty() ||
        albums.items.isNotEmpty() || artists.items.isNotEmpty()
    val anyFailed = songs.failed || playlists.failed || albums.failed || artists.failed
    val featuredArtist = remember(artists.items) { artists.items.firstOrNull() }
    val featuredAlbum = remember(featuredArtist, albums.items) {
        if (featuredArtist == null) albums.items.firstOrNull() else null
    }
    val albumRest = remember(featuredAlbum, albums.items) {
        if (featuredAlbum != null) albums.items.drop(1) else albums.items
    }
    val artistRest = remember(featuredArtist, artists.items) {
        if (featuredArtist != null) artists.items.drop(1) else artists.items
    }
    val songPreview = remember(songs.items) { songs.items.take(PREVIEW_SONGS) }
    val playlistPreview = remember(playlists.items) { playlists.items.take(PREVIEW_REST) }
    val albumPreview = remember(albumRest) { albumRest.take(PREVIEW_REST) }
    val artistPreview = remember(artistRest) { artistRest.take(PREVIEW_REST) }
    ResultColumn(
        content = listOf(songs.items, playlists.items, albums.items, artists.items),
        query = trimmed,
        phase = found.phase,
        resultQuery = found.resultQuery,
        hasItems = hasItems,
        failed = anyFailed && !hasItems,
        restart = listOf(currentId, inRoom, likedIds),
        emptyText = "没有结果",
        onRetry = vm::search,
    ) {

        if (featuredArtist != null) {
            item(key = "feature-artist-${featuredArtist.id}") {
                EntityRow(
                    rowKey = "fa-${featuredArtist.id}",
                    cover = featuredArtist.cover,
                    title = featuredArtist.name,
                    caption = artistCaption(featuredArtist),
                    query = query,
                    kicker = "歌手",
                    onClick = { vm.openArtist(featuredArtist.name, featuredArtist.id, featuredArtist.cover) },
                    index = 0,
                )
            }
        } else if (featuredAlbum != null) {
            item(key = "feature-album-${featuredAlbum.id}") {
                EntityRow(
                    rowKey = "fal-${featuredAlbum.id}",
                    cover = featuredAlbum.cover,
                    title = featuredAlbum.name,
                    caption = albumCaption(featuredAlbum),
                    query = query,
                    kicker = "专辑",
                    onClick = { vm.openAlbum(featuredAlbum.name, featuredAlbum.id) },
                    index = 0,
                )
            }
        }
        val hasSection = songPreview.isNotEmpty() || playlistPreview.isNotEmpty() ||
            albumPreview.isNotEmpty() || artistPreview.isNotEmpty()
        if ((featuredArtist != null || featuredAlbum != null) && hasSection) {
            item(key = "feature-rule") {
                Hairline(Modifier.padding(top = 8.dp, bottom = 2.dp))
            }
        }
        if (songPreview.isNotEmpty()) {
            item(key = "sec-songs") {
                SectionHeader(
                    title = "[单曲]",
                    onPlay = { vm.playAll(songs.items, 0) },
                    onMore = { onMore(SearchTab.Songs) }.takeIf {
                        songs.more || songs.items.size > songPreview.size
                    },
                )
            }
            itemsIndexed(songPreview, key = { i, song -> "cs-${song.id}-$i" }) { i, song ->
                SongRow(
                    song = song,
                    onClick = { vm.playAll(songs.items, i) },
                    onPush = { vm.listenPushSong(song) },
                    pushOnClick = inRoom,
                    on = currentId == song.id,
                    isLiked = likedIds.contains(song.id),
                    index = i,
                    highlight = query,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (playlistPreview.isNotEmpty()) {
            item(key = "sec-pl") {
                SectionHeader(
                    title = "[歌单]",
                    onMore = { onMore(SearchTab.Playlists) }.takeIf {
                        playlists.more || playlists.items.size > playlistPreview.size
                    },
                )
            }
            itemsIndexed(playlistPreview, key = { i, pl -> "cp-${pl.id}-$i" }) { i, pl ->
                PlaylistRow(
                    playlist = pl,
                    onClick = { vm.openPlaylist(pl) },
                    index = i,
                    caption = playlistCaption(pl),
                    highlight = query,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (albumPreview.isNotEmpty()) {
            item(key = "sec-al") {
                SectionHeader(
                    title = "[专辑]",
                    onMore = { onMore(SearchTab.Albums) }.takeIf {
                        albums.more || albums.items.size > albumPreview.size + if (featuredAlbum != null) 1 else 0
                    },
                )
            }
            itemsIndexed(albumPreview, key = { i, album -> "ca-${album.id}-$i" }) { i, album ->
                EntityRow(
                    rowKey = "ca-${album.id}",
                    cover = album.cover,
                    title = album.name,
                    caption = albumCaption(album),
                    query = query,
                    onClick = { vm.openAlbum(album.name, album.id) },
                    index = i,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (artistPreview.isNotEmpty()) {
            item(key = "sec-ar") {
                SectionHeader(
                    title = "[歌手]",
                    onMore = { onMore(SearchTab.Artists) }.takeIf {
                        artists.more || artists.items.size > artistPreview.size + if (featuredArtist != null) 1 else 0
                    },
                )
            }
            itemsIndexed(artistPreview, key = { i, artist -> "cr-${artist.id}-$i" }) { i, artist ->
                EntityRow(
                    rowKey = "cr-${artist.id}",
                    cover = artist.cover,
                    title = artist.name,
                    caption = artistCaption(artist),
                    query = query,
                    onClick = { vm.openArtist(artist.name, artist.id, artist.cover) },
                    index = i,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        val failedLabels = listOfNotNull(
            "单曲".takeIf { songs.failed },
            "歌单".takeIf { playlists.failed },
            "专辑".takeIf { albums.failed },
            "歌手".takeIf { artists.failed },
        )
        if (failedLabels.isNotEmpty()) {
            item(key = "partial-fail") {
                val colors = LocalHypochloriteColors.current
                HoverBold(
                    text = "[${failedLabels.joinToString("、")}没有加载出来，点此重试]",
                    onClick = { vm.search() },
                    size = 13,
                    color = colors.warning,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SongResults(
    songs: SearchHit<Song>,
    query: String,
    phase: SearchPhase,
    resultQuery: String,
    currentId: String?,
    likedIds: Set<String>,
    inRoom: Boolean,
    moreLoading: Boolean,
    moreFailed: Boolean,
    vm: HypochloriteViewModel,
) {
    val trimmed = query.trim()
    ResultColumn(
        content = songs.items,
        query = trimmed,
        phase = phase,
        resultQuery = resultQuery,
        hasItems = songs.items.isNotEmpty(),
        failed = songs.failed,
        restart = listOf(currentId, inRoom, likedIds, moreLoading, moreFailed),
        emptyText = "没有单曲",
        onRetry = vm::search,
    ) {
        item(key = "song-head") {
            CountPlayRow(
                label = countLabel(songs.items.size, songs.total, "首"),
                onPlay = { vm.playAll(songs.items, 0) },
            )
        }
        itemsIndexed(songs.items, key = { i, song -> "s-${song.id}-$i" }) { i, song ->
            SongRow(
                song = song,
                onClick = { vm.playAll(songs.items, i) },
                onPush = { vm.listenPushSong(song) },
                pushOnClick = inRoom,
                on = currentId == song.id,
                isLiked = likedIds.contains(song.id),
                index = i,
                highlight = trimmed,
                modifier = Modifier.animateItem(),
            )
        }
        moreFooter(
            more = songs.more,
            loading = moreLoading,
            failed = moreFailed,
            tab = SearchTab.Songs,
            size = songs.items.size,
            onMore = { vm.searchLoadMore(SearchTab.Songs) },
        )
    }
}

@Composable
private fun PlaylistResults(
    playlists: SearchHit<Playlist>,
    query: String,
    phase: SearchPhase,
    resultQuery: String,
    moreLoading: Boolean,
    moreFailed: Boolean,
    vm: HypochloriteViewModel,
) {
    val trimmed = query.trim()
    ResultColumn(
        content = playlists.items,
        query = trimmed,
        phase = phase,
        resultQuery = resultQuery,
        hasItems = playlists.items.isNotEmpty(),
        failed = playlists.failed,
        restart = moreLoading to moreFailed,
        emptyText = "没有歌单",
        onRetry = vm::search,
    ) {
        item(key = "pl-head") {
            MonoText(
                countLabel(playlists.items.size, playlists.total, "个歌单"),
                muted = true,
                size = 13,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(playlists.items, key = { i, pl -> "p-${pl.id}-$i" }) { i, pl ->
            PlaylistRow(
                playlist = pl,
                onClick = { vm.openPlaylist(pl) },
                index = i,
                caption = playlistCaption(pl),
                highlight = trimmed,
                modifier = Modifier.animateItem(),
            )
        }
        moreFooter(
            more = playlists.more,
            loading = moreLoading,
            failed = moreFailed,
            tab = SearchTab.Playlists,
            size = playlists.items.size,
            onMore = { vm.searchLoadMore(SearchTab.Playlists) },
        )
    }
}

@Composable
private fun AlbumResults(
    albums: SearchHit<Album>,
    query: String,
    phase: SearchPhase,
    resultQuery: String,
    moreLoading: Boolean,
    moreFailed: Boolean,
    vm: HypochloriteViewModel,
) {
    val trimmed = query.trim()
    ResultColumn(
        content = albums.items,
        query = trimmed,
        phase = phase,
        resultQuery = resultQuery,
        hasItems = albums.items.isNotEmpty(),
        failed = albums.failed,
        restart = moreLoading to moreFailed,
        emptyText = "没有专辑",
        onRetry = vm::search,
    ) {
        item(key = "al-head") {
            MonoText(
                countLabel(albums.items.size, albums.total, "张专辑"),
                muted = true,
                size = 13,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(albums.items, key = { i, album -> "a-${album.id}-$i" }) { i, album ->
            EntityRow(
                rowKey = "a-${album.id}",
                cover = album.cover,
                title = album.name,
                caption = albumCaption(album),
                query = trimmed,
                onClick = { vm.openAlbum(album.name, album.id) },
                index = i,
                modifier = Modifier.animateItem(),
            )
        }
        moreFooter(
            more = albums.more,
            loading = moreLoading,
            failed = moreFailed,
            tab = SearchTab.Albums,
            size = albums.items.size,
            onMore = { vm.searchLoadMore(SearchTab.Albums) },
        )
    }
}

@Composable
private fun ArtistResults(
    artists: SearchHit<Artist>,
    query: String,
    phase: SearchPhase,
    resultQuery: String,
    moreLoading: Boolean,
    moreFailed: Boolean,
    vm: HypochloriteViewModel,
) {
    val trimmed = query.trim()
    ResultColumn(
        content = artists.items,
        query = trimmed,
        phase = phase,
        resultQuery = resultQuery,
        hasItems = artists.items.isNotEmpty(),
        failed = artists.failed,
        restart = moreLoading to moreFailed,
        emptyText = "没有歌手",
        onRetry = vm::search,
    ) {
        item(key = "ar-head") {
            MonoText(
                countLabel(artists.items.size, artists.total, "位歌手"),
                muted = true,
                size = 13,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(artists.items, key = { i, artist -> "r-${artist.id}-$i" }) { i, artist ->
            EntityRow(
                rowKey = "r-${artist.id}",
                cover = artist.cover,
                title = artist.name,
                caption = artistCaption(artist),
                query = trimmed,
                onClick = { vm.openArtist(artist.name, artist.id, artist.cover) },
                index = i,
                modifier = Modifier.animateItem(),
            )
        }
        moreFooter(
            more = artists.more,
            loading = moreLoading,
            failed = moreFailed,
            tab = SearchTab.Artists,
            size = artists.items.size,
            onMore = { vm.searchLoadMore(SearchTab.Artists) },
        )
    }
}

@Composable
private fun ResultColumn(
    content: Any,
    query: String,
    phase: SearchPhase,
    resultQuery: String,
    hasItems: Boolean,
    failed: Boolean,
    restart: Any,
    emptyText: String,
    onRetry: () -> Unit,
    body: LazyListScope.() -> Unit,
) {
    // content / restart 都是跳过键。列表正文是 lambda，播放行、加载更多这些变化必须写进参数，
    // 否则父级重组时这列会被判成「没变」而留下旧的高亮。
    val ready = hasItems && content !is Unit && restart !is Unit
    val pending = phase == SearchPhase.Loading || (query.isNotEmpty() && resultQuery != query)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
    ) {
        when {
            pending -> item { SearchNotice("搜索中…") }
            query.isEmpty() -> item { SearchNotice("输入歌名、歌手、专辑、歌单或链接") }
            phase == SearchPhase.Error -> item {
                SearchNotice("搜索失败", warning = true, action = "[重试]", onAction = onRetry)
            }
            failed && !ready -> item {
                SearchNotice("没有加载出来", warning = true, action = "[重试]", onAction = onRetry)
            }
            !ready -> item { SearchNotice(emptyText) }
            else -> body()
        }
    }
}

@Composable
private fun SearchNotice(
    text: String,
    warning: Boolean = false,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalHypochloriteColors.current
    Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
        MonoText(
            text,
            color = if (warning) colors.warning else colors.muted,
            size = 14,
        )
        if (action != null && onAction != null) {
            HoverBold(
                text = action,
                onClick = onAction,
                size = 14,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    onPlay: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    val colors = LocalHypochloriteColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(title, bold = true, size = 16)
        Spacer(Modifier.weight(1f))
        if (onPlay != null) PlayAll(onPlay)
        if (onPlay != null && onMore != null) Spacer(Modifier.width(14.dp))
        if (onMore != null) {
            HoverBold(
                text = "[更多]",
                onClick = onMore,
                size = 13,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun CountPlayRow(label: String, onPlay: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(label, muted = true, size = 13)
        Spacer(Modifier.weight(1f))
        PlayAll(onPlay)
    }
}

@Composable
private fun PlayAll(onClick: () -> Unit) {
    val colors = LocalHypochloriteColors.current
    Row(
        Modifier
            .clickableNoRipple(onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayIcon(color = colors.text, size = 11.dp)
        Spacer(Modifier.width(4.dp))
        MonoText("播放", size = 13)
    }
}

@Composable
private fun EntityRow(
    rowKey: String,
    cover: String,
    title: String,
    caption: String,
    query: String,
    onClick: () -> Unit,
    index: Int,
    modifier: Modifier = Modifier,
    kicker: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shouldAnimate = index < 12
    val offsetX = remember(rowKey) { Animatable(if (shouldAnimate) -32f else 0f) }
    val alpha = remember(rowKey) { Animatable(if (shouldAnimate) 0f else 1f) }
    if (shouldAnimate) {
        LaunchedEffect(rowKey) {
            val stagger = (index * 20).toLong()
            if (stagger > 0) delay(stagger)
            launch { alpha.animateTo(1f, tween(160)) }
            offsetX.animateTo(0f, tween(200, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)))
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = offsetX.value
                this.alpha = alpha.value
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(cover, Modifier.size(54.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (!kicker.isNullOrEmpty()) {
                MonoText(kicker, muted = true, size = 11)
            }
            HighlightText(
                text = title,
                query = query,
                bold = pressed,
                size = 15,
                maxLines = 1,
                marquee = true,
            )
            if (caption.isNotEmpty()) {
                HighlightText(
                    text = caption,
                    query = query,
                    muted = true,
                    size = 12,
                    maxLines = 1,
                    marquee = true,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

private fun LazyListScope.moreFooter(
    more: Boolean,
    loading: Boolean,
    failed: Boolean,
    tab: SearchTab,
    size: Int,
    onMore: () -> Unit,
) {
    if (!more) return
    item(key = "more-${tab.name}") {
        when {
            loading -> MonoText(
                "加载更多中…",
                muted = true,
                size = 13,
                modifier = Modifier.padding(vertical = 14.dp),
            )
            failed -> HoverBold(
                text = "[加载失败，点此重试]",
                onClick = onMore,
                size = 13,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            else -> {
                LaunchedEffect(size) { onMore() }
                MonoText("…", muted = true, size = 13, modifier = Modifier.padding(vertical = 14.dp))
            }
        }
    }
}

private fun countLabel(loaded: Int, total: Int, unit: String): String =
    if (total > loaded) "$loaded / $total $unit" else "$loaded $unit"

private fun artistCaption(artist: Artist): String = listOfNotNull(
    artist.alias.takeIf { it.isNotEmpty() },
    artist.musicSize.takeIf { it > 0 }?.let { "$it 首" },
    artist.albumSize.takeIf { it > 0 }?.let { "$it 张专辑" },
).joinToString(" · ")

private fun albumCaption(album: Album): String = listOfNotNull(
    album.artistName.takeIf { it.isNotEmpty() },
    publishYear(album.publishTime),
    album.songCount.takeIf { it > 0 }?.let { "$it 首" },
).joinToString(" · ")

private fun playlistCaption(playlist: Playlist): String = listOfNotNull(
    playlist.trackCount.takeIf { it > 0 }?.let { "$it 首" },
    playlist.creatorName.takeIf { it.isNotEmpty() },
).joinToString(" · ")

private fun publishYear(raw: Long): String? {
    if (raw <= 0L) return null
    val ms = if (raw < 10_000_000_000L) raw * 1000 else raw
    val year = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).year
    return year.takeIf { it in 1900..2100 }?.toString()
}
