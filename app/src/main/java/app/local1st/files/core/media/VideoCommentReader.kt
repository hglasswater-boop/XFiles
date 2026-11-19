package app.local1st.files.core.media

import android.net.Uri
import app.local1st.files.core.fs.SmbRandomAccessFile
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads QuickTime/iTunes MP4 comment tags without decoding media or loading video payloads.
 * The reader takes positional reads, so a trailing moov atom works on both local and SMB files.
 */
internal object Mp4CommentParser {
    private const val MAX_TAG_BYTES = 65_536
    private const val MAX_BOXES_PER_LEVEL = 4096

    fun parse(length: Long, read: (Long, Int) -> ByteArray?): String? {
        if (length < 8) return null
        var comment: String? = null
        var description: String? = null

        fun uint32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xff) shl 24) or
                ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or
                (bytes[offset + 3].toLong() and 0xff)

        fun tagText(start: Long, end: Long): String? {
            var pos = start
            while (pos <= end - 8) {
                val header = read(pos, 8)?.takeIf { it.size == 8 } ?: return null
                val size = uint32(header, 0)
                if (size < 8 || size > end - pos) return null
                if (String(header, 4, 4, StandardCharsets.ISO_8859_1) == "data") {
                    val count = size - 16
                    if (count <= 0 || count > MAX_TAG_BYTES) return null
                    val payload = read(pos + 16, count.toInt())
                        ?.takeIf { it.size == count.toInt() } ?: return null
                    return String(payload, Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
                }
                pos += size
            }
            return null
        }

        fun walk(start: Long, end: Long, parent: String, depth: Int, recurse: (Long, Long, String, Int) -> Unit) {
            if (depth > 7 || start < 0 || end > length) return
            var pos = start
            var boxes = 0
            while (pos <= end - 8 && boxes++ < MAX_BOXES_PER_LEVEL) {
                val header = read(pos, 8)?.takeIf { it.size == 8 } ?: return
                val type = String(header, 4, 4, StandardCharsets.ISO_8859_1)
                val size32 = uint32(header, 0)
                val extended = if (size32 == 1L) read(pos + 8, 8)?.takeIf { it.size == 8 } else null
                if (size32 == 1L && extended == null) return
                val boxSize = when (size32) {
                    0L -> end - pos
                    1L -> {
                        var value = 0L
                        for (byte in extended!!) {
                            if (value > (Long.MAX_VALUE ushr 8)) return
                            value = (value shl 8) or (byte.toLong() and 0xff)
                        }
                        value
                    }
                    else -> size32
                }
                val headerSize = if (size32 == 1L) 16L else 8L
                if (boxSize < headerSize || boxSize > end - pos) return
                val payload = pos + headerSize
                val boxEnd = pos + boxSize
                when {
                    parent == "ilst" && type == "©cmt" -> {
                        if (comment == null) comment = tagText(payload, boxEnd)
                    }
                    parent == "ilst" && type == "©des" -> {
                        if (description == null) description = tagText(payload, boxEnd)
                    }
                    type == "moov" && parent == "root" ||
                        type == "udta" && parent == "moov" ||
                        type == "ilst" && parent == "meta" -> recurse(payload, boxEnd, type, depth + 1)
                    type == "meta" && parent == "udta" && boxEnd - payload >= 4 ->
                        recurse(payload + 4, boxEnd, type, depth + 1)
                }
                pos = boxEnd
            }
        }

        // Recursive lambda permits a small, pure parser without allocating an MP4 atom tree.
        lateinit var visit: (Long, Long, String, Int) -> Unit
        visit = { start, end, parent, depth -> walk(start, end, parent, depth, visit) }
        visit(0, length, "root", 0)
        return comment ?: description
    }
}

object VideoCommentReader {
    suspend fun read(entry: XEntry): String? = withContext(Dispatchers.IO) {
        if (entry.isDir || !entry.name.substringAfterLast('.', "").lowercase()
                .let { it == "mp4" || it == "m4v" || it == "mov" }) {
            return@withContext null
        }
        runCatching {
            when {
                entry.localPath != null || entry.scheme == XId.SCHEME_FILE -> {
                    RandomAccessFile(entry.localPath ?: entry.path, "r").use { file ->
                        Mp4CommentParser.parse(file.length()) { offset, count ->
                            ByteArray(count).also { bytes ->
                                file.seek(offset)
                                file.readFully(bytes)
                            }
                        }
                    }
                }
                entry.scheme == XId.SCHEME_SMB && entry.size > 0 -> {
                    SmbRandomAccessFile.open(entry.id, Graph.smbConnections).use { file ->
                        Mp4CommentParser.parse(entry.size) { offset, count ->
                            val bytes = ByteArray(count)
                            var copied = 0
                            while (copied < count) {
                                val n = file.read(offset + copied, bytes, copied, count - copied)
                                if (n <= 0) return@parse null
                                copied += n
                            }
                            bytes
                        }
                    }
                }
                else -> null
            }
        }.getOrNull()
    }
}
