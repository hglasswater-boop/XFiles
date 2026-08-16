package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionConfig
import app.local1st.files.core.prefs.SmbConnectionRepo

/**
 * Tree-facing SMB filesystem wrapper.
 *
 * Extends the actual SMB protocol implementation while exposing one synthetic action row
 * directly under the SMB root so connections can be added where the user is already browsing.
 * Keeping this as a real [SmbFileSystem] subtype is important: operation fast paths resolve the
 * registered filesystem by scheme and need access to SMB-specific server-side move support.
 */
class SmbTreeFileSystem(
    private val connections: SmbConnectionRepo,
) : SmbFileSystem(connections) {

    override fun list(dir: XEntry): List<XEntry> =
        if (dir.id == ROOT_ID) {
            connections.connections.value.map(::connectionEntry) + addServerEntry()
        } else {
            super.list(dir)
        }

    override fun stat(id: String): XEntry? = when (id) {
        ADD_SERVER_ID -> addServerEntry()
        else -> super.stat(id)
    }

    override fun canWrite(entry: XEntry): Boolean =
        entry.id != ADD_SERVER_ID && super.canWrite(entry)

    private fun connectionEntry(config: SmbConnectionConfig): XEntry = XEntry(
        id = "$scheme://${config.id}",
        name = config.name,
        isDir = true,
        kind = EntryKind.DIR,
        badge = config.uncPath,
        canRead = true,
        canWrite = true,
    )

    companion object {
        const val ADD_SERVER_ID = "smb://@add-server"

        fun addServerEntry(): XEntry = XEntry(
            id = ADD_SERVER_ID,
            name = "＋ サーバーを追加",
            isDir = false,
            kind = EntryKind.APP_COMPONENT,
            badge = "SMB2 / SMB3",
            canRead = false,
            canWrite = false,
        )
    }
}
