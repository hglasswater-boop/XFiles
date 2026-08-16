package app.local1st.files.core.prefs

import android.content.Context
import app.local1st.files.core.fs.priv.TransportPref
import app.local1st.files.core.util.ExternalOpenKind
import app.local1st.files.core.util.ExternalOpenRegistry
import app.local1st.files.di.Graph
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Portable, password-protected backup of user settings and saved SMB credentials. */
object SettingsBackup {
    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_BACKUP_CHARS = 4 * 1024 * 1024

    suspend fun export(context: Context, password: String): String {
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "バックアップ用パスワードは${MIN_PASSWORD_LENGTH}文字以上にしてください"
        }
        val settings = Graph.settings
        val appSettings = JSONObject()
            .put("showHidden", settings.showHidden.first())
            .put("sortBy", settings.sortBy.first().name)
            .put("sortDescending", settings.sortDescending.first())
            .put("dirsFirst", settings.dirsFirst.first())
            .put("collapseSiblingFolders", settings.collapseSiblingFolders.first())
            .put("themeMode", settings.themeMode.first().name)
            .put("dynamicColor", settings.dynamicColor.first())
            .put("textWrap", settings.textWrap.first())
            .put("rootEnabled", settings.rootEnabled.first())
            .put("rootReadOnly", settings.rootReadOnly.first())
            .put("privilegedTransport", settings.privilegedTransport.first().storedValue)

        val favorites = JSONArray().apply {
            settings.favorites.first().forEach { favorite ->
                put(JSONObject().put("id", favorite.id).put("dir", favorite.isDir))
            }
        }

        val display = BrowserDisplaySettings.current(context)
        val displayJson = JSONObject()
            .put("thumbnailSize", display.thumbnailSize.name)
            .put("filenameMode", display.filenameMode.name)
            .put("treeLevels", display.treeLevels)

        val folderSorts = JSONArray().apply {
            Graph.folderSorts.sorts.value.forEach { (id, spec) ->
                put(
                    JSONObject()
                        .put("id", id)
                        .put("by", spec.by.name)
                        .put("descending", spec.descending)
                        .put("dirsFirst", spec.dirsFirst),
                )
            }
        }

        val associations = JSONObject()
        ExternalOpenKind.entries.forEach { kind ->
            associations.put(kind.name, ExternalOpenRegistry.isEnabled(context, kind))
        }

        val smb = JSONArray().apply {
            Graph.smbConnections.connections.value.forEach { connection ->
                put(
                    JSONObject()
                        .put("id", connection.id)
                        .put("name", connection.name)
                        .put("host", connection.host)
                        .put("share", connection.share)
                        .put("basePath", connection.basePath)
                        .put("username", connection.username)
                        .put("domain", connection.domain)
                        .put("port", connection.port)
                        .put("password", Graph.smbConnections.password(connection.id)),
                )
            }
        }

        val plain = JSONObject()
            .put("format", PLAIN_FORMAT)
            .put("version", BACKUP_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("settings", appSettings)
            .put("favorites", favorites)
            .put("browserDisplay", displayJson)
            .put("folderSorts", folderSorts)
            .put("fileAssociations", associations)
            .put("smbConnections", smb)
            .toString()

        return SettingsBackupCrypto.encrypt(plain, password)
    }

    suspend fun import(context: Context, encryptedBackup: String, password: String) {
        require(encryptedBackup.length <= MAX_BACKUP_CHARS) { "バックアップファイルが大きすぎます" }
        val plainText = SettingsBackupCrypto.decrypt(encryptedBackup, password)
        val root = runCatching { JSONObject(plainText) }
            .getOrElse { throw IllegalArgumentException("バックアップファイルを読み取れません", it) }
        require(root.optString("format") == PLAIN_FORMAT) { "XFilesの設定バックアップではありません" }
        require(root.optInt("version", -1) == BACKUP_VERSION) { "未対応のバックアップ形式です" }

        val appSettings = root.getJSONObject("settings")
        val settings = Graph.settings
        settings.setShowHidden(appSettings.optBoolean("showHidden", false))
        settings.setSortBy(enumValueOrDefault(appSettings.optString("sortBy"), SortBy.NAME))
        settings.setSortDescending(appSettings.optBoolean("sortDescending", false))
        settings.setDirsFirst(appSettings.optBoolean("dirsFirst", true))
        settings.setCollapseSiblingFolders(appSettings.optBoolean("collapseSiblingFolders", true))
        settings.setThemeMode(enumValueOrDefault(appSettings.optString("themeMode"), ThemeMode.SYSTEM))
        settings.setDynamicColor(appSettings.optBoolean("dynamicColor", true))
        settings.setTextWrap(appSettings.optBoolean("textWrap", false))
        settings.setRootEnabled(appSettings.optBoolean("rootEnabled", DEFAULT_ROOT_ENABLED))
        settings.setRootReadOnly(appSettings.optBoolean("rootReadOnly", true))
        settings.setPrivilegedTransport(
            TransportPref.fromStoredValue(appSettings.optString("privilegedTransport", "auto")),
        )

        val restoredFavorites = buildList {
            val array = root.optJSONArray("favorites") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (!id.contains("://")) continue
                add(Favorite(id = id, isDir = item.optBoolean("dir", true)))
            }
        }
        settings.setFavorites(restoredFavorites)

