package app.hypochlorite.audio

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 系统音频抓取的总闸。挂在 [app.hypochlorite.HypochloriteApplication] 上。
 *
 * 抓别的 App 的声音只有 MediaProjection 一条路，而它有两道硬约束：
 * 1. 授权 Intent 用一次即废（14+ 复用旧 data 直接 SecurityException）——所以每次识别都现取。
 * 2. 用投影前必须先起一个 mediaProjection 类型的前台服务——所以 [projection] 由
 *    [AudioCaptureService] 创建后回交，而不是这边直接 getMediaProjection。
 *
 * [ready] 翻 true 才代表投影可用、可以识别。识别页盯着它决定「点中心要不要先走授权」。
 */
class SystemAudioController(private val appContext: Context) {

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile
    var projection: MediaProjection? = null
        private set

    /**
     * 采集期间的实时峰值 [0,1]，由 [MediaProjectionCaptureSource] 每读一块写一次，停下时归零。
     *
     * 为什么要单开一条电平：识别页的动画原本吃 `HypochloritePlayer.audioLevel()`，而它在自己
     * 没在播放时恒返回 0 —— 识别期间我们自己正是暂停的（见 [MediaProjectionCaptureSource] 上
     * 关于 removeMatchingUids 的注释），那条输入会在最需要动画的三秒里躺平。
     *
     * 走 @Volatile 而非 StateFlow：读方是 60fps 的绘制阶段 lambda，那里不该订阅、也不该重组。
     */
    @Volatile
    var captureLevel: Float = 0f

    val supported: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    /**
     * 用户在系统授权框点了允许 → 起前台服务，由服务去 getMediaProjection。
     * 结果异步回到 [onProjectionReady]。
     */
    fun beginCapture(resultCode: Int, data: Intent) {
        val intent = Intent(appContext, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_DATA, data)
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(intent)
            else appContext.startService(intent)
        }
    }

    /** 由 [AudioCaptureService] 在拿到投影后回调。 */
    fun onProjectionReady(mp: MediaProjection) {
        projection = mp
        _ready.value = true
    }

    /** 投影失效（用户在系统面板停止共享 / 服务被杀）时回调，收回可识别状态。 */
    fun onProjectionLost() {
        projection = null
        _ready.value = false
    }

    /** 结束抓取会话：停服务、stop 投影。只在离开识别页时调。 */
    fun endCapture() {
        runCatching { appContext.stopService(Intent(appContext, AudioCaptureService::class.java)) }
        projection?.let { mp -> runCatching { mp.stop() } }
        onProjectionLost()
    }

    fun newCaptureSource(): AudioCaptureSource = MediaProjectionCaptureSource(this)
}
