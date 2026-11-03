package app.local1st.files.core.fs

import app.local1st.files.core.fs.rust.RustSmbUnavailableException

/**
 * Shared policy for one-shot SMB mutations.
 *
 * In auto mode, SMBJ is a fallback only before a Rust operation starts, when the JNI library
 * is missing or incompatible. Once Rust is called, an ambiguous network error must not replay
 * a rename, mkdir or delete through SMBJ.
 */
internal object SmbMutationRouting {
    fun route(
        mode: String,
        ensureRustAvailable: () -> Unit,
        rust: () -> Unit,
        smbj: () -> Unit,
    ) {
        if (mode == "smbj") {
            smbj()
            return
        }
        if (mode == "rust") {
            ensureRustAvailable()
            rust()
            return
        }
        try {
            ensureRustAvailable()
        } catch (_: RustSmbUnavailableException) {
            smbj()
            return
        }
        rust()
    }
}
