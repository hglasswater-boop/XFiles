package app.local1st.files.core.ops

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMediaIndexerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val root: File get() = temporaryFolder.root
    private val scanned = mutableListOf<String>()
    private fun indexer() = LocalMediaIndexer(listOf(root)) { scanned += it }

    @Test
    fun completedPngAndOtherMediaFormatsArePublished() {
        val dir = File(root, "Pictures/NAS").apply { mkdirs() }
        val indexer = indexer()

        listOf("photo.PNG", "image.jpg", "clip.mp4", "audio.mp3").forEach { name ->
            val file = File(dir, name).apply { writeText("complete") }
            indexer.publish(file)
        }

        assertEquals(
            listOf("photo.PNG", "image.jpg", "clip.mp4", "audio.mp3"),
            scanned.map { File(it).name },
        )
    }

    @Test
    fun onlyExistingMediaFilesArePublished() {
        val indexer = indexer()
        val dir = File(root, "Pictures").apply { mkdirs() }
        indexer.publish(File(dir, "not-created.png"))
        indexer.publish(File(dir, "folder.jpg").apply { mkdirs() })
        indexer.publish(File(dir, "notes.txt").apply { writeText("complete") })
        assertTrue(scanned.isEmpty())
    }

    @Test
    fun noMediaInParentOrRootExcludesFiles() {
        val indexer = indexer()
        val hiddenAlbum = File(root, "Pictures/Private").apply { mkdirs() }
        File(hiddenAlbum, ".nomedia").writeText("")
        indexer.publish(File(hiddenAlbum, "photo.png").apply { writeText("complete") })
        File(root, ".nomedia").writeText("")
        indexer.publish(File(root, "photo.png").apply { writeText("complete") })
        assertTrue(scanned.isEmpty())
    }

    @Test
    fun hiddenPathsAndAndroidPrivateDirectoriesAreExcluded() {
        val indexer = indexer()
        listOf(
            ".hidden.png",
            ".private/photo.png",
            "Android/data/example.app/files/photo.png",
            "Android/obb/example.app/photo.png",
        ).forEach { relative ->
            val file = File(root, relative)
            file.parentFile!!.mkdirs()
            file.writeText("complete")
            indexer.publish(file)
        }
        assertTrue(scanned.isEmpty())
    }

    @Test
    fun unrelatedRootWithSameTextualPrefixIsNotPublic() {
        val indexer = indexer()
        val outside = File(root.parentFile, "${root.name}-outside/photo.png")
        outside.parentFile!!.mkdirs()
        outside.writeText("complete")
        try {
            indexer.publish(outside)
            assertTrue(scanned.isEmpty())
        } finally {
            outside.delete()
            outside.parentFile!!.delete()
        }
    }
}
