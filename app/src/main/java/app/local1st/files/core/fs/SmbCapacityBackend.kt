package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.rust.RustSmbNative
import app.local1st.files.core.fs.rust.RustSmbUnavailableException
import app.local1st.files.core.fs.rust.RustStorageSpacePage
import app.local1st.files.core.prefs.SmbConnectionConfig
import java.io.IOException

/** Signed Rust capacity queries; auto fallback is allowed only before any native SMB session. */
internal object SmbCapacityBackend {
    fun <T> route(
        mode: String,
        ensureNative: () -> Unit,
        prepareNetwork: () -> Unit,
        rust: () -> T,
        smbj: () -> T,
        onSuccess: (SmbCapacityEngine) -> Unit = {},
    ): T {
        fun execute(engine: SmbCapacityEngine, action: () -> T): T {
            val result = action()
            if (result != null) onSuccess(engine)
            return result
        }

        if (mode == "smbj") return execute(SmbCapacityEngine.SMBJ, smbj)
        if (mode == "rust") {
            ensureNative()
        } else {
            try {
                ensureNative()
            } catch (_: RustSmbUnavailableException) {
                return execute(SmbCapacityEngine.SMBJ, smbj)
            }
        }
        prepareNetwork()
        return execute(SmbCapacityEngine.RUST, rust)
    }

    /** Always close the authenticated root directory handle, even if the query throws. */
    fun <T> withDirectory(
        open: () -> Long,
        query: (Long) -> T,
        close: (Long) -> Unit,
    ): T {
        val handle = open()
        if (handle <= 0L) throw IOException("Rust SMB directory handle was not opened")
        return AutoCloseable { close(handle) }.use { query(handle) }
    }

    fun storageSpace(
        config: SmbConnectionConfig,
        password: () -> String,
        prepareNetwork: () -> Unit,
        smbj: () -> StorageSpace?,
    ): StorageSpace? = query(
        operation = SmbCapacityOperation.SHARE_CAPACITY,
        config = config,
        path = config.basePath,
        password = password,
        prepareNetwork = prepareNetwork,
        read = { RustStorageSpacePage.decode(RustSmbNative.nativeDirectoryCapacity(it)) },
        smbj = smbj,
    )

    fun directorySize(
        config: SmbConnectionConfig,
        path: String,
        password: () -> String,
        prepareNetwork: () -> Unit,
        smbj: () -> Long?,
    ): Long? = query(
        operation = SmbCapacityOperation.DIRECTORY_SIZE,
        config = config,
        path = path,
        password = password,
        prepareNetwork = prepareNetwork,
        read = { handle ->
            RustSmbNative.nativeDirectorySize(handle).also {
                if (it < 0L) throw IOException("Rust SMB directory size was negative")
            }
        },
        smbj = smbj,
    )

    private fun <T> query(
        operation: SmbCapacityOperation,
        config: SmbConnectionConfig,
        path: String,
        password: () -> String,
        prepareNetwork: () -> Unit,
        read: (Long) -> T,
        smbj: () -> T,
    ): T = route(
        mode = BuildConfig.SMB_RANDOM_ACCESS_BACKEND,
        ensureNative = RustSmbNative::ensureLoaded,
        prepareNetwork = prepareNetwork,
        rust = {
            withDirectory(
                open = {
                    RustSmbNative.nativeOpenDirectory(
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
                query = read,
                close = RustSmbNative::nativeCloseDirectory,
            )
        },
        smbj = smbj,
        onSuccess = { engine -> smbBackendExecutions.record(operation, engine, config.name) },
    )
}
