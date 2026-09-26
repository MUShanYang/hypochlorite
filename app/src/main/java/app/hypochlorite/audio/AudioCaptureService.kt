package app.hypochlorite.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.hypochlorite.HypochloriteApplication

/**
 * 识曲抓系统音频的短时前台服务。存在的唯一理由：Android 14 要求「创建 / 使用 MediaProjection
 * 之前，必须已有一个 mediaProjection 类型的前台服务在跑」，否则 getMediaProjection 之后的抓取
 * 直接 SecurityException。抓完一段就停，不常驻。
 *
 * 投影由本服务创建后回交给 [SystemAudioController]（见 [app.hypochlorite.HypochloriteApplication.systemAudio]），
 * 抓取源从控制器读它——顺序天然是「服务已前台 → getMediaProjection → 交给源」。
 */
class AudioCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须先把前台服务起起来，后面 getMediaProjection 才不被 14 拦
        runCatching { promote() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val app = application as HypochloriteApplication
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null || resultCode == 0) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        // 重复授权（用户又点了一次开关）：先放掉上一份，保证「一个投影用一次」
        projection?.let { runCatching { it.stop() } }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val mp = mgr.getMediaProjection(resultCode, data) ?: run {
            app.systemAudio.onProjectionLost()
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户在系统面板点了"停止共享"，投影随时会失效
                projection = null
                app.systemAudio.onProjectionLost()
                stopSelfSafely()
            }
        }
        mp.registerCallback(cb, null)
        projection = mp
        callback = cb
        app.systemAudio.onProjectionReady(mp)
        return START_STICKY
    }

    override fun onDestroy() {
        stopSelfSafely()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        val mp = projection
        val cb = callback
        if (mp != null && cb != null) runCatching { mp.unregisterCallback(cb) }
        callback = null
        if (mp != null) runCatching { mp.stop() }
        projection = null
        (application as? HypochloriteApplication)?.systemAudio?.onProjectionLost()
        runCatching { stopSelf() }
    }

    private fun promote() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "识别中", NotificationManager.IMPORTANCE_LOW)
            ch.description = "听歌识曲正在读取其它应用播放的声音"
            ch.setShowBadge(false)
            ch.setSound(null, null)
            mgr?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("正在识别歌曲")
            .setContentText("读取系统播放的声音")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val CHANNEL = "hypochlorite-capture"
        private const val NOTIF_ID = 0x72c1
        const val EXTRA_RESULT_CODE = "app.hypochlorite.extra.CAPTURE_RESULT_CODE"
        const val EXTRA_DATA = "app.hypochlorite.extra.CAPTURE_DATA"
        const val ACTION_STOP = "app.hypochlorite.ACTION_STOP_CAPTURE"
    }
}
