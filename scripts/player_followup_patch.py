from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# Keep orientation/PiP ownership alive for the entire video player lifetime.
player_path = Path("app/src/main/java/app/local1st/files/ui/viewer/VideoPlayer.kt")
player = player_path.read_text()
if "val orientationController = rememberVideoOrientationController()" not in player:
    player = replace_once(
        player,
        "    var showPlayerSettings by remember { mutableStateOf(false) }\n\n    SystemBarsHidden(hidden = !controlsVisible)",
        '''    var showPlayerSettings by remember { mutableStateOf(false) }

    // Orientation and PiP belong to the player lifetime, not the auto-hidden controls panel.
    val orientationController = rememberVideoOrientationController()
    VideoPictureInPicture(
        player = player,
        playing = playing,
        title = entry.name,
        onModeChanged = { inPip ->
            if (inPip) {
                controlsVisible = false
                showPlayerSettings = false
            }
        },
    )

    SystemBarsHidden(hidden = !controlsVisible)''',
        "player lifetime controllers",
    )

if "VideoOrientationQuickControls(orientationController)" not in player:
    player = replace_once(
        player,
        "                        VideoOrientationQuickControls {\n",
        "                        VideoOrientationQuickControls(orientationController) {\n",
        "orientation controls call",
    )
player_path.write_text(player)


# Persist technical video metadata (including list-overlay duration) in app cacheDir.
metadata_path = Path("app/src/main/java/app/local1st/files/core/media/VideoMetadata.kt")
metadata = metadata_path.read_text()
if 'PERSISTED_CACHE_DIR = "video-metadata-v1"' not in metadata:
    metadata = replace_once(
        metadata,
        "import java.util.LinkedHashMap\nimport java.util.Locale\n",
        "import java.io.File\nimport java.security.MessageDigest\nimport java.util.LinkedHashMap\nimport java.util.Locale\nimport org.json.JSONObject\n",
        "metadata imports",
    )

    metadata = replace_once(
        metadata,
        "    private const val MAX_CACHE_ENTRIES = 512\n",
        '''    private const val MAX_CACHE_ENTRIES = 512
    private const val PERSISTED_CACHE_VERSION = 1
    private const val PERSISTED_CACHE_DIR = "video-metadata-v1"
    private const val MAX_PERSISTED_CACHE_ENTRIES = 1024
    private const val PERSISTED_CACHE_TARGET = 896
''',
        "metadata cache constants",
    )

    metadata = replace_once(
        metadata,
        '''            val metadata = withContext(Dispatchers.IO) { readBlocking(entry) }
            synchronized(cache) { cache[key] = CacheValue(metadata) }
            metadata
''',
        '''            val metadata = withContext(Dispatchers.IO) {
                readPersistent(key) ?: readBlocking(entry)?.also { writePersistent(key, it) }
            }
            synchronized(cache) { cache[key] = CacheValue(metadata) }
            metadata
''',
        "metadata read path",
    )

    metadata = replace_once(
        metadata,
        '''    private fun cacheKey(entry: XEntry): String = "${entry.id}|${entry.mtime}|${entry.size}"

''',
        r'''    private fun cacheKey(entry: XEntry): String = "${entry.id}|${entry.mtime}|${entry.size}"

    /**
     * Process-independent cache for list duration badges and the Details metadata they share.
     * The key contains id + mtime + size, so replacing a video naturally invalidates stale data.
     * cacheDir keeps this disposable: it survives normal app restarts but Android may reclaim it.
     */
    private fun readPersistent(key: String): VideoMetadata? {
        val file = persistentFile(key)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optInt("version", 0) != PERSISTED_CACHE_VERSION) return@runCatching null
            VideoMetadata(
                width = json.intOrNull("width"),
                height = json.intOrNull("height"),
                frameRate = json.doubleOrNull("frameRate"),
                durationMs = json.longOrNull("durationMs"),
                codec = json.stringOrNull("codec"),
                bitrate = json.longOrNull("bitrate"),
            )
        }.getOrElse {
            file.delete()
            null
        }?.also {
            // lastModified is the persistent LRU timestamp used by prunePersistent().
            file.setLastModified(System.currentTimeMillis())
        }
    }

    private fun writePersistent(key: String, metadata: VideoMetadata) {
        runCatching {
            val target = persistentFile(key)
            val dir = target.parentFile ?: return
            prunePersistent(dir)
            val json = JSONObject()
                .put("version", PERSISTED_CACHE_VERSION)
                .putNullable("width", metadata.width)
                .putNullable("height", metadata.height)
                .putNullable("frameRate", metadata.frameRate)
                .putNullable("durationMs", metadata.durationMs)
                .putNullable("codec", metadata.codec)
                .putNullable("bitrate", metadata.bitrate)
            val tmp = File.createTempFile("metadata-", ".tmp", dir)
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            }
        }
    }

    private fun persistentFile(key: String): File {
        val dir = File(Graph.appContext.cacheDir, PERSISTED_CACHE_DIR).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(dir, "$name.json")
    }

    private fun prunePersistent(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" } ?: return
        if (files.size < MAX_PERSISTED_CACHE_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take((files.size - PERSISTED_CACHE_TARGET + 1).coerceAtLeast(1))
            .forEach { it.delete() }
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.intOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else runCatching { getInt(name) }.getOrNull()

    private fun JSONObject.longOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else runCatching { getLong(name) }.getOrNull()

    private fun JSONObject.doubleOrNull(name: String): Double? =
        if (!has(name) || isNull(name)) null else runCatching { getDouble(name) }.getOrNull()

    private fun JSONObject.stringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

''',
        "persistent metadata methods",
    )
metadata_path.write_text(metadata)
