package app.local1st.files.core.fs

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageMetricsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun storageSpaceCalculatesUsedBytesAndFraction() {
        val space = StorageSpace(totalBytes = 1_000L, freeBytes = 250L)

        assertEquals(750L, space.usedBytes)
        assertEquals(0.75f, space.usedFraction, 0.0001f)
    }

    @Test
    fun storageSpaceClampsInconsistentFreeSpace() {
        val space = StorageSpace(totalBytes = 100L, freeBytes = 150L)

        assertEquals(0L, space.usedBytes)
        assertEquals(0f, space.usedFraction, 0.0001f)
    }

    @Test
    fun directorySizeSumsNestedRegularFiles() {
        val root = temporaryFolder.newFolder("root")
        root.resolve("a.bin").writeBytes(ByteArray(3))
        root.resolve("nested").mkdir()
        root.resolve("nested/b.bin").writeBytes(ByteArray(7))
        root.resolve("nested/deeper").mkdir()
        root.resolve("nested/deeper/c.bin").writeBytes(ByteArray(11))
        val entry = XEntry(
            id = XId.file(root.absolutePath),
            name = root.name,
            isDir = true,
        )

        assertEquals(21L, LocalFileSystem().directorySize(entry))
    }

    @Test
    fun directorySizeDoesNotFollowDirectorySymlinks() {
        val root = temporaryFolder.newFolder("root")
        val nested = root.resolve("nested").apply { mkdir() }
        nested.resolve("inside.bin").writeBytes(ByteArray(13))
        val outside = temporaryFolder.newFolder("outside")
        outside.resolve("outside.bin").writeBytes(ByteArray(29))
        Files.createSymbolicLink(nested.resolve("outside-link").toPath(), outside.toPath())
        Files.createSymbolicLink(nested.resolve("loop-back").toPath(), root.toPath())
        val entry = XEntry(
            id = XId.file(root.absolutePath),
            name = root.name,
            isDir = true,
        )

        assertEquals(13L, LocalFileSystem().directorySize(entry))
    }

    @Test
    fun directorySizeRejectsFiles() {
        val file = temporaryFolder.newFile("one.bin").apply { writeBytes(ByteArray(5)) }
        val entry = XEntry(
            id = XId.file(file.absolutePath),
            name = file.name,
            isDir = false,
            size = file.length(),
        )

        assertNull(LocalFileSystem().directorySize(entry))
    }
}
