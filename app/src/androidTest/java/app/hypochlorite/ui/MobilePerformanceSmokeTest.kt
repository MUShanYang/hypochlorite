package app.hypochlorite.ui

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.MainActivity
import app.hypochlorite.SearchHit
import app.hypochlorite.SearchPhase
import app.hypochlorite.SearchState
import app.hypochlorite.netease.LyricLine
import app.hypochlorite.netease.Playlist
import app.hypochlorite.netease.Song
import app.hypochlorite.player.PlayerClock
import app.hypochlorite.player.PlayerSnapshot
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline fixtures exercise the real Activity/Root without a music account or HTTP timing. */
@RunWith(AndroidJUnit4::class)
class MobilePerformanceSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val verticalList = hasScrollAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    // Test-only injection: keep fixtures and state mutation APIs out of the APK.
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: HypochloriteViewModel, name: String): MutableStateFlow<T> =
        HypochloriteViewModel::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(vm) as MutableStateFlow<T>
        }

    @Test fun longListsTypingDetailsLyricsAndTrackTransitions() {
        lateinit var vm: HypochloriteViewModel
        lateinit var ui: MutableStateFlow<HomeState>
        lateinit var clock: MutableStateFlow<PlayerClock>
        val songs = List(300) { Song("fixture-$it", "Perf Track $it", listOf("Fixture Artist"), durationMs = 180000) }
        val lyrics = List(60) { LyricLine(it * 3000L, "测试歌词 $it") }
        val frameTimes = Collections.synchronizedList(mutableListOf<Long>())
        val thread = HandlerThread("perf-smoke-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frameTimes.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[HypochloriteViewModel::class.java]
            ui = flow(vm, "_ui")
            clock = flow(vm, "_clock")
            compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            ui.value = ui.value.copy(
                loading = false,
                liked = List(300) { Playlist("fixture-$it", "Perf Playlist $it") },
                player = PlayerSnapshot(queue = songs, index = 0, current = songs[0],
                    lyricLines = lyrics, lyricsResolved = true),
            )
        }
        try {
            compose.onNode(verticalList).performScrollToIndex(250)
            compose.onNodeWithText("Perf Playlist 250").assertIsDisplayed()
            compose.onNode(verticalList).performScrollToIndex(0)

            compose.runOnUiThread { vm.setSearchOpen(true) }
            compose.onNode(hasSetTextAction()).performTextInput("mobile performance")
            compose.runOnIdle { assertTrue(ui.value.searchQuery == "mobile performance") }
            compose.runOnUiThread {
                vm.setSearchQuery("") // Cancel the HTTP debounce before installing offline results.
                ui.value = ui.value.copy(searchQuery = "mobile performance", search = SearchState(
                    phase = SearchPhase.Ready, resultQuery = "mobile performance",
                    songs = SearchHit(items = songs, total = songs.size),
                ))
            }
            compose.onNode(hasText("[单曲]") and hasClickAction()).performClick()
            compose.onNode(verticalList and hasAnyDescendant(hasText("Perf Track 0")))
                .performScrollToIndex(200)
            compose.onNodeWithText("Perf Track 200").assertIsDisplayed()
            compose.runOnUiThread { vm.setSearchOpen(false) }

            repeat(8) { iteration ->
                compose.runOnUiThread { vm.openNowPlaying() }
                compose.onNodeWithText("[查看歌词]").assertIsDisplayed().performClick()
                compose.onNodeWithText("[切回封面]").assertIsDisplayed()
                compose.runOnUiThread {
                    val index = iteration + 1
                    ui.value = ui.value.copy(
                        player = ui.value.player.copy(current = songs[index], index = index),
                        songTransitionSeq = index.toLong(),
                    )
                    clock.value = PlayerClock(12000, 180000, 4)
                }
                compose.onNodeWithText("[切回封面]").performClick()
                compose.onNodeWithText("[查看歌词]").assertIsDisplayed()
                compose.runOnUiThread { vm.openQueue() }
                compose.onNodeWithText("播放列表").assertIsDisplayed()
                compose.runOnUiThread { vm.closeQueue(); vm.closeNowPlaying() }
                compose.waitForIdle()
            }
            assertTrue(frameTimes.isNotEmpty())
        } finally {
            compose.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            val sorted = synchronized(frameTimes) { frameTimes.sorted() }
            if (sorted.isNotEmpty()) {
                val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)] / 1_000_000.0
                val slow = sorted.count { it > 16_666_667 }
                Log.i("MobilePerfSmoke", "frames=${sorted.size} p95Ms=$p95 over16ms=$slow (debug/emulator; not a release benchmark)")
            }
        }
    }
}
