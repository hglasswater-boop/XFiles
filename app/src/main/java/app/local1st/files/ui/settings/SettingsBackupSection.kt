package app.local1st.files.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.local1st.files.core.prefs.SettingsBackup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BackupPasswordMode { EXPORT, IMPORT }

@Composable
internal fun SettingsBackupSection(onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var passwordMode by remember { mutableStateOf<BackupPasswordMode?>(null) }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val payload = SettingsBackup.export(context, password)
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("保存先を開けません")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                }
            }
            busy = false
            Toast.makeText(
                context,
                result.fold(
                    onSuccess = { "設定をエクスポートしました" },
                    onFailure = { "エクスポート失敗: ${it.message ?: "不明なエラー"}" },
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            passwordMode = BackupPasswordMode.IMPORT
        }
    }

    Text(
        "バックアップ",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 4.dp),
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "設定とSMB接続をバックアップします。SMBパスワードを含むため、ファイル全体をパスワードで暗号化します。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { passwordMode = BackupPasswordMode.EXPORT },
            ) {
                Text("設定をエクスポート")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { openBackup.launch(arrayOf("application/json", "application/octet-stream")) },
            ) {
                Text("設定をインポート")
            }
        }
    }

    when (passwordMode) {
        BackupPasswordMode.EXPORT -> BackupPasswordDialog(
            title = "バックアップ用パスワード",
            description = "8文字以上で設定してください。このパスワードがないと復元できません。",
            confirmPassword = true,
            confirmLabel = "保存先を選択",
            onDismiss = { passwordMode = null },
            onConfirm = { password ->
                passwordMode = null
                pendingExportPassword = password
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                createBackup.launch("xfiles_settings_$stamp.json")
            },
        )

        BackupPasswordMode.IMPORT -> BackupPasswordDialog(
            title = "バックアップのパスワード",
            description = "エクスポート時に設定したパスワードを入力してください。",
            confirmPassword = false,
            confirmLabel = "復元",
            onDismiss = {
                passwordMode = null
                pendingImportUri = null
            },
            onConfirm = { password ->
                val uri = pendingImportUri
                if (uri != null) {
                    passwordMode = null
                    pendingImportUri = null
                    busy = true
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                val stream = context.contentResolver.openInputStream(uri)
                                    ?: error("バックアップファイルを開けません")
                                val payload = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                                    val text = StringBuilder()
                                    val buffer = CharArray(8192)
                                    while (true) {
                                        val count = reader.read(buffer)
                                        if (count < 0) break
                                        require(text.length + count <= SettingsBackup.MAX_BACKUP_CHARS) {
                                            "バックアップファイルが大きすぎます"
                                        }
                                        text.append(buffer, 0, count)
                                    }
                                    text.toString()
                                }
                                SettingsBackup.import(context, payload, password)
                            }
                        }
                        busy = false
                        if (result.isSuccess) onRestored()
                        Toast.makeText(
                            context,
                            result.fold(
                                onSuccess = { "設定をインポートしました" },
                                onFailure = { "インポート失敗: ${it.message ?: "不明なエラー"}" },
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )

        null -> Unit
    }
}

@Composable
private fun BackupPasswordDialog(
    title: String,
    description: String,
    confirmPassword: Boolean,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val longEnough = password.length >= SettingsBackup.MIN_PASSWORD_LENGTH
    val matches = !confirmPassword || password == confirmation

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("パスワード") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        if (password.isNotEmpty() && !longEnough) Text("8文字以上必要です")
                    },
                )
                if (confirmPassword) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("パスワード（確認）") },
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            if (confirmation.isNotEmpty() && !matches) Text("パスワードが一致しません")
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = longEnough && matches,
                onClick = { onConfirm(password) },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
