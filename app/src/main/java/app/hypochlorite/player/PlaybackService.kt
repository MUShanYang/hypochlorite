package app.hypochlorite.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.hypochlorite.MainActivity
import app.hypochlorite.HypochloriteApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private var stateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val app = application as HypochloriteApplication
        app.ensureMediaSession()
        createNotificationChannel()
        runCatching { startInForeground(app.player.state.value) }

        stateJob = serviceScope.launch {
            app.player.state.collect { snapshot ->
                updateNotification(snapshot)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HypochloriteApplication
        app.ensureMediaSession()
        createNotificationChannel()
        runCatching { startInForeground(app.player.state.value) }
        when (intent?.action) {
            ACTION_TOGGLE -> app.player.toggle()
            ACTION_NEXT -> app.player.next()
            ACTION_PREV -> app.player.prev(force = true)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return (application as HypochloriteApplication).mediaSession
    }

    override fun onDestroy() {
        stateJob?.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "Hypochlorite 播放控制", NotificationManager.IMPORTANCE_LOW)
            ch.description = "正在播放控制与通知"
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            ch.setShowBadge(false)
            ch.setSound(null, null)
            mgr?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(snapshot: PlayerSnapshot): Notification {
        val song = snapshot.current
        val title = song?.name ?: "hypochlorite"
        val artist = song?.artists?.joinToString(" / ") ?: "未在播放"

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val prevIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val toggleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val nextIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val playPauseIcon = if (snapshot.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (snapshot.playing) "暂停" else "播放"

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setOngoing(snapshot.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_previous, "上一首", prevIntent)
            .addAction(playPauseIcon, playPauseTitle, toggleIntent)
            .addAction(android.R.drawable.ic_media_next, "下一首", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun startInForeground(snapshot: PlayerSnapshot) {
        val n = buildNotification(snapshot)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(snapshot: PlayerSnapshot) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val n = buildNotification(snapshot)
        runCatching { mgr.notify(NOTIF_ID, n) }
    }

    companion object {
        const val CHANNEL = "hypochlorite-play"
        const val NOTIF_ID = 0x72e0
        const val ACTION_TOGGLE = "app.hypochlorite.ACTION_TOGGLE"
        const val ACTION_NEXT = "app.hypochlorite.ACTION_NEXT"
        const val ACTION_PREV = "app.hypochlorite.ACTION_PREV"
    }
}
