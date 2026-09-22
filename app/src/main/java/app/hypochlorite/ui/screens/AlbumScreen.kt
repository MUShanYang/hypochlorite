@file:OptIn(ExperimentalFoundationApi::class)

package app.hypochlorite.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
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
import app.hypochlorite.ui.theme.LocalHypochloriteColors

@Composable
internal fun AlbumScreen(albumName: String, albumId: String?, state: HomeState, vm: HypochloriteViewModel) {
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
                                    onClick = { vm.openArtist(artist, state.album?.artistId) },
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
                    pushOnClick = state.listen.room != null,
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
