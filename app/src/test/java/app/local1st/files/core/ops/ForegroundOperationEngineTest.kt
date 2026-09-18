package app.local1st.files.core.ops

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ForegroundOperationEngineTest {
    @Test
    fun submitStartsKeepAliveSynchronouslyAfterDelegating() {
        val delegate = FakeOperationEngine()
        var keepAliveStarts = 0
        val engine = ForegroundOperationEngine(delegate) {
            assertEquals(1, delegate.submitCalls)
            keepAliveStarts++
        }
        val op = FileOp.Delete(listOf(entry("file://source.txt")))

        val running = engine.submit(op)

        assertSame(delegate.running, running)
        assertEquals(1, delegate.submitCalls)
        assertEquals(1, keepAliveStarts)
    }

    private fun entry(id: String) = XEntry(
        id = id,
        name = id.substringAfterLast('/'),
        isDir = false,
        kind = EntryKind.FILE,
    )

    private class FakeOperationEngine : OperationEngine {
        override val active = MutableStateFlow<List<RunningOp>>(emptyList())
        override val events: SharedFlow<OpEvent> = MutableSharedFlow()
        val running = FakeRunningOp()
        var submitCalls = 0
            private set

        override fun submit(op: FileOp): RunningOp {
            submitCalls++
            return running
        }
    }

    private class FakeRunningOp : RunningOp {
        override val id: Long = 1L
        override val progress: StateFlow<OpProgress> = MutableStateFlow(OpProgress(title = "test"))
        override val pendingConflict: StateFlow<Conflict?> = MutableStateFlow(null)
        override fun resolveConflict(resolution: ConflictResolution) = Unit
        override fun cancel() = Unit
    }
}
