package app.local1st.files.core.ops

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.FsRegistry
import app.local1st.files.core.fs.LocalFileSystem
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XFileSystem
import app.local1st.files.core.fs.XId
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CopyMediaVisibilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun smbToLocalPngIsIndexedOnlyAfterStreamIsClosed() = runBlocking {
        val targetDir = temporaryFolder.newFolder("Pictures")
        val scans = mutableListOf<String>()
        val indexer = LocalMediaIndexer(listOf(temporaryFolder.root)) { path ->
            assertEquals("finished", File(path).readText())
            scans += path
        }
        val engine = engine(FakeNasFileSystem { ByteArrayInputStream("finished".toByteArray()) }, indexer)
        val running = engine.submit(FileOp.Copy(listOf(nasFile()), localDir(targetDir)))

        withTimeout(5_000) { running.progress.first { it.state == OpState.DONE } }

        assertEquals("finished", File(targetDir, "photo.png").readText())
        assertEquals(listOf(File(targetDir, "photo.png").canonicalPath), scans)
    }

    @Test
    fun copyingNasDirectoryPublishesEachCompletedImage() = runBlocking {
        val targetDir = temporaryFolder.newFolder("Pictures")
        val dir = XEntry(id = "smb://conn/Album", name = "Album", isDir = true)
        val first = XEntry(id = "smb://conn/Album/one.png", name = "one.png", isDir = false, size = 4)
        val second = XEntry(id = "smb://conn/Album/two.jpg", name = "two.jpg", isDir = false, size = 4)
        val scans = mutableListOf<String>()
        val nas = FakeNasFileSystem(
            source = { ByteArrayInputStream("data".toByteArray()) },
            children = mapOf(dir.id to listOf(first, second)),
        )
        val engine = engine(nas, LocalMediaIndexer(listOf(temporaryFolder.root)) { scans += it })
        val running = engine.submit(FileOp.Copy(listOf(dir), localDir(targetDir)))

        withTimeout(5_000) { running.progress.first { it.state == OpState.DONE } }

        assertEquals(listOf("one.png", "two.jpg"), scans.map { File(it).name })
    }

    @Test
    fun failedTransferDeletesPartialFileAndDoesNotIndex() = runBlocking {
        val targetDir = temporaryFolder.newFolder("Pictures")
        val scans = mutableListOf<String>()
        val brokenStream = {
            object : InputStream() {
                private var calls = 0
                override fun read(): Int = throw IOException("NAS disconnected")
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    if (calls++ == 0) {
                        bytes[off] = 1
                        return 1
                    }
                    throw IOException("NAS disconnected")
                }
            }
        }
        val engine = engine(FakeNasFileSystem(brokenStream), LocalMediaIndexer(listOf(temporaryFolder.root)) {
            scans += it
        })
        val running = engine.submit(FileOp.Copy(listOf(nasFile()), localDir(targetDir)))

        withTimeout(5_000) { running.progress.first { it.state == OpState.FAILED } }

        assertFalse(File(targetDir, "photo.png").exists())
        assertTrue(scans.isEmpty())
    }

    @Test
    fun localFastMovePublishesNewDestination() = runBlocking {
        val sourceDir = temporaryFolder.newFolder("Source")
        val destinationDir = temporaryFolder.newFolder("Pictures")
        val source = File(sourceDir, "photo.png").apply { writeText("finished") }
        val scanned = mutableListOf<String>()
        val indexer = LocalMediaIndexer(listOf(temporaryFolder.root)) { scanned += it }
        val engine = engine(FakeNasFileSystem { ByteArrayInputStream(byteArrayOf()) }, indexer)
        val running = engine.submit(
            FileOp.Copy(
                sources = listOf(XEntry(id = XId.file(source.path), name = source.name, isDir = false)),
                destDir = localDir(destinationDir),
                move = true,
            ),
        )

        withTimeout(5_000) { running.progress.first { it.state == OpState.DONE } }

        assertFalse(source.exists())
        assertTrue(File(destinationDir, "photo.png").exists())
        assertTrue(scanned.contains(File(destinationDir, "photo.png").canonicalPath))
    }

    private fun engine(nas: XFileSystem, indexer: LocalMediaIndexer): DefaultOperationEngine {
        val registry = FsRegistry().apply {
            register(LocalFileSystem())
            register(nas)
        }
        return DefaultOperationEngine(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
            registry = registry,
            cacheDir = temporaryFolder.root,
            mediaIndexer = indexer,
        )
    }

    private fun nasFile() = XEntry(
        id = "smb://conn/photo.png",
        name = "photo.png",
        isDir = false,
        size = 8L,
        kind = EntryKind.FILE,
    )

    private fun localDir(dir: File) = XEntry(
        id = XId.file(dir.absolutePath),
        name = dir.name,
        isDir = true,
        kind = EntryKind.DIR,
    )

    private class FakeNasFileSystem(
        private val source: () -> InputStream,
        private val children: Map<String, List<XEntry>> = emptyMap(),
    ) : XFileSystem {
        override val scheme = XId.SCHEME_SMB
        override fun list(dir: XEntry): List<XEntry> = children[dir.id].orEmpty()
        override fun stat(id: String): XEntry? = null
        override fun openIn(entry: XEntry): InputStream = source()
        override fun openOut(parentDir: XEntry, name: String): OutputStream = error("unsupported")
        override fun createFile(parentDir: XEntry, name: String): XEntry = error("unsupported")
        override fun mkdir(parentDir: XEntry, name: String): XEntry = error("unsupported")
        override fun delete(entry: XEntry) = Unit
        override fun rename(entry: XEntry, newName: String): XEntry = error("unsupported")
        override fun canWrite(entry: XEntry) = false
    }
}
