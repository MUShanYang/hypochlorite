package app.hypochlorite.player

import app.hypochlorite.audio.AudioCaptureSource
import app.hypochlorite.audio.AudioFingerprintGenerator
import app.hypochlorite.netease.AudioMatchHit
import app.hypochlorite.netease.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMatchTest {

    private class FakeCapture : AudioCaptureSource {
        var calls = 0
        override suspend fun capture(durationSeconds: Int): FloatArray {
            calls++
            return FloatArray(durationSeconds * 8000)
        }
    }

    private class FakeGenerator(private val out: String = "FINGERPRINT") : AudioFingerprintGenerator {
        var lastInputSize = -1
        var calls = 0
        override suspend fun generate(pcmMono8k: FloatArray): String {
            calls++
            lastInputSize = pcmMono8k.size
            return out
        }
    }

    private fun hit(songId: String) = AudioMatchHit(Song(id = songId, name = "n$songId"), startTimeMs = 0)

    @Test
    fun `happy path runs the full phase sequence and lands on Hit`() = runTest {
        val engine = AudioMatch(this, FakeCapture(), FakeGenerator(), matcher = { _, _ -> listOf(hit("77")) })
        val seen = mutableListOf<AudioMatchPhase>()
        // 收集器是无限流，必须挂在 backgroundScope（测试收尾自动取消），否则 runTest 会等它完成而挂死
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.state.map { it.phase }.toList(seen) }

        engine.start()
        advanceUntilIdle()

        // StateFlow 去重相同值，逐阶段不同 -> 顺序即链路走过的阶段
        assertEquals(
            listOf(
                AudioMatchPhase.Idle,
                AudioMatchPhase.Capturing,
                AudioMatchPhase.Fingerprinting,
                AudioMatchPhase.Matching,
                AudioMatchPhase.Hit,
            ),
            seen,
        )
        val st = engine.state.value
        assertEquals(AudioMatchPhase.Hit, st.phase)
        assertEquals("77", st.hit?.id)
        assertEquals(1, st.hitSeq)
        assertNull(st.error)
    }

    @Test
    fun `matcher receives the fingerprint the generator produced`() = runTest {
        val generator = FakeGenerator(out = "ABC123")
        var received: String? = null
        val engine = AudioMatch(this, FakeCapture(), generator) { fp, seconds ->
            received = fp
            assertEquals(3, seconds)
            listOf(hit("1"))
        }
        engine.start()
        advanceUntilIdle()
        assertEquals("ABC123", received)
    }

    @Test
    fun `empty matcher result is NoResult with a toast, not an error`() = runTest {
        val engine = AudioMatch(this, FakeCapture(), FakeGenerator(), matcher = { _, _ -> emptyList() })
        engine.start()
        advanceUntilIdle()
        assertEquals(AudioMatchPhase.NoResult, engine.state.value.phase)
        assertTrue(engine.state.value.toast != null)
        assertNull(engine.state.value.hit)
        assertNull(engine.state.value.error)
    }

    @Test
    fun `matcher throwing lands on Error with a message`() = runTest {
        val engine = AudioMatch(this, FakeCapture(), FakeGenerator(), matcher = { _, _ -> throw RuntimeException("boom") })
        engine.start()
        advanceUntilIdle()
        assertEquals(AudioMatchPhase.Error, engine.state.value.phase)
        assertTrue(engine.state.value.error!!.isNotEmpty())
    }

    @Test
    fun `cancel during matching does not resurrect a late hit`() = runTest {
        val gate = CompletableDeferred<List<AudioMatchHit>>()
        val engine = AudioMatch(this, FakeCapture(), FakeGenerator(), matcher = { _, _ -> gate.await() })
        engine.start()
        advanceUntilIdle()
        // 此刻链路应停在 Matching，等 gate
        assertEquals(AudioMatchPhase.Matching, engine.state.value.phase)

        engine.cancel()
        assertEquals(AudioMatchPhase.Idle, engine.state.value.phase)

        // 迟到的结果回来，generation 变了，不该把状态从 Idle 拽回 Hit
        gate.complete(listOf(hit("late")))
        advanceUntilIdle()
        assertEquals(AudioMatchPhase.Idle, engine.state.value.phase)
        assertNull(engine.state.value.hit)
    }

    @Test
    fun `start while running is ignored`() = runTest {
        val capture = FakeCapture()
        val gate = CompletableDeferred<List<AudioMatchHit>>()
        val engine = AudioMatch(this, capture, FakeGenerator(), matcher = { _, _ -> gate.await() })
        engine.start()
        advanceUntilIdle()
        engine.start() // 已在跑，应被忽略
        advanceUntilIdle()
        assertEquals(1, capture.calls)
        gate.complete(listOf(hit("x")))
        advanceUntilIdle()
    }

    @Test
    fun `notify sets toast and clearToast drops it`() = runTest {
        val engine = AudioMatch(this, FakeCapture(), FakeGenerator(), matcher = { _, _ -> listOf(hit("1")) })
        engine.notify("歌名")
        assertEquals("歌名", engine.state.value.toast)
        engine.notify("")
        assertEquals("歌名", engine.state.value.toast) // 空消息不改
        engine.clearToast()
        assertNull(engine.state.value.toast)
    }
}
