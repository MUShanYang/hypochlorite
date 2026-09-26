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

    /**
     * @param peak 样本峰值。默认 0.1（明显高于 SilentPeak），全 0 用来测静音跳过。
     */
    private class FakeCapture(private val peak: Float = 0.1f) : AudioCaptureSource {
        var calls = 0
        override suspend fun capture(durationSeconds: Int): FloatArray {
            calls++
            return FloatArray(durationSeconds * 8000) { peak }
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
    }

    @Test
    fun `matcher throwing lands on Error with a toast`() = runTest {
        val engine = AudioMatch(this, FakeCapture(), FakeGenerator(), matcher = { _, _ -> throw RuntimeException("boom") })
        engine.start()
        advanceUntilIdle()
        assertEquals(AudioMatchPhase.Error, engine.state.value.phase)
        // 界面上识别中刻意不放任何文字，失败只有 toast 这一条出口
        assertTrue(engine.state.value.toast!!.isNotEmpty())
    }

    @Test
    fun `running marks exactly the three in-flight phases`() {
        assertTrue(AudioMatchPhase.Capturing.running)
        assertTrue(AudioMatchPhase.Fingerprinting.running)
        assertTrue(AudioMatchPhase.Matching.running)
        assertTrue(!AudioMatchPhase.Idle.running)
        assertTrue(!AudioMatchPhase.Hit.running)
        assertTrue(!AudioMatchPhase.NoResult.running)
        assertTrue(!AudioMatchPhase.Error.running)
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
    fun `第一段没中就再听一段，第二段命中`() = runTest {
        val capture = FakeCapture()
        val generator = FakeGenerator()
        var queries = 0
        val engine = AudioMatch(this, capture, generator, matcher = { _, _ ->
            queries += 1
            if (queries == 1) emptyList() else listOf(hit("77"))
        })
        engine.start()
        advanceUntilIdle()

        assertEquals(AudioMatchPhase.Hit, engine.state.value.phase)
        assertEquals("77", engine.state.value.hit?.id)
        // 命中即刻停：只听了两段、只算了两段指纹，第三段不再听
        assertEquals(2, capture.calls)
        assertEquals(2, generator.calls)
        assertEquals(2, engine.state.value.attempt)
        assertEquals(1, engine.state.value.hitSeq)
    }

    @Test
    fun `全都没中就听满上限再停在 NoResult`() = runTest {
        val capture = FakeCapture()
        val engine = AudioMatch(this, capture, FakeGenerator(), matcher = { _, _ -> emptyList() })
        engine.start()
        advanceUntilIdle()

        assertEquals(AudioMatchPhase.NoResult, engine.state.value.phase)
        assertEquals(AUDIO_MATCH_ATTEMPTS, capture.calls)
        assertEquals(AUDIO_MATCH_ATTEMPTS, engine.state.value.attempt)
        assertNull(engine.state.value.hit)
    }

    @Test
    fun `中止之后不再为下一段算指纹`() = runTest {
        val gate = CompletableDeferred<List<AudioMatchHit>>()
        val generator = FakeGenerator()
        val engine = AudioMatch(this, FakeCapture(), generator, matcher = { _, _ -> gate.await() })
        engine.start()
        advanceUntilIdle()
        assertEquals(AudioMatchPhase.Matching, engine.state.value.phase)
        assertEquals(1, generator.calls)

        engine.cancel()
        // 迟到的空结果回来：generation 已经变了，不该再领着采集去听第二段
        gate.complete(emptyList())
        advanceUntilIdle()

        assertEquals(AudioMatchPhase.Idle, engine.state.value.phase)
        assertEquals(1, generator.calls)
        assertNull(engine.state.value.hit)
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

    @Test
    fun `静音段跳过指纹与比对，全静音走没听到声音`() = runTest {
        val capture = FakeCapture(peak = 0f)
        val generator = FakeGenerator()
        var matchCalls = 0
        val engine = AudioMatch(this, capture, generator, matcher = { _, _ ->
            matchCalls += 1
            emptyList()
        })
        engine.start()
        advanceUntilIdle()

        assertEquals(AudioMatchPhase.NoResult, engine.state.value.phase)
        assertEquals(AUDIO_MATCH_ATTEMPTS, capture.calls)
        assertEquals(0, generator.calls)
        assertEquals(0, matchCalls)
        assertTrue(engine.state.value.toast!!.contains("没听到声音"))
    }

    @Test
    fun `前段静音后段有声仍会算指纹`() = runTest {
        var captureCalls = 0
        val capture = object : AudioCaptureSource {
            override suspend fun capture(durationSeconds: Int): FloatArray {
                captureCalls++
                // 第 1 段静音，第 2 段有声
                val peak = if (captureCalls == 1) 0f else 0.1f
                return FloatArray(durationSeconds * 8000) { peak }
            }
        }
        val generator = FakeGenerator()
        val engine = AudioMatch(this, capture, generator, matcher = { _, _ -> listOf(hit("9")) })
        engine.start()
        advanceUntilIdle()

        assertEquals(AudioMatchPhase.Hit, engine.state.value.phase)
        assertEquals(2, captureCalls)
        assertEquals(1, generator.calls)
        assertEquals("9", engine.state.value.hit?.id)
    }
}
