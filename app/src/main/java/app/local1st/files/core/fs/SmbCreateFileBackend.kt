package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.rust.RustSmbNative
import app.local1st.files.core.prefs.SmbConnectionConfig

/** CREATE-only adapter: a failed or ambiguous native mutation is never retried on SMBJ. */
internal object SmbCreateFileBackend {
    fun route(
        mode: String,
        ensureNative: () -> Unit,
        prepareNetwork: () -> Unit,
        rust: () -> Unit,
        smbj: () -> Unit,
    ) {
        SmbMutationRouting.route(
            mode = mode,
            ensureRustAvailable = ensureNative,
            rust = {
                prepareNetwork()
                rust()
            },
            smbj = smbj,
        )
    }

    fun create(
        config: SmbConnectionConfig,
        path: String,
        password: () -> String,
        prepareNetwork: () -> Unit,
        smbj: () -> Unit,
    ) {
        route(
            mode = BuildConfig.SMB_RANDOM_ACCESS_BACKEND,
            ensureNative = RustSmbNative::ensureLoaded,
            prepareNetwork = prepareNetwork,
            rust = {
                RustSmbNative.nativeCreateFile(
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
