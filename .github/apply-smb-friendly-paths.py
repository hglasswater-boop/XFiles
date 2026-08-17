from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    p.write_text(s.replace(old, new, count))


# Central SMB user-facing path helpers.
path = "app/src/main/java/app/local1st/files/core/prefs/SmbConnectionRepo.kt"
replace(path, "import android.util.Base64\n", "import android.util.Base64\nimport app.local1st.files.core.fs.XId\n")
replace(
    path,
    " * `video_a/actress` means SMB share `video_a`, with `actress` as the starting directory.\n",
    " * `share/folder` means SMB share `share`, with `folder` as the starting directory.\n",
)
replace(
    path,
    "    fun find(id: String): SmbConnectionConfig? = _connections.value.firstOrNull { it.id == id }\n\n    fun password(id: String): String = secrets.get(id).orEmpty()\n",
    '''    fun find(id: String): SmbConnectionConfig? = _connections.value.firstOrNull { it.id == id }

    /** Full user-facing UNC path. Internal connection UUIDs are never exposed. */
    fun displayPathForId(id: String): String = smbDisplayPath(id, ::find)

    /** Compact display-name path for breadcrumbs, search results and destination labels. */
    fun displayLabelPathForId(id: String): String = smbDisplayLabelPath(id, ::find)

    fun password(id: String): String = secrets.get(id).orEmpty()
''',
)
marker = "\nclass SmbConnectionRepo(context: Context) {"
helper = r'''
internal fun smbDisplayPath(
    id: String,
    findConnection: (String) -> SmbConnectionConfig?,
): String {
    if (XId.schemeOf(id) != XId.SCHEME_SMB) return id.substringAfter("://")
    val raw = id.removePrefix("${XId.SCHEME_SMB}://").trim('/')
    if (raw.isEmpty()) return "SMB"
    val connectionId = raw.substringBefore('/')
    val relativePath = raw.substringAfter('/', "")
    val connection = findConnection(connectionId)
    if (connection == null) {
        return if (relativePath.isBlank()) "SMB"
        else "SMB\\${relativePath.replace('/', '\\')}"
    }
    val base = connection.uncPath.trimEnd('\\')
    return if (relativePath.isBlank()) base
    else "$base\\${relativePath.replace('/', '\\')}"
}

internal fun smbDisplayLabelPath(
    id: String,
    findConnection: (String) -> SmbConnectionConfig?,
): String {
    if (XId.schemeOf(id) != XId.SCHEME_SMB) return id.substringAfter("://")
    val raw = id.removePrefix("${XId.SCHEME_SMB}://").trim('/')
    if (raw.isEmpty()) return "SMB"
    val connectionId = raw.substringBefore('/')
    val relativePath = raw.substringAfter('/', "")
    val connection = findConnection(connectionId)
    if (connection == null) {
        return if (relativePath.isBlank()) "SMB" else "SMB / $relativePath"
    }
    return if (relativePath.isBlank()) connection.name else "${connection.name} / $relativePath"
}
'''
p = Path(path)
s = p.read_text()
if marker not in s:
    raise SystemExit("SmbConnectionRepo class marker missing")
p.write_text(s.replace(marker, "\n" + helper.strip("\n") + "\n" + marker, 1))

# Details dialog.
replace(
    "app/src/main/java/app/local1st/files/ui/dialogs/Dialogs.kt",
    "                Text(stringResource(R.string.location, req.entry.id))\n",
    '''                val displayLocation = when (req.entry.scheme) {
                    XId.SCHEME_SMB -> Graph.smbConnections.displayPathForId(req.entry.id)
                    XId.SCHEME_ROOT -> "root:${req.entry.path}"
                    else -> req.entry.path
                }
                Text(stringResource(R.string.location, displayLocation))
''',
)

# Destination picker.
replace(
    "app/src/main/java/app/local1st/files/ui/dialogs/DestinationPickerScreen.kt",
    '''private fun pathLabel(dir: XEntry): String = when (dir.scheme) {
    XId.SCHEME_ROOT -> "root:" + dir.path
    else -> dir.path
}
''',
    '''private fun pathLabel(dir: XEntry): String = when (dir.scheme) {
    XId.SCHEME_SMB -> Graph.smbConnections.displayLabelPathForId(dir.id)
    XId.SCHEME_ROOT -> "root:" + dir.path
    else -> dir.path
}
''',
)

# Search result path.
replace(
    "app/src/main/java/app/local1st/files/ui/search/SearchScreen.kt",
    '''private fun displayParentPath(parentId: String): String {
    if (XId.schemeOf(parentId) != XId.SCHEME_SMB) {
        return parentId.substringAfter("://")
    }
    val raw = parentId.removePrefix("${XId.SCHEME_SMB}://").trim('/')
    if (raw.isEmpty()) return "SMB"
    val connectionId = raw.substringBefore('/')
    val relativePath = raw.substringAfter('/', "")
    val connection = Graph.smbConnections.find(connectionId)
        ?: return relativePath.ifBlank { "SMB" }
    return if (relativePath.isBlank()) {
        connection.name
    } else {
        "${connection.name} / $relativePath"
    }
}
''',
    '''private fun displayParentPath(parentId: String): String =
    if (XId.schemeOf(parentId) == XId.SCHEME_SMB) {
        Graph.smbConnections.displayLabelPathForId(parentId)
    } else {
        parentId.substringAfter("://")
    }
''',
)

