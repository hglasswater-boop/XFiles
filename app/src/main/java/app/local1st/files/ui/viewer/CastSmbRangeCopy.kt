package app.local1st.files.ui.viewer

import java.io.EOFException

/**
 * Copies a relay byte range with positional reads. Independent HTTP range requests may share one
 * SMB handle: do not seek the handle here, because Rust seek cancels its outstanding read generation.
 */
internal fun copyCastSmbRange(
    start: Long,
    length: Long,
    chunkSize: Int,
    isActive: () -> Boolean,
    readAt: (position: Long, buffer: ByteArray, count: Int) -> Int,
    write: (buffer: ByteArray, count: Int) -> Unit,
) {
    require(start >= 0L && length >= 0L && chunkSize > 0)
    val buffer = ByteArray(chunkSize)
    var position = start
    var remaining = length
    while (remaining > 0L && isActive()) {
        val requested = minOf(remaining, buffer.size.toLong()).toInt()
        val count = readAt(position, buffer, requested)
        if (count <= 0) throw EOFException("Cast SMB source ended before the advertised range")
        require(count <= requested) { "SMB relay read exceeded requested range" }
        if (!isActive()) break
        write(buffer, count)
        position += count
        remaining -= count
    }
}
