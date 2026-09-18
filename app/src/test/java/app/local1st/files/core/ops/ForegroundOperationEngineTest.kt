package app.local1st.files.core.ops

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundOperationEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun submitStartsKeepAliveSynchronouslyAfterDelegating() {
        val delegate = FakeOperationEngine()
        var keepAliveStarts = 0
        val engine = ForegroundOperationEngine(delegate, scope) {
            assertEquals(1, delegate.submitCalls)
            keepAliveStarts++
        }
        val op = FileOp.Delete(listOf(entry("file://source.txt")))

        val running = engine.submit(op)

        assertSame(delegate.running, running)
        assertEquals(1, delegate.submitCalls)
        assertEquals(1, keepAliveStarts)
        assertFalse(engine.networkKeepAliveRequired.value)
    }

    @Test
    fun smbOperationKeepsNetworkAliveUntilOperationLeavesActiveList() {
        val delegate = FakeOperationEngine()
        val engine = ForegroundOperationEngine(delegate, scope) { }
        val op = smbCopy()

        engine.submit(op)
        assertTrue(engine.networkKeepAliveRequired.value)

        delegate.active.value = emptyList()
        assertFalse(engine.networkKeepAliveRequired.value)
    }

    @Test
    fun smbOperationThatFinishesInsideSubmitDoesNotLeaveNetworkKeepAliveStuck() {
        val delegate = FakeOperationEngine(keepSubmittedOpActive = false)
        val engine = ForegroundOperationEngine(delegate, scope) { }

        engine.submit(smbCopy())

        assertFalse(engine.networkKeepAliveRequired.value)
    }

    private fun smbCopy() = FileOp.Copy(
        sources = listOf(entry("smb://server/source.bin")),
        destDir = entry("file://destination", isDir = true),
    )

    private fun entry(id: String, isDir: Boolean = false) = XEntry(
        id = id,
        name = id.substringAfterLast('/'),
        isDir = isDir,
        kind = if (isDir) EntryKind.DIR else EntryKind.FILE,
    )

    private class FakeOperationEngine(
        private val keepSubmittedOpActive: Boolean = true,
    ) : OperationEngine {
        override val active = MutableStateFlow<List<RunningOp>>(emptyList())
        override val events: SharedFlow<OpEvent> = MutableSharedFlow()
        val running = FakeRunningOp()
        var submitCalls = 0
            private set

        override fun submit(op: FileOp): RunningOp {
            submitCalls++
            active.value = if (keepSubmittedOpActive) listOf(running) else emptyList()
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
