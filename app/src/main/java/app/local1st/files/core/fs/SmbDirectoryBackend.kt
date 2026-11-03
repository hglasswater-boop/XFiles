package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.rust.RustDirectoryEntry
import app.local1st.files.core.fs.rust.RustDirectoryPage
import app.local1st.files.core.fs.rust.RustSmbNative
import app.local1st.files.core.fs.rust.RustSmbUnavailableException
import app.local1st.files.core.prefs.SmbConnectionConfig

/**
 * Read-only directory listing through persistent Rust SMB QUERY_DIRECTORY pages.
 *
 * A missing JNI library can select SMBJ before the query begins. Network failures after opening
 * a Rust cursor are never disguised as EOF or retried on SMBJ.
 */
internal object SmbDirectoryBackend {
    fun <T> route(
        mode: String,
        ensureNative: () -> Unit,
        rust: () -> T,
        smbj: () -> T,
    ): T {
        if (mode == "smbj") return smbj()
        if (mode == "rust") {
            ensureNative()
            return rust()
        }
        try {
            ensureNative()
        } catch (_: RustSmbUnavailableException) {
            return smbj()
        }
        return rust()
    }

    fun <T> list(
        config: SmbConnectionConfig,
        path: String,
        password: () -> String,
        prepareNetwork: () -> Unit,
        map: (RustDirectoryEntry) -> T,
        smbj: () -> List<T>,
    ): List<T> = route(
        mode = BuildConfig.SMB_RANDOM_ACCESS_BACKEND,
        ensureNative = RustSmbNative::ensureLoaded,
        rust = {
            prepareNetwork()
            collectPages(
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
                read = RustSmbNative::nativeReadDirectoryPage,
                close = RustSmbNative::nativeCloseDirectory,
                map = map,
            )
        },
        smbj = smbj,
    )

    /** No folder-wide byte or entry cap. Always close the open SMB handle even on page errors. */
    fun <T> collectPages(
        open: () -> Long,
        read: (Long) -> ByteArray,
        close: (Long) -> Unit,
        map: (RustDirectoryEntry) -> T,
    ): List<T> {
        val handle = open()
        val resource = object : AutoCloseable {
            override fun close() = close(handle)
        }
        return resource.use {
            val output = ArrayList<T>()
            while (true) {
                val page = RustDirectoryPage.decode(read(handle))
                if (page.isEmpty()) break
                page.forEach { output.add(map(it)) }
            }
            output
        }
    }
}
