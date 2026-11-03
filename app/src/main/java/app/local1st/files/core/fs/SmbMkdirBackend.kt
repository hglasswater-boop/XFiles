package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.rust.RustSmbNative
import app.local1st.files.core.prefs.SmbConnectionConfig

/** One backend boundary for mkdir. Auto mode prefers Rust; SMBJ remains a pre-mutation fallback. */
internal object SmbMkdirBackend {
    fun route(
        mode: String,
        ensureRustAvailable: () -> Unit,
        rust: () -> Unit,
        smbj: () -> Unit,
    ) = SmbMutationRouting.route(mode, ensureRustAvailable, rust, smbj)

    fun create(
        config: SmbConnectionConfig,
        path: String,
        password: () -> String,
        prepareNetwork: () -> Unit,
        smbj: () -> Unit,
    ) {
        route(
            mode = BuildConfig.SMB_RANDOM_ACCESS_BACKEND,
            ensureRustAvailable = RustSmbNative::ensureLoaded,
            rust = {
                // Ensure the saved LAN/VPN route is ready *before* issuing any SMB mutation.
                prepareNetwork()
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
