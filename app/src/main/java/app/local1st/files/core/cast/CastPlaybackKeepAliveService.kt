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
        if (intent?.action == ACTION_STOP) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
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
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("キャスト再生中")
            .setContentText("XFilesからテレビへメディアを配信しています")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

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
        private const val CHANNEL_ID = "cast_playback"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_STOP = "app.local1st.files.cast.STOP_KEEP_ALIVE"

        fun start(context: Context) {
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
