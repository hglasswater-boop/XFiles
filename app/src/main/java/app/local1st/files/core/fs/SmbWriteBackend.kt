package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.rust.RustSmbNative
import app.local1st.files.core.fs.rust.RustSmbOutputStream
import app.local1st.files.core.prefs.SmbConnectionConfig
import java.io.IOException
import java.io.OutputStream

/** Explicit diagnostic Rust output only; production auto retains the proven SMBJ pipeline. */
internal object SmbWriteBackend {
    fun route(
        mode: String,
        ensureNative: () -> Unit,
        prepareNetwork: () -> Unit,
        rust: () -> OutputStream,
        smbj: () -> OutputStream,
    ): OutputStream {
        if (mode != "rust") return smbj()
        ensureNative()
        prepareNetwork()
        return rust()
    }

    fun open(
        config: SmbConnectionConfig,
        path: String,
        password: () -> String,
        prepareNetwork: () -> Unit,
        smbj: () -> OutputStream,
    ): OutputStream = route(
        mode = BuildConfig.SMB_RANDOM_ACCESS_BACKEND,
        ensureNative = RustSmbNative::ensureLoaded,
        prepareNetwork = prepareNetwork,
        rust = {
            val handle = RustSmbNative.nativeOpenWritable(
                host = config.host,
                port = config.port,
                share = config.share,
                path = path,
                username = config.username,
                password = if (config.username.isBlank()) "" else password(),
                domain = if (config.username.isBlank()) "" else config.domain,
                workstation = "",
            )
            if (handle <= 0L) throw IOException("Rust SMB writable handle was not opened")
            RustSmbOutputStream(
                handle = handle,
                writeNative = RustSmbNative::nativeWriteAt,
                flushNative = RustSmbNative::nativeFlushWritable,
                closeNative = RustSmbNative::nativeCloseWritable,
            )
        },
        smbj = smbj,
    )
}
