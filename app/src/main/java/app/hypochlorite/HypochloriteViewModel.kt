package app.hypochlorite

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.DisplayMetrics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hypochlorite.netease.Album
import app.hypochlorite.netease.Artist
import app.hypochlorite.netease.Crypto
import app.hypochlorite.netease.ListenRoomKind
import app.hypochlorite.netease.listenCanDropQueueTo
import app.hypochlorite.netease.Playlist
import app.hypochlorite.netease.SearchPage
import app.hypochlorite.netease.Song
import app.hypochlorite.netease.parseListenInvite
import app.hypochlorite.player.AudioMatchPhase
import app.hypochlorite.player.AudioMatchState
import app.hypochlorite.player.ListenTogetherState
import app.hypochlorite.player.PlaybackService
import app.hypochlorite.player.PlayerClock
import app.hypochlorite.player.PlayerSnapshot
import app.hypochlorite.player.clock
import app.hypochlorite.player.sameUiAs
import app.hypochlorite.player.audioOutputs
import app.hypochlorite.player.hasUsbAudioHost
import app.hypochlorite.player.isUsb
import app.hypochlorite.player.pickUsbDevice
import app.hypochlorite.player.shortName
import app.hypochlorite.ui.CoverUrls
import app.hypochlorite.ui.coverScrim
import app.hypochlorite.ui.theme.Monet
import app.hypochlorite.ui.theme.MonetPalette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ThemeMode(val id: String, val label: String) {
    Dark("dark", "深色模式"),
    Light("light", "白天模式"),
    System("system", "跟随系统");

    companion object {
        fun fromId(id: String?): ThemeMode = when (id) {
            "light" -> Light
            "dark" -> Dark
            else -> System
        }
    }
}

enum class Space { Liked, Mine, Daily }

/** 综合搜索的页签。顺序就是顶栏从左到右。 */
enum class SearchTab { All, Songs, Playlists, Albums, Artists }

enum class SearchPhase { Idle, Loading, Ready, Empty, Error }

data class SearchHit<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val more: Boolean = false,
    val failed: Boolean = false,
)

/**
 * 一次综合搜索的四类结果。
 *
 * [resultQuery] 是这批结果对应的词。输入框里的字变了、新结果还没回来时，两者不一致，
 * 界面不要把旧列表当成这次搜索。
 */
data class SearchState(
    val phase: SearchPhase = SearchPhase.Idle,
    val resultQuery: String = "",
    val songs: SearchHit<Song> = SearchHit(),
    val playlists: SearchHit<Playlist> = SearchHit(),
    val albums: SearchHit<Album> = SearchHit(),
    val artists: SearchHit<Artist> = SearchHit(),
    val moreTab: SearchTab? = null,
    /** 最近一次「加载更多」失败的页签。别的页签不要跟着显示失败。 */
    val moreErrorTab: SearchTab? = null,
)

private data class SearchBundle(
    val songs: Result<SearchPage<Song>>,
    val playlists: Result<SearchPage<Playlist>>,
    val albums: Result<SearchPage<Album>>,
    val artists: Result<SearchPage<Artist>>,
)

/** 漫游过渡动画的正常时长约 1.2s，超过这个值一定是卡住了 */
private const val ROAM_TRANSITION_TIMEOUT_MS = 2500L

/** 点了允许但投影没回交（服务被系统掐了）时，最多再等这么久就放弃这一次识别。 */
private const val PROJECTION_GRANT_MS = 4_000L

/** 按下暂停到真正开录之间的留白：让 ExoPlayer 管道里剩下的声音流完，别录进尾巴。 */
private const val CAPTURE_SETTLE_MS = 200L

/** 命中后等播放器挂上那首歌再弹详情页的上限；超时就别弹了（[openNowPlaying] 空队列不认）。 */
private const val NOW_PLAYING_WAIT_MS = 2_000L

/** 综合搜索一次拉一页，滑到底再要下一页。 */
private const val SEARCH_PAGE = 20

/** 再往下翻也不超过这个数，避免总数缺失时一直请求。 */
private const val SEARCH_CAP = 200

/** 输入停一下再打四次 cloudsearch。 */
private const val SEARCH_DEBOUNCE_MS = 280L

/**
 * USB 音频设备的热插拔轮询间隔。
 *
 * 系统没有「USB 音频设备变化」的广播可听，只能比对设备列表。2s 的延迟对
 * 「插上耳机再点播放」这种操作来说够快了，同时轮询本身几乎不花钱
 * （一次 `getDevices` 是本地调用）。
 */
private const val USB_POLL_MS = 2000L

/**
 * 一次主题扩散的请求。id 递增，背景层据此判断「换了一次主题」。
 * 只带旧底色，新底色由 palette 提供，避免两份数据不一致。
 */
data class ThemeReveal(val id: Long, val from: Color, val direction: Int = 1)

/**
 * 波纹半径的**基准**：从起点出发、刚好够把整块可见矩形扫干净的距离 ——
 * 也就是**起点到最远那个角**的距离。
 *
 * 这里试过两个错误口径，都记一下：
 * - 只按对角线（`hypot(w, h)`）：**太大了**。对角线是「角到角」，而基准是「起点到角」；
 *   起点不在角上时前者恒大于后者。封面在屏幕 40% 高处时对角线比真实需求大 **71%**，
 *   在正中心更大一倍。基准虚高 → 就算外扩系数很小，圆也已经冲出屏幕了，
 *   于是「屏幕内没有波纹边界」这个目标在起手几十毫秒就意外达成 —— 扩散看不见了。
 * - 只按节点尺寸：分屏 / 自由窗口下窗口比屏幕小，要靠屏幕尺寸兜底。
 *
 * 所以基准 = [fromCorners] 与 [fromMetrics] 取大者，再乘 [REVEAL_RADIUS_MARGIN]
 * （只是防止浮点边界差一点，不用来制造外扩感 —— 外扩交给 [REVEAL_OVERSCAN]）。
 */
internal fun revealRadiusPx(activity: Activity?, pivot: Offset, size: Size): Float {
    val base = cornerReach(pivot, size.width, size.height)
    if (activity == null) return base * REVEAL_RADIUS_MARGIN

    val bounds = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            DisplayMetrics().also { activity.windowManager.defaultDisplay.getRealMetrics(it) }
                .let { Rect(0, 0, it.widthPixels, it.heightPixels) }
        }
    }.getOrNull() ?: return base * REVEAL_RADIUS_MARGIN

    val w = bounds.width().toFloat()
    val h = bounds.height().toFloat()
    if (w <= 0f || h <= 0f) return base * REVEAL_RADIUS_MARGIN

    // 窗口取较大、屏幕取较大，同时兼容「分屏（窗口小）」和「窗口大于屏幕」两种情况。
    // 两种口径各自从自己的原点算一遍再取大：窗口与屏幕的原点不一定重合。
    val coverW = max(w, size.width)
    val coverH = max(h, size.height)
    val fromMetrics = max(
        cornerReach(pivot, coverW, coverH),
        cornerReach(Offset(pivot.x - w / 2f + coverW / 2f, pivot.y), coverW, coverH),
    )

    return max(base, fromMetrics) * REVEAL_RADIUS_MARGIN
}

/** 起点到矩形四个角的距离，取最远的那个 */
private fun cornerReach(pivot: Offset, w: Float, h: Float): Float = hypotOf(
    max(pivot.x, w - pivot.x),
    max(pivot.y, h - pivot.y),
)

/**
 * 基准上的安全系数。基准本身已经是「刚好覆盖」，这里只留一点浮点余量，
 * 起点无论落在哪里都保证铺得满。
 */
private const val REVEAL_RADIUS_MARGIN = 1.02f

private fun hypotOf(x: Float, y: Float): Float = sqrt(x * x + y * y)

sealed class Route {
    data object Home : Route()
    data class PlaylistSongs(val playlist: Playlist) : Route()
    data class ArtistDetail(val artistName: String) : Route()
    data class AlbumDetail(val albumName: String, val albumId: String? = null) : Route()
    data object NowPlaying : Route()
    data object Roam : Route()
    data object Login : Route()
    data object Config : Route()
    data object ListenTogether : Route()
    data object AudioMatch : Route()
}

/**
 * 「正在播放」这种虚拟歌单用 0 当 id —— 网易云的歌单 id 都是九位数往上，
 * 不会撞车；[openPlaylist] 只按 id 拉接口，别让它被点开。
 */
private const val NOW_PLAYING_QUEUE_ID = "0"

/** 底栏长按跳不过去时的兜底容器：把当前播放队列当列表看。 */
private fun nowPlayingQueuePlaylist(size: Int) = Playlist(
    id = NOW_PLAYING_QUEUE_ID,
    name = "正在播放",
    cover = "",
    trackCount = size,
    specialType = -1,
)

/**
 * UI 层的音频输出开关快照。
 *
 * 和 [app.hypochlorite.player.AudioOutConfig] 字段一致但**故意分开**：配置是「正在生效的」，
 * 这一份是「用户看到的」，两者在边界情形下会短暂不一致 —— 比如用户打开独占但没插
 * USB 设备，配置里被拒了（保持关闭），界面也要跟着回到关闭并给出原因。
 */
data class AudioOut(
    val usbExclusive: Boolean = false,
    val exclusiveFocus: Boolean = false,
    val keepAwake: Boolean = true,
    val directVolume: Boolean = false,
    val gainPercent: Int = 93,
)

