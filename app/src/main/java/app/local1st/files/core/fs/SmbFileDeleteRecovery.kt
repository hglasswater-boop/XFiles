package app.local1st.files.core.fs

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.DiskShare

internal interface SmbFileDeleteOperations {
    fun delete()
    fun readAttributes(): Long
    fun writeAttributes(attributes: Long)
}

internal class SmbjFileDeleteOperations(
    private val share: DiskShare,
    private val path: String,
) : SmbFileDeleteOperations {
    override fun delete() {
        share.rm(path)
    }

    override fun readAttributes(): Long =
        share.getFileInformation(path, FileBasicInformation::class.java).fileAttributes

    override fun writeAttributes(attributes: Long) {
        share.setFileInformation(
            path,
            FileBasicInformation(
                FileBasicInformation.DONT_SET,
                FileBasicInformation.DONT_SET,
                FileBasicInformation.DONT_SET,
                FileBasicInformation.DONT_SET,
                attributes,
            ),
        )
    }
}

/**
 * Deletes one SMB file, recovering only from the protocol-defined read-only failure.
 *
 * FileDispositionInformation must return STATUS_CANNOT_DELETE when FILE_ATTRIBUTE_READONLY is set.
 * We verify that attribute before mutating anything, clear only read-only, then retry exactly once.
 */
internal fun deleteSmbFileWithReadOnlyRecovery(operations: SmbFileDeleteOperations) {
    try {
        operations.delete()
        return
    } catch (error: SMBApiException) {
        if (error.statusCode != STATUS_CANNOT_DELETE) throw error

        val originalAttributes = operations.readAttributes()
        if (originalAttributes and READ_ONLY_ATTRIBUTE == 0L) throw error

        val attributesWithoutReadOnly = originalAttributes and READ_ONLY_ATTRIBUTE.inv()
        val writableAttributes = attributesWithoutReadOnly.takeIf { it != 0L } ?: NORMAL_ATTRIBUTE
        operations.writeAttributes(writableAttributes)

        try {
            operations.delete()
        } catch (retryError: Throwable) {
            try {
                operations.writeAttributes(originalAttributes)
            } catch (restoreError: Throwable) {
                retryError.addSuppressed(restoreError)
            }
            throw retryError
        }
    }
}

private const val STATUS_CANNOT_DELETE = 0xC0000121L
private val READ_ONLY_ATTRIBUTE = FileAttributes.FILE_ATTRIBUTE_READONLY.value
private val NORMAL_ATTRIBUTE = FileAttributes.FILE_ATTRIBUTE_NORMAL.value
