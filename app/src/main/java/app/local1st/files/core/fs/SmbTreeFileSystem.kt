package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionConfig
import app.local1st.files.core.prefs.SmbConnectionRepo

/**
 * Tree-facing SMB filesystem wrapper.
 *
 * Keeps the actual SMB protocol implementation in [SmbFileSystem], while exposing one
 * synthetic action row directly under the SMB root so connections can be added where
 * the user is already browsing them.
 */
class SmbTreeFileSystem(
    private val connections: SmbConnectionRepo,
    private val delegate: SmbFileSystem = SmbFileSystem(connections),
) : XFileSystem by delegate {
    override val scheme: String = XId.SCHEME_SMB

    override fun list(dir: XEntry): List<XEntry> =
        if (dir.id == SmbFileSystem.ROOT_ID) {
            connections.connections.value.map(::connectionEntry) + addServerEntry()
        } else {
            delegate.list(dir)
        }

    override fun stat(id: String): XEntry? = when (id) {
        ADD_SERVER_ID -> addServerEntry()
        else -> delegate.stat(id)
    }

    override fun canWrite(entry: XEntry): Boolean =
        entry.id != ADD_SERVER_ID && delegate.canWrite(entry)

    private fun connectionEntry(config: SmbConnectionConfig): XEntry = XEntry(
        id = "$scheme://${config.id}",
        name = config.name,
        isDir = true,
        kind = EntryKind.DIR,
        badge = "\\\\${config.host}\\${config.share}",
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
