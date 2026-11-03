package app.local1st.files.core.fs.rust

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RustDirectoryPageTest {
    @Test
    fun unicodeAndAttributesAndFiletimeArePreserved() {
        val data = page(
            record("映画 01.mkv", 0x02, 1234567, WINDOWS_EPOCH_TICKS + 10_000_000),
            record("folder", 0x10, 0, WINDOWS_EPOCH_TICKS),
        )
        val parsed = RustDirectoryPage.decode(data)
        assertEquals(2, parsed.size)
        assertEquals("映画 01.mkv", parsed[0].name)
        assertEquals(1234567, parsed[0].size)
        assertEquals(1000L, parsed[0].modifiedMillis)
        assertTrue(parsed[0].hidden)
        assertFalse(parsed[0].directory)
        assertTrue(parsed[1].directory)
        assertEquals(0L, parsed[1].modifiedMillis)
    }

    @Test
    fun emptyPageMeansEndOfScanOnlyWhenNoTrailingData() {
        assertTrue(RustDirectoryPage.decode(page()).isEmpty())
        assertThrows(IOException::class.java) { RustDirectoryPage.decode(page() + byteArrayOf(1)) }
    }

    @Test
    fun rejectsTruncatedInvalidVersionAndUnknownTrailingData() {
        val valid = page(record("sample", 0, 10, WINDOWS_EPOCH_TICKS))
        assertThrows(IOException::class.java) { RustDirectoryPage.decode(byteArrayOf()) }
        assertThrows(IOException::class.java) { RustDirectoryPage.decode(valid.copyOfRange(0, valid.size - 1)) }
        assertThrows(IOException::class.java) { RustDirectoryPage.decode(valid + byteArrayOf(0)) }
        assertThrows(IOException::class.java) { RustDirectoryPage.decode(valid.copyOf().apply { this[0] = 'Z'.code.toByte() }) }
    }

    @Test
    fun rejectsInvalidUtf8NegativeSizeAndTime() {
        val valid = page(record("a", 0, 0, WINDOWS_EPOCH_TICKS))
        val badUtf8 = valid.copyOf().apply { this[12] = 0xff.toByte() }
        assertThrows(IOException::class.java) { RustDirectoryPage.decode(badUtf8) }

        val negativeSize = page(record("a", 0, -1, WINDOWS_EPOCH_TICKS))
        assertThrows(IOException::class.java) { RustDirectoryPage.decode(negativeSize) }

        val negativeTime = page(record("a", 0, 0, -1))
        assertThrows(IOException::class.java) { RustDirectoryPage.decode(negativeTime) }
    }

    @Test
    fun cursorCanAccumulateManyValidPagesWithoutWholeFolderNativeLimit() {
        val first = RustDirectoryPage.decode(page(record("one", 0, 1, WINDOWS_EPOCH_TICKS)))
        val second = RustDirectoryPage.decode(page(record("two", 0, 2, WINDOWS_EPOCH_TICKS)))
        assertEquals(listOf("one", "two"), (first + second).map { it.name })
        assertTrue(RustDirectoryPage.decode(page()).isEmpty())
    }

    private fun page(vararg records: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(8 + records.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf('X'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte()))
        buffer.putInt(records.size)
        records.forEach(buffer::put)
        return buffer.array()
    }

    private fun record(name: String, attributes: Int, size: Long, filetime: Long): ByteArray {
        val utf8 = name.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + utf8.size + 28).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(utf8.size)
        buffer.put(utf8)
        buffer.putInt(attributes)
        buffer.putLong(size)
        buffer.putLong(filetime)
        buffer.putLong(filetime)
        return buffer.array()
    }

    companion object {
        private const val WINDOWS_EPOCH_TICKS = 116_444_736_000_000_000L
    }
}
