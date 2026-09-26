package app.hypochlorite.player

import app.hypochlorite.audio.AudioCaptureSource
import app.hypochlorite.audio.AudioFingerprintGenerator
import app.hypochlorite.audio.AUDIO_MATCH_SECONDS
import app.hypochlorite.audio.peakOf
import app.hypochlorite.netease.AudioMatchHit
import app.hypochlorite.netease.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 识别链路的阶段。UI 用它决定方块文案、环是否转动、要不要触发交接。 */
enum class AudioMatchPhase {
    Idle,
    Capturing,
    Fingerprinting,
    Matching,
    Hit,
    NoResult,
    Error,
}

/**
 * 识曲状态。
 *
 * 引擎只负责跑到 [Hit]/[NoResult]/[Error] 并停下，**不自己播放** —— 播放与页面交接
 * 归 ViewModel（见 [app.hypochlorite.HypochloriteViewModel.onAudioMatchHit]）。[hitSeq]
 * 每命中一次递增，让详情页知道该演一次"封面先出、其余后进"的入场（而不是每次重开详情页都演）。
 */
data class AudioMatchState(
    val phase: AudioMatchPhase = AudioMatchPhase.Idle,
    val hit: Song? = null,
    val hits: List<AudioMatchHit> = emptyList(),
    val error: String? = null,
    val toast: String? = null,
    val hitSeq: Long = 0,
    /** 本次采集到的峰值 [0,1]。指纹还是假的，这是「到底有没有抓到声音」唯一的可验证信号。 */
    val capturedPeak: Float = 0f,
)

/**
 * 拿指纹换命中。做成函数注入而不是直接持有 [app.hypochlorite.netease.NeteaseClient]，
 * 是为了让整台状态机能脱离 Android/网络在 JVM 单元测试里跑（见 AudioMatchTest）。
 *
 * 引擎不自己管线程：生产接线（见 HypochloriteApplication）在实现里用 `withContext(IO)`
 * 把阻塞的网络调用挪出主线程，[generator]/[capture] 同理各自负责调度。
 */
typealias AudioMatchMatcher = suspend (fingerprint: String, durationSeconds: Int) -> List<AudioMatchHit>

/**
 * 听歌识曲引擎。
 *
 * 三步串行：采集 → 算指纹 → 比对。[generation] 计数保证被 [cancel] 掉的旧链路不会把
 * 状态复活（照 [ListenTogether] 的做法）。采集源 / 指纹生成器 / 比对都是注入的，UI 链路
 * 阶段前两个是假的（正弦 + 定值指纹）；因为假指纹打真接口必无果，命中分支靠
 * [forceHitForDebug] 走一首固定歌曲，把交接链路跑满。网络那一步由 OkHttp 的连接/读取
 * 超时兜底（见 `NeteaseClient.defaultHttp`），引擎不再叠一层超时。
 */
class AudioMatch(
    private val scope: CoroutineScope,
    /** 运行时可在假源 ↔ 真系统音频源之间切换（[app.hypochlorite.HypochloriteApplication] 接线）。 */
    var capture: AudioCaptureSource,
    private val generator: AudioFingerprintGenerator,
    private val matcher: AudioMatchMatcher,
) {
    private val _state = MutableStateFlow(AudioMatchState())
    val state: StateFlow<AudioMatchState> = _state

    private var job: Job? = null
    private var generation = 0

    /** debug 构建里置 true：假指纹无果，用它走完整命中/交接链路。正式指纹层落地后删。 */
    var forceHitForDebug: Boolean = false

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        job?.cancel()
        val gen = ++generation
        job = scope.launch {
            try {
                _state.update {
                    it.copy(phase = AudioMatchPhase.Capturing, hit = null, hits = emptyList(), error = null, toast = null, capturedPeak = 0f)
                }
                val pcm = capture.capture(AUDIO_MATCH_SECONDS)
                if (gen != generation) return@launch
                val peak = peakOf(pcm)
                _state.update { it.copy(capturedPeak = peak) }

                _state.update { it.copy(phase = AudioMatchPhase.Fingerprinting) }
                val fp = generator.generate(pcm)
                if (gen != generation) return@launch

                _state.update { it.copy(phase = AudioMatchPhase.Matching) }
                val result: List<AudioMatchHit> = if (forceHitForDebug) {
                    // 给 Matching 态留一点可见时长；假命中不碰网络，直接给一首固定歌
                    delay(MinimumMatchingMillis)
                    debugHits()
                } else {
                    matcher(fp, AUDIO_MATCH_SECONDS)
                }
                if (gen != generation) return@launch
                finish(gen, result)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (gen == generation) {
                    _state.update {
                        it.copy(phase = AudioMatchPhase.Error, error = "识别失败，再试一次", toast = "识别失败")
                    }
                }
            }
        }
    }

    private fun finish(gen: Int, result: List<AudioMatchHit>) {
        if (gen != generation) return
        if (result.isEmpty()) {
            _state.update {
                it.copy(phase = AudioMatchPhase.NoResult, hits = emptyList(), hit = null, toast = "没听出来，靠近一点再试")
            }
            return
        }
        _state.update {
            it.copy(
                phase = AudioMatchPhase.Hit,
                hits = result,
                hit = result.first().song,
                hitSeq = it.hitSeq + 1,
                error = null,
            )
        }
    }

    /** 假指纹走不到真命中，这里给一首固定的、带封面的歌把交接链路跑满。不碰网络。 */
    private fun debugHits(): List<AudioMatchHit> = listOf(
        AudioMatchHit(
            song = Song(
                id = DebugSongId,
                name = "示例命中",
                artists = listOf("调试用"),
                album = "识别链路自检",
                cover = "https://p1.music.126.net/placeholder/debug.jpg",
            ),
            startTimeMs = 0,
        ),
    )

    /** 回到空闲态并掐断在跑的链路。页面退出（back）走这里。 */
    fun cancel() {
        generation += 1
        job?.cancel()
        job = null
        _state.update { it.copy(phase = AudioMatchPhase.Idle) }
    }

    /**
     * 只把状态收回空闲，不掐在跑的链路。
     *
     * 进页面时若停在上一次结果上用这个 —— 采集还没开始，没必要 cancel；两者行为此刻相同，
     * 但语义分开：cancel 是"中止"，resetToIdle 是"归零待命"。
     */
    fun resetToIdle() {
        _state.update { it.copy(phase = AudioMatchPhase.Idle, hit = null, hits = emptyList(), error = null) }
    }

    fun clearToast() {
        if (_state.value.toast != null) _state.update { it.copy(toast = null) }
    }

    /** 一次性提示（命中歌名等）。经 collector 收回 HomeState，UI 侧只读不写。 */
    fun notify(message: String) {
        if (message.isNotEmpty()) _state.update { it.copy(toast = message) }
    }

    private companion object {
        const val DebugSongId = "1325513428"
        const val MinimumMatchingMillis = 600L
    }
}
