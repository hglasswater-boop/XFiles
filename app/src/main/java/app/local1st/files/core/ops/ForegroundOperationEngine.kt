package app.local1st.files.core.ops

/**
 * Starts the operation keep-alive immediately on the same user action that submits the work.
 *
 * Relying only on an asynchronous observer of [OperationEngine.active] leaves a small race where
 * the activity can move to the background before Android receives startForegroundService().
 * Keeping this as a decorator avoids coupling the copy engine itself to Android service APIs.
 */
internal class ForegroundOperationEngine(
    private val delegate: OperationEngine,
    private val startKeepAlive: () -> Unit,
) : OperationEngine by delegate {
    override fun submit(op: FileOp): RunningOp {
        val running = delegate.submit(op)
        startKeepAlive()
        return running
    }
}
