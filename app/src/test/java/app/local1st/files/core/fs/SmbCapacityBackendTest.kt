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
            valid + 0.toByte(),
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
    fun trackingReportsRustOnlyAfterSuccessfulOperation() {
        val successes = mutableListOf<SmbCapacityEngine>()
        val result = SmbCapacityBackend.route(
            mode = "auto",
            ensureNative = {},
            prepareNetwork = {},
            rust = { 100L },
            smbj = { error("must not fall back") },
            onSuccess = successes::add,
        )
        assertEquals(100L, result)
        assertEquals(listOf(SmbCapacityEngine.RUST), successes)
    }

    @Test
    fun trackingReportsSmbjOnlyWhenFallbackOrExplicitSmbjCompletes() {
        val successes = mutableListOf<SmbCapacityEngine>()
        val fallback = SmbCapacityBackend.route(
            mode = "auto",
            ensureNative = { throw RustSmbUnavailableException("ABI mismatch") },
            prepareNetwork = { error("native preflight should not run") },
            rust = { error("must not call native") },
            smbj = { 200L },
            onSuccess = successes::add,
        )
        assertEquals(200L, fallback)
        assertEquals(listOf(SmbCapacityEngine.SMBJ), successes)

        val smbj = SmbCapacityBackend.route(
            mode = "smbj",
            ensureNative = { error("must not check JNI") },
            prepareNetwork = { error("must not run native preflight") },
            rust = { error("must not call native") },
            smbj = { 300L },
            onSuccess = successes::add,
        )
        assertEquals(300L, smbj)
        assertEquals(listOf(SmbCapacityEngine.SMBJ, SmbCapacityEngine.SMBJ), successes)
    }

    @Test
    fun trackingNeverClaimsCompletionOnErrorOrNull() {
        val successes = mutableListOf<SmbCapacityEngine>()
        assertThrows(IOException::class.java) {
            SmbCapacityBackend.route(
                mode = "auto",
                ensureNative = {},
                prepareNetwork = {},
                rust = { throw IOException("SMB server rejected query") },
                smbj = { error("no automatic replay") },
                onSuccess = successes::add,
            )
        }
        assertThrows(RustSmbUnavailableException::class.java) {
            SmbCapacityBackend.route(
                mode = "rust",
                ensureNative = { throw RustSmbUnavailableException("missing JNI") },
                prepareNetwork = {},
                rust = { 1L },
                smbj = { error("must not use SMBJ") },
                onSuccess = successes::add,
            )
        }
        val absent: Long? = SmbCapacityBackend.route(
            mode = "smbj",
            ensureNative = {},
            prepareNetwork = {},
            rust = { 2L },
            smbj = { null },
            onSuccess = successes::add,
        )
        assertEquals(null, absent)
        assertEquals(emptyList<SmbCapacityEngine>(), successes)
    }

    @Test
    fun trackerKeepsLastSuccessfulShareAndFolderIndependentAndObservable() {
        val tracker = SmbBackendExecutionTracker()
        assertEquals(emptyMap<SmbCapacityOperation, SmbBackendExecution>(), tracker.lastSuccess.value)
        tracker.record(SmbCapacityOperation.SHARE_CAPACITY, SmbCapacityEngine.RUST, "家庭NAS")
        assertEquals(
            SmbBackendExecution(SmbCapacityEngine.RUST, "家庭NAS"),
            tracker.lastSuccess.value[SmbCapacityOperation.SHARE_CAPACITY],
        )
        assertEquals(null, tracker.lastSuccess.value[SmbCapacityOperation.DIRECTORY_SIZE])
        tracker.record(SmbCapacityOperation.DIRECTORY_SIZE, SmbCapacityEngine.SMBJ, "サブNAS")
        assertEquals(
            SmbBackendExecution(SmbCapacityEngine.RUST, "家庭NAS"),
            tracker.lastSuccess.value[SmbCapacityOperation.SHARE_CAPACITY],
        )
        assertEquals(
            SmbBackendExecution(SmbCapacityEngine.SMBJ, "サブNAS"),
            tracker.lastSuccess.value[SmbCapacityOperation.DIRECTORY_SIZE],
        )
        tracker.record(SmbCapacityOperation.SHARE_CAPACITY, SmbCapacityEngine.SMBJ, "新NAS")
        assertEquals(
            SmbBackendExecution(SmbCapacityEngine.SMBJ, "新NAS"),
            tracker.lastSuccess.value[SmbCapacityOperation.SHARE_CAPACITY],
        )
    }

    @Test
    fun recordingIsAfterHandleCloseAndNeverAfterCloseFailure() {
        val successes = mutableListOf<SmbCapacityEngine>()
        val order = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            SmbCapacityBackend.route(
                mode = "rust",
                ensureNative = {},
                prepareNetwork = {},
                rust = {
                    SmbCapacityBackend.withDirectory(
                        open = { 4L },
                        query = { order += "query"; 70L },
                        close = { order += "close"; throw IOException("SMB CLOSE failed") },
                    )
                },
                smbj = { error("no replay after close failure") },
                onSuccess = { successes.add(it); order += "record" },
            )
        }
        assertEquals(listOf("query", "close"), order)
        assertEquals(emptyList<SmbCapacityEngine>(), successes)

        val size = SmbCapacityBackend.route(
            mode = "rust",
            ensureNative = {},
            prepareNetwork = {},
            rust = {
                SmbCapacityBackend.withDirectory(
                    open = { 5L },
                    query = { order += "query"; 80L },
                    close = { order += "close" },
                )
            },
            smbj = { error("no fallback") },
            onSuccess = { successes.add(it); order += "record" },
        )
        assertEquals(80L, size)
        assertEquals(listOf("query", "close", "query", "close", "record"), order)
        assertEquals(listOf(SmbCapacityEngine.RUST), successes)
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