data class HomeState(
    val route: Route = Route.Home,
    val space: Space = Space.Liked,
    val nickname: String = "未登录",
    val loggedIn: Boolean = false,
    /** 当前登录账号的 uid。一起听要靠它区分「我」和别的成员。 */
    val profileUserId: String? = null,
    val liked: List<Playlist> = emptyList(),
    val mine: List<Playlist> = emptyList(),
    val dailyPlaylists: List<Playlist> = emptyList(),
    val dailySongs: List<Song> = emptyList(),
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val search: SearchState = SearchState(),
    val playlistSongs: List<Song> = emptyList(),
    val artistSongs: List<Song> = emptyList(),
    val artistCover: String? = null,
    val artistLoading: Boolean = false,
    val artistHasMore: Boolean = false,
    val artistLoadingMore: Boolean = false,
    val artistTotal: Int = 0,
    val album: Album? = null,
    val albumSongs: List<Song> = emptyList(),
    val albumLoading: Boolean = false,
    val nowPlayingOpen: Boolean = false,
    val nowPlayingRoam: Boolean = false,
    /** 播放列表（队列管理）面板。盖在详情页之上，见 Root 的渲染顺序 */
    val queueOpen: Boolean = false,
    val loading: Boolean = false,
    val msg: String = "",
    val roamAnimating: Boolean = false,
    val roamTransitionDir: Int = 0,
    val qr: ImageBitmap? = null,
    val loginMsg: String = "",
    val cookieInput: String = "",
    // --- 手机号登录 ---
    /** 0 = 扫码，1 = 手机号 */
    val loginTab: Int = 0,
    val phone: String = "",
    val phoneCountry: String = "86",
    val phonePassword: String = "",
    val phoneCaptcha: String = "",
    /** true = 验证码登录，false = 密码登录 */
    val phoneUseCaptcha: Boolean = true,
    val phoneSending: Boolean = false,
    val phoneLoggingIn: Boolean = false,
    /** 短信验证码倒计时剩余秒数，0 = 可再次发送 */
    val phoneCountdown: Int = 0,
    /** 云盾人机验证页地址，非空说明要用户过一次验证 */
    val loginVerifyUrl: String = "",
    /**
     * 登录冷却剩余秒数，> 0 时登录/发码按钮点不动。
     *
     * 风控是按「同一设备短时间内连续失败登录」打分的，失败后立刻重试等于自己喂分。
     * 冷却长度按失败原因给：人机验证最久，风控次之，单纯填错最短。
     */
    val loginCooldown: Int = 0,
    /** 是否正在显示内嵌的云盾验证页 */
    val showVerify: Boolean = false,
    val player: PlayerSnapshot = PlayerSnapshot(),
    val likedSongIds: Set<String> = emptySet(),
    val themeMode: ThemeMode = ThemeMode.System,
    val palette: MonetPalette = MonetPalette.Mono,
    val reveal: ThemeReveal? = null,
    val songTransitionDir: Int = 1,
    val songTransitionSeq: Long = 0L,
    /**
     * 这次换歌是不是用户自己点的（点歌 / 切上一首下一首）。
     *
     * 用来区分「主动换」与「静默换」：主动换时用户已经知道自己做了什么，
     * 底栏不必再演一遍动画；只有自动续播、一起听同步、漫游这类用户没动手的换歌，
     * 底栏才需要动起来告诉用户「歌换了」。
     */
    val songTransitionManual: Boolean = false,
    val monetEnabled: Boolean = true,
    val revealEnabled: Boolean = true,
    /**
     * 当前这首歌用来铺虚化背景的封面 URL。
     *
     * 歌曲对象上的 [Song.cover] 偶尔是空的（搜索缺字段、旧队列），这里会再去拉一次详情；
     * UI 的模糊底和图标都读这一份，避免各写一套回退。
     */
    val backdropCoverUrl: String? = null,
    val listen: ListenTogetherState = ListenTogetherState(),
    val listenInput: String = "",
    /** 听歌识曲引擎状态。见 [app.hypochlorite.player.AudioMatch]。 */
    val audioMatch: AudioMatchState = AudioMatchState(),
    /** 系统音频投影是否已授权可用。低版本（无 AudioPlaybackCapture）恒 false。 */
    val audioCaptureReady: Boolean = false,
    /**
     * 指纹层是不是真在跑 wasm 提取器。false = 装机包里没有那份私有 wasm（或它读不出来），
     * 此时走假指纹兜底：链路照样跑完，但只会「没听出来」而且快得多 —— 认不出歌时先看这一位。
     */
    val audioMatchFingerprintReal: Boolean = false,
    /** 底栏长按跳列表的目标行。null = 没有待处理的高亮。 */
    val pendingListJump: Int? = null,
    /** 同一行连续长按也要重新滚一次 —— 索引相同的话 State 去重会让 effect 不重跑。 */
    val listJumpSeq: Int = 0,
    // --- HiFi 音频输出 ---
    val audioOut: AudioOut = AudioOut(),
    /** 检测到的 USB 音频输出设备数量（0 = 没插） */
    val usbDevices: Int = 0,
    /**
     * 这台设备硬件上有没有 USB 音频宿主。
     *
     * 和 [usbDevices] 是两回事：「支持但没插」和「根本不支持」对用户的含义完全不同，
     * 文案要分开写 —— 后者得直接说别等了，不能给一句含糊的「没检测到设备」。
     */
    val usbHostSupported: Boolean = true,
    /** 当前选中的 USB 设备短名，给界面显示用 */
    val usbDeviceName: String? = null,
    /** 输出有没有真的钉在 USB 设备上。开关开着但这里是 false = 没钉上，声音还在默认出口 */
    val usbExclusiveActive: Boolean = false,
    /** 音频输出区的即时反馈文案 */
    val hifiMsg: String = "",
    // --- 采样率匹配（避免系统重采样）---
    /** 当前这条流的采样率（Hz），null = 接口没报 */
    val sourceSampleRate: Int? = null,
    /** 设备认定的原生采样率，null = 设备没报能力 */
    val nativeSampleRate: Int? = null,
    /** 设备明确支持的采样率列表，给界面展示 */
    val supportedRates: List<Int> = emptyList(),
    /** 会不会被系统重采样。只有拿得到双方数据时才为 true —— 拿不到一律不报警 */
    val willResample: Boolean = false,
    /** 正在出声的那只设备名（可能不是 USB，比如没开独占时的扬声器） */
    val activeDeviceName: String? = null,
)

class HypochloriteViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<HypochloriteApplication>()

    private val _ui = MutableStateFlow(HomeState())
    val ui: StateFlow<HomeState> = _ui

    /** 走针 / 歌词下标。进度条和底栏副标题读这一份，别的页面不要订。 */
    private val _clock = MutableStateFlow(PlayerClock())
    val clock: StateFlow<PlayerClock> = _clock

    private var qrPoll: Job? = null
    private var qrKey: String? = null
    private var searchJob: Job? = null
    private var searchMoreJob: Job? = null
    private var searchGen = 0
    private var smsCountdown: Job? = null
    private var loginCooldownJob: Job? = null
    private val stack = ArrayDeque<Route>()
    private var playbackServiceStarted = false
    private var currentArtistId: String? = null
    private var themeJob: Job? = null
    private var revealSeq = 0L
    private var roamWatchdog: Job? = null

    /** 最后一次触发取色的歌曲，用来避免重复计算（切歌只在真正换歌时跑） */
    private var themedSongId: String? = null
    private var manualDirection: Int = 0

    /**
     * seed 缓存：同一张封面只算一次，重复播放 / 来回切歌都是瞬时的。
     *
     * **必须声明在 [init] 之前。** Kotlin 按声明顺序初始化属性，而 `init` 里那条
     * `player.state.collect` 是跑在 `Main.immediate` 上的 —— `launch` 会同步执行、
     * `StateFlow.collect` 立刻吐出当前值，于是 `prefetchNextSeed()` 在构造过程中就被调到了。
     * 它之前声明在 `init` 之后（第 269 行），那一刻还是 null，`containsKey` 直接 NPE。
     * 恢复播放后队列非空才会走到这一行，所以症状恰好是「保存了歌曲 → 重开就闪退」。
     */
    private val seedCache = LinkedHashMap<String, Int>()

    init {
        // restoreTheme 必须在启动 player.state 收集之前：viewModelScope 是 Main.immediate，
        // launch 会同步执行，而 StateFlow 的 collect 会立刻吐出当前值并触发一次取色，
        // 先跑 restoreTheme 才不会把刚取到的配色又覆盖回纯黑。
        restoreTheme()
        _ui.update { it.copy(likedSongIds = app.session.getLikedSongIds()) }
        _ui.update { it.copy(audioMatchFingerprintReal = app.fingerprintIsReal) }
        refresh()
        viewModelScope.launch {
            var wasPlaying = false
            var lastTrackId: String? = null
            var lastTrackIndex = -1
            app.player.state.collect { snap ->
                val nextClock = snap.clock()
                if (_clock.value != nextClock) _clock.value = nextClock

                val prevPlayer = _ui.value.player
                val uiChanged = !prevPlayer.sameUiAs(snap)
                if (uiChanged) {
                    _ui.update { it.copy(player = snap) }
                }

                if (snap.playing && !wasPlaying) {
                    startPlaybackService()
                }
                wasPlaying = snap.playing
                val curSong = snap.current
                if (curSong != null && curSong.id != lastTrackId) {
                    // manualDirection 会在下面被消费清零，先记下「这次是用户自己点的」
                    val manual = manualDirection != 0
                    val dir = when {
                        manualDirection != 0 -> manualDirection.also { manualDirection = 0 }
                        snap.index > lastTrackIndex || (snap.index == 0 && lastTrackIndex > 0) || snap.roam -> 1
                        snap.index < lastTrackIndex -> -1
                        else -> 1
                    }
                    lastTrackId = curSong.id
                    lastTrackIndex = snap.index
                    onSongChanged(curSong, dir, manual)
                    prefetchNextSeed()
                    refreshResampleVerdict()
                } else if (uiChanged) {
                    if (prevPlayer.index != snap.index || prevPlayer.queue !== snap.queue) {
                        prefetchNextSeed()
                    }
                    if (prevPlayer.playable != snap.playable) {
                        refreshResampleVerdict()
                    }
                }
            }
        }
        viewModelScope.launch {
            app.player.restoreAndResume()
        }
        // 一起听：独立收集，不与播放状态那条流纠缠（房间状态变化很频繁，混在一起会让
        // 每次心跳都触发一次 onSongChanged / 取色计算的检查）
        viewModelScope.launch {
            app.listen.state.collect { lt ->
                _ui.update { it.copy(listen = lt) }
            }
        }
        // 听歌识曲：和一起听一样独立收集，状态变化只驱动识别页，不进播放/取色那条链
        viewModelScope.launch {
            var seenHitSeq = app.audioMatch.state.value.hitSeq
            app.audioMatch.state.collect { am ->
                // 采集窗口一关就收回自家播放：暂停只为「那 3 秒别录进自己的声音」而存在，
                // 没理由让用户音乐在随后几秒算指纹 + 走网络期间继续哑着。
                // cancel() 会把阶段打回 Idle，所以「中止」和「返回」也都走这一条。
                if (pausedOwnForCapture && am.phase != AudioMatchPhase.Capturing) releaseCapturePause()
                _ui.update { it.copy(audioMatch = am) }
                // 命中就接管播放并弹详情页 —— 识别页不再自己演命中，结果直接落在详情页上。
                // 只认比进来时更新的 seq：引擎挂在 Application 上，重进页面不该把上一次命中再演一遍。
                if (am.hitSeq > seenHitSeq) {
                    seenHitSeq = am.hitSeq
                    onAudioMatchHit()
                }
            }
        }
        // 系统音频投影就绪状态：识别页据此决定「点中心要不要先走一遍授权」
        viewModelScope.launch {
            app.systemAudio.ready.collect { ready ->
                _ui.update { it.copy(audioCaptureReady = ready) }
            }
        }
        // 续房放在最后：它会改播放器队列，要等 restoreAndResume 把本地队列铺好之后，
        // 否则会被本地恢复的队列盖掉。
        viewModelScope.launch {
            delay(600)
            app.listen.restore()
        }
        initAudioOut()
        watchUsbDevices()
    }

    // ---------------------------------------------------------------- HiFi 音频输出

    /**
     * 盯着 USB 音频设备的热插拔。
     *
     * 系统没有「USB 音频设备变化」的广播，只能定期比对设备列表。这个循环**只在两个
     * 前提下跑**：USB 独占开着，或用户正停在设置页 —— 其它时候它完全不执行任何工作，
     * 不会给播放路径增加开销。轮询间隔 2s，插拔的反应延迟最多这么多，足够了。
     */
    private fun watchUsbDevices() {
        viewModelScope.launch {
            while (true) {
                delay(USB_POLL_MS)
                val st = _ui.value
                val shouldWatch = st.audioOut.usbExclusive || st.route == Route.Config
                if (!shouldWatch) continue
                if (app.player.refreshUsbDevices()) refreshAudioOutDevices()
            }
        }
    }

    /** 冷启动把音频输出开关和 USB 设备状态铺到界面上 */
    private fun initAudioOut() = runCatching {
        val cfg = app.player.audioOut()
        _ui.update {
            it.copy(
                audioOut = AudioOut(
                    usbExclusive = cfg.usbExclusive,
                    exclusiveFocus = cfg.exclusiveFocus,
                    keepAwake = cfg.keepAwake,
                    directVolume = cfg.directVolume,
                    gainPercent = cfg.gainPercent,
                ),
            )
        }
        refreshAudioOutDevices()
    }

    // ---------------------------------------------------------------- 一起听

    fun openListen() = push(Route.ListenTogether)

    // ---------------------------------------------------------------- 听歌识曲

    fun openAudioMatch() = push(Route.AudioMatch)

    /** 进识别页时若停在上次结果上，收回空闲，避免一进页面就显示旧命中。 */
    fun enterAudioMatch() {
        if (_ui.value.audioMatch.phase != AudioMatchPhase.Idle) app.audioMatch.resetToIdle()
    }

    /** 授权给了但投影迟迟不回交时占的等待协程。攒着它，免得连点起两组等待。 */
    private var awaitingProjection: Job? = null

    /** 这一次识别，暂停是不是我们按下的。只有 true 时收尾才该 resume。 */
    private var pausedOwnForCapture = false

    /**
     * 点中心、授权齐了之后的正式一遍：先停掉自己的播放，再开链。
     *
     * 必须先停 —— MediaProjectionCaptureSource 只按 usage 匹配、从不调 removeMatchingUids，
     * 我们自己的 ExoPlayer 也在采集集合里（见该类的注释）。不停，用户点一下识曲录到的就是自己。
     */
    fun startAudioMatch() {
        if (app.audioMatch.running || awaitingProjection?.isActive == true) return
        awaitingProjection = viewModelScope.launch {
            // 授权框是异步的：用户可能在等它回来的路上就退出了页面，那这条链路不该再跑
            if (_ui.value.route != Route.AudioMatch) return@launch
            if (!app.systemAudio.ready.value) {
                // 录屏框刚点完允许，投影是前台服务异步回交的（Android 14 要求用投影前先有
                // mediaProjection 类型前台服务）。等一小会儿；真不来就别把人静默卡在原地。
                val granted = withTimeoutOrNull(PROJECTION_GRANT_MS) {
                    app.systemAudio.ready.first { it }
                    true
                } == true
                if (!granted) {
                    app.audioMatch.notify("录屏授权没生效，再点一次")
                    return@launch
                }
            }
            pauseOwnForCapture()
            delay(CAPTURE_SETTLE_MS)
            app.audioMatch.start()
        }
    }

    fun cancelAudioMatch() = app.audioMatch.cancel()

    fun clearAudioMatchToast() = app.audioMatch.clearToast()

    /** 识别页的即时提示（授权被拒之类），走 toast 不占阶段状态。 */
    fun audioMatchNote(message: String) = app.audioMatch.notify(message)

    /**
     * 识别页动画的电平源：抓到的是**系统音频**峰值，不是自家播放器。
     *
     * 不能再用 audioLevel()：HypochloritePlayer 那边 `if (!exo.isPlaying) return 0f`，
     * 而识别期间我们自己正是暂停的 —— 最需要动画的三秒里那条输入恒为 0。
     */
    fun captureLevel(): Float = app.systemAudio.captureLevel

    /**
     * 用户在系统录屏授权框点了允许 → 交给控制器去起前台服务并创建投影。
     *
     * 紧接着就把识别续上：授权是「一次会话一签」（见 [MediaProjectionCaptureSource]），
     * 所以点一下 = 签一次 + 跑一次识别，不该再要用户点第二下。[startAudioMatch] 自己会等
     * 投影回交（[PROJECTION_GRANT_MS] 内）。
     */
    fun submitAudioProjectionResult(resultCode: Int, data: android.content.Intent) {
        app.systemAudio.beginCapture(resultCode, data)
        startAudioMatch()
    }

    private fun pauseOwnForCapture() {
        // 只在**确实正在播放**时才下手。resume() 只守 playing/current，不守「用户本来就自己
        // 按了暂停」—— 无条件 pause/resume 会把用户手动停掉的那首擅自放回去。
        if (!_ui.value.player.playing) return
        app.player.pause()
        pausedOwnForCapture = true
    }

    /** 收回 [pauseOwnForCapture] 按下的暂停。幂等：不是我们按的就什么都不做。 */
    private fun releaseCapturePause() {
        if (!pausedOwnForCapture) return
        pausedOwnForCapture = false
        app.player.resume()
    }

    /**
     * 离开识别页：收掉投影与前台服务。
     *
     * 代价是每次进页要重弹一次录屏授权框；换回来的是那条「正在录屏」的通知不会跟在用户
     * 后面一路挂着，也省得去赌 Android 14 的投影复用规则。刻意取舍，不是忘了缓存。
     */
    private fun releaseAudioProjection() = app.systemAudio.endCapture()

    /**
     * 命中：接管播放，然后把详情页弹出来。
     *
     * 引擎在 Hit 时不自己播（见 [app.hypochlorite.player.AudioMatchState]），是这里替用户按下
     * 「播放这首」：抢音频焦点让守规矩的后台 App 自行停，再等播放器真的挂上这首才开覆盖层 ——
     * [openNowPlaying] 在队列还空着时会直接返回。自家播放早在采集窗口结束时回来了
     * （见收集 audioMatch.state 的那段），所以这一步就是一次普通的用户切歌。
     */
    fun onAudioMatchHit() {
        val hit = _ui.value.audioMatch.hit ?: return
        playSong(hit)
        // toast 由引擎发，collector 再收回 _ui —— 别在这里直接写 _ui.audioMatch，会被下一次 collect 覆盖
        app.audioMatch.notify("已接管播放")
        viewModelScope.launch {
            withTimeoutOrNull(NOW_PLAYING_WAIT_MS) { app.player.state.first { it.current?.id == hit.id } }
            openNowPlaying()
        }
    }

    fun setListenInput(s: String) = _ui.update { it.copy(listenInput = s) }
    fun listenCreateRoom(kind: ListenRoomKind = ListenRoomKind.Duo) = app.listen.createRoom(kind)

    fun listenJoinRoom(id: String = _ui.value.listenInput) {
        app.listen.join(id)
    }

    /** 从分享链接进来。认不出邀请就当普通打开，不打断正在看的页面。 */
    fun offerListenInvite(raw: String?) {
        if (raw.isNullOrBlank() || parseListenInvite(raw) == null) return
        openListen()
        app.listen.join(raw)
    }

    fun listenLeaveRoom() {
        app.listen.leaveRoom()
    }

    /**
     * 房间已经没了，才离开房间页。
     * 退出请求还在飞、或者失败了，就留在这页，错误文案才看得见。
     */
    fun dismissListenScreen() {
        if (_ui.value.route != Route.ListenTogether) return
        val prev = stack.removeLastOrNull() ?: Route.Home
        _ui.update { it.copy(route = prev, roamAnimating = false) }
    }

    fun listenNote(message: String) = app.listen.note(message)

    fun listenNotify(message: String) = app.listen.notify(message)

    /** 把这首歌加进一起听房间队列（不切歌）。只在已进房时由列表点击触发。 */
    fun listenPushSong(song: Song) = app.listen.addSongToRoomQueue(song)

    fun clearListenToast() = app.listen.clearToast()

    /**
     * 写剪贴板 + 给一条 toast 反馈。
     *
     * 反馈必须走 [app.listen] 的 toast 通道 —— 房间页自己没法飘提示，
     * 而且这样「复制成功」和其它房间操作反馈的视觉是一套的。
     */
    fun copyText(text: String, message: String) {
        if (text.isEmpty()) return
        runCatching {
            val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("hypochlorite", text))
        }
        app.listen.notify(message)
    }

    // ---------------------------------------------------------------- 主题

    fun isLightActive(mode: ThemeMode = _ui.value.themeMode): Boolean = when (mode) {
        ThemeMode.Light -> true
        ThemeMode.Dark -> false
        ThemeMode.System -> {
            val uiMode = getApplication<Application>().resources.configuration.uiMode
            (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * 冷启动时用上次的 seed 直接还原，避免先闪一下纯黑再扩散。
     * 整体包 runCatching：这段跑在 ViewModel 构造里（即启动关键路径），
     * 任何异常都不该让 app 起不来 —— 大不了退回纯黑白。
     */
    private fun restoreTheme() = runCatching {
        val themeMode = ThemeMode.fromId(app.session.getThemeMode())
        val isLight = isLightActive(themeMode)
        val defaultMono = if (isLight) MonetPalette.MonoLight else MonetPalette.MonoDark
        val saved = app.session.getMonetSeed()
        val monetOn = app.session.isMonetEnabled()
        _ui.update {
            it.copy(
                themeMode = themeMode,
                palette = when {
                    !monetOn -> defaultMono
                    it.palette.seed != null -> Monet.derive(it.palette.seed, isLight)
                    saved != null -> runCatching { Monet.derive(saved, isLight) }.getOrDefault(defaultMono)
                    else -> defaultMono
                },
                monetEnabled = monetOn,
                revealEnabled = app.session.isRevealEnabled(),
                reveal = null,
            )
        }
    }.onFailure {
        // 兜底也要守住白天模式：直接写 MonoDark 会让浅色用户在启动瞬间闪一帧黑。
        val fallback = runCatching {
            if (isLightActive(ThemeMode.fromId(app.session.getThemeMode()))) MonetPalette.MonoLight
            else MonetPalette.MonoDark
        }.getOrDefault(MonetPalette.MonoDark)
        _ui.update { it.copy(palette = fallback, reveal = null) }
    }

    private fun onSongChanged(song: Song?, dir: Int = 1, manual: Boolean = false) {
        if (song == null || song.id == themedSongId) return
        themedSongId = song.id

        // 统一在切歌发生时立即且仅递增一次 songTransitionSeq，避免取色完成时造成二次触发动画
        val nextSeq = _ui.value.songTransitionSeq + 1
        val knownCover = song.cover.takeIf { it.isNotEmpty() }
        _ui.update {
            it.copy(
                songTransitionDir = dir,
                songTransitionSeq = nextSeq,
                songTransitionManual = manual,
                backdropCoverUrl = knownCover,
            )
        }

        themeJob?.cancel()
        themeJob = viewModelScope.launch {
            val cover = knownCover ?: fetchCover(song.id)
            if (!cover.isNullOrEmpty() && _ui.value.player.current?.id == song.id) {
                _ui.update { it.copy(backdropCoverUrl = cover) }
            }
            if (!_ui.value.monetEnabled) return@launch
            if (cover.isNullOrEmpty()) return@launch
            val seed = resolveSeed(cover) ?: return@launch
            val previous = _ui.value.palette
            val isLight = isLightActive()
            val next = Monet.derive(seed, isLight = isLight)
            app.session.saveMonetSeed(seed)

            revealSeq += 1
            // 色调跟上一首几乎一样就不值得再扩散一次，静默换色
            val tooSimilar = previous.seed != null && Monet.looksSame(previous.seed, seed) && previous.isLight == next.isLight
            if (tooSimilar || !_ui.value.revealEnabled) {
                _ui.update { it.copy(palette = next) }
                return@launch
            }

            val from = previous.coverScrim(previous.seed != null)
            val reveal = ThemeReveal(revealSeq, from, dir)
            _ui.update { it.copy(palette = next, reveal = reveal) }
        }
    }

    private suspend fun fetchCover(songId: String): String? = withContext(Dispatchers.IO) {
        runCatching { app.client.getSongDetail(songId)?.cover }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * 提前给队列里下一首封面取色。不这么做的话，取色要等封面下载 + 解码完才开始，
     * 表现出来就是「歌都放了半秒了波纹才冒出来」。
     */
    private fun prefetchNextSeed() {
        val snap = _ui.value.player
        val next = snap.queue.getOrNull(snap.index + 1) ?: return
        val cover = next.cover
        if (cover.isNullOrEmpty()) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            ctx.imageLoader.enqueue(
                ImageRequest.Builder(ctx)
                    .data(CoverUrls.sized(cover))
                    .size(256)
                    .build(),
            )
        }
        if (seedCache.containsKey(cover)) return
        // 独立 Job：不能挂到 themeJob 上，否则一切歌就被取消了
        viewModelScope.launch { resolveSeed(cover) }
    }

    private suspend fun resolveSeed(url: String): Int? {
        seedCache[url]?.let { return it }
        val seed = loadSeed(url) ?: return null
        if (seedCache.size >= 48) seedCache.clear()
        seedCache[url] = seed
        return seed
    }

    private suspend fun loadSeed(url: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getApplication<Application>()
            val request = ImageRequest.Builder(ctx)
                .data(url)
                .allowHardware(false)
                .size(112)
                .build()
            val drawable = (ctx.imageLoader.execute(request) as? SuccessResult)?.drawable
            val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
            Monet.extractSeed(bitmap)
        }.getOrNull()
    }

    /**
     * 收尾某一次扩散动画。[id] 必须带上：切歌时被取消的旧动画也会走到这里，
     * 如果不比对 id 就会把刚开始的新动画一起清掉（表现为颜色闪一下又变回去）。
     */
    fun finishReveal(id: Long) {
        if (_ui.value.reveal?.id == id) {
            _ui.update { it.copy(reveal = null) }
        }
    }

    fun setMonetEnabled(on: Boolean) {
        app.session.saveMonetEnabled(on)
        val isLight = isLightActive()
        val defaultMono = if (isLight) MonetPalette.MonoLight else MonetPalette.MonoDark
        if (on) {
            _ui.update { it.copy(monetEnabled = true) }
            themedSongId = null
            onSongChanged(_ui.value.player.current)
        } else {
            themeJob?.cancel()
            themeJob = null
            themedSongId = null
            val previous = _ui.value.palette
            val animate = _ui.value.revealEnabled && previous.seed != null
            revealSeq += 1
            val from = previous.coverScrim(!_ui.value.backdropCoverUrl.isNullOrEmpty() && previous.seed != null)
            _ui.update {
                it.copy(
                    monetEnabled = false,
                    palette = defaultMono,
                    reveal = if (animate) ThemeReveal(revealSeq, from, 1) else null,
                )
            }
        }
    }

    fun setRevealEnabled(on: Boolean) {
        app.session.saveRevealEnabled(on)
        _ui.update { it.copy(revealEnabled = on) }
    }

    fun setThemeMode(mode: ThemeMode) {
        app.session.saveThemeMode(mode.id)
        val isLight = isLightActive(mode)
        val defaultMono = if (isLight) MonetPalette.MonoLight else MonetPalette.MonoDark
        val previous = _ui.value.palette
        val currentSeed = previous.seed ?: app.session.getMonetSeed()
        val nextPalette = if (_ui.value.monetEnabled && currentSeed != null) {
            runCatching { Monet.derive(currentSeed, isLight = isLight) }.getOrDefault(defaultMono)
        } else {
            defaultMono
        }
        // 主题模式切换**总是**走一次过渡：深浅互换是整屏级别的变化，瞬切太生硬。
        // 「切歌扩散动画」那个开关管的是切歌，不该顺手把模式切换也一起关掉。
        val animate = previous.background != nextPalette.background
        if (animate) {
            revealSeq += 1
            val coverVisible = _ui.value.monetEnabled && !_ui.value.backdropCoverUrl.isNullOrEmpty()
            _ui.update {
                it.copy(
                    themeMode = mode,
                    palette = nextPalette,
                    reveal = ThemeReveal(revealSeq, previous.coverScrim(coverVisible), 1),
                )
            }
        } else {
            _ui.update {
                it.copy(
                    themeMode = mode,
                    palette = nextPalette,
                )
            }
        }
    }

    fun onConfigurationChanged() {
        if (_ui.value.themeMode == ThemeMode.System) {
            setThemeMode(ThemeMode.System)
        }
    }

    private fun push(route: Route) {
        val cur = _ui.value.route
        if (cur != route) stack.addLast(cur)
        _ui.update { it.copy(route = route) }
    }

    private fun startPlaybackService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, PlaybackService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(ctx, intent)
            else ctx.startService(intent)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val cachedLiked = app.session.getLikedSongIds()
            _ui.update {
                it.copy(
                    loggedIn = app.session.isLoggedIn(),
                    nickname = app.session.getProfile()?.nickname ?: if (app.session.isLoggedIn()) "已登录" else "未登录",
                    profileUserId = app.session.getProfile()?.userId,
                    likedSongIds = if (it.likedSongIds.isEmpty()) cachedLiked else it.likedSongIds,
                )
            }
            if (!app.session.isLoggedIn()) return@launch
            _ui.update { it.copy(loading = true) }
            val uid = app.session.getProfile()?.userId ?: withContext(Dispatchers.IO) {
                runCatching { app.client.refreshAccount()?.userId }.getOrNull()
            }
            val playlists = if (uid != null) withContext(Dispatchers.IO) {
                runCatching { app.client.userPlaylists(uid) }.getOrDefault(emptyList())
            } else emptyList()
            val likedMusic = playlists.firstOrNull { it.specialType == 5 } ?: playlists.firstOrNull()
            val mine = playlists.filter { it.creatorId == uid && it.specialType != 5 }
            val collected = playlists.filter { it.creatorId != uid }
            val liked = buildList {
                if (likedMusic != null) add(likedMusic)
                addAll(collected.filter { it.id != likedMusic?.id })
            }
            val dailyS = withContext(Dispatchers.IO) { runCatching { app.client.dailySongs() }.getOrDefault(emptyList()) }
            val dailyP = withContext(Dispatchers.IO) { runCatching { app.client.dailyPlaylists() }.getOrDefault(emptyList()) }
            val likedIds = if (uid != null) withContext(Dispatchers.IO) {
                runCatching { app.client.likeList(uid) }.getOrDefault(emptyList()).toSet()
            } else emptySet()
            val playlistTrackIds = if (likedMusic != null) withContext(Dispatchers.IO) {
                runCatching {
                    val detail = app.client.playlistDetail(likedMusic.id)
                    detail.second.map { it.id }.toSet()
                }.getOrDefault(emptySet())
            } else emptySet()
            val finalLikedIds = (cachedLiked + likedIds + playlistTrackIds).toSet()
            if (finalLikedIds.isNotEmpty()) {
                app.session.saveLikedSongIds(finalLikedIds)
            }
            _ui.update {
                it.copy(
                    liked = liked,
                    mine = mine,
                    dailyPlaylists = dailyP,
                    dailySongs = dailyS,
                    likedSongIds = finalLikedIds,
                    loading = false,
                    loggedIn = app.session.isLoggedIn(),
                    nickname = app.session.getProfile()?.nickname ?: it.nickname,
                    profileUserId = app.session.getProfile()?.userId,
                )
            }
        }.invokeOnCompletion {
            if (_ui.value.loading) _ui.update { it.copy(loading = false) }
        }
    }

    fun setSpace(space: Space) {
        if (_ui.value.space == space) return
        _ui.update { it.copy(space = space) }
    }

    fun setSearchOpen(open: Boolean) {
        if (!open) {
            searchJob?.cancel()
            searchMoreJob?.cancel()
            searchGen++
            _ui.update { it.copy(searchOpen = false, search = it.search.copy(moreTab = null)) }
            return
        }
        _ui.update { it.copy(searchOpen = true) }
        val snap = _ui.value
        val q = snap.searchQuery.trim()
        val settled = snap.search.resultQuery == q &&
            snap.search.phase != SearchPhase.Loading &&
            snap.search.phase != SearchPhase.Idle
        if (q.isNotEmpty() && !settled) scheduleSearch(immediate = true)
    }

    fun toggleSearch() = setSearchOpen(!_ui.value.searchOpen)

    fun setSearchQuery(q: String) {
        if (_ui.value.searchQuery == q) return
        _ui.update { it.copy(searchQuery = q) }
        scheduleSearch(immediate = false)
    }

    fun search() = scheduleSearch(immediate = true)

    fun searchLoadMore(tab: SearchTab) {
        if (tab == SearchTab.All) return
        val snap = _ui.value
        val q = snap.searchQuery.trim()
        val search = snap.search
        if (q.isEmpty() || search.resultQuery != q) return
        if (search.phase != SearchPhase.Ready) return
        if (search.moreTab != null) return
        val hit = search.hit(tab)
        if (!hit.more || hit.items.size >= SEARCH_CAP) return
        val start = hit.items.size
        val gen = searchGen
        searchMoreJob?.cancel()
        searchMoreJob = viewModelScope.launch {
            _ui.update { it.copy(search = it.search.copy(moreTab = tab, moreErrorTab = null)) }
            when (tab) {
                SearchTab.Songs -> applyMore(gen, q, tab, loadPage { app.client.searchSongPage(q, SEARCH_PAGE, start) }) { current, page ->
                    current.copy(songs = current.songs.merged(page) { it.id })
                }
                SearchTab.Playlists -> applyMore(gen, q, tab, loadPage { app.client.searchPlaylistPage(q, SEARCH_PAGE, start) }) { current, page ->
                    current.copy(playlists = current.playlists.merged(page) { it.id })
                }
                SearchTab.Albums -> applyMore(gen, q, tab, loadPage { app.client.searchAlbumPage(q, SEARCH_PAGE, start) }) { current, page ->
                    current.copy(albums = current.albums.merged(page) { it.id })
                }
                SearchTab.Artists -> applyMore(gen, q, tab, loadPage { app.client.searchArtistPage(q, SEARCH_PAGE, start) }) { current, page ->
                    current.copy(artists = current.artists.merged(page) { it.id })
                }
                SearchTab.All -> Unit
            }
        }
    }

    private suspend fun <T> loadPage(block: () -> SearchPage<T>): Result<SearchPage<T>> =
        withContext(Dispatchers.IO) { catchSearch(block) }

    private fun <T> applyMore(
        gen: Int,
        query: String,
        tab: SearchTab,
        page: Result<SearchPage<T>>,
        merge: (SearchState, SearchPage<T>) -> SearchState,
    ) {
        if (gen != searchGen) return
        _ui.update { state ->
            if (state.searchQuery.trim() != query) return@update state
            val next = if (page.isFailure) {
                state.search.copy(moreErrorTab = tab, moreTab = null)
            } else {
                merge(state.search, page.getOrThrow()).copy(moreTab = null, moreErrorTab = null)
            }
            state.copy(search = next)
        }
    }

    private fun scheduleSearch(immediate: Boolean) {
        val q = _ui.value.searchQuery.trim()
        searchJob?.cancel()
        searchMoreJob?.cancel()
        val gen = ++searchGen
        if (q.isEmpty()) {
            _ui.update { it.copy(search = SearchState()) }
            return
        }
        val current = _ui.value.search
        if (!immediate &&
            current.resultQuery == q &&
            current.phase != SearchPhase.Loading &&
            current.phase != SearchPhase.Error &&
            current.phase != SearchPhase.Idle
        ) {
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(SEARCH_DEBOUNCE_MS)
            if (gen != searchGen) return@launch
            _ui.update {
                it.copy(search = SearchState(phase = SearchPhase.Loading, resultQuery = it.search.resultQuery))
            }
            val loaded = withContext(Dispatchers.IO) {
                coroutineScope {
                    val songs = async { catchSearch { app.client.searchSongPage(q, SEARCH_PAGE) } }
                    val playlists = async { catchSearch { app.client.searchPlaylistPage(q, SEARCH_PAGE) } }
                    val albums = async { catchSearch { app.client.searchAlbumPage(q, SEARCH_PAGE) } }
                    val artists = async { catchSearch { app.client.searchArtistPage(q, SEARCH_PAGE) } }
                    SearchBundle(songs.await(), playlists.await(), albums.await(), artists.await())
                }
            }
            if (gen != searchGen) return@launch
            val songs = loaded.songs.toHit()
            val playlists = loaded.playlists.toHit()
            val albums = loaded.albums.toHit()
            val artists = loaded.artists.toHit()
            val failedAll = songs.failed && playlists.failed && albums.failed && artists.failed
            val anyItem = songs.items.isNotEmpty() || playlists.items.isNotEmpty() ||
                albums.items.isNotEmpty() || artists.items.isNotEmpty()
            val phase = when {
                failedAll -> SearchPhase.Error
                anyItem -> SearchPhase.Ready
                else -> SearchPhase.Empty
            }
            _ui.update {
                it.copy(
                    search = SearchState(
                        phase = phase,
                        resultQuery = q,
                        songs = songs,
                        playlists = playlists,
                        albums = albums,
                        artists = artists,
                    ),
                )
            }
        }
    }

    private fun SearchState.hit(tab: SearchTab): SearchHit<*> = when (tab) {
        SearchTab.Songs -> songs
        SearchTab.Playlists -> playlists
        SearchTab.Albums -> albums
        SearchTab.Artists -> artists
        SearchTab.All -> songs
    }

    private fun <T> Result<SearchPage<T>>.toHit(): SearchHit<T> {
        val page = getOrNull() ?: return SearchHit(failed = true)
        return SearchHit(
            items = page.items,
            total = page.total,
            more = page.items.size < SEARCH_CAP && pageHasMore(page.items.size, page.items.size, page.total),
            failed = false,
        )
    }

    private fun <T> SearchHit<T>.merged(page: SearchPage<T>, idOf: (T) -> String): SearchHit<T> {
        val known = items.map(idOf).toHashSet()
        val fresh = page.items.filter { idOf(it) !in known }
        if (fresh.isEmpty()) {
            return copy(more = false, total = if (page.total >= 0) page.total else total, failed = false)
        }
        val mergedItems = items + fresh
        val totalNow = if (page.total >= 0) page.total else total
        return copy(
            items = mergedItems,
            total = totalNow,
            more = mergedItems.size < SEARCH_CAP && pageHasMore(mergedItems.size, page.items.size, totalNow),
            failed = false,
        )
    }

    private fun pageHasMore(loaded: Int, fetched: Int, total: Int): Boolean {
        if (fetched <= 0) return false
        if (total >= 0) return loaded < total
        return fetched >= SEARCH_PAGE
    }

    private inline fun <T> catchSearch(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    fun openPlaylist(pl: Playlist) {
        viewModelScope.launch {
            val isLikedMusic = pl.specialType == 5 || pl.id == _ui.value.liked.firstOrNull { it.specialType == 5 }?.id
            push(Route.PlaylistSongs(pl))
            _ui.update { it.copy(loading = true, playlistSongs = emptyList()) }
            var songs = withContext(Dispatchers.IO) {
                runCatching { app.client.playlistDetail(pl.id).second }.getOrDefault(emptyList())
            }
            if (isLikedMusic && songs.isEmpty()) {
                val ids = _ui.value.likedSongIds.take(50)
                if (ids.isNotEmpty()) {
                    songs = withContext(Dispatchers.IO) {
                        ids.mapNotNull { runCatching { app.client.getSongDetail(it) }.getOrNull() }
                    }
                }
            }
            val finalSongs = if (isLikedMusic) {
                val currentSong = _ui.value.player.current
                if (currentSong != null && _ui.value.likedSongIds.contains(currentSong.id) && songs.none { it.id == currentSong.id }) {
                    listOf(currentSong) + songs
                } else {
                    songs
                }
            } else {
                songs
            }

            val updatedLikedIds = if (isLikedMusic && songs.isNotEmpty()) {
                val merged = (_ui.value.likedSongIds + songs.map { it.id }).toSet()
                app.session.saveLikedSongIds(merged)
                merged
            } else {
                _ui.value.likedSongIds
            }

            _ui.update { it.copy(playlistSongs = finalSongs, likedSongIds = updatedLikedIds, loading = false) }
        }
    }

    fun playSong(song: Song) {
        val curIdx = _ui.value.player.index
        val q = _ui.value.player.queue
        val targetIdx = q.indexOfFirst { it.id == song.id }
        manualDirection = if (targetIdx >= 0 && curIdx >= 0) {
            if (targetIdx >= curIdx) 1 else -1
        } else {
            1
        }
        // 在房间里也是先改本机播放器。切歌会由一起听盯着播放状态上报，
        // 对方收到的是这首歌，而不是各自再算一遍下一首。
        app.player.playSong(song)
    }

    fun playAll(songs: List<Song>, start: Int = 0) {
        manualDirection = 1
        app.player.playAll(songs, start)
    }

    fun toggle() {
        app.player.toggle()
    }

    fun next() {
        manualDirection = 1
        app.player.next(true)
    }

    fun prev(force: Boolean = false) {
        manualDirection = -1
        app.player.prev(force)
    }

    private val inRoom: Boolean get() = _ui.value.listen.room != null

    fun audioLevel(): Float = app.player.audioLevel()

    fun seekFraction(f: Float) {
        val dur = _clock.value.durationMs.takeIf { it > 0 }
            ?: _ui.value.player.current?.durationMs
            ?: 0L
        if (dur > 0) seekMs((dur * f).toLong())
    }

    fun seekMs(ms: Long) {
        app.player.seek(ms)
        if (inRoom) app.listen.broadcastSeek(ms)
    }

    fun toggleLike(song: Song? = _ui.value.player.current) {
        if (song == null) return
        val currentLiked = _ui.value.likedSongIds.contains(song.id)
        val nextLiked = !currentLiked
        val likedMusic = _ui.value.liked.firstOrNull { it.specialType == 5 } ?: _ui.value.liked.firstOrNull()

        val nextSongIds = if (nextLiked) _ui.value.likedSongIds + song.id else _ui.value.likedSongIds - song.id
        app.session.saveLikedSongIds(nextSongIds)

        _ui.update { state ->
            val nextPlaylistSongs = if (state.route is Route.PlaylistSongs && state.route.playlist.id == likedMusic?.id) {
                if (nextLiked) {
                    listOf(song) + state.playlistSongs.filter { it.id != song.id }
                } else {
                    state.playlistSongs.filter { it.id != song.id }
                }
            } else {
                state.playlistSongs
            }

            val nextLikedPlaylists = state.liked.map { pl ->
                if (pl.id == likedMusic?.id) {
                    val delta = if (nextLiked) 1 else -1
                    pl.copy(trackCount = (pl.trackCount + delta).coerceAtLeast(0))
                } else pl
            }

            state.copy(
                likedSongIds = nextSongIds,
                playlistSongs = nextPlaylistSongs,
                liked = nextLikedPlaylists,
            )
        }

        viewModelScope.launch {
            if (app.session.isLoggedIn()) {
                withContext(Dispatchers.IO) {
                    runCatching { app.client.likeSong(song.id, nextLiked) }
                    val pid = likedMusic?.id
                    if (pid != null) {
                        runCatching { app.client.manipulatePlaylistTracks(pid, song.id, nextLiked) }
                    }
                    val uid = app.session.getProfile()?.userId
                    if (uid != null) {
                        val refreshed = runCatching { app.client.likeList(uid).toSet() }.getOrNull()
                        if (refreshed != null) {
                            val safeMerged = if (nextLiked) {
                                (_ui.value.likedSongIds + refreshed + song.id).toSet()
                            } else {
                                ((_ui.value.likedSongIds + refreshed) - song.id).toSet()
                            }
                            app.session.saveLikedSongIds(safeMerged)
                            _ui.update { it.copy(likedSongIds = safeMerged) }
                        }
                    }
                }
            }
        }
    }

    fun togglePlayMode() = app.player.togglePlayMode()

    fun openNowPlaying() {
        if (_ui.value.player.current == null) return
        _ui.update { it.copy(nowPlayingOpen = true, nowPlayingRoam = it.player.roam) }
    }

    /**
     * 底栏长按 → 把正在播的那首歌的所在列表拉到眼前。
     *
     * 「所在列表」怎么定：当前路由如果就是一首歌的列表（歌单 / 歌手 / 专辑），
     * 且队列里确实有这首 —— 那就留在这个页面，只把列表滚到它那一行。否则（首页、日推、
     * 一起听房间、漫游……）退回播放队列本身：把队列灌进「正在播放」歌单页再滚过去。
     *
     * 队列是唯一的真相源，所以这里只认 [PlayerSnapshot.queue]，不去猜歌单。
     */
    fun revealCurrentInList() {
        val snap = _ui.value.player
        val song = snap.current
        val queue = snap.queue
        val inQueueIdx = queue.indexOfFirst { it.id == song?.id }
        if (song == null || inQueueIdx < 0) {   // 队列还没铺好，没什么可跳的
            _ui.update { it.copy(listJumpSeq = it.listJumpSeq + 1) }
            return
        }

        val route = _ui.value.route
        val routeList = when (route) {
            is Route.PlaylistSongs -> _ui.value.playlistSongs
            is Route.ArtistDetail -> _ui.value.artistSongs
            is Route.AlbumDetail -> _ui.value.albumSongs
            else -> null
        }
        val routeIdx = if (route == Route.Home) null else routeList?.indexOfFirst { it.id == song.id }?.takeIf { it >= 0 }

        if (routeIdx != null) {
            pushListJump(routeIdx)
        } else {
            // 用播放队列当列表。放在这里而不是进页面时做 —— 用户可能稍后才长按，
            // 期间队列已经换过好几轮了。
            app.player.replaceQueue(queue, keepCurrent = true)
            push(Route.PlaylistSongs(nowPlayingQueuePlaylist(queue.size)))
            pushListJump(inQueueIdx)
        }
    }

    private fun pushListJump(index: Int) {
        _ui.update { it.copy(pendingListJump = index, listJumpSeq = it.listJumpSeq + 1) }
    }

    /** 长按跳过去之后，目标页把这一行高亮一小会儿再自己撤掉。 */
    fun clearListJump() = _ui.update { it.copy(pendingListJump = null) }

    fun closeNowPlaying() {
        _ui.update { it.copy(nowPlayingOpen = false) }
    }

    // ------------------------------------------------------------------ 播放列表（队列管理）

    fun openQueue() = _ui.update { it.copy(queueOpen = true) }

    fun closeQueue() = _ui.update { it.copy(queueOpen = false) }

    /** 点队列里的某一首：窗口里有就无缝 seek 过去，没有才重新加载 */
    fun queueJumpAt(index: Int) = app.player.jumpTo(index)

    fun queueRemoveAt(index: Int) {
        val after = _ui.value.player.queue.size - 1
        if (!listenCanDropQueueTo(_ui.value.listen.room != null, after)) {
            app.listen.notify("一起听至少留一首，空队列同步不出去")
            return
        }
        app.player.removeFromQueue(index)
    }

    fun queueMove(from: Int, to: Int) = app.player.moveInQueue(from, to)

    fun queueClear() {
        if (!listenCanDropQueueTo(_ui.value.listen.room != null, 0)) {
            app.listen.notify("一起听至少留一首，空队列同步不出去")
            return
        }
        app.player.clearQueue()
    }

    fun openLogin() = push(Route.Login)

    fun openConfig() = push(Route.Config)

    /**
     * [knownId] 有值时不再按名字重搜。搜索结果里同名歌手不止一个，重搜会打开错的那个。
     */
    fun openArtist(artistName: String, knownId: String? = null, coverHint: String? = null) {
        val trimmed = artistName.trim()
        if (trimmed.isEmpty()) return
        _ui.update { it.copy(nowPlayingOpen = false) }
        push(Route.ArtistDetail(trimmed))
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    artistLoading = true,
                    artistSongs = emptyList(),
                    artistCover = coverHint,
                    artistHasMore = false,
                    artistLoadingMore = false,
                    artistTotal = 0,
                )
            }
            val (artistId, artistPic) = if (!knownId.isNullOrEmpty()) {
                knownId to coverHint?.takeIf { it.isNotEmpty() }
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { app.client.searchArtist(trimmed) }.getOrDefault(null to null)
                }
            }
            currentArtistId = artistId
            val (songs, hasMore, total) = withContext(Dispatchers.IO) {
                var (list, more, tot) = if (!artistId.isNullOrEmpty()) {
                    runCatching { app.client.artistSongsPaged(artistId, offset = 0, limit = 100) }
                        .getOrDefault(Triple(emptyList(), false, 0))
                } else Triple(emptyList(), false, 0)
                if (list.isEmpty()) {
                    list = runCatching { app.client.searchSongs(trimmed, limit = 50) }.getOrDefault(emptyList())
                    tot = list.size
                    more = false
                }
                val mapped = list.map { s ->
                    if (s.cover.isEmpty() && !artistPic.isNullOrEmpty()) s.copy(cover = artistPic) else s
                }
                Triple(mapped, more, tot)
            }
            val cover = artistPic ?: songs.firstOrNull { it.cover.isNotEmpty() }?.cover
            _ui.update {
                it.copy(
                    artistLoading = false,
                    artistSongs = songs,
                    artistCover = cover,
                    artistHasMore = hasMore,
                    artistTotal = total,
                )
            }
        }
    }

    fun loadMoreArtistSongs() {
        val artistId = currentArtistId ?: return
        if (_ui.value.artistLoadingMore || !_ui.value.artistHasMore) return
        viewModelScope.launch {
            _ui.update { it.copy(artistLoadingMore = true) }
            val offset = _ui.value.artistSongs.size
            val artistPic = _ui.value.artistCover
            val (mapped, hasMore, total) = withContext(Dispatchers.IO) {
                val (list, more, tot) = runCatching {
                    app.client.artistSongsPaged(artistId, offset = offset, limit = 100)
                }.getOrDefault(Triple(emptyList<Song>(), false, 0))
                Triple(
                    list.map { s ->
                        if (s.cover.isEmpty() && !artistPic.isNullOrEmpty()) s.copy(cover = artistPic) else s
                    },
                    more,
                    tot,
                )
            }
            _ui.update {
                it.copy(
                    artistLoadingMore = false,
                    artistSongs = it.artistSongs + mapped,
                    artistHasMore = hasMore,
                    artistTotal = if (total > 0) total else it.artistTotal,
                )
            }
        }
    }

    fun openAlbum(albumName: String, albumId: String? = null) {
        val trimmed = albumName.trim()
        if (trimmed.isEmpty()) return
        _ui.update { it.copy(nowPlayingOpen = false) }
        push(Route.AlbumDetail(trimmed, albumId))
        viewModelScope.launch {
            _ui.update { it.copy(albumLoading = true, albumSongs = emptyList(), album = null) }
            var targetId = albumId
            if (targetId.isNullOrEmpty()) {
                targetId = withContext(Dispatchers.IO) {
                    runCatching { app.client.searchAlbum(trimmed).first }.getOrNull()
                }
            }
            val (alb, songs) = withContext(Dispatchers.IO) {
                var (resAlb, resSongs) = if (!targetId.isNullOrEmpty()) {
                    runCatching { app.client.albumDetail(targetId) }.getOrDefault(null to emptyList())
                } else null to emptyList()
                if (resSongs.isEmpty()) {
                    resSongs = runCatching { app.client.searchSongs(trimmed, limit = 30) }.getOrDefault(emptyList())
                }
                resAlb to resSongs
            }
            _ui.update { it.copy(albumLoading = false, album = alb, albumSongs = songs) }
        }
    }

    fun back() {
        if (_ui.value.nowPlayingOpen) {
            _ui.update { it.copy(nowPlayingOpen = false) }
            return
        }
        val r = _ui.value.route
        when (r) {
            is Route.Login -> qrPoll?.cancel()
            // 离开房间页不等于退出房间 —— 用户可以边听边在别的页面逛，
            // 退出房间是显式动作（leaveRoom）。这里只是退出这个页面。
            Route.ListenTogether -> {}
            // 识别是"点了才录 3 秒"的一次性动作，离开页面就该把在跑的采集掐掉，
            // 免得回头进来看还挂着半截链路。
            Route.AudioMatch -> {
                // cancel() 之后收集器本会收到 Idle 顺手恢复播放，但 viewModelScope 若先拆完
                // 就没有下一次收集了 —— 这里不等它，当场收回。有标志位，重复调无害。
                releaseCapturePause()
                // 授权框还开着就退出页面：别让"点了允许"在离开之后把识别续上
                awaitingProjection?.cancel()
                app.audioMatch.cancel()
                releaseAudioProjection()
            }
            Route.Home -> return
            else -> {}
        }
        val prev = stack.removeLastOrNull() ?: Route.Home
        _ui.update { it.copy(route = prev, roamAnimating = false) }
    }

    fun startRoamTransition(dir: Int) {
        _ui.update { it.copy(roamAnimating = true, roamTransitionDir = dir) }
        // 看门狗：过渡动画一旦没走到收尾（帧时钟被掐、协程被吞、页面异常），
        // roamTransitionDir 会一直非 0。底栏的手势处理整段被它拦住 ——
        // 表现出来就是「切不了漫游，也切不了歌」。所以超时强制复位。
        roamWatchdog?.cancel()
        roamWatchdog = viewModelScope.launch {
            delay(ROAM_TRANSITION_TIMEOUT_MS)
            finishRoamTransition()
        }
    }

    fun enterRoamNow() {
        app.player.enterRoam()
        _ui.update { it.copy(nowPlayingOpen = true, nowPlayingRoam = true) }
    }

    fun finishRoamTransition() {
        roamWatchdog?.cancel()
        roamWatchdog = null
        _ui.update { it.copy(roamAnimating = false, roamTransitionDir = 0) }
    }

    fun enterRoam(dir: Int = 1) {
        startRoamTransition(dir)
    }

    fun setQuality(id: String) {
        app.player.setQuality(id)
        // 档位变了，音源的采样率也会变 —— 重采样结论要重算，
        // 而且「换了档位还是被重采样」正是用户最需要看到的提示
        refreshResampleVerdict()
    }

    // ---------------------------------------------------------------- HiFi 音频输出

    /**
     * 把 UI 里的音频输出开关落成实际配置。
     *
     * 每一项都要顺带处理「不该让它生效」的边界情形，否则界面会显示一个假的状态：
     *
     * - **USB 独占**：没有 USB 设备时不允许打开（开关保持关闭并给出提示）。
     *   打开之后是热切换，不重建播放器。
     * - **精确音量**：和「系统级音量直控」互斥 —— 后者就是不走 app 侧衰减，
     *   两者同时开的话滑块会变成摆设。打开直控时自动把精确音量归 100。
     *
     * 独奏焦点需要重建音频管道（会短暂断一下当前播放）。直控和精确音量只热改
     * `setVolume`，交给 [HypochloritePlayer.applyAudioOut] 统一判断要不要重建。
     */
    private fun applyAudioOut(next: AudioOut, note: String? = null) {
        val devices = audioOutputs(app)
        val usbOk = devices.any { it.isUsb() }

        var cfg = next
        var msg = note
        if (cfg.usbExclusive && !usbOk) {
            cfg = cfg.copy(usbExclusive = false)
            msg = if (hasUsbAudioHost(app)) {
                "还没插解码器 / 小尾巴，先插上。"
            } else {
                "这台机器没有 USB 音频，插了也认不出来。"
            }
        }
        if (cfg.directVolume && cfg.gainPercent != 100) {
            cfg = cfg.copy(gainPercent = 100)
        }
        app.player.applyAudioOut(
            app.player.audioOut().let { current ->
                current.copy(
                    usbExclusive = cfg.usbExclusive,
                    exclusiveFocus = cfg.exclusiveFocus,
                    keepAwake = cfg.keepAwake,
                    directVolume = cfg.directVolume,
                    gainPercent = cfg.gainPercent,
                )
            },
        )
        _ui.update { it.copy(audioOut = cfg, hifiMsg = msg ?: "") }
        refreshAudioOutDevices()
    }

    fun setUsbExclusive(on: Boolean) {
        applyAudioOut(_ui.value.audioOut.copy(usbExclusive = on))
    }

    fun setExclusiveFocus(on: Boolean) {
        applyAudioOut(_ui.value.audioOut.copy(exclusiveFocus = on))
    }

    fun setKeepAwake(on: Boolean) {
        applyAudioOut(_ui.value.audioOut.copy(keepAwake = on))
        _ui.update { it.copy(hifiMsg = if (on) "熄屏也会继续出声" else "熄屏可能被系统暂停") }
    }

    fun setDirectVolume(on: Boolean) {
        applyAudioOut(_ui.value.audioOut.copy(directVolume = on))
    }

    /**
     * 拖动精确音量。
     *
     * 拖动过程改内存并热改 [ExoPlayer.setVolume]，不落盘、不重建管道。
     * 手指抬起时才算落盘，见 [commitGain]。
     */
    fun setGain(percent: Int) {
        val p = percent.coerceIn(0, 100)
        _ui.update { it.copy(audioOut = it.audioOut.copy(gainPercent = p)) }
        app.player.applyAudioOut(
            app.player.audioOut().copy(gainPercent = p),
            persist = false,
        )
    }

    fun commitGain() {
        applyAudioOut(_ui.value.audioOut)
    }

    /** 手动重新扫一遍 USB 设备（插拔后不想等轮询时用） */
    fun refreshAudioOutDevices() {
        val devices = audioOutputs(app)
        val usb = devices.filter { it.isUsb() }
        val bound = app.player.boundDeviceId()
        val sel = pickUsbDevice(devices, app.player.audioOut().rememberedUsbId)
        val host = hasUsbAudioHost(app)
        val name = sel?.shortName()
        val note = when {
            usb.isNotEmpty() && bound != null -> "声音走 ${name ?: "USB 解码器"}。"
            usb.isNotEmpty() -> "认到了 ${name ?: "USB 解码器"}，但钉不住，声音还在默认出口。"
            host -> "插上解码器 / 小尾巴就能用。"
            else -> "这台机器认不出 USB 解码器，插了也没用。"
        }
        _ui.update {
            it.copy(
                usbDevices = usb.size,
                usbDeviceName = sel?.shortName(),
                usbExclusiveActive = bound != null,
                usbHostSupported = host,
                hifiMsg = note,
            )
        }
        refreshResampleVerdict()
    }

    /**
     * 刷新「会不会被重采样」的判定。
     *
     * 比的是**正在播放的那条流**的采样率和设备能力 —— 换歌、换档位、插拔设备
     * 都会让结论变化，所以这三处都要调一次。
     */
    private fun refreshResampleVerdict() = runCatching {
        val v = app.player.resampleVerdict()
        _ui.update {
            if (it.sourceSampleRate == v.sourceRate &&
                it.nativeSampleRate == v.nativeRate &&
                it.supportedRates == v.supportedRates &&
                it.willResample == v.willResample
            ) {
                it
            } else {
                it.copy(
                    sourceSampleRate = v.sourceRate,
                    nativeSampleRate = v.nativeRate,
                    supportedRates = v.supportedRates,
                    willResample = v.willResample,
                )
            }
        }
    }

    fun setCookieInput(s: String) = _ui.update { it.copy(cookieInput = s) }

    // ------------------------------------------------------------------ 手机号登录

    fun setLoginTab(tab: Int) {
        if (tab == _ui.value.loginTab) return
        if (tab != 1) qrPoll?.cancel()
        _ui.update { it.copy(loginTab = tab, loginMsg = "") }
    }

    fun setPhone(s: String) = _ui.update { it.copy(phone = s.filter { c -> c.isDigit() }.take(11)) }

    fun setPhoneCountry(s: String) = _ui.update { it.copy(phoneCountry = s.filter { c -> c.isDigit() }.take(3)) }

    fun setPhonePassword(s: String) = _ui.update { it.copy(phonePassword = s) }

    fun setPhoneCaptcha(s: String) =
        _ui.update { it.copy(phoneCaptcha = s.filter { c -> c.isDigit() }.take(6)) }

    fun setPhoneUseCaptcha(useCaptcha: Boolean) = _ui.update { it.copy(phoneUseCaptcha = useCaptcha, loginMsg = "") }

    /** 发送短信验证码，成功后跑 60s 倒计时。 */
    fun sendSmsCode() {
        val s = _ui.value
        if (s.phoneSending || s.phoneCountdown > 0) return
        if (s.loginCooldown > 0) {
            _ui.update { it.copy(loginMsg = "刚被拦过一次，${it.loginCooldown} 秒后再试") }
            return
        }
        if (!isPhoneValid(s.phone)) {
            _ui.update { it.copy(loginMsg = "手机号格式不对") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(phoneSending = true, loginMsg = "发送中…") }
            val (code, msg) = withContext(Dispatchers.IO) {
                runCatching { app.client.sendSmsCaptcha(s.phone, s.phoneCountry) }
                    .getOrDefault(-1 to "发送失败")
            }
            if (code == 200) {
                _ui.update { it.copy(phoneSending = false, loginMsg = "验证码已发送", loginVerifyUrl = "") }
                startSmsCountdown()
            } else {
                val url = app.client.lastLoginVerifyUrl
                val bizCode = app.client.lastLoginCode
                _ui.update {
                    it.copy(
                        phoneSending = false,
                        loginMsg = msg.ifEmpty { "发送失败 ($code)" },
                        loginVerifyUrl = url,
                    )
                }
                startLoginCooldown(bizCode)
            }
        }
    }

    private fun startSmsCountdown() {
        smsCountdown?.cancel()
        smsCountdown = viewModelScope.launch {
            for (left in 60 downTo 1) {
                _ui.update { it.copy(phoneCountdown = left) }
                delay(1000)
            }
            _ui.update { it.copy(phoneCountdown = 0) }
        }
    }

    /**
     * 登录/发码失败后的冷却。
     *
     * 分档是有理由的，别一刀切：
     * - `-462`（云盾人机验证）光等没用，出路是去过验证页 —— 给最长的一档，
     *   但「过一次安全验证」会把冷却直接清掉（见 [completeLoginVerify]）；
     * - `460` / 「网络环境异常」是出口 IP 被打分，等几分钟一般能恢复；
     * - 纯填错（验证码不对 / 密码不对）只给十几秒，别挡着人重填。
     *
     * 之前这里完全没有冷却 —— 用户看到失败就再点一次，而风控正是按「同一设备短时间
     * 内连续失败登录」累加的，等于自己往枪口上撞。
     */
    private fun startLoginCooldown(code: Int) {
        val secs = when {
            code == -462 -> 300
            code == 460 || code == 503 -> 180
            else -> 15
        }
        loginCooldownJob?.cancel()
        loginCooldownJob = viewModelScope.launch {
            for (left in secs downTo 1) {
                _ui.update { it.copy(loginCooldown = left) }
                delay(1000)
            }
            _ui.update { it.copy(loginCooldown = 0) }
        }
    }

    /**
     * 提交手机号登录。
     *
     * [afterVerify] = true 表示这是「刚在验证页过完滑块」的重试 —— 此时**不能碰会话**
     * （不刷新 NMTID、不动 cookie），否则服务端认不出这是同一个已验证的身份，
     * 会再挡一次。表现上就是「滑块明明过了，登录还是 -462」。
     */
    fun submitPhoneLogin(afterVerify: Boolean = false) {
        val s = _ui.value
        if (s.phoneLoggingIn) return
        if (s.loginCooldown > 0) {
            _ui.update { it.copy(loginMsg = "刚被拦过一次，${it.loginCooldown} 秒后再试") }
            return
        }
        if (!isPhoneValid(s.phone)) {
            _ui.update { it.copy(loginMsg = "手机号格式不对") }
            return
        }
        if (s.phoneUseCaptcha && s.phoneCaptcha.length < 4) {
            _ui.update { it.copy(loginMsg = "验证码位数不对") }
            return
        }
        if (!s.phoneUseCaptcha && s.phonePassword.isEmpty()) {
            _ui.update { it.copy(loginMsg = "密码不能为空") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(phoneLoggingIn = true, loginMsg = "登录中…") }
            val profile = withContext(Dispatchers.IO) {
                runCatching {
                    val refreshTicket = !afterVerify
                    if (s.phoneUseCaptcha) {
                        app.client.loginWithCaptcha(
                            s.phone, s.phoneCaptcha, s.phoneCountry, refreshTicket = refreshTicket,
                        )
                    } else {
                        app.client.loginWithPassword(
                            s.phone, s.phonePassword, s.phoneCountry, refreshTicket = refreshTicket,
                        )
                    }
                }.getOrNull()
            }
            val reason = app.client.lastLoginError
            val verifyUrl = app.client.lastLoginVerifyUrl
            val bizCode = app.client.lastLoginCode
            val ok = profile != null && withContext(Dispatchers.IO) { app.session.isLoggedIn() }
            // 过了验证还是 -462：这就不是客户端能解的了，得说清楚是网络出口的问题，
            // 否则用户只会以为「验证没生效」，再去过一遍滑块 —— 白折腾。
            val msg = when {
                ok -> "已登录"
                afterVerify && bizCode == -462 -> "验证过了还是被挡 —— 这是出口网络被网易盯上了，换网络或改用 cookie 登录"
                else -> reason.ifEmpty { "登录失败" }
            }
            _ui.update {
                it.copy(
                    phoneLoggingIn = false,
                    loginMsg = msg,
                    loginVerifyUrl = if (ok) "" else verifyUrl,
                    // 失败时**不清空**输入：-462 的出路就是过完验证再点一次，
                    // 清空等于让用户把密码重敲一遍。
                    phonePassword = if (ok) "" else it.phonePassword,
                    phoneCaptcha = if (ok) "" else it.phoneCaptcha,
                )
            }
            if (ok) {
                smsCountdown?.cancel()
                loginCooldownJob?.cancel()
                onLoggedIn()
            } else {
                startLoginCooldown(bizCode)
            }
        }
    }

    /**
     * 打开云盾验证页 —— **在 app 内的 WebView 里**，不是系统浏览器。
     *
     * 云盾过完验证是把结果**以 cookie 的形式下发到那个浏览器的 cookie jar** 里。
     * 之前用 `ACTION_VIEW` 甩给系统浏览器，结果落在别人的 jar 里：app 这边的会话
     * 什么也没拿到，用户点几次都是同一个 `-462`。所以必须内嵌，且要和 app 共享
     * CookieManager，验证完再把 cookie 收回来（见 [completeLoginVerify]）。
     */
    fun openLoginVerify() {
        if (_ui.value.loginVerifyUrl.isEmpty()) return
        _ui.update { it.copy(showVerify = true) }
    }

    fun closeLoginVerify() = _ui.update { it.copy(showVerify = false) }

    /** 种进验证页 WebView 的 cookie：必须是 app 自己那套，否则验证结果对不上这次登录。 */
    fun verifySeedCookies(): List<Pair<String, String>> =
        app.session.getCookies().entries.map { it.key to it.value }

    /**
     * 验证页跑完了：把 WebView 里的 cookie 收回来，清掉冷却，自动重试一次登录。
     *
     * 重试走 [submitPhoneLogin] 的 `afterVerify = true` —— 直连时代这要求会话一概不动
     * （登录前不再换 NMTID 票据）；现在走代理，票据调度在服务端，这个语义只影响
     * 客户端自己的重试路径选择，保留同样的开关。
     */
    fun completeLoginVerify(cookieStr: String) {
        val parsed = Crypto.parseCookieString(cookieStr)
        if (parsed.isNotEmpty()) app.session.setCookies(parsed, merge = true)
        loginCooldownJob?.cancel()
        _ui.update { it.copy(showVerify = false, loginCooldown = 0, loginVerifyUrl = "") }
        if (parsed.isEmpty()) {
            _ui.update { it.copy(loginMsg = "没拿到验证结果，重开一次验证页再试") }
            return
        }
        submitPhoneLogin(afterVerify = true)
    }

    private fun isPhoneValid(phone: String) = phone.length == 11 && phone.startsWith("1")

    /** 登录成功后的统一收尾：刷新首页数据。若有一条还没用上的一起听邀请，回到房间页。 */
    private fun onLoggedIn() {
        val reopenListen = app.listen.peekPending()
        refresh()
        stack.clear()
        _ui.update { it.copy(route = if (reopenListen) Route.ListenTogether else Route.Home) }
        if (reopenListen) app.listen.onLoggedIn()
    }

    fun submitCookie() {
        viewModelScope.launch {
            val cookie = _ui.value.cookieInput.trim()
            if (cookie.isEmpty()) {
                _ui.update { it.copy(loginMsg = "cookie 无效") }
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching { app.client.loginWithCookieString(cookie); app.session.isLoggedIn() }.getOrDefault(false)
            }
            _ui.update { it.copy(loginMsg = if (ok) "已登录" else "cookie 无效") }
            if (ok) onLoggedIn()
        }
    }

    fun logout() {
        qrPoll?.cancel()
        smsCountdown?.cancel()
        viewModelScope.launch {
            app.listen.endForLogout()
            app.session.clear()
            _ui.update {
                it.copy(
                    loggedIn = false,
                    nickname = "未登录",
                    liked = emptyList(),
                    mine = emptyList(),
                    dailyPlaylists = emptyList(),
                    dailySongs = emptyList(),
                    likedSongIds = emptySet(),
                    loginMsg = "已退出",
                    phonePassword = "",
                    phoneCaptcha = "",
                    phoneCountdown = 0,
                    profileUserId = null,
                )
            }
        }
    }

    fun requestQr() {
        qrPoll?.cancel()
        viewModelScope.launch {
            _ui.update { it.copy(loginMsg = "取码…", qr = null) }
            val key = withContext(Dispatchers.IO) { runCatching { app.client.qrUnikey() }.getOrNull() }
            if (key.isNullOrEmpty()) {
                _ui.update { it.copy(loginMsg = "二维码失败") }
                return@launch
            }
            qrKey = key
            val bmp = withContext(Dispatchers.Default) { runCatching { makeQr(Crypto.loginQrUrl(key)) }.getOrNull() }
            _ui.update { it.copy(qr = bmp, loginMsg = "用网易云扫码") }
            qrPoll = viewModelScope.launch {
                while (true) {
                    delay(1500)
                    val code = withContext(Dispatchers.IO) { runCatching { app.client.qrStatus(key) }.getOrDefault(0) }
                    when (code) {
                        803 -> {
                            withContext(Dispatchers.IO) { runCatching { app.client.refreshAccount() } }
                            _ui.update { it.copy(loginMsg = "已登录") }
                            onLoggedIn()
                            break
                        }
                        800 -> {
                            _ui.update { it.copy(loginMsg = "二维码过期") }
                            break
                        }
                        -462 -> {
                            // 二维码这条路照样吃云盾 —— 实测**第一次轮询就 -462**，
                            // 那时候还没人扫码。所以必须立刻停轮询：继续每 1.5 秒打一次，
                            // 只会一直往风控里喂分（这也是「二维码好像死了」的真实原因，
                            // 界面上它只是一直转，什么都不说）。
                            val url = app.client.lastLoginVerifyUrl
                            _ui.update { it.copy(loginMsg = "二维码被风控挡了", loginVerifyUrl = url) }
                            startLoginCooldown(-462)
                            break
                        }
                    }
                }
            }
        }
    }

    private val inviteQrCache = LinkedHashMap<String, ImageBitmap>(8, 0.75f, true)

    suspend fun inviteQr(text: String): ImageBitmap? {
        synchronized(inviteQrCache) { inviteQrCache[text] }?.let { return it }
        val bmp = withContext(Dispatchers.Default) { runCatching { makeQr(text) }.getOrNull() } ?: return null
        synchronized(inviteQrCache) {
            inviteQrCache[text] = bmp
            while (inviteQrCache.size > 8) {
                val eldest = inviteQrCache.entries.firstOrNull()?.key ?: break
                inviteQrCache.remove(eldest)
            }
        }
        return bmp
    }

    private fun makeQr(text: String, size: Int = 168): ImageBitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        val pixels = IntArray(size * size)
        var i = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[i++] = if (matrix[x, y]) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp.asImageBitmap()
    }
}
