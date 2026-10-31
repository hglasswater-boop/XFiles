package app.local1st.files.core.ops

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XFileSystem
import app.local1st.files.core.fs.XId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies the operation engine's streaming fallback, used when the SMB filesystem
 * refuses a cross-share fast rename. This is not an SMB COPYCHUNK interoperability test.
 */
class SmbCrossShareMoveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun successfulStreamingMoveDeletesSourceOnlyAfterDestinationClose() = runBlocking {
        val fs = FakeSmbFileSystem()
        val data = byteArrayOf(1, 2, 3, 4)
        fs.files[SOURCE.id] = data

        val running = engine(fs).submit(FileOp.Copy(listOf(SOURCE), DESTINATION, move = true))
        withTimeout(5_000) { running.progress.first { it.state == OpState.DONE } }

        assertArrayEquals(data, fs.files[XId.child(DESTINATION, SOURCE.name)])
        assertFalse(fs.files.containsKey(SOURCE.id))
        assertEquals(listOf("closeDestination", "deleteSource"), fs.events)
    }

    @Test
    fun failedStreamingMoveRemovesPartialDestinationButPreservesSource() = runBlocking {
        val fs = FakeSmbFileSystem(failWrite = true)
        val data = byteArrayOf(1, 2, 3, 4)
        fs.files[SOURCE.id] = data

        val running = engine(fs).submit(FileOp.Copy(listOf(SOURCE), DESTINATION, move = true))
        withTimeout(5_000) { running.progress.first { it.state == OpState.FAILED } }

        assertArrayEquals(data, fs.files[SOURCE.id])
        assertFalse(fs.files.containsKey(XId.child(DESTINATION, SOURCE.name)))
        assertFalse(fs.events.contains("deleteSource"))
        assertTrue(fs.events.contains("deletePartial"))
    }

    private fun engine(fs: FakeSmbFileSystem): DefaultOperationEngine =
        DefaultOperationEngine(
            CoroutineScope(Dispatchers.IO),
            FsRegistry().apply { register(fs) },
            temp.root,
        )

    private class FakeSmbFileSystem(private val failWrite: Boolean = false) : XFileSystem {
        override val scheme: String = XId.SCHEME_SMB
        val files = mutableMapOf<String, ByteArray>()
        val events = mutableListOf<String>()

        override fun list(dir: XEntry): List<XEntry> = files.keys
            .filter { XId.parent(it) == dir.id }
            .map { id -> file(id, files.getValue(id).size.toLong()) }

        override fun stat(id: String): XEntry? =
            files[id]?.let { file(id, it.size.toLong()) }

        override fun openIn(entry: XEntry): InputStream =
            ByteArrayInputStream(files.getValue(entry.id))

        override fun openOut(parentDir: XEntry, name: String): OutputStream {
            val id = XId.child(parentDir, name)
            files[id] = byteArrayOf()
            return object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    super.write(b, off, if (failWrite) minOf(1, len) else len)
                    files[id] = toByteArray()
                    if (failWrite) throw IOException("Simulated SMB write failure")
                }

                override fun close() {
                    super.close()
                    events += "closeDestination"
                }
            }
        }

        override fun createFile(parentDir: XEntry, name: String): XEntry =
            error("Not needed")

        override fun mkdir(parentDir: XEntry, name: String): XEntry =
            error("Not needed")

        override fun delete(entry: XEntry) {
            files.remove(entry.id)
            events += if (entry.id == SOURCE.id) "deleteSource" else "deletePartial"
        }

        override fun rename(entry: XEntry, newName: String): XEntry =
            error("Cross-share rename must not be invoked by the fake")

        override fun canWrite(entry: XEntry): Boolean = true
    }

    private companion object {
        val SOURCE = file("smb://video_a/clip.mkv", 4L)
        val DESTINATION = XEntry(
            id = "smb://video",
            name = "video",
            isDir = true,
            kind = EntryKind.DIR,
        )

        fun file(id: String, size: Long): XEntry = XEntry(
            id = id,
            name = id.substringAfterLast('/'),
            isDir = false,
            size = size,
            kind = EntryKind.FILE,
        )
    }
}
