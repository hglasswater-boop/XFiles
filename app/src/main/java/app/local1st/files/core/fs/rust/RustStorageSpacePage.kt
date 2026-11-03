package app.local1st.files.core.fs.rust

import app.local1st.files.core.fs.StorageSpace
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Binary JNI v9 volume capacity payload: XSS1 + total/caller-free i64 LE. */
internal object RustStorageSpacePage {
    fun decode(bytes: ByteArray): StorageSpace {
        if (bytes.size != 20 || !bytes.copyOfRange(0, 4).contentEquals("XSS1".toByteArray(Charsets.US_ASCII))) {
            throw IOException("Invalid Rust SMB filesystem capacity response")
        }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val total = data.getLong(4)
        val callerFree = data.getLong(12)
        if (total < 0L || callerFree < 0L) {
            throw IOException("Rust SMB filesystem capacity returned a negative value")
        }
        return StorageSpace(totalBytes = total, freeBytes = callerFree)
    }
}
