package app.local1st.files.core.fs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Only instrumented capacity operations; no claim about other SMB paths. */
internal enum class SmbCapacityOperation {
    SHARE_CAPACITY,
    DIRECTORY_SIZE,
}

internal enum class SmbCapacityEngine {
    RUST,
    SMBJ,
}

internal data class SmbBackendExecution(
    val engine: SmbCapacityEngine,
    val connectionName: String,
)

/**
 * Last completed capacity call in the current app process, grouped by operation.
 * Stored in memory, never persisted. Does not contain credentials, paths or server addresses.
 */
internal class SmbBackendExecutionTracker {
    private val _lastSuccess = MutableStateFlow<Map<SmbCapacityOperation, SmbBackendExecution>>(emptyMap())
    val lastSuccess: StateFlow<Map<SmbCapacityOperation, SmbBackendExecution>> = _lastSuccess.asStateFlow()

    fun record(operation: SmbCapacityOperation, engine: SmbCapacityEngine, connectionName: String) {
        _lastSuccess.update { current ->
            current + (operation to SmbBackendExecution(engine, connectionName))
        }
    }
}

internal val smbBackendExecutions = SmbBackendExecutionTracker()
