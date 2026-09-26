package app.hypochlorite

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.session.MediaSession
import app.hypochlorite.audio.FakeAudioFingerprintGenerator
import app.hypochlorite.audio.SineAudioCaptureSource
import app.hypochlorite.audio.SystemAudioController
import app.hypochlorite.netease.NeteaseClient
import app.hypochlorite.netease.SessionStore
import app.hypochlorite.player.AudioMatch
import app.hypochlorite.player.ListenTogether
import app.hypochlorite.player.MEDIA_CACHE_DIR
import app.hypochlorite.player.HypochloritePlayer
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "hypochlorite"
private const val PREFS_GUARD = "boot-guard"
private const val KEY_BOOT = "boot_in_progress"
private const val KEY_STRIKES = "boot_strikes"

/** 上一轮是否走了正常收尾（[markCleanExit]）。划掉后台时不会写，但那只代表「没走 onDestroy」，不代表崩溃 */
private const val KEY_CLEAN = "boot_clean_exit"

/**
 * 活过这段时间就算这次启动是正常的。
 *
 * 这只是「主线程曾经跑到过这里且没卡住」的证据，**不再作为崩溃判据** ——
 * 见 [installBootGuard] 里关于「划掉后台」为什么会误判的说明。
 * 现在它只用来把 strikes 计数衰减掉，避免历史遗留的计数永久挂着。
 */
private const val BOOT_OK_DELAY_MS = 3500L

