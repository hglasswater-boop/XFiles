package app.local1st.files.core.fs

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig

/**
 * SMBJ defaults to a fixed 1 MiB transfer buffer. For large media I/O, let the negotiated SMB2/3
 * limits choose the largest safe read/write request size advertised by the server.
 */
internal object SmbClientFactory {
    private val config: SmbConfig = SmbConfig.builder()
        .withNegotiatedBufferSize()
        .build()

    fun create(): SMBClient = SMBClient(config)
}
