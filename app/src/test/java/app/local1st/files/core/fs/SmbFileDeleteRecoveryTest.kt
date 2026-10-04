package app.local1st.files.core.fs

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbFileDeleteRecoveryTest {
    @Test
    fun ordinaryDeleteDoesNotTouchAttributes() {
        val ops = FakeDeleteOperations(attributes = ARCHIVE)

        deleteSmbFileWithReadOnlyRecovery(ops)

        assertEquals(1, ops.deleteCalls)
        assertEquals(0, ops.readAttributeCalls)
        assertEquals(emptyList<Long>(), ops.writtenAttributes)
    }

    @Test
    fun unrelatedDeleteFailureIsPropagatedWithoutRecovery() {
        val failure = smbFailure(STATUS_ACCESS_DENIED)
        val ops = FakeDeleteOperations(
            attributes = READ_ONLY or ARCHIVE,
            deleteFailures = mutableListOf(failure),
        )

        val thrown = assertThrows(SMBApiException::class.java) {
            deleteSmbFileWithReadOnlyRecovery(ops)
        }

        assertSame(failure, thrown)
        assertEquals(1, ops.deleteCalls)
        assertEquals(0, ops.readAttributeCalls)
        assertEquals(emptyList<Long>(), ops.writtenAttributes)
    }

    @Test
    fun cannotDeleteWithoutReadOnlyIsPropagatedWithoutMutation() {
        val failure = smbFailure(STATUS_CANNOT_DELETE)
        val ops = FakeDeleteOperations(
            attributes = ARCHIVE,
            deleteFailures = mutableListOf(failure),
        )

        val thrown = assertThrows(SMBApiException::class.java) {
            deleteSmbFileWithReadOnlyRecovery(ops)
        }

        assertSame(failure, thrown)
        assertEquals(1, ops.deleteCalls)
        assertEquals(1, ops.readAttributeCalls)
        assertEquals(emptyList<Long>(), ops.writtenAttributes)
    }

    @Test
    fun readOnlyFileClearsOnlyReadOnlyAndRetriesOnce() {
        val ops = FakeDeleteOperations(
            attributes = READ_ONLY or ARCHIVE,
            deleteFailures = mutableListOf(smbFailure(STATUS_CANNOT_DELETE)),
        )

        deleteSmbFileWithReadOnlyRecovery(ops)

        assertEquals(2, ops.deleteCalls)
        assertEquals(1, ops.readAttributeCalls)
        assertEquals(listOf(ARCHIVE), ops.writtenAttributes)
    }

    @Test
    fun readOnlyOnlyFileUsesNormalInsteadOfZero() {
        val ops = FakeDeleteOperations(
            attributes = READ_ONLY,
            deleteFailures = mutableListOf(smbFailure(STATUS_CANNOT_DELETE)),
        )

        deleteSmbFileWithReadOnlyRecovery(ops)

        assertEquals(listOf(NORMAL), ops.writtenAttributes)
    }

    @Test
    fun failedRetryRestoresOriginalAttributes() {
        val retryFailure = smbFailure(STATUS_ACCESS_DENIED)
        val originalAttributes = READ_ONLY or ARCHIVE
        val ops = FakeDeleteOperations(
            attributes = originalAttributes,
            deleteFailures = mutableListOf(
                smbFailure(STATUS_CANNOT_DELETE),
                retryFailure,
            ),
        )

        val thrown = assertThrows(SMBApiException::class.java) {
            deleteSmbFileWithReadOnlyRecovery(ops)
        }

        assertSame(retryFailure, thrown)
        assertEquals(2, ops.deleteCalls)
        assertEquals(listOf(ARCHIVE, originalAttributes), ops.writtenAttributes)
    }

    private class FakeDeleteOperations(
        private var attributes: Long,
        private val deleteFailures: MutableList<SMBApiException> = mutableListOf(),
    ) : SmbFileDeleteOperations {
        var deleteCalls = 0
        var readAttributeCalls = 0
        val writtenAttributes = mutableListOf<Long>()

        override fun delete() {
            deleteCalls += 1
            if (deleteFailures.isNotEmpty()) throw deleteFailures.removeAt(0)
        }

        override fun readAttributes(): Long {
            readAttributeCalls += 1
            return attributes
        }

        override fun writeAttributes(attributes: Long) {
            writtenAttributes += attributes
            this.attributes = attributes
        }
    }

    companion object {
        private val READ_ONLY = FileAttributes.FILE_ATTRIBUTE_READONLY.value
        private val ARCHIVE = FileAttributes.FILE_ATTRIBUTE_ARCHIVE.value
        private val NORMAL = FileAttributes.FILE_ATTRIBUTE_NORMAL.value
        private const val STATUS_CANNOT_DELETE = 0xC0000121L
        private const val STATUS_ACCESS_DENIED = 0xC0000022L

        private fun smbFailure(status: Long): SMBApiException = SMBApiException(
            status,
            SMB2MessageCommandCode.SMB2_SET_INFO,
            "test",
            RuntimeException("test"),
        )
    }
}
