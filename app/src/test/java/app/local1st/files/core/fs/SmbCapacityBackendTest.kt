package app.local1st.files.core.fs

import app.local1st.files.core.fs.rust.RustStorageSpacePage
import app.local1st.files.core.fs.rust.RustSmbUnavailableException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbCapacityBackendTest {
    @Test
    fun strictVolumePayloadUsesCallerFreeValue() {
        val data = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put("XSS1".toByteArray(Charsets.US_ASCII))
            .putLong(300_000L)
            .putLong(50_000L)
            .array()
        assertEquals(StorageSpace(300_000L, 50_000L), RustStorageSpacePage.decode(data))
    }

    @Test
    fun malformedAndNegativeCapacityIsNotMisrepresentedAsZero() {
        val valid = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put("XSS1".toByteArray(Charsets.US_ASCII))
            .putLong(100L)
            .putLong(20L)
            .array()
        for (bad in listOf(
            valid.copyOfRange(0, 19),
            valid + 0,
            valid.clone().also { it[0] = 0 },
            valid.clone().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(4, -1L) },
            valid.clone().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(12, -1L) },
        )) {
            assertThrows(IOException::class.java) { RustStorageSpacePage.decode(bad) }
        }
    }

    @Test
    fun autoUsesRustWithNetworkPreparationFirst() {
        val calls = mutableListOf<String>()
        val value = SmbCapacityBackend.route(
            mode = "auto",
            ensureNative = { calls += "JNI" },
            prepareNetwork = { calls += "VPN" },
            rust = { calls += "Rust"; 42L },
            smbj = { calls += "SMBJ"; -1L },
        )
        assertEquals(42L, value)
        assertEquals(listOf("JNI", "VPN", "Rust"), calls)
    }

    @Test
    fun explicitSmbjDoesNotTouchNativeOrNetworkPreflight() {
        val value = SmbCapacityBackend.route(
            mode = "smbj",
            ensureNative = { error("unexpected JNI") },
            prepareNetwork = { error("unexpected VPN") },
            rust = { error("unexpected Rust") },
            smbj = { "SMBJ" },
        )
        assertEquals("SMBJ", value)
    }

    @Test
    fun unavailableJniFallsBackOnlyBeforeOpeningRust() {
        val value = SmbCapacityBackend.route(
            mode = "auto",
            ensureNative = { throw RustSmbUnavailableException("no JNI") },
            prepareNetwork = { error("unexpected VPN") },
            rust = { error("unexpected Rust") },
            smbj = { "SMBJ" },
        )
        assertEquals("SMBJ", value)
        assertThrows(RustSmbUnavailableException::class.java) {
            SmbCapacityBackend.route(
                mode = "rust",
                ensureNative = { throw RustSmbUnavailableException("no JNI") },
                prepareNetwork = {},
                rust = { 1L },
                smbj = { 2L },
            )
        }
    }

    @Test
    fun remoteFailureNeverRetriesOnSmbj() {
        val failure = IOException("remote server refused query")
        val actual = assertThrows(IOException::class.java) {
            SmbCapacityBackend.route(
                mode = "auto",
                ensureNative = {},
                prepareNetwork = {},
                rust = { throw failure },
                smbj = { error("must not replay") },
            )
        }
        assertSame(failure, actual)
    }

    @Test
    fun nativeDirectoryHandleIsClosedEvenWhenQueryFails() {
        val calls = mutableListOf<String>()
        val problem = IOException("failed")
        assertSame(problem, assertThrows(IOException::class.java) {
            SmbCapacityBackend.withDirectory(
                open = { calls += "open"; 7L },
                query = { calls += "query"; throw problem },
                close = { calls += "close" },
            )
        })
        assertEquals(listOf("open", "query", "close"), calls)
    }

    @Test
    fun successfulNativeDirectoryQueryClosesExactlyOnce() {
        val calls = mutableListOf<String>()
        assertEquals(99L, SmbCapacityBackend.withDirectory(
            open = { calls += "open"; 14L },
            query = { calls += "query"; 99L },
            close = { calls += "close" },
        ))
        assertEquals(listOf("open", "query", "close"), calls)
    }
}
