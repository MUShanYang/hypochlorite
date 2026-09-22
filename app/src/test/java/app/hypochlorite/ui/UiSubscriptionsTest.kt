package app.hypochlorite.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import app.hypochlorite.HomeState
import app.hypochlorite.SearchPhase
import app.hypochlorite.SearchState
import app.hypochlorite.netease.Song
import app.hypochlorite.player.sameUiAs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSubscriptionsTest {
    private fun <T> invalidations(
        initial: HomeState,
        project: (HomeState) -> T,
        edits: List<(HomeState) -> HomeState>,
    ): Int {
        val source = mutableStateOf(initial)
        val selected = source.slice(project)
        val observer = SnapshotStateObserver { it() }
        val scope = Any()
        var count = 0
        val onChanged: (Any) -> Unit = { count++ }
        observer.start()
        try {
            edits.forEach { edit ->
                observer.observeReads(scope, onChanged) { selected.value }
                source.value = edit(source.value)
                Snapshot.sendApplyNotifications()
            }
        } finally {
            observer.stop()
            observer.clear()
        }
        return count
    }

    @Test fun typingDoesNotInvalidateOpenDetailsOrQueue() {
        val initial = HomeState(nowPlayingOpen = true, queueOpen = true)
        val edits = (1..20).map { n -> { s: HomeState -> s.copy(searchQuery = "song$n") } }
        assertEquals(0, invalidations(initial, HomeState::nowPlayingUi, edits))
        assertEquals(0, invalidations(initial, HomeState::queueUi, edits))
    }

    @Test fun queueIgnoresPlaybackButTracksSelection() {
        assertEquals(0, invalidations(HomeState(), HomeState::queueUi,
            listOf({ it.copy(player = it.player.copy(playing = true)) })))
        assertEquals(1, invalidations(HomeState(), HomeState::queueUi,
            listOf({ it.copy(player = it.player.copy(index = 1)) })))
    }

    @Test fun debounceTypingInvalidatesResultsOnlyOnce() {
        val edits = (1..20).map { n -> { s: HomeState -> s.copy(searchQuery = "song$n") } }
        assertEquals(1, invalidations(HomeState(), HomeState::searchResultsUi, edits))
        val loading = HomeState(searchQuery = "song", search = SearchState(phase = SearchPhase.Loading))
        assertEquals(1, invalidations(loading, HomeState::searchResultsUi,
            listOf({ it.copy(search = SearchState(phase = SearchPhase.Empty, resultQuery = "song")) })))
    }

    @Test fun progressDoesNotPublishHomeState() {
        val player = HomeState().player
        repeat(100) { tick ->
            assertTrue(player.sameUiAs(player.copy(positionMs = tick * 200L, durationMs = 200000L, lyricIndex = tick)))
        }
    }

    @Test fun homeAndMiniBarIgnoreTypingAndQueueEdits() {
        val edits = (1..20).map { n -> { s: HomeState -> s.copy(searchQuery = "song$n") } }
        assertEquals(0, invalidations(HomeState(), HomeState::homeLibraryUi, edits))
        assertEquals(0, invalidations(HomeState(), HomeState::miniBarUi, edits))
        val queueEdit: (HomeState) -> HomeState = {
            it.copy(player = it.player.copy(queue = listOf(Song("1", "Track"))))
        }
        assertEquals(0, invalidations(HomeState(), HomeState::nowPlayingUi, listOf(queueEdit)))
        assertEquals(0, invalidations(HomeState(), HomeState::miniBarUi, listOf(queueEdit)))
        assertEquals(1, invalidations(HomeState(), HomeState::queueUi, listOf(queueEdit)))
    }

    @Test fun detailAndMiniBarStillObserveTrackAndPlaybackChanges() {
        val edits: List<(HomeState) -> HomeState> = listOf(
            { it.copy(player = it.player.copy(current = Song("1", "Track"))) },
            { it.copy(player = it.player.copy(playing = true)) },
        )
        assertEquals(2, invalidations(HomeState(), HomeState::nowPlayingUi, edits))
        assertEquals(2, invalidations(HomeState(), HomeState::miniBarUi, edits))
    }

    @Test fun searchClearingAndReturningToCompletedQueryRemainVisible() {
        val ready = SearchState(phase = SearchPhase.Empty, resultQuery = "song")
        val state = HomeState(searchQuery = "song", search = ready)
        assertEquals(ready, state.searchResultsUi().search)
        assertEquals(SearchPhase.Loading, state.copy(searchQuery = "new").searchResultsUi().search.phase)
        assertEquals(SearchPhase.Idle, state.copy(searchQuery = " ").searchResultsUi().search.phase)
        assertEquals(ready, state.copy(searchQuery = " song ").searchResultsUi().search)
    }
}
