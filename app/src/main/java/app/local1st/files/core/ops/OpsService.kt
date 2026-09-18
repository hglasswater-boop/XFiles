package app.local1st.files.core.ops

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.local1st.files.MainActivity
import app.local1st.files.core.util.Format
import app.local1st.files.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps long work running when the app is backgrounded: file operations (copy/move/zip/extract)
 * from the [OperationEngine], and [BackgroundJobs] such as the package-install pipeline. Both
 * live on the app-lifetime scope; this service holds the process alive with a foreground
 * notification + wake lock and mirrors whatever is running. It stops itself once both are empty.
 */
class OpsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xfiles:ops")
            .apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }

        // A CPU wake lock keeps the copy coroutine running but does not keep an idle Wi-Fi radio
        // awake. Hold a plain WifiLock only while a bulk copy/move is active; do not request a
        // latency/performance mode whose semantics differ across Android releases.
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock("xfiles:ops-wifi")
            .apply { setReferenceCounted(false) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) cancelEverything()

        // Must call startForeground promptly. Explicitly identify this as user-visible data sync
        // so modern Android keeps the service in the correct foreground-service class.
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(currentNotice()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }

        if (!collecting) {
            collecting = true
            scope.launch {
                combine(Graph.opEngine.active, BackgroundJobs.active) { ops, jobs -> ops to jobs }
                    .flatMapLatest { (ops, jobs) ->
                        // Copy/move operations expose transfer stats. Keep Wi-Fi awake for those
                        // bulk transfers, while local-only background jobs such as installs and
                        // archive work do not unnecessarily hold the radio.
                        syncWifiLock(
                            shouldHold = ops.any { it.progress.value.showTransferStats },
                        )
                        val count = ops.size + jobs.size
                        val op = ops.firstOrNull()
                        val job = jobs.firstOrNull()
                        when {
                            // Ops carry richer progress, so they lead when both are running.
                            op != null -> op.progress.map { notice(count, it) }
                            job != null -> job.progress.map { notice(count, it) }
                            else -> flowOf(null)
                        }
                    }
                    .collect { notice ->
                        if (notice == null) {
                            syncWifiLock(shouldHold = false)
                            stop()
                        } else {
                            // Renew the wake lock each tick so work longer than the timeout keeps going.
                            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                            notificationManager().notify(NOTIF_ID, buildNotification(notice))
                        }
                    }
            }
        }
        // No in-memory op state survives a process restart, so don't re-deliver.
        return START_NOT_STICKY
    }

    /** Android 14+ dataSync FGS time limit: cancel outstanding work and stop cleanly. */
    override fun onTimeout(startId: Int) {
        cancelEverything()
        stop()
    }

    private fun cancelEverything() {
        Graph.opEngine.active.value.forEach { it.cancel() }
        BackgroundJobs.active.value.forEach { it.cancel() }
    }

    private fun syncWifiLock(shouldHold: Boolean) {
        val lock = wifiLock ?: return
        if (shouldHold) {
            if (!lock.isHeld) runCatching { lock.acquire() }
        } else if (lock.isHeld) {
            runCatching { lock.release() }
        }
    }

    private fun stop() {
        syncWifiLock(shouldHold = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        syncWifiLock(shouldHold = false)
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    /** What the one notification shows: [fraction] null means "indeterminate". */
    private data class Notice(val title: String, val text: String, val fraction: Float?)

    private fun heading(count: Int, single: String) =
        if (count > 1) "$count background tasks" else single

    private fun notice(count: Int, progress: OpProgress) = Notice(
        title = heading(count, progress.title),
        text = when (progress.state) {
            OpState.SCANNING -> "Scanning… ${progress.currentItem}"
            else -> "${(progress.fraction * 100).toInt()}%  ·  " +
                "${Format.bytes(progress.doneBytes)} / ${Format.bytes(progress.totalBytes)}"
        },
        fraction = progress.fraction
            .takeIf { progress.state != OpState.SCANNING && progress.totalBytes > 0 },
    )

    private fun notice(count: Int, progress: JobProgress) = Notice(
        title = heading(count, progress.title),
        text = if (progress.indeterminate) {
            progress.message
        } else {
            "${progress.message}  ·  " +
                "${Format.bytes(progress.doneBytes)} / ${Format.bytes(progress.totalBytes)}"
        },
        fraction = progress.fraction.takeIf { !progress.indeterminate },
    )

    /** Snapshot for the mandatory first [startForeground], before the collector has ticked. */
    private fun currentNotice(): Notice {
        val ops = Graph.opEngine.active.value
        val jobs = BackgroundJobs.active.value
        val count = ops.size + jobs.size
        ops.firstOrNull()?.let { return notice(count, it.progress.value) }
        jobs.firstOrNull()?.let { return notice(count, it.progress.value) }
        return Notice("Working…", "", null)
    }

    private fun buildNotification(notice: Notice): android.app.Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, OpsService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)

        if (notice.fraction != null) {
            builder.setProgress(100, (notice.fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File operations",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Progress of copy, move, compress, extract and install" }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "file_operations"
        private const val NOTIF_ID = 42
        private const val ACTION_CANCEL_ALL = "app.local1st.files.ops.CANCEL_ALL"
        private const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L // 1 hour safety cap

        /** Starts (or refreshes) the foreground service to cover running ops and jobs. */
        fun start(context: Context) {
            val intent = Intent(context, OpsService::class.java)
            // Work is submitted from the foreground, so this is normally allowed; guard the
            // rare background-start race (ForegroundServiceStartNotAllowedException) — it
            // still runs on the app-lifetime scope even without the service.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
