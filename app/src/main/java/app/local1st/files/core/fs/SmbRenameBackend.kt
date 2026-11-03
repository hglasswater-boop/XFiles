package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.rust.RustSmbNative
import app.local1st.files.core.prefs.SmbConnectionConfig

/** Same-share rename, with Rust-first auto and a one-time non-mutating VPN/LAN preflight. */
internal object SmbRenameBackend {
    fun route(
        mode: String,
        ensureRustAvailable: () -> Unit,
        prepareNetwork: () -> Unit,
        rust: () -> Unit,
        smbj: () -> Unit,
    ) {
        SmbMutationRouting.route(
            mode = mode,
            ensureRustAvailable = ensureRustAvailable,
            rust = {
                prepareNetwork()
                rust()
            },
            smbj = smbj,
        )
    }

    fun rename(
        config: SmbConnectionConfig,
        sourcePath: String,
        destinationPath: String,
        password: () -> String,
        prepareNetwork: () -> Unit,
        smbj: () -> Unit,
    ) {
        route(
            mode = BuildConfig.SMB_RANDOM_ACCESS_BACKEND,
            ensureRustAvailable = RustSmbNative::ensureLoaded,
            prepareNetwork = prepareNetwork,
            rust = {
                RustSmbNative.nativeRename(
                    host = config.host,
                    port = config.port,
                    share = config.share,
                    source = sourcePath,
                    destination = destinationPath,
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
