package app.local1st.files.core.fs.rust

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One fully validated entry from a single signed SMB QUERY_DIRECTORY response. */
internal data class RustDirectoryEntry(
    val name: String,
    val attributes: Int,
    val size: Long,
    val createdMillis: Long,
    val modifiedMillis: Long,
) {
    val directory: Boolean get() = attributes and 0x10 != 0
    val hidden: Boolean get() = attributes and 0x02 != 0
}

/**
 * Strict XFL1 decoder. A page has no folder-wide size/count limit; callers read pages until
 * the signed SMB server returns an empty page. A malformed page never means EOF.
 */
internal object RustDirectoryPage {
    private const val FILETIME_UNIX_OFFSET = 116_444_736_000_000_000L
    private const val FILETIME_TICKS_PER_MILLISECOND = 10_000L
    private const val FIXED_RECORD_BYTES = 4 + 4 + 8 + 8 + 8

    fun decode(bytes: ByteArray): List<RustDirectoryEntry> {
        if (bytes.size < 8) throw IOException("Rust SMB directory page header is truncated")
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (data.get() != 'X'.code.toByte() ||
            data.get() != 'F'.code.toByte() ||
            data.get() != 'L'.code.toByte() ||
            data.get() != '1'.code.toByte()
        ) {
            throw IOException("Unsupported Rust SMB directory page version")
        }
        val count = data.int.toLong() and 0xffff_ffffL
        if (count > data.remaining() / FIXED_RECORD_BYTES) {
            throw IOException("Rust SMB directory page record count exceeds payload")
        }
        val entries = ArrayList<RustDirectoryEntry>(count.toInt())
        repeat(count.toInt()) {
            if (data.remaining() < FIXED_RECORD_BYTES) {
                throw IOException("Rust SMB directory record is truncated")
            }
            val nameLength = data.int.toLong() and 0xffff_ffffL
            if (nameLength > data.remaining().toLong() - 28L) {
                throw IOException("Rust SMB directory record has invalid name length")
            }

            val nameStart = data.position()
            val name = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, nameStart, nameLength.toInt()))
                    .toString()
            } catch (error: CharacterCodingException) {
                throw IOException("Rust SMB directory entry contains invalid UTF-8", error)
            }
            data.position(nameStart + nameLength.toInt())
            val attributes = data.int
            val length = data.long
            if (length < 0) throw IOException("Rust SMB directory entry size exceeds Long range")
            val created = filetimeMillis(data.long)
            val modified = filetimeMillis(data.long)
            entries += RustDirectoryEntry(name, attributes, length, created, modified)
        }
        if (data.hasRemaining()) {
            throw IOException("Rust SMB directory page has trailing bytes")
        }
        return entries
    }

    private fun filetimeMillis(ticks: Long): Long {
        if (ticks < 0) throw IOException("Rust SMB FILETIME exceeds signed Long range")
        return Math.floorDiv(ticks - FILETIME_UNIX_OFFSET, FILETIME_TICKS_PER_MILLISECOND)
    }
}
