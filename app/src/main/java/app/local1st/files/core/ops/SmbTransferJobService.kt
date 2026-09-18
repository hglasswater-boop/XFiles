package app.local1st.files.core.ops

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import app.local1st.files.MainActivity
import app.local1st.files.core.util.Format
import app.local1st.files.di.Graph
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Android 14+ user-initiated data-transfer lease for an SMB operation.
 *
 * The actual copy stays in [OperationEngine]; this JobService owns the OS-visible lifetime of that
 * same user-requested transfer, mirrors its progress notification, and cancels the operation if
 * JobScheduler explicitly stops the job. UIDT jobs receive the platform's user-transfer priority
 * and are not subject to ordinary JobScheduler quotas.
 */
class SmbTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val collectors = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (!params.extras.containsKey(EXTRA_OPERATION_ID)) return false

        val operationId = params.extras.getLong(EXTRA_OPERATION_ID)
        val running = Graph.opEngine.active.value.firstOrNull { it.id == operationId } ?: return false

        createChannel()
        publish(params, running, running.progress.value)
        collectors.remove(params.jobId)?.cancel()
        collectors[params.jobId] = scope.launch {
            running.progress
                .takeWhile { !it.state.isTerminal() }
                .collect { progress -> publish(params, running, progress) }

            publish(params, running, running.progress.value)
            collectors.remove(params.jobId)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        collectors.remove(params.jobId)?.cancel()
        val operationId = params.extras
            .takeIf { it.containsKey(EXTRA_OPERATION_ID) }
            ?.getLong(EXTRA_OPERATION_ID)
        operationId?.let { id -> Graph.opEngine.active.value.firstOrNull { it.id == id }?.cancel() }
        // XFiles operations are not resumable yet; automatic retry could duplicate partial output.
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun publish(params: JobParameters, running: RunningOp, progress: OpProgress) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        setNotification(
            params,
            params.jobId,
            buildNotification(running.id, progress),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
    }

    private fun buildNotification(operationId: Long, progress: OpProgress): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (progress.state) {
            OpState.SCANNING -> "Scanning… ${progress.currentItem}"
            else -> buildString {
                append("${(progress.fraction * 100).toInt()}%  ·  ")
                append("${Format.bytes(progress.doneBytes)} / ${Format.bytes(progress.totalBytes)}")
                if (progress.bytesPerSecond > 0L) {
                    append("  ·  ${Format.bytes(progress.bytesPerSecond)}/s")
                }
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(progress.title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(!progress.state.isTerminal())
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_delete,
                "Cancel",
                OperationCancelReceiver.pendingIntent(this, operationId),
            )
            .apply {
                if (progress.state != OpState.SCANNING && progress.totalBytes > 0L) {
                    setProgress(100, (progress.fraction * 100).toInt(), false)
                } else if (!progress.state.isTerminal()) {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SMB transfers",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress of user-initiated SMB file transfers" },
        )
    }

    companion object {
        private const val CHANNEL_ID = "smb_transfer_jobs"
        private const val EXTRA_OPERATION_ID = "operation_id"
        private const val JOB_ID_BASE = 1_600_000
        private const val JOB_ID_RANGE = 100_000

        /**
         * Schedules the OS-visible UIDT lease. Returns false when UIDT is unavailable or scheduling
         * is rejected, allowing the caller to fall back to the foreground-service path.
         */
        fun schedule(context: Context, operationId: Long): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
            val extras = PersistableBundle().apply { putLong(EXTRA_OPERATION_ID, operationId) }
            val jobInfo = JobInfo.Builder(
                jobId(operationId),
                ComponentName(context, SmbTransferJobService::class.java),
            )
                .setExtras(extras)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setUserInitiated(true)
                .build()
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            return runCatching { scheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS }
                .getOrDefault(false)
        }

        private fun jobId(operationId: Long): Int =
            JOB_ID_BASE + Math.floorMod(operationId, JOB_ID_RANGE.toLong()).toInt()
    }
}

private fun OpState.isTerminal(): Boolean =
    this == OpState.DONE || this == OpState.FAILED || this == OpState.CANCELLED
