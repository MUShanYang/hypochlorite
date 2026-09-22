package app.hypochlorite.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import app.hypochlorite.HomeState
import app.hypochlorite.SearchPhase
import app.hypochlorite.SearchState
import app.hypochlorite.netease.Song
import app.hypochlorite.netease.Playlist
import app.hypochlorite.player.PlayerSnapshot
import androidx.compose.ui.graphics.Color
import app.hypochlorite.ui.screens.NowPlayingUi

internal fun <T> State<HomeState>.slice(project: (HomeState) -> T): State<T> =
    derivedStateOf(structuralEqualityPolicy()) { project(value) }

internal fun HomeState.nowPlayingUi() = NowPlayingUi(
    player = PlayerSnapshot(
        current = player.current, index = player.index, playing = player.playing,
        lyricLines = player.lyricLines, lyricsResolved = player.lyricsResolved,
        error = player.error, playable = player.playable, playMode = player.playMode,
    ),
    reveal = reveal,
    backdropCoverUrl = backdropCoverUrl,
    monetEnabled = monetEnabled,
    palette = palette,
    songTransitionDir = songTransitionDir,
    songTransitionSeq = songTransitionSeq,
    route = route,
    likedSongIds = likedSongIds,
    playlistSongs = playlistSongs,
)

internal data class QueueUi(val queue: List<Song>, val index: Int, val history: List<Song>)

internal fun HomeState.queueUi() = QueueUi(player.queue, player.index, player.history)

internal data class HomeHeaderUi(val searchOpen: Boolean, val searchQuery: String, val connected: Boolean)
internal fun HomeState.homeHeaderUi() = HomeHeaderUi(searchOpen, searchQuery, listen.connected)

internal data class HomeLibraryUi(
    val liked: List<Playlist>, val mine: List<Playlist>,
    val dailyPlaylists: List<Playlist>, val dailySongs: List<Song>,
    val loading: Boolean, val loggedIn: Boolean,
    val currentId: String?, val likedSongIds: Set<String>, val inRoom: Boolean,
)
internal fun HomeState.homeLibraryUi() = HomeLibraryUi(
    liked, mine, dailyPlaylists, dailySongs, loading, loggedIn,
    player.current?.id, likedSongIds, listen.room != null,
)

internal data class MiniBarUi(
    val player: PlayerSnapshot, val backdropCoverUrl: String?, val banner: Color,
    val songTransitionDir: Int, val songTransitionSeq: Long,
    val songTransitionManual: Boolean, val roamTransitionDir: Int,
)
internal fun HomeState.miniBarUi() = MiniBarUi(
    PlayerSnapshot(current = player.current, playing = player.playing,
        error = player.error, lyricLines = player.lyricLines,
        roam = player.roam, playable = player.playable),
    backdropCoverUrl, palette.banner, songTransitionDir, songTransitionSeq,
    songTransitionManual, roamTransitionDir,
)

internal data class SearchResultsUi(
    val search: SearchState,
    val currentId: String?,
    val likedSongIds: Set<String>,
    val inRoom: Boolean,
)

internal fun HomeState.searchResultsUi(): SearchResultsUi {
    val query = searchQuery.trim()
    // During debounce all nonempty edits render the same loading notice. Do not
    // rebuild five result lists for each character, or highlight stale results.
    val visible = when {
        query.isEmpty() -> SearchState()
        query != search.resultQuery || search.phase == SearchPhase.Loading ->
            SearchState(phase = SearchPhase.Loading)
        else -> search
    }
    return SearchResultsUi(visible, player.current?.id, likedSongIds, listen.room != null)
}
