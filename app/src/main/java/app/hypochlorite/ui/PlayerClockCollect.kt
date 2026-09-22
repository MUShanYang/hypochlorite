package app.hypochlorite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.player.PlayerClock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun playerClock(vm: HypochloriteViewModel): PlayerClock {
    val clock by vm.clock.collectAsStateWithLifecycle()
    return clock
}

@Composable
internal fun lyricIndex(vm: HypochloriteViewModel): Int {
    val flow = remember(vm) { vm.clock.map { it.lyricIndex }.distinctUntilChanged() }
    val index by flow.collectAsStateWithLifecycle(vm.clock.value.lyricIndex)
    return index
}
