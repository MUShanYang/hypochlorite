package app.hypochlorite.player

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.hypochlorite.netease.LyricLine
import app.hypochlorite.netease.NeteaseClient
import app.hypochlorite.netease.Playable
import app.hypochlorite.netease.PlaybackStateRecord
import app.hypochlorite.netease.Quality
import app.hypochlorite.netease.QualityPreset
import app.hypochlorite.netease.SessionStore
import app.hypochlorite.netease.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 唤醒锁的标签，出问题时能在 dumpsys power 里一眼认出来 */
private const val WAKE_LOCK_TAG = "hypochlorite:playback"

/** 进度落盘的最小间隔；队列可能上千首，写一次不便宜，宁可粗一点 */
private const val SAVE_INTERVAL_MS = 10_000L

/** 位置比上次多走了这么多才值得再写一次 */
private const val SAVE_MIN_DELTA_MS = 3_000L

/**
 * 预加载往字节缓存里写多少。起播要攒 ~2.5s 的音频（DefaultLoadControl 默认），
 * 无损档 ~1000kbps 下大约 320KB，取 1MB 给高码率 / 慢网留足余量。
 */
private const val PRELOAD_BYTES = 1L * 1024 * 1024

/** 音频字节缓存的目录名（挂在 cacheDir 下）。Application 侧清过期缓存时要跳过它。 */
const val MEDIA_CACHE_DIR = "media_cache"

/** 字节缓存上限，LRU 淘汰。无损单曲 30~50MB，够放五六首热歌。 */
private const val MEDIA_CACHE_MAX_BYTES = 256L * 1024 * 1024

/**
 * 预缓冲窗口大小：当前 + 之后两首。
 *
 * ExoPlayer 的「预缓冲下一首」（[ExoPlayer.PreloadConfiguration]）是按**播放列表**工作的：
 * 列表里没有下一项，它就没有东西可提前读 —— 切歌必然掉进一次重新 prepare 的停顿。
 */
private const val WINDOW_SIZE = 3

/** 让 ExoPlayer 把下一首提前读满多少毫秒。一首歌放完之前，下一首早就准备好了。 */
private const val PRELOAD_TARGET_MS = 30_000L

enum class PlayMode(val label: String) {
    SEQUENCE("[列表循环]"),
    LOOP_ONE("[单曲循环]"),
    SHUFFLE("[随机播放]"),
}

data class PlayerSnapshot(
    val queue: List<Song> = emptyList(),
    val index: Int = -1,
    val current: Song? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val lyricLines: List<LyricLine> = emptyList(),
    val lyricIndex: Int = -1,
    /** 当前这首的歌词已经拉完（空也算）。没置位时 empty 只是在路上，不能当成纯音乐。 */
    val lyricsResolved: Boolean = false,
    val error: String? = null,
    val roam: Boolean = false,
    val playable: Playable? = null,
    val quality: QualityPreset = Quality.resolve("high"),
    val playMode: PlayMode = PlayMode.SEQUENCE,
    /** 播放历史（最近的在后）。内存态，不落盘 —— 队列的教训：prefs 不是数据库 */
    val history: List<Song> = emptyList(),
)

/**
 * HiFi 音频输出配置。
 *
 * 和 [QualityPreset] 的区别：「音质偏好」决定跟服务器要哪一档码流，
 * 这里决定拿到之后怎么送进 DAC。两者独立 —— 换输出方式不需要重新取流。
 */
data class AudioOutConfig(
    /**
     * 固定走 USB 输出，避免系统把流送到其它出口。
     *
     * 注意「避开重采样」**不是这一个开关就能做到的** —— 它靠的是
     * 「音源采样率 == 设备原生采样率」。这个开关做的是让输出**固定**走 USB
     * 设备 —— 否则系统会把流送到别的出口（那些出口的原生采样率
     * 往往与 USB 设备不同，会额外插一次重采样）。
     * 真正的判定见 `judgeResample`，界面据此如实显示当前状态。
     */
    val usbExclusive: Boolean = false,
    /** 独占音频焦点：关掉「和其它 app 一起响」 */
    val exclusiveFocus: Boolean = false,
    /** 保持唤醒：熄屏继续出声，同时压住 CPU 降频导致的爆音 */
    val keepAwake: Boolean = true,
    /** 系统级输出音量直控：不走 app 侧的数字衰减 */
    val directVolume: Boolean = false,
    /** 精确音量 0..100，仅在 [directVolume] 关闭时生效 */
    val gainPercent: Int = 93,
    /** 记住的 USB 设备 id，−1 = 没记过 */
    val rememberedUsbId: Int = -1,
) {
    fun gain(): Float = if (directVolume) 1f else gainForPercent(gainPercent)

    /**
     * 改这些必须重建 player：它们是构建期参数。
     *
     * 目前只有独奏焦点（[ExoPlayer.Builder.setAudioAttributes] 的 handleAudioFocus）。
     * USB 独占走 `setPreferredAudioDevice`；精确音量 / 系统直控走 `setVolume`。
     * 为它们重建 player 会把已经交给 MediaSession 的那只实例释放掉，
     * 通知栏 / 锁屏按键握着一具尸体，开关开完播放直接停。
     */
    fun samePipelineAs(other: AudioOutConfig): Boolean =
        exclusiveFocus == other.exclusiveFocus

    /**
     * 唤醒模式 / 绑定设备可以热改，不需要重建 —— 重建会打断当前播放。
     */
    fun sameLiveAs(other: AudioOutConfig): Boolean =
        keepAwake == other.keepAwake && rememberedUsbId == other.rememberedUsbId
}