# Main screen other-pane labels.
path = "app/src/main/java/app/local1st/files/ui/main/MainScreen.kt"
replace(path, "import app.local1st.files.core.fs.XId\n", "import app.local1st.files.core.fs.XId\nimport app.local1st.files.di.Graph\n")
replace(
    path,
    '''    val id = focusedDirId ?: return "…"
    val raw = id.substringAfter("://")
    return when (raw) {
''',
    '''    val id = focusedDirId ?: return "…"
    if (id.startsWith("${XId.SCHEME_SMB}://")) {
        return Graph.smbConnections.displayLabelPathForId(id).substringAfterLast(" / ")
    }
    val raw = id.substringAfter("://")
    return when (raw) {
''',
)
replace(
    path,
    '''private fun paneLocationPath(destination: XEntry?, focusedDirId: String?): String {
    val id = destination?.id ?: focusedDirId ?: return "…"
    val path = id.substringAfter("://")
    return if (id.startsWith("${XId.SCHEME_ROOT}://")) "root:$path" else path.ifBlank { "/" }
}
''',
    '''private fun paneLocationPath(destination: XEntry?, focusedDirId: String?): String {
    val id = destination?.id ?: focusedDirId ?: return "…"
    val path = id.substringAfter("://")
    return when {
        id.startsWith("${XId.SCHEME_SMB}://") ->
            Graph.smbConnections.displayLabelPathForId(id)
        id.startsWith("${XId.SCHEME_ROOT}://") -> "root:$path"
        else -> path.ifBlank { "/" }
    }
}
''',
)

# Breadcrumb connection fallback.
replace(
    "app/src/main/java/app/local1st/files/ui/browser/PaneView.kt",
    '''            id.startsWith("${XId.SCHEME_SMB}://") && raw.isNotBlank() && !raw.contains('/') ->
                Graph.smbConnections.find(raw)?.name ?: raw
''',
    '''            id.startsWith("${XId.SCHEME_SMB}://") && raw.isNotBlank() && !raw.contains('/') ->
                Graph.smbConnections.displayLabelPathForId(id)
''',
)

# Favorites.
path = "app/src/main/java/app/local1st/files/core/fs/DefaultRootsRepository.kt"
replace(path, "import app.local1st.files.core.prefs.Favorite\n", "import app.local1st.files.core.prefs.Favorite\nimport app.local1st.files.core.prefs.SmbConnectionRepo\n")
replace(
    path,
    '''    private val favorites: () -> List<Favorite> = { emptyList() },
    private val statById: (String) -> XEntry? = { null },
) : RootsRepository {
''',
    '''    private val favorites: () -> List<Favorite> = { emptyList() },
    private val statById: (String) -> XEntry? = { null },
    private val smbConnections: SmbConnectionRepo? = null,
) : RootsRepository {
''',
)
replace(
    path,
    '''            val fallbackName = fav.id.substringAfter("://").trimEnd('/')
                .substringAfterLast('/').substringAfterLast(XId.ARCHIVE_SEP).ifEmpty { "/" }
''',
    '''            val fallbackName = if (XId.schemeOf(fav.id) == XId.SCHEME_SMB) {
                smbConnections?.displayLabelPathForId(fav.id)
                    ?.substringAfterLast(" / ")
                    ?.ifBlank { "SMB" } ?: "SMB"
            } else {
                fav.id.substringAfter("://").trimEnd('/')
                    .substringAfterLast('/').substringAfterLast(XId.ARCHIVE_SEP).ifEmpty { "/" }
            }
''',
)
replace(
    path,
    '                    badge = if (stat == null) "Not available" else fav.id.substringAfter("://"),\n',
    '''                    badge = if (stat == null) {
                        "Not available"
                    } else if (XId.schemeOf(fav.id) == XId.SCHEME_SMB) {
                        smbConnections?.displayPathForId(fav.id) ?: "SMB"
                    } else {
                        fav.id.substringAfter("://")
                    },
''',
)

replace(
    "app/src/main/java/app/local1st/files/di/GraphInit.kt",
    '''        favorites = { Graph.favorites.value.orEmpty() },
        statById = { id -> Graph.fsRegistry.forId(id).stat(id) },
    )
''',
    '''        favorites = { Graph.favorites.value.orEmpty() },
        statById = { id -> Graph.fsRegistry.forId(id).stat(id) },
        smbConnections = graph.smbConnections,
    )
''',
)

# Generic test fixtures and display-path regression coverage.
path = "app/src/test/java/app/local1st/files/core/prefs/SmbConnectionConfigTest.kt"
p = Path(path)
s = p.read_text()
s = s.replace("video_a", "share").replace("actress", "folder").replace("works", "subfolder")
insert = r'''
    @Test
    fun `friendly SMB paths never expose connection id`() {
        val config = smbConnectionFromInput(
            id = "123e4567-e89b-12d3-a456-426614174000",
            name = "Home NAS",
            host = "nas.local",
            sharePath = "share/start",
            username = "",
            domain = "",
        )
        val lookup: (String) -> SmbConnectionConfig? = { if (it == config.id) config else null }
        val id = "smb://${config.id}/photos/2026"

        assertEquals("\\\\nas.local\\share\\start\\photos\\2026", smbDisplayPath(id, lookup))
        assertEquals("Home NAS / photos/2026", smbDisplayLabelPath(id, lookup))
        assertEquals("SMB / photos/2026", smbDisplayLabelPath("smb://missing/photos/2026") { null })
        assertEquals("SMB\\photos\\2026", smbDisplayPath("smb://missing/photos/2026") { null })
    }
'''
if "\n}\n" not in s:
    raise SystemExit("test class ending missing")
p.write_text(s.rsplit("\n}", 1)[0] + "\n" + insert.strip("\n") + "\n}\n")
