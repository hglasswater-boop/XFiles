package app.local1st.files.core.fs

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbDirectoryDeleteTest {
    @Test
    fun deletesEmptyDirectory() {
        val ops = FakeOperations(mapOf("folder" to emptyList()))

        deleteSmbDirectoryRecursively("folder", ops)

        assertEquals(listOf("list:folder", "dir:folder"), ops.events)
    }

    @Test
    fun deletesNestedFilesAndDirectoriesInPostorder() {
        val ops = FakeOperations(
            mapOf(
                "folder" to listOf(
                    SmbDeleteDirectoryEntry("one.mp4", isDirectory = false),
                    SmbDeleteDirectoryEntry("sub", isDirectory = true),
                ),
                "folder\\sub" to listOf(
                    SmbDeleteDirectoryEntry("two.mp4", isDirectory = false),
                    SmbDeleteDirectoryEntry("deep", isDirectory = true),
                ),
                "folder\\sub\\deep" to listOf(
                    SmbDeleteDirectoryEntry("three.mp4", isDirectory = false),
                ),
            ),
        )

        deleteSmbDirectoryRecursively("folder", ops)

        assertEquals(
            listOf(
                "list:folder",
                "file:folder\\one.mp4",
                "list:folder\\sub",
                "file:folder\\sub\\two.mp4",
                "list:folder\\sub\\deep",
                "file:folder\\sub\\deep\\three.mp4",
                "dir:folder\\sub\\deep",
                "dir:folder\\sub",
                "dir:folder",
            ),
            ops.events,
        )
    }

    @Test
    fun doesNotTraverseReparsePointDirectory() {
        val ops = FakeOperations(
            mapOf(
                "folder" to listOf(
                    SmbDeleteDirectoryEntry(
                        "link",
                        isDirectory = true,
                        isReparsePoint = true,
                    ),
                ),
            ),
        )

        deleteSmbDirectoryRecursively("folder", ops)

        assertEquals(listOf("list:folder", "dir:folder\\link", "dir:folder"), ops.events)
    }

    @Test
    fun skipsDotEntriesAndRejectsUnsafeChildNamesBeforeDeleting() {
        val safeOps = FakeOperations(
            mapOf(
                "folder" to listOf(
                    SmbDeleteDirectoryEntry(".", isDirectory = true),
                    SmbDeleteDirectoryEntry("..", isDirectory = true),
                    SmbDeleteDirectoryEntry("safe.txt", isDirectory = false),
                ),
            ),
        )

        deleteSmbDirectoryRecursively("folder", safeOps)

        assertEquals(listOf("list:folder", "file:folder\\safe.txt", "dir:folder"), safeOps.events)

        for (invalid in listOf("", "child\\escape", "child/escape")) {
            val badOps = FakeOperations(
                mapOf(
                    "folder" to listOf(
                        SmbDeleteDirectoryEntry("safe.txt", isDirectory = false),
                        SmbDeleteDirectoryEntry(invalid, isDirectory = false),
                    ),
                ),
            )
            assertThrows(IOException::class.java) {
                deleteSmbDirectoryRecursively("folder", badOps)
            }
            assertEquals(listOf("list:folder"), badOps.events)
        }
    }

    @Test
    fun rejectsShareRootWithoutDoingAnyIo() {
        val ops = FakeOperations(emptyMap())

        assertThrows(IOException::class.java) {
            deleteSmbDirectoryRecursively("", ops)
        }

        assertEquals(emptyList<String>(), ops.events)
    }

    @Test
    fun stopsOnFirstFailureWithoutDeletingParents() {
        val failure = IOException("STATUS_ACCESS_DENIED")
        val ops = FakeOperations(
            directories = mapOf(
                "folder" to listOf(
                    SmbDeleteDirectoryEntry("locked.mp4", isDirectory = false),
                    SmbDeleteDirectoryEntry("later.mp4", isDirectory = false),
                ),
            ),
            fileFailure = failure,
        )

        val thrown = assertThrows(IOException::class.java) {
            deleteSmbDirectoryRecursively("folder", ops)
        }

        assertSame(failure, thrown)
        assertEquals(listOf("list:folder", "file:folder\\locked.mp4"), ops.events)
    }

    private class FakeOperations(
        private val directories: Map<String, List<SmbDeleteDirectoryEntry>>,
        private val fileFailure: IOException? = null,
    ) : SmbDirectoryDeleteOperations {
        val events = mutableListOf<String>()

        override fun list(path: String): List<SmbDeleteDirectoryEntry> {
            events += "list:$path"
            return directories[path] ?: error("Unexpected traversal: $path")
        }

        override fun deleteFile(path: String) {
            events += "file:$path"
            fileFailure?.let { throw it }
        }

        override fun deleteDirectory(path: String) {
            events += "dir:$path"
        }
    }
}
