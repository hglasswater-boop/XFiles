package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.rust.RustSmbNative
import app.local1st.files.core.prefs.SmbConnectionConfig

/**
 * One migration seam for SMB directory creation.
 *
 * Only the explicit Rust diagnostic build may exercise the new mutation JNI. Production auto
 * stays on SMBJ until VPN, NAS interoperability and mutation-outcome safety are verified.
 */
internal object SmbMkdirBackend {
    fun route(mode: String, rust: () -> Unit, smbj: () -> Unit) {
        if (mode == "rust") rust() else smbj()
    }

    fun create(
        config: SmbConnectionConfig,
        path: String,
        password: () -> String,
        smbj: () -> Unit,
    ) {
        route(
            mode = BuildConfig.SMB_RANDOM_ACCESS_BACKEND,
            rust = {
                RustSmbNative.ensureLoaded()
                RustSmbNative.nativeMkdir(
                    host = config.host,
                    port = config.port,
                    share = config.share,
                    path = path,
                    username = config.username,
                    password = if (config.username.isBlank()) "" else password(),
                    domain = if (config.username.isBlank()) "" else config.domain,
                    workstation = "",
                )
            },
            smbj = smbj,
        )
    }
}
