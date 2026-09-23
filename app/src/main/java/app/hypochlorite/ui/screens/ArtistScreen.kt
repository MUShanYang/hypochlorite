@file:OptIn(ExperimentalFoundationApi::class)

package app.hypochlorite.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.netease.Song
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.Cover
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.HoverBold
import app.hypochlorite.ui.ListJumpEffect
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.SongRow
import app.hypochlorite.ui.SongRowHeight
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.sections.MiniBar

@Composable
internal fun ArtistScreen(artistName: String, state: HomeState, vm: HypochloriteViewModel) {
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
        ArtistTracks(
            artistName = artistName,
            songs = state.artistSongs,
            cover = state.artistCover,
            loading = state.artistLoading,
            hasMore = state.artistHasMore,
            loadingMore = state.artistLoadingMore,
            total = state.artistTotal,
            currentId = state.player.current?.id,
            likedIds = state.likedSongIds,
            inRoom = state.listen.room != null,
            vm = vm,
            listState = listState,
            modifier = Modifier.weight(1f),
        )
        MiniBar(state, vm)
    }
}

@Composable
private fun ArtistTracks(
    artistName: String,
    songs: List<Song>,
    cover: String?,
    loading: Boolean,
    hasMore: Boolean,
    loadingMore: Boolean,
    total: Int,
    currentId: String?,
    likedIds: Set<String>,
    inRoom: Boolean,
    vm: HypochloriteViewModel,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val headerCover = cover ?: songs.firstOrNull()?.cover
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!headerCover.isNullOrEmpty()) {
                    Cover(headerCover, modifier = Modifier.size(72.dp))
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    MonoText(artistName, bold = true, size = 24)
                    if (songs.isNotEmpty()) {
                        val countText = if (total > songs.size) {
                            "${songs.size} / $total 首歌曲"
                        } else {
                            "${songs.size} 首歌曲"
                        }
                        MonoText(countText, muted = true, size = 13, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (loading) {
            item { MonoText("…", muted = true) }
        } else if (songs.isEmpty()) {
            item { MonoText("暂无歌曲", muted = true) }
        }
        itemsIndexed(songs, key = { i, s -> "${s.id}-$i" }) { i, song ->
            SongRow(
                song = song,
                onClick = { vm.playAll(songs, i) },
                onPush = { vm.listenPushSong(song) },
                pushOnClick = inRoom,
                on = currentId == song.id,
                isLiked = likedIds.contains(song.id),
                index = i,
                modifier = Modifier.animateItem(),
            )
        }
        if (hasMore) {
            item {
                LaunchedEffect(songs.size) {
                    vm.loadMoreArtistSongs()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loadingMore) {
                        MonoText("加载更多中…", muted = true, size = 13)
                    } else {
                        HoverBold(
                            text = "[点击加载更多歌曲 (${songs.size}/$total)]",
                            onClick = { vm.loadMoreArtistSongs() },
                            size = 13,
                        )
                    }
                }
            }
        }
    }
}
