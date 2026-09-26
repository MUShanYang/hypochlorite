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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 识别链路的阶段。UI 用它决定环转不转、要不要弹命中卡。 */
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
 * 在链路里（采集 / 算指纹 / 比对中）。
 *
 * 识别期间界面零文字，只有动画读它。**权限不是一个阶段**：它由两个 ActivityResult launcher 决定，
 * 那些东西进不了没有 Android 依赖的 player 层（否则 [AudioMatchTest] 得去假造 Android 契约），
 * 而 UI 本来就从 `HomeState.audioCaptureReady` 知道授权到哪一步了。
 */
val AudioMatchPhase.running: Boolean
    get() = this == AudioMatchPhase.Capturing ||
        this == AudioMatchPhase.Fingerprinting ||
        this == AudioMatchPhase.Matching

/**
 * 一次点按最多听几段。每段都是 [app.hypochlorite.audio.AUDIO_MATCH_SECONDS] 秒，
 * 第一段没中就换个位置再听一段（见 [AudioMatch.start]）。
 *
 * 为什么不是把单段拉长：实测这道接口 7 秒以上的 query 指纹稳定认不出来（3/4/6 秒能中，
 * 5/7/8/10 秒无果）—— 它是围绕 3 秒那套 query 设计的。多次短段才是「多听一会儿」的正解，
 * 也是官方客户端在做的事（它给人的「三十秒」其实是多次尝试的总时长）。
 */
const val AUDIO_MATCH_ATTEMPTS = 3

/**
 * 识曲状态。
 *
 * 引擎只负责跑到 [Hit]/[NoResult]/[Error] 并停下，**不自己播放、也不自己切页** ——
 * 播放归 ViewModel（见 [app.hypochlorite.HypochloriteViewModel.onAudioMatchHit]），由用户在命中卡上
 * 按「播放这首」触发。[hitSeq] 每命中一次递增，识别页拿它当「把这条时间线演一次」的 key，
 * 而不是「自动交接」的触发器。
 *
 * 失败与「没听出来」只有 [toast] 一条出口：识别中的界面刻意不放文字，读数全部交给根层提示。
 */
data class AudioMatchState(
    val phase: AudioMatchPhase = AudioMatchPhase.Idle,
    /** 命中曲目。接口其实会回多首，但界面只认第一首。 */
    val hit: Song? = null,
    val toast: String? = null,
    val hitSeq: Long = 0,
    /** 当前听到第几段（1 起，上限 [AUDIO_MATCH_ATTEMPTS]）。界面用它显示重试进度。 */
    val attempt: Int = 1,
)

/**
 * 拿指纹换命中。做成函数注入而不是直接持有 [app.hypochlorite.netease.NeteaseClient]，
 * 是为了让整台状态机能脱离 Android/网络在 JVM 单元测试里跑（见 AudioMatchTest）。
 *
 * 引擎不自己管线程：生产接线（见 HypochloriteApplication）在实现里用 `withContext(IO)`
 * 把阻塞的网络调用挪出主线程，[capture] 同理各自负责调度。
 */
typealias AudioMatchMatcher = suspend (fingerprint: String, durationSeconds: Int) -> List<AudioMatchHit>

/**
 * 听歌识曲引擎。
 *
 * 一轮 = 最多 [AUDIO_MATCH_ATTEMPTS] 段，每段走「采集 → 算指纹 → 比对」；某段命中就当场收工，
 * 全落空才停 [AudioMatchPhase.NoResult]。分段而不是拉长单段，是因为这道接口对超过 6 秒的 query
 * 指纹稳定不认（见 [AUDIO_MATCH_ATTEMPTS] 的注释）。[generation] 计数保证被 [cancel] 掉的旧链路
 * 不会把状态复活（照 [ListenTogether] 的做法）。采集源在构造期定死（Android 10+ 用真投影源，
 * 低版本退回正弦源，见 HypochloriteApplication 的接线），引擎自己不切线程。
 * 网络那一步由 OkHttp 的连接/读取超时兜底（见 `NeteaseClient.defaultHttp`），引擎不再叠一层超时。
 */