class HypochloritePlayer(
    /** 留作成员：重建 [exo] 时还要用它建新的实例 */
    private val context: Context,
    private val client: NeteaseClient,
    private val scope: CoroutineScope,
    private val okHttp: OkHttpClient,
    private val session: SessionStore,
) : DjMixEngine.DjHost {
    private val savedQueue = mutableListOf<Song>()
    private var savedIndex = -1

    /**
     * 播放器实例。**不是 val** —— 改独占 / 音量控制方式时要换成一条全新的音频管道，
     * 见 [applyAudioOut] 里的重建分支。引用本身只在主线程改，跨线程读的是换完之后的那个。
     */
    @Volatile
    var exo: ExoPlayer
        private set

    private val _state = MutableStateFlow(PlayerSnapshot())
    val state: StateFlow<PlayerSnapshot> = _state

    private val playableCache = ConcurrentHashMap<String, Playable>()
    private val lyricCache = ConcurrentHashMap<String, List<LyricLine>>()
    private var preloadedForId: String? = null

    /**
     * exo 播放列表项 ↔ 本地队列下标 的映射：exo 的第 i 项放的是 `queue[windowQueue[i]]`。
     *
     * **只在主线程读写**（[loadJob] 跑在 Main.immediate，播放器回调也在主线程）。
     * 窗口的第 0 项永远是「正在放的那首」，播过的项会在换项时被摘掉，
     * 所以这张表和 exo 列表的长度必须始终一致 —— 对不上就宁可放弃这一轮补窗。
     */
    private val windowQueue = mutableListOf<Int>()
    private var windowJob: Job? = null

    /** 播放历史（最近的在后）。只在主线程改，快照里给的是拷贝 */
    private val history = ArrayDeque<Song>()

    /**
     * 把一首歌记进历史：去连续重复、封顶 200 条。
     * 只在「这首歌确实开始播了」的时机调用 —— [playAt] 的入口（离开的那首）和换项回调（播完的那首）。
     */
    private fun recordHistory(song: Song?) {
        if (song == null) return
        if (history.lastOrNull()?.id == song.id) return
        history.addLast(song)
        while (history.size > 200) history.removeFirst()
        _state.update { it.copy(history = history.toList()) }
    }

    /**
     * 音频**字节**缓存（进程内唯一实例，SimpleCache 强制一个目录只能有一个实例）。
     *
     * 之前的「预加载」只是把下一首歌的头 256KB 下载下来**扔掉** —— 连接是热了，
     * 字节没留下。切歌时 ExoPlayer 仍要从零开流、重新下满起播缓冲才能出声，
     * 网络稍慢就是一次能听出来的停顿。字节落进这里之后，切歌时开流先命中缓存，
     * 起播缓冲秒满，听感上是无缝续上。
     *
     * 播放本身也走这条缓存（write-through）：一首歌完整听过一遍后，重播直接走本地。
     * 建实例可能开一次 SQLite（索引库），包 runCatching —— 缓存建不出来顶多退回网络直连。
     */
    private val mediaCache: SimpleCache? by lazy {
        runCatching {
            SimpleCache(
                File(context.cacheDir, MEDIA_CACHE_DIR),
                LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_MAX_BYTES),
                StandaloneDatabaseProvider(context),
            )
        }.getOrNull()
    }

    private var tick: Job? = null
    private var loadJob: Job? = null
    private var playGen = 0
    private var endedForId: String? = null

    /** 音频输出配置。改到影响管道的项会重建 [exo]（见 [rebuildOutputIfNeeded]） */
    private var audioOut = AudioOutConfig()

    /**
     * 主管道的 DJ 音效处理器。平时全中性（快速拷贝路径，不花 CPU），
     * 只有自动接歌的十几秒里才由 [dj] 推参数。增益走它而不是 `exo.setVolume` ——
     * 后者装着用户的精确音量偏好，交叉淡化不能碰。
     */
    private val djFxMain = DjFxProcessor()

    fun audioLevel(): Float {
        if (!exo.isPlaying) return 0f
        val mainLevel = djFxMain.audioLevel()
        val deckLevel = if (::dj.isInitialized) dj.audioLevel() else 0f
        return (maxOf(mainLevel, deckLevel) * exo.volume).coerceIn(0f, 1f)
    }

    /** DJ 自动接歌引擎。init 末尾才建（要拿 this 当宿主），用前判 null 的地方没有 */
    private lateinit var dj: DjMixEngine

    /** 用户偏好：DJ 自动接歌开没开。落盘 key `dj_mix` */
    private var djEnabled = false

    /** 分析器拉流用的请求头：和播放数据厂同一套（CDN 要 Referer 才放行） */
    private val djHttpHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        "Origin" to "https://music.163.com",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 被系统实际接受的输出设备 id，−1 = 没绑（走默认输出） */
    private var boundUsbId: UsbDeviceId = -1

    /** 绑设备时会重建音频轨，会话 id 一变又会回进来；重入直接丢掉 */
    private var bindingDevice: Boolean = false

    init {
        exo = buildExo(context)
        installListener()
        audioOut = loadAudioOutConfig()
        exo.setWakeMode(if (audioOut.keepAwake) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE)
        applyQualityFromStore()
        exo.setVolume(audioOut.gain())
        bindPreferredDevice()
        dj = DjMixEngine(context, buildDataFactory(), djHttpHeaders, this)
        djEnabled = session.isDjMix()
        dj.setEnabled(djEnabled)
        startTick()
    }

    // ------------------------------------------------------------------ 构建

    private fun buildOkHttpDataFactory(): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(okHttp)
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            )
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://music.163.com/",
                    "Origin" to "https://music.163.com",
                ),
            )

    /**
     * 播放开流先查字节缓存，未命中的区间走 OkHttp 上游，读到的数据顺手写回缓存。
     * 缓存建不出来（理论上不会）就退回纯网络直连，行为和从前一样。
     * 抽成独立方法：DJ 引擎的副播放器要用同一条数据厂（共享缓存才能秒备 deck）。
     */
    private fun buildDataFactory(): androidx.media3.datasource.DataSource.Factory =
        mediaCache?.let { cache ->
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(buildOkHttpDataFactory())
                // 缓存层出任何错都不拦播放：直接落到上游重开，顶多慢一点
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } ?: buildOkHttpDataFactory()

    /**
     * 带 DJ 音效处理器的渲染厂。
     *
     * 自定义 AudioProcessor 通过「覆写 buildAudioSink」挂进去 —— media3 没有
     * 直接往 ExoPlayer.Builder 塞处理器的口子。sink 建不出来就退回父类默认
     * （等于没有 DJ 音效），绝不让播放本身起不来。
     */
    private fun buildRenderersFactory(): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val custom: AudioSink? = runCatching<AudioSink> {
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(djFxMain))
                        .build()
                }.getOrNull()
                return custom ?: super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)!!
            }
        }

    private fun buildExo(context: Context): ExoPlayer {
        val data: androidx.media3.datasource.DataSource.Factory = buildDataFactory()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val builder = ExoPlayer.Builder(context)
            .setRenderersFactory(buildRenderersFactory())
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .setAudioAttributes(attrs, audioOut.exclusiveFocus)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 60_000,
                        // 起播门槛压到 800ms 音频：字节缓存已经预热过，没必要非等满默认的 2.5s
                        /* bufferForPlaybackMs = */ 800,
                        // 卡顿恢复还是给足余量，避免弱网下反复触发
                        /* bufferForPlaybackAfterRebufferMs = */ 2_000,
                    )
                    .build(),
            )
        val player = builder.build()
        // 官方「预缓冲下一首」：列表里有下一项时提前把它读满 PRELOAD_TARGET_MS，
        // 换项时直接接上、不用重新准备。跑不起来也不影响播放，包一层。
        runCatching {
            player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(PRELOAD_TARGET_MS * 1_000L))
        }
        return player
    }

    /** 监听器要跟着 [exo] 重建一起搬过去，否则新实例没人转发结束事件 */
    private fun installListener() {
        exo.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(playing = isPlaying) }
                    // 独占模式下播放期间要显式压住系统省电策略，见 syncWakeLock
                    if (audioOut.exclusiveFocus) {
                        if (isPlaying) acquireExclusiveFocus() else releaseExclusiveFocus()
                    }
                    syncWakeLock()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val dur = runCatching {
                        exo.duration.let { if (it == C.TIME_UNSET || it < 0) 0 else it }
                    }.getOrDefault(0)
                    if (playbackState == Player.STATE_ENDED) {
                        // 窗口还有下一项时 ExoPlayer 会自己接上去，根本走不到这儿；
                        // 到这里说明后面真的没东西了（漫游放到底 / 补窗失败）→ 走老路重新加载
                        val id = _state.value.current?.id
                        if (id != null && endedForId != id) {
                            endedForId = id
                            scope.launch { next(manual = false) }
                        }
                    }
                    _state.update {
                        it.copy(
                            durationMs = dur,
                            playing = runCatching { exo.isPlaying }.getOrDefault(false),
                        )
                    }
                }

                /**
                 * 换项：ExoPlayer 自己无缝接上预缓冲的那首（AUTO），或者我们对它 seek 到窗口里的某项。
                 *
                 * 这里只负责**把本地状态同步过去** —— 曲目信息、歌词、进度落盘，
                 * 再把窗口往后补一首。音频那边什么都没重来，所以听感是连着的。
                 */
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val pos = runCatching { exo.currentMediaItemIndex }.getOrDefault(0)
                    if (pos !in windowQueue.indices) return
                    val queueIdx = windowQueue[pos]
                    val song = _state.value.queue.getOrNull(queueIdx) ?: return
                    val changed = queueIdx != _state.value.index
                    // 播过的项从列表和映射里一起摘掉，让「正在放的」永远停在窗口第 0 位。
                    // 只有 exo 那边真的删成功了才动映射 —— 两边一旦错位，后面补窗会一直放弃
                    if (pos > 0) {
                        val removed = runCatching { exo.removeMediaItems(0, pos) }.isSuccess
                        if (removed) repeat(pos) { windowQueue.removeAt(0) }
                    }
                    if (changed) {
                        recordHistory(_state.value.current)
                        endedForId = null
                        _state.update {
                            it.copy(
                                index = queueIdx,
                                current = song,
                                positionMs = 0L,
                                durationMs = 0L,
                                lyricLines = emptyList(),
                                lyricIndex = -1,
                                lyricsResolved = false,
                                playable = playableCache[playableKey(song.id)],
                                error = null,
                            )
                        }
                        saveCurrentPlayback(0L)
                        scope.launch { loadLyrics(song, playGen) }
                    }
                    fillWindow(playGen)
                }

                /**
                 * 音频会话变了（音频轨（重）建）。
                 *
                 * 这是独占绑定必须重做的时刻：会话 id 到这一步才稳定，切歌会重建音频轨、
                 * 会话跟着换一个 —— 不重绑的话用户会听到「刚开还行，听两首就回默认输出」。
                 *
                 * 不在这里改音频格式：位深与采样率由音频数据和设备能力共同决定，
                 * app 侧改动只会被系统丢掉或触发一次重采样，反而更差。
                 */
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (audioOut.usbExclusive) onAudioTrackReady()
                }
            },
        )
    }

    /** 冷启动把上次选的音质档读回来；读不到 / 档位已下线都由 resolve 兜回默认 */
    private fun applyQualityFromStore() {
        val saved = session.getQualityId() ?: return
        val q = Quality.resolve(saved)
        client.setQuality(q)
        _state.update { it.copy(quality = q) }
    }

    private fun loadAudioOutConfig(): AudioOutConfig = AudioOutConfig(
        usbExclusive = session.isUsbExclusive(),
        exclusiveFocus = session.isExclusiveFocus(),
        keepAwake = session.isKeepAwake(),
        directVolume = session.isDirectVolume(),
        gainPercent = session.getGainPercent(),
        rememberedUsbId = session.getRememberedUsbDeviceId(),
    )

    private fun startTick() {
        tick?.cancel()
        tick = scope.launch {
            var lastSaveTime = 0L
            var lastSavedPos = Long.MIN_VALUE
            while (isActive) {
                runCatching {
                    val pos = exo.currentPosition.coerceAtLeast(0)
                    val dur = exo.duration.let { if (it == C.TIME_UNSET || it < 0) 0 else it }
                    val lines = _state.value.lyricLines
                    _state.update {
                        it.copy(
                            positionMs = pos,
                            durationMs = dur,
                            lyricIndex = Lyrics.currentIndex(lines, pos),
                            playing = exo.isPlaying,
                        )
                    }
                    // 位置没动（暂停 / 播完停住）就一次都不写：既省 IO，也避免空转刷盘
                    val now = System.currentTimeMillis()
                    if (now - lastSaveTime > SAVE_INTERVAL_MS &&
                        abs(pos - lastSavedPos).let { it > SAVE_MIN_DELTA_MS || lastSavedPos == Long.MIN_VALUE }
                    ) {
                        lastSaveTime = now
                        lastSavedPos = pos
                        saveCurrentPlayback(pos)
                    }
                    val rem = dur - pos
                    if (dur > 20_000L && rem in 1..15_000L) {
                        // 预热「窗口里接着要播的那一首」——直接读窗口，别按模式再算一遍：
                        // 随机模式下重算会算出另一首，白忙一场
                        val curId = _state.value.current?.id
                        val nextIdx = windowQueue.getOrNull(1)
                        val marker = "$curId>$nextIdx"
                        if (curId != null && preloadedForId != marker) {
                            preloadedForId = marker
                            if (nextIdx != null) {
                                preloadNext(curId, nextIdx)
                            } else {
                                // 窗口没接上（取流失败 / 刚换过队列）→ 趁这首还没放完补一次。
                                // marker 每首歌只放行一次，失败也不会变成每 200ms 重试
                                refreshUpcoming()
                            }
                        }
                    }
                    // DJ 自动接歌：状态机挂在 tick 上走，内部自己判相位，关了是空转
                    dj.onTick(pos, dur)
                }
                delay(200)
            }
        }
    }

    // ------------------------------------------------------------------ HiFi 音频输出

    /** 当前生效的音频输出配置（含记得的 USB 设备 id） */
    fun audioOut(): AudioOutConfig = audioOut

    // ------------------------------------------------------------------ DJ 自动接歌

    fun djMixEnabled(): Boolean = djEnabled

    /** 开关 DJ 自动接歌。用户显式偏好 → 落盘；关掉时引擎立刻收场（增益、速度归位） */
    fun setDjMix(on: Boolean) {
        djEnabled = on
        session.saveDjMix(on)
        dj.setEnabled(on)
    }

    // --- DjMixEngine.DjHost：引擎只读状态，全在主线程被调 ---------------------

    override val main: ExoPlayer get() = exo
    override val mainFx: DjFxProcessor get() = djFxMain
    override fun currentSongId(): String? = _state.value.current?.id
    override fun nextWindowQueueIndex(): Int? = windowQueue.getOrNull(1)
    override fun nextWindowMediaItem(): MediaItem? = runCatching {
        if (exo.mediaItemCount > 1) exo.getMediaItemAt(1) else null
    }.getOrNull()

    override fun songAt(queueIdx: Int): Song? = _state.value.queue.getOrNull(queueIdx)
    override fun playableUrl(songId: String): String? =
        playableCache[playableKey(songId)]?.playUrl?.takeIf { it.isNotEmpty() }

    override fun durationOf(songId: String): Long =
        _state.value.queue.firstOrNull { it.id == songId }?.durationMs ?: 0L

    override fun qualityId(): String = _state.value.quality.id
    override fun mixAllowed(): Boolean =
        _state.value.playMode != PlayMode.LOOP_ONE && _state.value.error == null

    override fun isPlaying(): Boolean = runCatching { exo.isPlaying }.getOrDefault(false)

    /** deck 也要钉到独占的 USB 设备上，否则过渡那几秒声音会从两个出口出来 */
    override fun bindDeckOutput(player: ExoPlayer) {
        if (!audioOut.usbExclusive) return
        val dev = pickUsbDevice(audioOutputs(context), audioOut.rememberedUsbId) ?: return
        runCatching { player.setPreferredAudioDevice(dev) }
    }

    /**
     * 应用一套新的音频输出配置。
     *
     * 分两档处理，[AudioOutConfig.samePipelineAs] 决定走哪一档：
     *
     * - **USB 独占 / 唤醒 / 设备记忆 / 精确音量 / 系统直控** → 热改，不重建 player。
     *   独占只是换输出设备，音量只是 `setVolume`。重建会打断当前播放，还会把
     *   MediaSession 握着的实例释放掉。
     * - **改了独奏焦点** → 必须重建 [exo]：`handleAudioFocus` 是构建期参数。
     *   重建时保留当前播放位置（和暂停状态）。
     *
     * 重建走的是「新建 → 换指针 → 释放旧的」的顺序，**不要反过来**：
     * 旧实例一旦先释放，中间这段时间 tick / UI 拿到的就是已释放的 player。
     */
    fun applyAudioOut(next: AudioOutConfig, persist: Boolean = true) {
        val prev = audioOut
        audioOut = next

        if (persist) {
            session.saveUsbExclusive(next.usbExclusive)
            session.saveExclusiveFocus(next.exclusiveFocus)
            session.saveKeepAwake(next.keepAwake)
            session.saveDirectVolume(next.directVolume)
            session.saveGainPercent(next.gainPercent)
            session.saveRememberedUsbDeviceId(next.rememberedUsbId)
        }

        val pipelineChanged = !next.samePipelineAs(prev)

        if (pipelineChanged) {
            // 换实例等于过渡现场作废：deck 还响着就乱套了
            dj.cancel()
            val wasPlaying = runCatching { exo.isPlaying }.getOrDefault(false)
            val songId = _state.value.current?.id
            val pos = runCatching { exo.currentPosition }.getOrDefault(0L)
            val item = runCatching { exo.currentMediaItem }.getOrNull()
            val hadMedia = item != null

            val old = exo
            val fresh = buildExo(context)
            exo = fresh
            fresh.setVolume(next.gain())
            installListener()
            DeviceHub.reset()
            boundUsbId = -1
            fresh.setWakeMode(if (next.keepAwake) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE)
            bindPreferredDevice()

            // 同一首歌 → 就地读回来接着放。放的是同一份 URL，
            // 所以不必重新跟服务端要流，也不会有「切档等于重新加载」的停顿。
            if (hadMedia) {
                runCatching {
                    fresh.setMediaItem(item!!)
                    if (pos > 0) fresh.seekTo(pos)
                    fresh.prepare()
                    if (wasPlaying) fresh.play()
                }
                // 换实例等于窗口清零：当前项按现在的下标重新对齐，再把后面几首接回来
                windowQueue.clear()
                val curIdx = _state.value.index
                if (curIdx >= 0) windowQueue.add(curIdx)
                applyRepeatMode()
                fillWindow(playGen)
            }

            runCatching { old.release() }
            _state.update { it.copy(playable = if (hadMedia) it.playable else null) }
            if (!hadMedia && songId != null) {
                // 没有媒体在手（比如刚切完歌还没加载完）→ 重新走一次加载
                val idx = _state.value.index
                if (idx >= 0) {
                    playGen += 1
                    playAt(idx, initialSeekMs = pos, autoPlay = wasPlaying)
                }
            }
        } else {
            if (next.keepAwake != prev.keepAwake) {
                exo.setWakeMode(if (next.keepAwake) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE)
            }
            if (next.gain() != prev.gain()) {
                runCatching { exo.setVolume(next.gain()) }
            }
            // 不要在音量拖动时重设偏好：设备没变的话重设一次会让 AudioSink
            // 重建音频轨，用户只是关个「保持唤醒」或拖一下音量也会听到一下断音。
            if (next.usbExclusive != prev.usbExclusive || next.rememberedUsbId != prev.rememberedUsbId) {
                bindPreferredDevice()
            }
        }

        if (pipelineChanged && !next.exclusiveFocus) releaseExclusiveFocus()
    }

    /** 读取当前生效的 USB 设备（可能因拔插变成 null） */
    fun currentUsbDevice(): android.media.AudioDeviceInfo? =
        pickUsbDevice(audioOutputs(context), audioOut.rememberedUsbId)

    /**
     * 轮询检查 USB 设备集合有没有变。
     *
     * 系统没有「USB 音频设备变化」的广播，只能靠比对设备列表发现。
     * 由 ViewModel 在设置页可见时按秒调用 —— 没在设置页时完全不跑，
     * 不会给播放路径增加任何开销。
     */
    fun refreshUsbDevices(): Boolean {
        val devices = audioOutputs(context)
        if (!DeviceHub.observe(devices)) return false

        // 用户选的那只被拔了 → 退回默认输出，并把记忆清掉，
        // 否则插上新的设备会一直被「找不到旧 id」绕回默认输出。
        if (audioOut.usbExclusive && !DeviceHub.stillPresent(devices, audioOut.rememberedUsbId)) {
            val fallback = pickUsbDevice(devices, audioOut.rememberedUsbId)
            applyAudioOut(
                audioOut.copy(rememberedUsbId = fallback?.id ?: -1),
                persist = true,
            )
        } else {
            // 设备集合变了就重新认一次。同设备 / 没设备的情况都会在
            // bindPreferredDevice 里被挡住，不会白重建音频轨。
            bindPreferredDevice()
        }
        return true
    }

    /**
     * 把输出钉到 USB 设备。
     *
     * 走的是 media3 自己的 `setPreferredAudioDevice` —— 它把偏好直接交给 AudioSink，
     * 最终落在**那条真正出声的 AudioTrack** 上。
     *
     * 以前这里用的是「照播放器的会话 id 反射造一条哑轨、在哑轨上设偏好」的老办法，
     * 那是行不通的：偏好是**逐轨**的，设在哑轨上不会传染给播放器自己的轨 ——
     * 播放器照旧往默认出口送，哑轨那边回读到的却是「我设成功了」，于是开关开完
     * 一直停在「系统没放行」，用户看到的就是「独占根本用不了」。
     *
     * 偏好由 AudioSink 自己记着，换歌重建音频轨不需要重设。打开/关掉独占是热切换，
     * 不换 player 实例（换了会把 MediaSession 绑在已释放的播放器上）。
     */
    private fun bindPreferredDevice() {
        if (bindingDevice) return
        bindingDevice = true
        try {
            bindPreferredDeviceLocked()
        } finally {
            bindingDevice = false
        }
    }

    private fun bindPreferredDeviceLocked() {
        if (!audioOut.usbExclusive) {
            if (boundUsbId != -1) clearPreferredDevice()
            boundUsbId = -1
            return
        }
        val dev = pickUsbDevice(audioOutputs(context), audioOut.rememberedUsbId)
        if (dev == null) {
            // 设备拔了 → 清掉偏好，让声音干净地回到默认出口。
            // 留着旧偏好只会把声音指到一只已经不存在的设备上。
            if (boundUsbId != -1) clearPreferredDevice()
            boundUsbId = -1
            return
        }
        // 已经钉在这只上就别再设一次 —— 重设会让 AudioSink 重建一次音频轨，
        // 白白在播放中间断一下。
        if (dev.id == boundUsbId) return
        // 先记下再下发：下发会重建音频轨，会话 id 一变会回进 onAudioTrackReady。
        // 不先记下的话会再绑一次，循环打断当前播放。
        boundUsbId = dev.id
        if (!applyPreferredDevice(dev)) {
            boundUsbId = -1
            return
        }
        if (dev.id != audioOut.rememberedUsbId) {
            // 第一次绑上（或换了一只）→ 记住，下次冷启动直接认出来
            audioOut = audioOut.copy(rememberedUsbId = dev.id)
            session.saveRememberedUsbDeviceId(dev.id)
        }
    }

    /**
     * 下发/撤销输出偏好设备。
     *
     * `dev` 传 null 表示撤销 —— 撤销是必要的动作，不是可选项：偏好一旦下发就不归
     * 我们管了，只把它设成 null 才能让声音回到系统默认出口。
     *
     * 偏好先交给 AudioSink，再推一次相同的音频属性：部分机型只在**建轨**时认
     * `setPreferredDevice`，光改已有轨会被忽略。推属性会让 AudioSink 换一条轨，
     * 新轨带上刚记下的偏好。不换 ExoPlayer 实例，MediaSession 不受影响。
     */
    private fun applyPreferredDevice(dev: android.media.AudioDeviceInfo?): Boolean =
        runCatching {
            exo.setPreferredAudioDevice(dev)
            // 没在播就让 AudioSink 把偏好记下，等建轨时自己带上。
            // 正在播才推一次音频属性，逼 AudioSink 换轨，否则部分机型会继续走旧出口。
            if (runCatching { exo.currentMediaItem }.getOrNull() != null) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
                exo.setAudioAttributes(attrs, audioOut.exclusiveFocus)
            }
        }.isSuccess

    private fun clearPreferredDevice() {
        applyPreferredDevice(null)
    }

    /** 被系统实际接受的输出设备；null = 独占没生效，正走默认输出 */
    fun boundDeviceId(): UsbDeviceId? = boundUsbId.takeIf { it >= 0 }

    /**
     * 当前这条流会不会被系统重采样，以及设备支持哪些采样率。
     *
     * 判定用的是**正在播放的这条流**的采样率（[Playable.sampleRate]）比对设备能力 ——
     * 这是唯一有意义的比法，因为重采样是「数据 → 设备」这一层的事。
     * 没在播 / 接口没报采样率时返回结果里的 [ResampleVerdict.known] 为 false，
     * 界面就不显示结论，而不是猜一个。
     */
    fun resampleVerdict(): ResampleVerdict {
        val devices = audioOutputs(context)
        // 优先看实际绑上的那只 USB 设备；没绑就看系统当前的默认输出
        val device = if (audioOut.usbExclusive) {
            pickUsbDevice(devices, audioOut.rememberedUsbId) ?: defaultOutput(devices)
        } else {
            defaultOutput(devices)
        }
        val sourceRate = _state.value.playable?.sampleRate
        return judgeResample(device, sourceRate)
    }

    /**
     * 系统当前的默认输出设备。
     *
     * 没有「查默认输出」的公开 API，`getDevices` 也不标哪个是默认，所以按类型
     * 优先级推一遍：USB → 有线耳机 → 蓝牙 A2DP → 扬声器。这只是为了在没开独占时
     * 也能估算「会不会重采样」，推错的后果仅仅是状态文案不准，不影响播放。
     */
    private fun defaultOutput(devices: List<android.media.AudioDeviceInfo>): android.media.AudioDeviceInfo? {
        val order = intArrayOf(
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        return order.asSequence()
            .mapNotNull { t -> devices.firstOrNull { it.type == t } }
            .firstOrNull()
            ?: devices.firstOrNull()
    }

    // --- 音频焦点 -------------------------------------------------------

    /**
     * 独奏模式下显式声明独占焦点。
     *
     * ExoPlayer 的 `setAudioAttributes(attrs, handleAudioFocus = true)` 本身就会
     * 申请焦点，但它用的是 `AUDIOFOCUS_GAIN`（允许与导航播报之类共存）。
     * 这里再叠一层 `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`：请求期间系统会压低
     * 其它一切声音，是「只要我在放，就别来打扰」的那个语义。
     */
    fun acquireExclusiveFocus() {
        if (!audioOut.exclusiveFocus || focusRequest != null) return
        runCatching {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(req)
            focusRequest = req
            syncWakeLock()
        }
    }

    fun releaseExclusiveFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager.abandonAudioFocusRequest(req) }
        syncWakeLock()
    }

    // --- 唤醒锁 ---------------------------------------------------------

    /**
     * 需要唤醒锁的场景有两个，满足其一就持有：
     *
     * - **独奏模式**：用户明确要求独占输出，中间不该被系统省电策略掐断。
     * - **保持唤醒开着且在播**：锁屏继续出声，同时避免 CPU 降频让音频线程来不及
     *   喂数据 —— 那是「锁屏后偶尔啪一声」的常见来源。
     *
     * 参考计数由 [PowerManager.WakeLock] 自己维护，重复 acquire/release 是安全的。
     */
    private fun syncWakeLock() {
        val want = audioOut.keepAwake && (_state.value.playing || audioOut.exclusiveFocus)
        runCatching {
            if (want) {
                val lock = wakeLock ?: run {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
                        it.setReferenceCounted(false)
                        wakeLock = it
                    }
                }
                if (!lock.isHeld) lock.acquire()
            } else {
                wakeLock?.let { if (it.isHeld) it.release() }
            }
        }
    }

    /**
     * 音频轨（重）建之后的兜底检查。
     *
     * 偏好设备由 AudioSink 自己记着，换歌不会丢，所以这里**不需要**重设 ——
     * 留着它只是为了在「设备被换成另一只」「拔插过一次」时补一次绑定。
     * 同设备会走 [bindPreferredDevice] 里的提前返回，不会白白重建音频轨。
     */
    fun onAudioTrackReady() {
        if (!audioOut.usbExclusive) return
        bindPreferredDevice()
    }

    /**
     * 切换音质档位。
     *
     * **必须落盘**：这一项以前只写在内存快照里，进程一没就悄悄退回默认档 ——
     * 用户「设了音质偏好，重开又变回去」。写 prefs 很小、又不是高频操作，
     * 直接同步落盘，不必绕 IO。
     */
    fun setQuality(id: String) {
        val q = Quality.resolve(id)
        client.setQuality(q)
        session.saveQualityId(q.id)
        _state.update { it.copy(quality = q) }
        // 窗口里预缓冲的那几首还挂着旧档位的 URL，按新档位重接一遍
        dj.cancel()
        refreshUpcoming()
    }

    fun playSong(song: Song, enqueueOnly: Boolean = false) {
        val q = _state.value.queue.toMutableList()
        val existing = q.indexOfFirst { it.id == song.id }
        val at = if (existing >= 0) existing else {
            q.add(song)
            q.lastIndex
        }
        _state.update { it.copy(queue = q, index = if (enqueueOnly && it.index >= 0) it.index else at) }
        if (!enqueueOnly) playAt(at)
    }

    fun playAll(songs: List<Song>, start: Int = 0) {
        if (songs.isEmpty()) return
        _state.update { it.copy(queue = songs, index = start.coerceIn(0, songs.lastIndex), roam = false) }
        playAt(start.coerceIn(0, songs.lastIndex))
    }

    fun playAt(at: Int, initialSeekMs: Long = 0L, autoPlay: Boolean = true) {
        val q = _state.value.queue
        if (at !in q.indices) return
        val song = q[at]
        val gen = ++playGen
        endedForId = null
        dj.cancel()
        recordHistory(_state.value.current.takeIf { it?.id != song.id })
        windowJob?.cancel()
        _state.update { it.copy(index = at, current = song, error = null, lyricLines = emptyList(), lyricIndex = -1, lyricsResolved = false, positionMs = initialSeekMs, playable = null) }
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val playable = resolvePlayable(song)
                if (gen != playGen) return@launch
                if (playable.playUrl.isNullOrEmpty()) {
                    _state.update { it.copy(error = "这首歌放不了", playable = playable) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        playable = playable,
                        error = if (playable.trial) "试听片段" else null,
                    )
                }
                applyRepeatMode()
                // 列表先只放当前这一首：出声优先，后面几首由 fillWindow 异步接上
                windowQueue.clear()
                windowQueue.add(at)
                exo.setMediaItems(listOf(buildItem(song, playable)), 0, initialSeekMs)
                exo.prepare()
                if (autoPlay) {
                    exo.play()
                }
                saveCurrentPlayback(initialSeekMs)
                fillWindow(gen)
                loadLyrics(song, gen)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == playGen) _state.update { it.copy(error = e.message?.take(48) ?: "取流失败") }
            }
        }
    }

    // ------------------------------------------------------------------ 预缓冲窗口

    /** 取流：内存缓存优先，未命中才走网络（挂 IO，不占主线程） */
    private suspend fun resolvePlayable(song: Song): Playable {
        playableCache[playableKey(song.id)]?.let { return it }
        val fresh = withContext(Dispatchers.IO) {
            client.setQuality(_state.value.quality)
            client.resolvePlayable(song.id)
        }
        playableCache[playableKey(song.id)] = fresh
        return fresh
    }

    private fun buildItem(song: Song, playable: Playable): MediaItem =
        MediaItem.Builder()
            .setUri(playable.playUrl)
            .setMediaId(song.id)
            .setTag(song)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(song.name)
                    .setArtist(song.artists.joinToString(" / "))
                    .setAlbumTitle(song.album)
                    .build()
            )
            .build()

    /** 歌词：缓存优先，网络放 IO；回来先按 gen 校验，避免过期请求覆盖新歌 */
    private suspend fun loadLyrics(song: Song, gen: Int) {
        val cached = lyricCache[song.id]
        if (cached != null) {
            val lines = Lyrics.withoutPlaceholders(cached)
            if (gen == playGen) _state.update { it.copy(lyricLines = lines, lyricsResolved = true) }
            return
        }
        val lines = withContext(Dispatchers.IO) {
            Lyrics.withoutPlaceholders(
                runCatching { client.lyric(song.id) }.getOrDefault(emptyList()),
            )
        }
        if (gen != playGen) return
        if (lines.isNotEmpty()) lyricCache[song.id] = lines
        _state.update { it.copy(lyricLines = lines, lyricsResolved = true) }
    }

    /**
     * 「当前这首之后该放哪首」，按播放模式算。
     *
     * 注意：这里算出来的是**要预缓冲的那一首**，[next] 会直接切过去。所以随机模式
     * 必须在这里就把随机结果定下来，不能等切歌那一刻再随机 —— 否则列表里缓冲的
     * 和实际要播的会是两首不同的歌，预缓冲全白费。
     */
    private fun upcomingIndex(after: Int): Int? {
        val s = _state.value
        if (s.queue.isEmpty()) return null
        if (s.playMode == PlayMode.LOOP_ONE) return null
        if (s.roam) return (after + 1).takeIf { it in s.queue.indices }
        if (s.playMode == PlayMode.SHUFFLE) {
            if (s.queue.size <= 1) return after
            return s.queue.indices.filter { it != after }.random()
        }
        return (after + 1) % s.queue.size
    }

    /**
     * 往后补齐预缓冲窗口：把后面几首的流解析出来、接在 exo 列表尾部。
     *
     * 接上之后 ExoPlayer 会按 [PRELOAD_TARGET_MS] 自己提前读下一首 —— 播完当前这首
     * 是**无缝**换过去的。没有这一段，切歌必然是一次重新 prepare + 重新攒缓冲的停顿。
     *
     * 挂在 `Dispatchers.Main`（不是 immediate）：整段都可能从播放器回调里被调起来，
     * 推后一帧执行，避免在回调里改播放列表引发的重入。
     */
    private fun fillWindow(gen: Int) {
        if (gen != playGen) return
        windowJob?.cancel()
        windowJob = scope.launch(Dispatchers.Main) {
            while (gen == playGen && windowQueue.size < WINDOW_SIZE) {
                val lastIdx = windowQueue.lastOrNull() ?: return@launch
                val nextIdx = upcomingIndex(lastIdx) ?: return@launch
                val song = _state.value.queue.getOrNull(nextIdx) ?: return@launch
                val playable = resolvePlayable(song)
                if (gen != playGen) return@launch
                if (playable.playUrl.isNullOrEmpty()) return@launch
                // 期间可能发生过跳项 / 队列替换 → 列表和映射对不上就放弃这一轮
                if (exo.mediaItemCount != windowQueue.size) return@launch
                if (windowQueue.size >= WINDOW_SIZE) return@launch
                exo.addMediaItem(buildItem(song, playable))
                windowQueue.add(nextIdx)
            }
        }
    }

    /**
     * 丢掉当前项之后的所有预缓冲，按最新队列 / 播放模式重新接一遍。
     *
     * 用 removeMediaItems 而不是 setMediaItems：只动**当前项之后**的部分，
     * 正在放的那一首完全不受影响，不会再来一次重新 prepare。
     */
    private fun refreshUpcoming() {
        if (_state.value.index < 0) return
        val cur = runCatching { exo.currentMediaItemIndex }.getOrDefault(0)
        runCatching { exo.removeMediaItems(cur + 1, exo.mediaItemCount) }
        while (windowQueue.size > cur + 1) windowQueue.removeAt(windowQueue.lastIndex)
        fillWindow(playGen)
    }

    /** 单曲循环交给 ExoPlayer 自己 repeat —— 不经过任何一次重新 prepare，是唯一真无缝的循环 */
    private fun applyRepeatMode() {
        runCatching {
            exo.repeatMode = if (_state.value.playMode == PlayMode.LOOP_ONE) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
        }
    }

    /**
     * 窗口里已经有目标那首 → 直接 seek 过去。
     *
     * 这就是手动切歌不再停顿的原因：绝大多数情况下目标项就躺在列表里，
     * 而且 ExoPlayer 可能已经把它预缓冲好了。
     */
    private fun jumpWithinWindow(expectQueueIndex: Int): Boolean {
        if (expectQueueIndex < 0) return false
        val pos = windowQueue.indexOfFirst { it == expectQueueIndex }
        if (pos <= 0) return false
        dj.cancel()
        return runCatching {
            exo.seekTo(pos, 0L)
            exo.play()
        }.isSuccess
    }

    /** 把某一首插到当前项之前并切过去（回退用）。解析不出来就返回 false，交给 [playAt] */
    private fun prepend(queueIdx: Int): Boolean {
        val song = _state.value.queue.getOrNull(queueIdx) ?: return false
        val playable = playableCache[playableKey(song.id)] ?: return false
        if (playable.playUrl.isNullOrEmpty()) return false
        val pos = runCatching { exo.currentMediaItemIndex }.getOrDefault(0)
        dj.cancel()
        return runCatching {
            exo.addMediaItem(pos, buildItem(song, playable))
            windowQueue.add(pos, queueIdx)
            exo.seekTo(pos, 0L)
            exo.play()
        }.isSuccess
    }

    fun toggle() {
        val s = _state.value
        if (s.index < 0) {
            if (s.queue.isNotEmpty()) playAt(0)
            return
        }
        runCatching {
            if (exo.isPlaying) exo.pause() else exo.play()
            saveCurrentPlayback()
        }
    }

    fun togglePlayMode() {
        val next = when (_state.value.playMode) {
            PlayMode.SEQUENCE -> PlayMode.LOOP_ONE
            PlayMode.LOOP_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENCE
        }
        dj.cancel()
        _state.update { it.copy(playMode = next) }
        // 模式的语义变了（单曲循环不要下一项、随机要重掷）→ 窗口重接
        applyRepeatMode()
        refreshUpcoming()
        saveCurrentPlayback()
    }

    fun next(manual: Boolean = true) {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (!manual && s.playMode == PlayMode.LOOP_ONE) {
            playAt(s.index)
            return
        }
        if (s.roam) {
            val n = s.index + 1
            if (n >= s.queue.size) {
                loadMoreRoam()
            } else if (!jumpWithinWindow(n)) {
                playAt(n)
            }
            return
        }
        if (s.playMode == PlayMode.SHUFFLE && s.queue.size > 1) {
            // 窗口里第 1 项就是补窗时定下的随机结果 —— 直接切，和「下一首」的语义一致
            if (jumpWithinWindow(windowQueue.getOrNull(1) ?: -1)) return
            playAt(s.queue.indices.filter { it != s.index }.random())
            return
        }
        val n = (s.index + 1) % s.queue.size
        if (!jumpWithinWindow(n)) playAt(n)
    }

    fun prev(force: Boolean = false) {
        if (!force && runCatching { exo.currentPosition }.getOrDefault(0) > 3000) {
            runCatching { exo.seekTo(0) }
            return
        }
        val s = _state.value
        if (s.queue.isEmpty()) return
        val p = if (s.playMode == PlayMode.SHUFFLE && s.queue.size > 1) {
            s.queue.indices.filter { it != s.index }.random()
        } else if (s.index - 1 < 0) {
            s.queue.lastIndex
        } else {
            s.index - 1
        }
        // 刚过去那首的流通常还在缓存里 → 插到当前项前面就能立刻切过去
        if (!prepend(p)) playAt(p)
    }

    /**
     * playable 缓存的键。同一首歌换音质档后服务端给的是不同码流的 URL，
     * 只按 song id 缓存会把旧档位的流播给新档位 —— 键里必须带上档位。
     */
    private fun playableKey(songId: String): String = "$songId|${_state.value.quality.id}"

    private fun preloadNext(currentId: String, nextIndex: Int) {
        val nextSong = _state.value.queue.getOrNull(nextIndex) ?: return
        if (nextSong.id == currentId && _state.value.playMode != PlayMode.LOOP_ONE) return
        scope.launch(Dispatchers.IO) {
            try {
                if (playableCache.size > 50) playableCache.clear()
                if (lyricCache.size > 50) lyricCache.clear()

                if (!playableCache.containsKey(playableKey(nextSong.id))) {
                    val playable = client.resolvePlayable(nextSong.id)
                    playableCache[playableKey(nextSong.id)] = playable
                    val url = playable.playUrl
                    if (!url.isNullOrEmpty()) {
                        preloadBytesIntoCache(url)
                    }
                }
                if (!lyricCache.containsKey(nextSong.id)) {
                    val lines = runCatching { client.lyric(nextSong.id) }.getOrDefault(emptyList())
                    if (lines.isNotEmpty()) {
                        lyricCache[nextSong.id] = lines
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 把下一首歌的头 [PRELOAD_BYTES] 字节写进 [mediaCache]。
     *
     * 之前的做法是「Range 拉一段然后 readBytes 扔掉」，只暖了连接；
     * 现在走 CacheWriter，字节真正落在字节缓存里，切歌后 ExoPlayer 开流
     * 先命中这段缓存，起播缓冲不用等网络就能攒满 —— 这才是「预加载」该有的样子。
     */
    private fun preloadBytesIntoCache(url: String) {
        val cache = mediaCache ?: return
        runCatching {
            val spec = DataSpec.Builder()
                .setUri(url)
                .setPosition(0)
                .setLength(PRELOAD_BYTES)
                .build()
            val source = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(buildOkHttpDataFactory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
            try {
                CacheWriter(source, spec, null, null).cache()
            } finally {
                source.close()
            }
        }
    }

    fun seek(positionMs: Long) {
        dj.cancel()
        runCatching { exo.seekTo(positionMs.coerceAtLeast(0)) }
    }

    fun pause() {
        if (!_state.value.playing) return
        runCatching {
            exo.pause()
            saveCurrentPlayback()
        }
    }

    fun resume() {
        if (_state.value.playing) return
        if (_state.value.current == null) return
        runCatching {
            exo.play()
            saveCurrentPlayback()
        }
    }

    /**
     * 整条队列被外部替换（一起听把房间队列套用到本机）。
     *
     * **故意不自动起播**：多人同时加入时谁先起播取决于网速，
     * 每个人听到的位置都会不一样。起播时机统一由房间的 GOTO 指令决定。
     *
     * [keepCurrent] = 队列变了但**别打断正在放的那首歌**（成员侧发现房间加了歌时的场景）。
     * 它会按 id 找回当前歌在新队列里的位置，找不到才退回第一首。
     */
    fun replaceQueue(songs: List<Song>, keepIndex: Boolean = false, keepCurrent: Boolean = false) {
        if (songs.isEmpty()) return
        dj.cancel()
        val cur = _state.value.current
        val keep = when {
            keepCurrent && cur != null -> songs.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
            keepIndex && _state.value.index in songs.indices -> _state.value.index
            else -> 0
        }
        val sameCurrent = keepCurrent && cur != null && songs.getOrNull(keep)?.id == cur.id
        _state.update {
            it.copy(
                queue = songs,
                index = keep,
                current = songs.getOrNull(keep),
                roam = false,
                error = null,
                // 当前歌没变就不要碰播放状态和位置，否则会听出一次顿挫
                playing = if (sameCurrent) it.playing else false,
                positionMs = if (sameCurrent) it.positionMs else 0L,
            )
        }
        if (sameCurrent) {
            // 队列换了 → 窗口里那些项的下标已经对不上，把当前项之后的部分重接一遍
            val exoPos = runCatching { exo.currentMediaItemIndex }.getOrDefault(0)
            if (exoPos in windowQueue.indices) windowQueue[exoPos] = keep
            refreshUpcoming()
        }
    }

    /** 只往队列末尾加一首，不改变正在播放的歌。 */
    fun addToQueue(song: Song) {
        val q = _state.value.queue
        if (q.any { it.id == song.id }) return
        _state.update { it.copy(queue = q + song) }
    }

    /** 在一期听里遇到一首不在队列里的歌：补到队尾并立刻播 */
    fun appendAndPlay(song: Song) {
        val q = _state.value.queue.toMutableList()
        q.add(song)
        _state.update { it.copy(queue = q) }
        playAt(q.lastIndex)
    }

    // ------------------------------------------------------------------ 队列管理（播放列表页）

    /**
     * 点队列里的某一首：目标项在窗口里（可能已预缓冲）就 seek 过去，否则重新加载。
     * 点正在放的那首 = 无操作。
     */
    fun jumpTo(queueIdx: Int) {
        val s = _state.value
        if (queueIdx !in s.queue.indices) return
        if (queueIdx == s.index) return
        if (jumpWithinWindow(queueIdx)) return
        playAt(queueIdx)
    }

    /**
     * 从队列里摘掉一首。
     *
     * 摘的是**正在放的那首**：下一首直接顶上（走 [playAt] 重新铺窗口，这一次的
     * 重新 prepare 是删除动作自带的，不算卡顿）。摘别的：如果它在窗口里就顺手
     * 把 exo 里对应的那一项也摘掉，正在放的歌完全不受影响。
     */
    fun removeFromQueue(queueIdx: Int) {
        val s = _state.value
        val q = s.queue.toMutableList()
        if (queueIdx !in q.indices) return
        val wasCurrent = queueIdx == s.index
        q.removeAt(queueIdx)
        if (q.isEmpty()) {
            clearQueue()
            return
        }
        if (wasCurrent) {
            val next = queueIdx.coerceAtMost(q.lastIndex)
            _state.update { it.copy(queue = q, index = next, current = q[next], roam = false) }
            playAt(next)
            return
        }
        val pos = windowQueue.indexOf(queueIdx)
        if (pos > 0) {
            // 只有 exo 那边删成功才动映射，保持两边一致（同 onMediaItemTransition 的守卫）
            if (runCatching { exo.removeMediaItem(pos) }.isSuccess) windowQueue.removeAt(pos)
        }
        // 被删项之后的队列下标整体前移
        for (i in windowQueue.indices) if (windowQueue[i] > queueIdx) windowQueue[i] -= 1
        val newIndex = if (s.index > queueIdx) s.index - 1 else s.index
        if (windowQueue.isNotEmpty()) windowQueue[0] = newIndex
        _state.update { it.copy(queue = q, index = newIndex, current = q.getOrNull(newIndex) ?: it.current) }
        saveCurrentPlayback()
        fillWindow(playGen)
    }

    /**
     * 拖动排序。正在放的那首换了位置也照样放 —— 只重排队列、重对映射，
     * 当前项之后的部分按新顺序重接（[refreshUpcoming] 不碰正在放的那一项）。
     */
    fun moveInQueue(from: Int, to: Int) {
        val s = _state.value
        if (from == to) return
        if (from !in s.queue.indices || to !in s.queue.indices) return
        val q = s.queue.toMutableList()
        val moved = q.removeAt(from)
        q.add(to, moved)
        val newIndex = q.indexOfFirst { it.id == s.current?.id }.coerceAtLeast(0)
        _state.update { it.copy(queue = q, index = newIndex) }
        val exoPos = runCatching { exo.currentMediaItemIndex }.getOrDefault(0)
        if (exoPos in windowQueue.indices) windowQueue[exoPos] = newIndex
        refreshUpcoming()
        saveCurrentPlayback()
    }

    /**
     * 清空队列：停播、清 exo 列表和窗口、清掉落盘的恢复记录（否则下次启动
     * `restoreAndResume` 会把清掉的队列又灌回来）。播放历史保留。
     */
    fun clearQueue() {
        playGen += 1
        endedForId = null
        dj.cancel()
        windowJob?.cancel()
        loadJob?.cancel()
        windowQueue.clear()
        runCatching { exo.stop() }
        runCatching { exo.clearMediaItems() }
        _state.update {
            it.copy(
                queue = emptyList(),
                index = -1,
                current = null,
                playing = false,
                positionMs = 0L,
                durationMs = 0L,
                lyricLines = emptyList(),
                lyricIndex = -1,
                lyricsResolved = true,
                playable = null,
                error = null,
                roam = false,
            )
        }
        scope.launch(Dispatchers.IO) { runCatching { session.clearPlaybackState() } }
    }

    fun enterRoam() {
        val s = _state.value
        if (!s.roam) {
            savedQueue.clear()
            savedQueue.addAll(s.queue)
            savedIndex = s.index
        }
        val seed = s.current
        loadJob?.cancel()
        loadJob = scope.launch {
            val fm = withContext(Dispatchers.IO) {
                runCatching {
                    val radio = client.personalFm()
                    if (radio.isNotEmpty()) radio
                    else if (seed != null) client.simiSongs(seed.id)
                    else emptyList()
                }.getOrDefault(emptyList())
            }
            if (fm.isEmpty()) {
                _state.update { it.copy(error = "漫游没有歌") }
                return@launch
            }
            _state.update { it.copy(queue = fm, index = 0, roam = true, current = fm[0], error = null) }
            playAt(0)
        }
    }

    fun exitRoam() {
        if (!_state.value.roam) return
        val restore = savedQueue.toList()
        val idx = savedIndex
        _state.update { it.copy(queue = restore, index = idx, roam = false) }
        if (idx in restore.indices) playAt(idx) else {
            windowJob?.cancel()
            windowQueue.clear()
            runCatching { exo.clearMediaItems() }
            runCatching { exo.stop() }
            _state.update { it.copy(current = null, playing = false) }
        }
    }

    private fun loadMoreRoam() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val more = withContext(Dispatchers.IO) {
                runCatching {
                    val radio = client.personalFm()
                    if (radio.isNotEmpty()) radio
                    else {
                        val cur = _state.value.current
                        if (cur != null) client.simiSongs(cur.id) else emptyList()
                    }
                }.getOrDefault(emptyList())
            }
            if (more.isEmpty()) return@launch
            val q = _state.value.queue + more.filter { n -> _state.value.queue.none { it.id == n.id } }
            _state.update { it.copy(queue = q) }
            val next = _state.value.index + 1
            if (next in (0 until q.size)) playAt(next)
        }
    }

    /**
     * 保存播放进度。
     *
     * **序列化 + 落盘一律扔到 IO**：这里存的是整条队列，几百上千首就是几百 KB 的 JSON。
     * 之前这段在主线程上跑，而 tick 每 2 秒就会调一次 —— 恢复播放进度后队列非空，
     * 于是启动后每 2 秒一次几百 KB 的 JSON 组装砸在主线程上，表现出来就是
     * 「打开自动恢复了上次进度的歌 → 界面卡死 / 打不开」，卸载重装清掉 prefs 才好。
     *
     * [blocking] 只给退出时用：进程随时可能没，必须当场写完。
     */
    fun saveCurrentPlayback(
        posMs: Long = runCatching { exo.currentPosition }.getOrDefault(0L),
        blocking: Boolean = false,
    ) {
        val s = _state.value
        if (s.queue.isEmpty() || s.index !in s.queue.indices) return
        val record = PlaybackStateRecord(
            queue = s.queue,
            index = s.index,
            positionMs = posMs.coerceAtLeast(0L),
            roam = s.roam,
            playMode = s.playMode.name,
        )
        if (blocking) {
            runCatching { session.savePlaybackState(record) }
        } else {
            scope.launch(Dispatchers.IO) { runCatching { session.savePlaybackState(record) } }
        }
    }

    fun restoreAndResume() {
        val record = session.getPlaybackState() ?: return
        if (record.queue.isEmpty()) return
        val idx = record.index.coerceIn(0, record.queue.lastIndex)
        val mode = runCatching { PlayMode.valueOf(record.playMode) }.getOrDefault(PlayMode.SEQUENCE)
        val song = record.queue[idx]
        val targetPos = record.positionMs
        val safePos = if (targetPos > 0 && (song.durationMs <= 0 || targetPos < song.durationMs - 3000)) targetPos else 0L
        _state.update {
            it.copy(
                queue = record.queue,
                index = idx,
                current = song,
                roam = record.roam,
                playMode = mode,
                positionMs = safePos,
            )
        }
        playAt(idx, initialSeekMs = safePos, autoPlay = true)
    }

    fun release() {
        // 退出路径：进程随时会没，这里必须同步写完
        saveCurrentPlayback(blocking = true)
        tick?.cancel()
        loadJob?.cancel()
        windowJob?.cancel()
        windowQueue.clear()
        releaseExclusiveFocus()
        dj.release()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        exo.release()
    }
}
