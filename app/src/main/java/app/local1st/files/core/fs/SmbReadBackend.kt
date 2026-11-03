package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.rust.RustSmbNative
import app.local1st.files.core.fs.rust.RustSmbUnavailableException
import app.local1st.files.core.prefs.SmbConnectionRepo
import java.io.BufferedInputStream
import java.io.InputStream

/** Rust-first sequential reads; fallback is permitted only before native SMB open. */
internal object SmbReadBackend {
    fun <T> route(
        mode: String,
        ensureNative: () -> Unit,
        prepareNetwork: () -> Unit,
        rust: () -> T,
        smbj: () -> T,
    ): T {
        if (mode == "smbj") return smbj()
        if (mode == "rust") {
            ensureNative()
        } else {
            try {
                ensureNative()
            } catch (_: RustSmbUnavailableException) {
                return smbj()
            }
        }
        prepareNetwork()
        return rust()
    }

    fun open(
        id: String,
        connections: SmbConnectionRepo,
        prepareNetwork: () -> Unit,
        smbj: () -> InputStream,
    ): InputStream = route(
        mode = BuildConfig.SMB_RANDOM_ACCESS_BACKEND,
        ensureNative = RustSmbNative::ensureLoaded,
        prepareNetwork = prepareNetwork,
        rust = {
            BufferedInputStream(
                SmbReadInputStream(RustSmbRandomAccessBackend.open(id, connections)),
                READ_BUFFER_BYTES,
            )
        },
        smbj = smbj,
    )

    private const val READ_BUFFER_BYTES = 64 * 1024
}