        root.optJSONObject("browserDisplay")?.let { display ->
            BrowserDisplaySettings.setThumbnailSize(
                context,
                enumValueOrDefault(display.optString("thumbnailSize"), ThumbnailSize.MEDIUM),
            )
            BrowserDisplaySettings.setFilenameMode(
                context,
                enumValueOrDefault(display.optString("filenameMode"), FilenameDisplayMode.TWO_LINES),
            )
            BrowserDisplaySettings.setTreeLevels(context, display.optInt("treeLevels", 4))
        }

        val folderSortRepo = Graph.folderSorts
        folderSortRepo.sorts.value.keys.toList().forEach { folderSortRepo.set(it, null) }
        val sortArray = root.optJSONArray("folderSorts") ?: JSONArray()
        for (index in 0 until sortArray.length()) {
            val item = sortArray.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (!id.contains("://")) continue
            val by = runCatching { SortBy.valueOf(item.optString("by")) }.getOrNull() ?: continue
            folderSortRepo.set(
                id,
                FolderSortSpec(
                    by = by,
                    descending = item.optBoolean("descending", false),
                    dirsFirst = item.optBoolean("dirsFirst", true),
                ),
            )
        }

        val smbArray = root.optJSONArray("smbConnections") ?: JSONArray()
        val smbConnections = buildList {
            val ids = HashSet<String>()
            for (index in 0 until smbArray.length()) {
                val item = smbArray.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank() || !ids.add(id)) continue
                val basePath = item.optString("basePath").trim('/').replace('\\', '/')
                val sharePath = buildString {
                    append(item.optString("share"))
                    if (basePath.isNotBlank()) append('/').append(basePath)
                }
                val config = runCatching {
                    smbConnectionFromInput(
                        id = id,
                        name = item.optString("name"),
                        host = item.optString("host"),
                        sharePath = sharePath,
                        username = item.optString("username"),
                        domain = item.optString("domain"),
                        port = item.optInt("port", 445),
                    )
                }.getOrNull() ?: continue
                add(SmbConnectionRestore(config, item.optString("password")))
            }
        }
        Graph.smbConnections.replaceAll(smbConnections)

        root.optJSONObject("fileAssociations")?.let { associations ->
            ExternalOpenKind.entries.forEach { kind ->
                if (associations.has(kind.name)) {
                    ExternalOpenRegistry.setEnabled(context, kind, associations.optBoolean(kind.name))
                }
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private const val PLAIN_FORMAT = "xfiles-settings"
    private const val BACKUP_VERSION = 1
}

/** Password-based encryption makes the backup portable across devices while protecting SMB secrets. */
internal object SettingsBackupCrypto {
    private const val ENVELOPE_FORMAT = "xfiles-settings-encrypted"
    private const val ENVELOPE_VERSION = 1
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val ITERATIONS = 200_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private val random = SecureRandom()

    fun encrypt(plainText: String, password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", ENVELOPE_FORMAT)
            .put("version", ENVELOPE_VERSION)
            .put("kdf", KDF)
            .put("iterations", ITERATIONS)
            .put("salt", Base64.getEncoder().encodeToString(salt))
            .put("cipher", CIPHER)
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put("data", Base64.getEncoder().encodeToString(encrypted))
            .toString()
    }

    fun decrypt(envelopeText: String, password: String): String {
        val envelope = runCatching { JSONObject(envelopeText) }
            .getOrElse { throw IllegalArgumentException("バックアップファイルを読み取れません", it) }
        require(envelope.optString("format") == ENVELOPE_FORMAT) { "暗号化されたXFilesバックアップではありません" }
        require(envelope.optInt("version", -1) == ENVELOPE_VERSION) { "未対応のバックアップ形式です" }
        require(envelope.optString("kdf") == KDF) { "未対応の暗号化方式です" }
        require(envelope.optString("cipher") == CIPHER) { "未対応の暗号化方式です" }
        val iterations = envelope.optInt("iterations", 0)
        require(iterations in 100_000..1_000_000) { "不正な暗号化パラメータです" }
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(envelope.getString("salt")) }
            .getOrElse { throw IllegalArgumentException("バックアップファイルを読み取れません", it) }
        val iv = runCatching { decoder.decode(envelope.getString("iv")) }
            .getOrElse { throw IllegalArgumentException("バックアップファイルを読み取れません", it) }
        val encrypted = runCatching { decoder.decode(envelope.getString("data")) }
            .getOrElse { throw IllegalArgumentException("バックアップファイルを読み取れません", it) }
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES) { "不正な暗号化パラメータです" }

        val decrypted = runCatching {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(password, salt, iterations),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(aad())
            cipher.doFinal(encrypted)
        }.getOrElse {
            throw IllegalArgumentException("パスワードが違うか、バックアップファイルが壊れています", it)
        }
        return String(decrypted, Charsets.UTF_8)
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(): ByteArray = "$ENVELOPE_FORMAT:$ENVELOPE_VERSION".toByteArray(Charsets.UTF_8)
}
