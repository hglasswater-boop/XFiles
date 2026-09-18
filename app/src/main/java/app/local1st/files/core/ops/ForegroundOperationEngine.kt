package app.local1st.files.core.ops

import app.local1st.files.core.fs.XId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Starts the operation keep-alive immediately on the same user action that submits the work and
 * classifies which operations actually need network-aware background execution.
 *
 * Relying only on an asynchronous observer of [OperationEngine.active] leaves a small race where
 * the activity can move to the background before Android receives the keep-alive request.
 * Keeping this as a decorator avoids coupling the copy engine itself to Android service APIs.
 */
internal class ForegroundOperationEngine(
    private val delegate: OperationEngine,
    scope: CoroutineScope,
    private val startKeepAlive: (running: RunningOp, usesSmb: Boolean) -> Unit,
) : OperationEngine by delegate {
    private val networkOpIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _networkKeepAliveRequired = MutableStateFlow(false)
    override val networkKeepAliveRequired: StateFlow<Boolean> = _networkKeepAliveRequired

    init {
        scope.launch {
            delegate.active.collect(::reconcileNetworkOps)
        }
    }

    override fun submit(op: FileOp): RunningOp {
        val running = delegate.submit(op)
        val usesSmb = op.usesSmb()
        if (usesSmb) {
            networkOpIds.update { it + running.id }
            _networkKeepAliveRequired.value = true
            // The delegate starts work before submit() returns. A very short operation can already
            // have left active by this point, so reconcile once synchronously after registration.
            reconcileNetworkOps(delegate.active.value)
        }
        startKeepAlive(running, usesSmb)
        return running
    }

    private fun reconcileNetworkOps(active: List<RunningOp>) {
        val activeIds = active.mapTo(HashSet()) { it.id }
        networkOpIds.update { ids -> ids.intersect(activeIds) }
        _networkKeepAliveRequired.value = networkOpIds.value.isNotEmpty()
    }

    private fun FileOp.usesSmb(): Boolean = when (this) {
        is FileOp.Copy -> sources.any { it.scheme == XId.SCHEME_SMB } || destDir.scheme == XId.SCHEME_SMB
        is FileOp.Delete -> sources.any { it.scheme == XId.SCHEME_SMB }
        is FileOp.FlattenOneLevel -> directory.scheme == XId.SCHEME_SMB
        is FileOp.Compress -> sources.any { it.scheme == XId.SCHEME_SMB } || destDir.scheme == XId.SCHEME_SMB
        is FileOp.Extract -> archive.scheme == XId.SCHEME_SMB || destDir.scheme == XId.SCHEME_SMB
    }
}
