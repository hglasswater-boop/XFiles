package app.local1st.files.core.ops

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.local1st.files.di.Graph

/** Cancels one user-visible file operation from a background-transfer notification action. */
class OperationCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val operationId = intent.getLongExtra(EXTRA_OPERATION_ID, INVALID_OPERATION_ID)
        if (operationId == INVALID_OPERATION_ID) return
        Graph.opEngine.active.value.firstOrNull { it.id == operationId }?.cancel()
    }

    companion object {
        private const val ACTION_CANCEL = "app.local1st.files.ops.CANCEL_OPERATION"
        private const val EXTRA_OPERATION_ID = "operation_id"
        private const val INVALID_OPERATION_ID = -1L

        fun pendingIntent(context: Context, operationId: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            operationId.hashCode(),
            Intent(context, OperationCancelReceiver::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_OPERATION_ID, operationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
