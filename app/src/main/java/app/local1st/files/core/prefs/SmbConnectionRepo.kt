package app.local1st.files.core.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Saved SMB share. Passwords are intentionally kept out of this value and encrypted separately. */
data class SmbConnectionConfig(
    val id: String,
    val name: String,
    val host: String,
    val share: String,
    val basePath: String = "",
    val username: String = "",
    val domain: String = "",
    val port: Int = 445,
) {
    /** User-facing `share/path` form. */
    val sharePath: String
        get() = if (basePath.isBlank()) share else "$share/$basePath"

    /** Full UNC location shown in the UI. */
    val uncPath: String
        get() = "\\\\$host\\${sharePath.replace('/', '\\')}"
}

/**
 * Normalizes the value entered in the "share" field.
 *
 * `video_a/actress` means SMB share `video_a`, with `actress` as the starting directory.
 * Backslashes are accepted too so pasted UNC-style subpaths behave naturally.
 */
fun smbConnectionFromInput(
    id: String,
    name: String,
    host: String,
    sharePath: String,
    username: String,
    domain: String,
    port: Int = 445,
): SmbConnectionConfig {
    val normalizedHost = host.trim()
    val normalizedPath = sharePath
        .trim()
        .replace('\\', '/')
        .trim('/')
    val parts = normalizedPath.split('/').filter { it.isNotBlank() }

    require(normalizedHost.isNotBlank()) { "Host is required" }
    require(parts.isNotEmpty()) { "Share is required" }
    require(parts.none { it == "." || it == ".." }) { "Share path cannot contain . or .." }

    val normalizedShare = parts.first()
    val normalizedBasePath = parts.drop(1).joinToString("/")
    return SmbConnectionConfig(
        id = id,
        name = name.trim().ifBlank { normalizedPath.ifBlank { normalizedHost } },
        host = normalizedHost,
        share = normalizedShare,
        basePath = normalizedBasePath,
        username = username.trim(),
        domain = domain.trim(),
        port = port.coerceIn(1, 65535),
    )
}

class SmbConnectionRepo(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("smb_connections", Context.MODE_PRIVATE)
    private val secrets = SmbSecretStore(appContext)
    private val _connections = MutableStateFlow(readConnections())
    val connections: StateFlow<List<SmbConnectionConfig>> = _connections

    fun add(
        name: String,
        host: String,
        share: String,
        username: String,
        password: String,
        domain: String,
        port: Int = 445,
    ): SmbConnectionConfig {
        val config = smbConnectionFromInput(
            id = UUID.randomUUID().toString(),
            name = name,
            host = host,
            sharePath = share,
            username = username,
            domain = domain,
            port = port,
        )
        secrets.put(config.id, password)
        val updated = _connections.value + config
        persist(updated)
        return config
    }

    /** Duplicate an existing definition, including its decrypted/re-encrypted password. */
    fun duplicate(id: String): SmbConnectionConfig {
        val source = find(id) ?: error("SMB connection not found")
        val baseName = "${source.name} のコピー"
        val usedNames = _connections.value.mapTo(HashSet()) { it.name }
        val copiedName = if (baseName !in usedNames) {
            baseName
        } else {
            generateSequence(2) { it + 1 }
                .map { "$baseName $it" }
                .first { it !in usedNames }
        }
        return add(
            name = copiedName,
            host = source.host,
            share = source.sharePath,
            username = source.username,
            password = password(source.id),
            domain = source.domain,
            port = source.port,
        )
    }

    /**
     * Updates a saved connection without changing its stable id.
     * A null [password] keeps the existing encrypted password; a non-null value replaces it.
     */
    fun update(
        id: String,
        name: String,
        host: String,
        share: String,
        username: String,
        password: String?,
        domain: String,
        port: Int = 445,
    ): SmbConnectionConfig {
        require(find(id) != null) { "SMB connection not found" }
        val config = smbConnectionFromInput(
            id = id,
            name = name,
            host = host,
            sharePath = share,
            username = username,
            domain = domain,
            port = port,
        )
        if (password != null) secrets.put(id, password)
        persist(_connections.value.map { if (it.id == id) config else it })
        return config
    }

    fun remove(id: String) {
        val updated = _connections.value.filterNot { it.id == id }
        secrets.remove(id)
        persist(updated)
    }

    fun find(id: String): SmbConnectionConfig? = _connections.value.firstOrNull { it.id == id }

    fun password(id: String): String = secrets.get(id).orEmpty()

    private fun persist(values: List<SmbConnectionConfig>) {
        _connections.value = values
        val array = JSONArray()
        values.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("host", item.host)
                    .put("share", item.share)
                    .put("basePath", item.basePath)
                    .put("username", item.username)
                    .put("domain", item.domain)
                    .put("port", item.port),
            )
        }
        prefs.edit().putString(KEY_CONNECTIONS, array.toString()).apply()
    }

    private fun readConnections(): List<SmbConnectionConfig> = runCatching {
        val array = JSONArray(prefs.getString(KEY_CONNECTIONS, "[]") ?: "[]")
        buildList {
            val seen = HashSet<String>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val host = item.optString("host")
                val rawShare = item.optString("share")
                if (id.isBlank() || host.isBlank() || rawShare.isBlank() || !seen.add(id)) continue

                // Older builds could persist `video_a/actress` entirely in `share`.
                // Fold any new basePath field onto it, then normalize into share + basePath.
                val storedBasePath = item.optString("basePath")
                val combinedSharePath = buildString {
                    append(rawShare)
                    if (storedBasePath.isNotBlank()) {
                        append('/')
                        append(storedBasePath)
                    }
                }
                val config = runCatching {
                    smbConnectionFromInput(
                        id = id,
                        name = item.optString("name"),
                        host = host,
                        sharePath = combinedSharePath,
                        username = item.optString("username"),
                        domain = item.optString("domain"),
                        port = item.optInt("port", 445),
                    )
                }.getOrNull() ?: continue
                add(config)
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_CONNECTIONS = "connections"
    }
}

/** AES-GCM secret store backed by Android Keystore; SMB passwords never land in preferences plain. */
private class SmbSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("smb_secrets", Context.MODE_PRIVATE)

    fun put(id: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString()
        prefs.edit().putString(id, packed).apply()
    }

    fun get(id: String): String? = runCatching {
        val packed = prefs.getString(id, null) ?: return null
        val json = JSONObject(packed)
        val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(json.getString("data"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            javax.crypto.spec.GCMParameterSpec(128, iv),
        )
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()

    fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "xfiles_smb_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