class AudioMatch(
    private val scope: CoroutineScope,
    private val capture: AudioCaptureSource,
    private val generator: AudioFingerprintGenerator,
    private val matcher: AudioMatchMatcher,
) {
    private val _state = MutableStateFlow(AudioMatchState())
    val state: StateFlow<AudioMatchState> = _state

    private var job: Job? = null
    private var generation = 0

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        job?.cancel()
        val gen = ++generation
        job = scope.launch {
            try {
                var heard = false
                var attempt = 0
                var hits: List<AudioMatchHit> = emptyList()
                // 一次采集会话里连续听、边听边算：第 n 段算指纹/比对时，第 n+1 段正在录
                // （见 AudioCaptureSource.stream）。命中就返回 false，采集当场停，后面的段不听了。
                _state.update {
                    it.copy(phase = AudioMatchPhase.Capturing, hit = null, toast = null, attempt = 1)
                }
                capture.stream(AUDIO_MATCH_SECONDS, AUDIO_MATCH_ATTEMPTS) { pcm ->
                    if (gen != generation) return@stream false
                    attempt += 1
                    // 静音段不算指纹、不打网络：省掉 AOT/接口开销，继续听下一段；
                    // 全静音时 finish 走「没听到声音」（heard 仍是 false）。
                    if (peakOf(pcm) < SilentPeak) {
                        _state.update { it.copy(phase = AudioMatchPhase.Capturing, attempt = attempt) }
                        return@stream true
                    }
                    heard = true

                    _state.update { it.copy(phase = AudioMatchPhase.Fingerprinting, attempt = attempt) }
                    val fp = generator.generate(pcm)
                    if (gen != generation) return@stream false

                    _state.update { it.copy(phase = AudioMatchPhase.Matching, attempt = attempt) }
                    hits = matcher(fp, AUDIO_MATCH_SECONDS)
                    if (gen != generation) return@stream false
                    hits.isEmpty()
                }
                if (gen != generation) return@launch
                finish(gen, hits, heard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 异常不重试：采集/指纹/网络抛出来的（"录音初始化失败…"）重试大概率还是同样结果，
                // 原样透出去比一句笼统的"识别失败"有用得多 —— 真机上排查全靠它。
                if (gen == generation) {
                    val why = e.message?.takeIf { it.isNotBlank() } ?: "识别失败，再试一次"
                    _state.update { it.copy(phase = AudioMatchPhase.Error, hit = null, toast = why) }
                }
            }
        }
    }

    private fun finish(gen: Int, result: List<AudioMatchHit>, heard: Boolean) {
        if (gen != generation) return
        if (result.isEmpty()) {
            // 无果有两种：听清了但库里没有，和三秒里压根没声音。后者在真机上最常见
            // （对面 App 静音、没在播、被别的东西抢了焦点），文案分开才不至于让人白试。
            _state.update {
                it.copy(
                    phase = AudioMatchPhase.NoResult,
                    hit = null,
                    toast = if (heard) "没听出来，换一段再试" else "没听到声音：确认对面 App 在放、音量没关",
                )
            }
            return
        }
        _state.update {
            it.copy(
                phase = AudioMatchPhase.Hit,
                hit = result.first().song,
                hitSeq = it.hitSeq + 1,
            )
        }
    }

    /** 回到空闲态并掐断在跑的链路。页面退出（back）与识别中再点一次都走这里。 */
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
        _state.update { it.copy(phase = AudioMatchPhase.Idle, hit = null) }
    }

    /** 进识别页时预热指纹宿主（AOT Machine）。失败忽略，点按时还会再建。 */
    fun warmGenerator() {
        scope.launch {
            runCatching { generator.warmUp() }
        }
    }

    fun clearToast() {
        if (_state.value.toast != null) _state.update { it.copy(toast = null) }
    }

    /** 一次性提示（授权被拒、没听出来等）。经 collector 收回 HomeState，UI 侧只读不写。 */
    fun notify(message: String) {
        if (message.isNotEmpty()) _state.update { it.copy(toast = message) }
    }

    private companion object {
        /** 低于这个峰值就当"这三秒里没有声音"：真歌实测 0.03 上下，静音时是 0。 */
        const val SilentPeak = 0.005f
    }
}
