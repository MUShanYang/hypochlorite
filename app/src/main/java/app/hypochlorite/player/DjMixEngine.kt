package app.hypochlorite.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.hypochlorite.netease.Song
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * DJ 自动混音引擎：两首歌之间不再硬切，而是像 DJ 一样接过去。
 *
 * 架构上最关键的取舍是**双播放器**：
 *
 * - 主播放器（宿主那只）照常放当前这首，播放列表、预缓冲、MediaSession 全不动；
 * - 过渡的那十几秒里，副播放器（deck）把下一首从「起混点」开始同时放，
 *   两台播放器各自挂一个 [DjFxProcessor]，增益 / 滤波 / 回响由这里统一编排；
 * - 淡完之后主播放器 seek 到窗口里的下一首（早已预缓冲好），deck 退场。
 *   交接瞬间两台在放同一首歌的同一段，450ms 内一个淡入一个淡出，
 *   人耳只会听到一次很轻的「回声感」，不会有断点。
 *
 * 状态机（由宿主的 200ms tick 驱动，全在主线程）：
 *
 * ```
 * IDLE → ARMED → MIXING → HANDOVER → COOLDOWN → IDLE
 * ```
 *
 * - IDLE：离结束 90s 内且两边分析都就绪 → 算好接法（[MixPlan]），进入 ARMED；
 * - ARMED：提前 4s 把 deck 备好（起混点、对齐后的速度），等到切点准时起混；
 * - MIXING：等功率交叉淡化 + 滤波编排 + 收尾回响，长度按拍数算；
 * - HANDOVER：主播放器带着对齐的速度 seek 接管，deck 淡出退场；
 * - COOLDOWN：主播放器速度用十几秒慢慢漂回 1.0（每 tick 收敛 2%，听不出在变调）。
 *
 * 任何一步出不来（分析失败 / deck 没备好 / 用户插手）都退回普通无缝切歌，
 * 这就是「没有 DJ 模式」时的原行为 —— 本引擎只许加分，不许添乱。
 */