class HypochloriteApplication : Application(), ImageLoaderFactory {
    lateinit var session: SessionStore
        private set
    lateinit var client: NeteaseClient
        private set
    lateinit var player: HypochloritePlayer
        private set
    lateinit var listen: ListenTogether
        private set
    lateinit var audioMatch: AudioMatch
        private set
    lateinit var systemAudio: SystemAudioController
        private set
    var mediaSession: MediaSession? = null
        private set

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, e -> Log.e("hypochlorite", "uncaught", e) },
        )

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志要在一切之前装：装完才有可能抓到启动阶段的崩溃
        installCrashLogger()
        session = SessionStore(this)
        val http = NeteaseClient.httpWithSession(session)
        client = NeteaseClient(session, http)
        player = HypochloritePlayer(this, client, scope, http, session)
        listen = ListenTogether(client, player, session, scope)
        systemAudio = SystemAudioController(this)
        // 采集源和指纹生成器都是假的（UI 链路阶段）；正式实现换 SineAudioCaptureSource →
        // 真 MediaProjection 源、FakeAudioFingerprintGenerator → Chicory 提取器，第 6 步。
        audioMatch = AudioMatch(
            scope = scope,
            capture = SineAudioCaptureSource(),
            generator = FakeAudioFingerprintGenerator(),
            // 引擎不自己切线程；阻塞的网络调用在这里挪到 IO（scope 是 Main.immediate）
            matcher = { fp, seconds -> withContext(Dispatchers.IO) { client.audioMatch(fp, seconds) } },
        ).apply { forceHitForDebug = BuildConfig.DEBUG }

        installBootGuard()

        scope.launch(Dispatchers.IO) {
            purgeExpiredCache(this@HypochloriteApplication)
        }
    }

    // ------------------------------------------------------------ 启动自愈

    /**
     * 把未捕获异常的堆栈写到 `/sdcard/Android/data/app.hypochlorite/files/crash_last.txt`，
     * 出问题时用户能直接把这个文件发给开发者，不用连 adb。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "crash_last.txt").writeText(
                    buildString {
                        append("time=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
                        append("thread=").append(thread.name).append('\n')
                        append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        append(" sdk=").append(Build.VERSION.SDK_INT).append("\n\n")
                        append(writer.toString())
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * 启动守卫，专治「打不开、只能卸载重装」。
     *
     * **判据是「上次进程有没有走完收尾」，不是「有没有活过 N 秒」。**
     *
     * 早先的写法是「启动时落一个 boot_in_progress=true，3.5s 后抹掉；下次启动发现它还在
     * 就认为上次崩了，清掉 playback_state / monet_seed / image_cache」。这个判据是错的：
     * 用户**从最近任务划掉 / 系统后台回收**时，进程是被 SIGKILL 掉的，`postDelayed`
     * 那个回调根本没机会跑 —— 标记必然残留。于是「正常退出」被当成「崩溃」，
     * 每次冷启动都清一遍播放状态。用户看到的现象就是「明明保存了关闭前的音乐，
     * 重开却没有 / 一开就出问题」，而且 strikes 会一直涨，永远自愈不了。
     *
     * 现在改成正向判定：由 [MainActivity.onDestroy] 走正常销毁时显式调
     * [markCleanExit] 落 KEY_CLEAN=true。启动时只有「上一轮留下 boot_in_progress=true
     * **且** 没有 clean 标记」才算异常 —— 也就是进程在启动途中死掉了（真正的启动崩溃 /
     * 卡死）。划掉后台属于正常退出，不会再触发清理。
     *
     * 清理只动**可再生的本地缓存态**（播放队列 / 取色 seed / 图片缓存）。
     * 登录 cookie 一定不动，否则用户要重新扫码。
     */
    private fun installBootGuard() {
        val guard = getSharedPreferences(PREFS_GUARD, MODE_PRIVATE)
        val unfinished = runCatching { guard.getBoolean(KEY_BOOT, false) }.getOrDefault(false)
        val cleanExit = runCatching { guard.getBoolean(KEY_CLEAN, false) }.getOrDefault(false)

        // 只有「上次启动标记没抹掉」且「上次也没有正常收尾」才是真的异常。
        // 划掉后台的 cleanExit 是 true，所以那条路径不会再被误判。
        val crashed = unfinished && !cleanExit
        val strikes = if (crashed) runCatching { guard.getInt(KEY_STRIKES, 0) }.getOrDefault(0) + 1 else 0

        if (crashed) {
            Log.e(TAG, "上次启动未完成（无正常收尾），重置本地缓存态（第 $strikes 次）")
            runCatching { session.clearPlaybackState() }
            runCatching { session.clearMonetSeed() }
            runCatching { cacheDir.resolve("image_cache").deleteRecursively() }
        }

        runCatching {
            guard.edit()
                .putInt(KEY_STRIKES, strikes)
                .putBoolean(KEY_BOOT, true)
                // 本轮还没收尾，先把干净标记降下来；onDestroy 正常时会再抬起来
                .putBoolean(KEY_CLEAN, false)
                .commit()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            // 活到这一刻说明启动阶段没崩，把 strikes 衰减掉，避免历史计数永久挂着
            runCatching {
                guard.edit().putInt(KEY_STRIKES, 0).putBoolean(KEY_BOOT, false).commit()
            }
        }, BOOT_OK_DELAY_MS)
    }

    /**
     * 由 [MainActivity] 在正常销毁时调用，标记「这一轮是干净退出的」。
     *
     * 划掉后台 / 系统回收不会走到这里，但那正是我们要的语义：**这两种情况也算正常退出**，
     * 因为播放状态已经在 `onStop` 里落过盘了。
     */
    fun markCleanExit() {
        runCatching {
            getSharedPreferences(PREFS_GUARD, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CLEAN, true)
                .putBoolean(KEY_BOOT, false)
                .commit()
        }
    }

    private fun purgeExpiredCache(context: Application, maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        val targets = listOfNotNull(
            context.cacheDir,
            context.externalCacheDir,
        )
        val now = System.currentTimeMillis()
        for (dir in targets) {
            runCatching { deleteExpired(dir, now, maxAgeMs) }
        }
    }

    private fun deleteExpired(dir: File, now: Long, maxAgeMs: Long) {
        if (!dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                // 跳过音频字节缓存：SimpleCache 有自己的索引和 LRU 淘汰，
                // 在它背后按 mtime 删文件 = 索引还在、文件没了，下次读会报错重建
                if (file.name == MEDIA_CACHE_DIR) continue
                deleteExpired(file, now, maxAgeMs)
                if (file.listFiles()?.isEmpty() == true) {
                    file.delete()
                }
            } else {
                val age = now - file.lastModified()
                if (file.lastModified() > 0 && age > maxAgeMs) {
                    file.delete()
                }
            }
        }
    }

    fun ensureMediaSession(): MediaSession {
        mediaSession?.let { return it }
        val created = MediaSession.Builder(this, player.exo).build()
        mediaSession = created
        return created
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .okHttpClient(NeteaseClient.httpWithSession(session))
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
}
