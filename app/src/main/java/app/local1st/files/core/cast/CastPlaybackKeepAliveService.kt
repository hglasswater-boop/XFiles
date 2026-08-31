package app.local1st.files.core.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.local1st.files.MainActivity
import app.local1st.files.R

/**
 * Keeps the app process at foreground priority while a Chromecast receiver is actively reading
 * media from XFiles' in-process HTTP relay. The Cast session itself remains owned by the mobile
 * playback session manager; this service only prevents Android from suspending/killing the relay
 * when the user switches to another app.
 */
class CastPlaybackKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PLAY_PAUSE -> CastPlaybackBridge.togglePlayPause()
            ACTION_SEEK_BACK -> CastPlaybackBridge.seekBy(-SEEK_STEP_MS)
            ACTION_SEEK_FORWARD -> CastPlaybackBridge.seekBy(SEEK_STEP_MS)
            ACTION_PREVIOUS -> CastPlaybackBridge.previous()
            ACTION_NEXT -> CastPlaybackBridge.next()
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        // Cast state is in memory and cannot be reconstructed after a process death.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): android.app.Notification {
        val state = CastPlaybackBridge.currentState()
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_PLAYBACK)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(state?.title ?: "キャスト再生中")
            .setContentText("XFilesからテレビへキャスト中")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state?.hasPrevious == true) {
            builder.addAction(
                android.R.drawable.ic_media_previous,
                getString(R.string.previous),
                controlIntent(ACTION_PREVIOUS, REQUEST_PREVIOUS),
            )
        }
        builder.addAction(
            android.R.drawable.ic_media_rew,
            "−10s",
            controlIntent(ACTION_SEEK_BACK, REQUEST_SEEK_BACK),
        )
        builder.addAction(
            if (state?.playing == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            getString(if (state?.playing == true) R.string.pause else R.string.play),
            controlIntent(ACTION_TOGGLE_PLAY_PAUSE, REQUEST_TOGGLE),
        )
        builder.addAction(
            android.R.drawable.ic_media_ff,
            "+10s",
            controlIntent(ACTION_SEEK_FORWARD, REQUEST_SEEK_FORWARD),
        )
        if (state?.hasNext == true) {
            builder.addAction(
                android.R.drawable.ic_media_next,
                getString(R.string.next),
                controlIntent(ACTION_NEXT, REQUEST_NEXT),
            )
        }

        return builder.build()
    }

    private fun controlIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, CastPlaybackKeepAliveService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "キャスト再生",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Chromecastへの再生をバックグラウンドで維持します"
            },
        )
    }

    companion object {
        const val ACTION_OPEN_PLAYBACK = "app.local1st.files.cast.OPEN_PLAYBACK"

        private const val CHANNEL_ID = "cast_playback"
        private const val NOTIFICATION_ID = 43
        private const val SEEK_STEP_MS = 10_000L
        private const val ACTION_STOP = "app.local1st.files.cast.STOP_KEEP_ALIVE"
        private const val ACTION_TOGGLE_PLAY_PAUSE = "app.local1st.files.cast.TOGGLE_PLAY_PAUSE"
        private const val ACTION_SEEK_BACK = "app.local1st.files.cast.SEEK_BACK"
        private const val ACTION_SEEK_FORWARD = "app.local1st.files.cast.SEEK_FORWARD"
        private const val ACTION_PREVIOUS = "app.local1st.files.cast.PREVIOUS"
        private const val ACTION_NEXT = "app.local1st.files.cast.NEXT"
        private const val REQUEST_OPEN = 430
        private const val REQUEST_PREVIOUS = 431
        private const val REQUEST_SEEK_BACK = 432
        private const val REQUEST_TOGGLE = 433
        private const val REQUEST_SEEK_FORWARD = 434
        private const val REQUEST_NEXT = 435

        fun start(context: Context) {
            startOrRefresh(context)
        }

        fun refresh(context: Context) {
            startOrRefresh(context)
        }

        private fun startOrRefresh(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, CastPlaybackKeepAliveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(Intent(appContext, CastPlaybackKeepAliveService::class.java))
            }
        }
    }
}