class DjMixEngine(
    private val context: Context,
    private val dataFactory: DataSource.Factory,
    private val httpHeaders: Map<String, String>,
    private val host: DjHost,
) {
    /** 宿主（HypochloritePlayer）暴露给引擎的最小接口。全在主线程调用。 */
    interface DjHost {
        val main: ExoPlayer
        val mainFx: DjFxProcessor
        fun currentSongId(): String?
        fun nextWindowQueueIndex(): Int?
        fun nextWindowMediaItem(): MediaItem?
        fun songAt(queueIdx: Int): Song?
        fun playableUrl(songId: String): String?
        fun durationOf(songId: String): Long
        fun qualityId(): String
        /** 当前状态允不允许接歌（单曲循环 / 出错时不接） */
        fun mixAllowed(): Boolean
        fun isPlaying(): Boolean
        /** deck 也要钉到 USB 独占设备上，否则过渡时声音会从两个出口出来 */
        fun bindDeckOutput(player: ExoPlayer)
    }

    private enum class Phase { IDLE, ARMED, MIXING, HANDOVER, COOLDOWN }

    /** 一份接法：什么时候切、从哪进、多快、多长、什么风格 */
    private data class MixPlan(
        val nextQueueIdx: Int,
        val nextSongId: String,
        val transitionStartMs: Long,
        val cueMs: Long,
        val speed: Float,
        val mixLenMs: Long,
        val filterStyle: Boolean,
        val echoDelaySec: Float,
    )

    // ------------------------------------------------------------------ 状态

    @Volatile
    private var enabled = false

    private var phase = Phase.IDLE
    private var watchedSongId: String? = null
    private var plan: MixPlan? = null
    /** 分析失败或来不及接的一对，标记后这首歌不再重试 */
    private var skipPair: String? = null

    private var deck: ExoPlayer? = null
    private val deckFx = DjFxProcessor()

    fun audioLevel(): Float = if (deck?.isPlaying == true) deckFx.audioLevel() else 0f
    private var deckPrepared = false

    /** 主播放器当前被引擎设成的速度（交接后漂回 1.0 的依据） */
    private var currentSpeed = 1f

    private var mixStartPosMs = 0L
    private var handoverElapsedMs = 0L

    /** 分析缓存：key = "songId|qualityId"，值为结果或 null（失败也缓存，不重试） */
    private val analyses = ConcurrentHashMap<String, TrackAnalysis?>()
    private val analyzing = ConcurrentHashMap.newKeySet<String>()

    /**
     * 分析线程压到最低优先级：解码 + FFT 是连续 CPU 突发，
     * 和音频混音线程抢核就会听到「卡一下」。宁可分析慢一点，反正离切点还早。
     */
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hypochlorite-dj-analysis").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * 换歌时刻（系统毫秒）。换歌后头几秒是 exo 最忙的时候（建新解码器、
     * 攒缓冲），分析任务里的 MediaCodec 创建会和它抢 codec 资源 ——
     * 所以分析统一推迟到换歌 [ANALYSIS_DELAY_MS] 之后才准启动。
     */
    private var songChangedAtMs = 0L

    /** 换歌后多久才准起分析 */
    private val ANALYSIS_DELAY_MS = 4_000L

    // ------------------------------------------------------------------ 对外

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) cancel()
    }

    /**
     * 用户插手（手动切歌 / seek / 暂停 / 队列变动）→ 立即放弃这次过渡。
     * 主播放器增益与速度当场归位：换歌 / 暂停的动作本身会盖住参数跳变。
     */
    fun cancel() {
        phase = Phase.IDLE
        plan = null
        deckPrepared = false
        stopDeck()
        host.mainFx.resetFx()
        resetMainSpeed()
    }

    fun release() {
        cancel()
        analysisExecutor.shutdown()
    }

    // ------------------------------------------------------------------ tick 驱动（主线程，200ms）

    fun onTick(posMs: Long, durMs: Long) {
        if (!enabled) return
        val curId = host.currentSongId()
        if (curId == null) {
            if (phase != Phase.IDLE) cancel()
            return
        }
        // 换歌了（自然换项 / 手动切 / 交接 seek 生效）→ 状态归零重来
        if (curId != watchedSongId) {
            if (phase == Phase.HANDOVER) {
                // seek 已经生效但 450ms 的增益坡还没跑完：当场收尾，
                // 主播放器直接满增益、deck 直接退场，别留着两台一起响
                host.mainFx.resetFx()
                stopDeck()
                phase = Phase.COOLDOWN
            } else if (phase != Phase.COOLDOWN) {
                phase = Phase.IDLE
                skipPair = null
                stopDeck()
                host.mainFx.resetFx()
                resetMainSpeed()
            }
            plan = null
            watchedSongId = curId
            songChangedAtMs = System.currentTimeMillis()
            // 注意：这里不立刻 ensureAnalysis —— 换歌瞬间 exo 正在建新解码器，
            // 分析里的 MediaCodec 创建会跟它抢 codec 资源，抢输就是用户听到的「卡一下」。
            // 分析由 idleTick 在 ANALYSIS_DELAY_MS 之后补起。
        }

        // 速度漂移：交接完成后用十几秒漂回 1.0。接歌进行中不动它。
        if (phase != Phase.MIXING && phase != Phase.HANDOVER) driftSpeed()

        if (!host.isPlaying()) {
            if (phase == Phase.MIXING || phase == Phase.HANDOVER) cancel()
            return
        }

        when (phase) {
            Phase.IDLE -> idleTick(curId, posMs, durMs)
            Phase.ARMED -> armedTick(posMs)
            Phase.MIXING -> mixingTick(posMs)
            Phase.HANDOVER -> handoverTick()
            Phase.COOLDOWN -> {
                if (currentSpeed == 1f) phase = Phase.IDLE
            }
        }
    }

    // ------------------------------------------------------------------ 各相位

    private fun idleTick(curId: String, posMs: Long, durMs: Long) {
        if (!host.mixAllowed()) return
        if (durMs < 60_000L) return
        // 换歌头几秒让 exo 先把新歌安顿好，分析 / deck 构建都往后排
        if (System.currentTimeMillis() - songChangedAtMs < ANALYSIS_DELAY_MS) return
        val nextIdx = host.nextWindowQueueIndex() ?: return
        val nextSong = host.songAt(nextIdx) ?: return
        if (durMs - posMs > 90_000L) {
            // 还早：先把两首的分析做掉（异步，tick 不等人）；
            // deck 播放器也趁空闲建好，别等第一次 ARMED 才在主线程现建
            ensureAnalysis(curId)
            ensureAnalysis(nextSong.id)
            ensureDeck()
            return
        }
        val pairKey = "$curId>${nextSong.id}"
        if (skipPair == pairKey) return
        ensureAnalysis(curId)
        ensureAnalysis(nextSong.id)

        val curKey = analysisKey(curId)
        val nextKey = analysisKey(nextSong.id)
        if (!analyses.containsKey(curKey) || !analyses.containsKey(nextKey)) return
        val cur = analyses[curKey]
        val next = analyses[nextKey]
        if (cur == null || next == null) {
            // 有一首分析不出来 → 这次不接，普通无缝切歌照旧发生
            skipPair = pairKey
            return
        }
        val p = buildPlan(nextIdx, nextSong.id, cur, next, posMs, durMs)
        if (p == null) {
            skipPair = pairKey
            return
        }
        plan = p
        phase = Phase.ARMED
    }

    private fun armedTick(posMs: Long) {
        val p = plan ?: run {
            phase = Phase.IDLE
            return
        }
        if (!host.mixAllowed()) {
            cancel()
            return
        }
        // 窗口变了（用户动了队列 / 随机重掷）→ 计划作废
        if (host.nextWindowQueueIndex() != p.nextQueueIdx ||
            host.songAt(p.nextQueueIdx)?.id != p.nextSongId
        ) {
            cancel()
            return
        }
        // 提前 4s 备 deck：起混点 seek + 预缓冲，让切点一到就能出声
        if (!deckPrepared && posMs >= p.transitionStartMs - 4_000L) {
            prepareDeck(p)
        }
        if (posMs >= p.transitionStartMs && deckPrepared) {
            beginMix(p, posMs)
        } else if (posMs > p.transitionStartMs + 3_000L) {
            // 没赶上（deck 迟迟备不好）→ 放弃这次，让普通无缝切歌发生
            cancel()
        }
    }

    private fun mixingTick(posMs: Long) {
        val p = plan ?: run {
            cancel()
            return
        }
        val t = ((posMs - mixStartPosMs).toFloat() / p.mixLenMs).coerceIn(0f, 1f)
        applyMixCurves(p, t)
        if (t >= 1f) beginHandover(p)
    }

    private fun handoverTick() {
        handoverElapsedMs += 200L
        val k = (handoverElapsedMs / 450f).coerceIn(0f, 1f)
        // 两台都在放下一首：主淡入、deck 淡出，450ms 内换完
        host.mainFx.setParams(DjFxProcessor.FxParams(gain = k))
        deckFx.setParams(DjFxProcessor.FxParams(gain = 1f - k))
        if (k >= 1f) {
            stopDeck()
            host.mainFx.resetFx()
            phase = Phase.COOLDOWN
        }
    }

    // ------------------------------------------------------------------ 计划

    /**
     * 算接法。任何条件不满足返回 null → 退回普通无缝切歌。
     *
     * - 速度：把下一首对齐到当前这首的**有效** BPM（含当前速度倍率），
     *   允许 ±6%（DJ 的常识范围，再多就跑调了），先试八度折叠；
     * - 时机：当前这首的 outro 点，且不晚于「结束前 混音长+1.5s」；
     *   太晚（剩不到 4s）就不接了 —— 接一半被自然换项打断更突兀；
     * - 长度：合拍 16 拍（6~14s 钳制）；不合拍砍半并走滤波接法 ——
     *   调性打架时长重叠 = 灾难，滤波接法把两首的频段错开才是正解。
     */
    private fun buildPlan(
        nextIdx: Int,
        nextId: String,
        cur: TrackAnalysis,
        next: TrackAnalysis,
        posMs: Long,
        durMs: Long,
    ): MixPlan? {
        val compatible = cur.keyCompatible(next)

        var speed = 1f
        if (cur.hasTempo && next.hasTempo) {
            val effectiveCur = cur.bpm * currentSpeed
            var ratio = effectiveCur / next.bpm
            // 八度折叠：125 对 62.5 不如按 125 对 125 接
            if (ratio > 1.5) ratio /= 2.0
            if (ratio < 0.67) ratio *= 2.0
            speed = if (ratio in 0.94..1.06) ratio.toFloat() else 1f
        }

        val beatMs = cur.beatMs
        val beats = if (compatible) 16 else 8
        val mixLen = (beatMs * beats).toLong().coerceIn(6_000L, 14_000L)

        var start = cur.outroStartMs
        val latest = durMs - mixLen - 1_500L
        if (start > latest) start = latest
        if (start < posMs + 4_000L) return null

        // 回响延迟 = 附点八分，跟当前这首的网格走
        val echoSec = (beatMs * 0.75 / 1000.0).toFloat().coerceIn(0.15f, 0.6f)

        return MixPlan(
            nextQueueIdx = nextIdx,
            nextSongId = nextId,
            transitionStartMs = start,
            cueMs = next.cueInMs,
            speed = speed,
            mixLenMs = mixLen,
            filterStyle = !compatible,
            echoDelaySec = echoSec,
        )
    }

    // ------------------------------------------------------------------ 执行

    private fun prepareDeck(p: MixPlan) {
        val item = host.nextWindowMediaItem() ?: return
        val d = ensureDeck()
        // 独占设备可能在 deck 建好之后才换过 → 每次起混前重新钉一次
        host.bindDeckOutput(d)
        runCatching {
            deckFx.resetFx()
            d.setMediaItem(item)
            d.seekTo(p.cueMs)
            d.setPlaybackParameters(PlaybackParameters(p.speed))
            d.prepare()
            deckPrepared = true
        }
    }

    private fun beginMix(p: MixPlan, posMs: Long) {
        val d = deck ?: run {
            cancel()
            return
        }
        // play() 在缓冲好之前不会真正出声，但此时淡入增益还压在 0 附近，
        // 起混晚几十毫秒听不出来；起混点的拍齐已经在 seek 时保证了
        runCatching { d.play() }
        mixStartPosMs = posMs
        phase = Phase.MIXING
    }

    /**
     * 混音曲线编排。t ∈ 0..1。
     *
     * - 音量永远走等功率（cos/sin），中点两首各 -6dB，不会出现「中间瘪一块」；
     * - 滤波接法（调性不合）：淡出那首高通扫上去（先把低音让出来），
     *   淡入那首低通慢慢打开（闷着进来、铺开为止）—— 两首的中低频不叠加，
     *   调性打架就听不出来了。中点附近完成「低音交接」；
     * - 平滑接法（调性合）：只做很轻的滤波点缀，靠音量曲线本身；
     * - 收尾：淡出那首最后 1/4 撒回响 + 一点混响，切走的边界藏在尾巴里。
     */
    private fun applyMixCurves(p: MixPlan, t: Float) {
        val outGain = cos(t * Math.PI / 2).toFloat()
        val inGain = sin(t * Math.PI / 2).toFloat()

        val hp: Float
        val lp: Float
        if (p.filterStyle) {
            hp = when {
                t < 0.5f -> 30f + (t / 0.5f) * 370f
                else -> 400f + ((t - 0.5f) / 0.5f) * 2200f
            }
            lp = if (t > 0.85f) 0f else 600f + (t / 0.85f) * 12_000f
        } else {
            hp = if (t < 0.2f) 0f else 30f + ((t - 0.2f) / 0.8f) * 220f
            lp = if (t > 0.6f) 0f else 350f + (t / 0.6f) * 11_000f
        }

        val tail = smoothstep(t, 0.75f, 1f)
        host.mainFx.setParams(
            DjFxProcessor.FxParams(
                gain = outGain,
                hpCutoffHz = hp,
                echoMix = tail * 0.38f,
                echoDelaySec = p.echoDelaySec,
                echoFeedback = 0.42f,
                reverbMix = smoothstep(t, 0.85f, 1f) * 0.28f,
            ),
        )
        deckFx.setParams(
            DjFxProcessor.FxParams(
                gain = inGain,
                lpCutoffHz = lp,
            ),
        )
    }

    /**
     * 交接：主播放器带对齐好的速度 seek 到窗口第 1 项（= deck 正在放的那首）。
     *
     * seek 目标位置取 deck 当前位置 +120ms：补偿「主线程发 seek → 音频轨
     * 真正出声」的延迟，让两台的内容位置尽量重叠。接着 handoverTick 用
     * 450ms 完成主入 / deck 出。
     */
    private fun beginHandover(p: MixPlan) {
        val d = deck ?: run {
            cancel()
            return
        }
        // 窗口第 1 项必须还在：交接就是 seek 到它。不在了就让 deck 播完这句、自然换项
        if (runCatching { host.main.mediaItemCount }.getOrDefault(0) < 2) {
            cancel()
            return
        }
        val deckPos = runCatching { d.currentPosition }.getOrDefault(p.cueMs)
        runCatching { host.main.setPlaybackParameters(PlaybackParameters(p.speed)) }
        currentSpeed = p.speed
        runCatching { host.main.seekTo(1, deckPos + 120L) }
        handoverElapsedMs = 0L
        phase = Phase.HANDOVER
    }

    // ------------------------------------------------------------------ 小件

    private fun ensureDeck(): ExoPlayer {
        deck?.let { return it }
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(deckFx))
                .build()
        }
        val attrs = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val d = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataFactory))
            // deck 不抢音频焦点、不响应拔耳机：焦点与暂停策略全归主播放器
            .setAudioAttributes(attrs, false)
            .setHandleAudioBecomingNoisy(false)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 30_000, 600, 1_500)
                    .build(),
            )
            .build()
        d.volume = 1f
        host.bindDeckOutput(d)
        deck = d
        return d
    }

    private fun stopDeck() {
        val d = deck ?: return
        runCatching { d.pause() }
        runCatching { d.stop() }
        runCatching { d.clearMediaItems() }
        deckFx.resetFx()
        deckPrepared = false
    }

    /** 速度漂移：每 tick 向 1.0 收敛 2%，~7s 收敛一半，二十来秒后到位 */
    private fun driftSpeed() {
        if (currentSpeed == 1f) return
        val next = currentSpeed + (1f - currentSpeed) * 0.02f
        val snapped = if (abs(next - 1f) < 0.0008f) 1f else next
        if (snapped != currentSpeed) {
            currentSpeed = snapped
            runCatching { host.main.setPlaybackParameters(PlaybackParameters(snapped)) }
        }
    }

    private fun resetMainSpeed() {
        if (currentSpeed != 1f) {
            currentSpeed = 1f
            runCatching { host.main.setPlaybackParameters(PlaybackParameters(1f)) }
        }
    }

    private fun smoothstep(x: Float, from: Float, to: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ------------------------------------------------------------------ 分析调度

    private fun analysisKey(songId: String) = "$songId|${host.qualityId()}"

    /**
     * 把一首的分析丢给单线程执行器（一次只跑一个，解码 + DSP 挺吃 CPU）。
     * 结果（含失败 null）都进缓存：失败不重试，免得每 200ms 踢一次网络。
     */
    private fun ensureAnalysis(songId: String) {
        val key = analysisKey(songId)
        if (analyses.containsKey(key)) return
        if (analyses.size > 100) analyses.clear()
        if (!analyzing.add(key)) return
        val url = host.playableUrl(songId)
        if (url.isNullOrEmpty()) {
            // 流还没解析出来：下個 tick 再来，不占「做过」的名额
            analyzing.remove(key)
            return
        }
        val dur = host.durationOf(songId)
        analysisExecutor.execute {
            try {
                analyses[key] = DjAnalyzer.analyze(url, httpHeaders, dur)
            } catch (_: Throwable) {
                analyses[key] = null
            } finally {
                analyzing.remove(key)
            }
        }
    }
}
