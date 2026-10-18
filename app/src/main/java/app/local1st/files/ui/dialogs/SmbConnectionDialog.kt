package app.local1st.files.ui.dialogs

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.local1st.files.R
import app.local1st.files.core.fs.SmbFileSystem
import app.local1st.files.core.fs.SmbWireGuardAndroid
import app.local1st.files.core.prefs.smbConnectionFromInput
import app.local1st.files.di.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connection editor opened directly from the SMB tree. */
@Composable
fun SmbConnectionDialog(
    connectionId: String? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(connectionId) { connectionId?.let(Graph.smbConnections::find) }

    if (connectionId != null && existing == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("SMBサーバーが見つかりません") },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        )
        return
    }

    val editing = existing != null
    var name by rememberSaveable(connectionId) { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable(connectionId) { mutableStateOf(existing?.host.orEmpty()) }
    var share by rememberSaveable(connectionId) { mutableStateOf(existing?.sharePath.orEmpty()) }
    var username by rememberSaveable(connectionId) { mutableStateOf(existing?.username.orEmpty()) }
    var password by rememberSaveable(connectionId) { mutableStateOf("") }
    var domain by rememberSaveable(connectionId) { mutableStateOf(existing?.domain.orEmpty()) }
    var wireGuardEnabled by rememberSaveable(connectionId) { mutableStateOf(existing?.wireGuardTunnel?.isNotBlank() == true) }
    var wireGuardTunnel by rememberSaveable(connectionId) { mutableStateOf(existing?.wireGuardTunnel.orEmpty()) }
    var wireGuardPermissionGranted by remember {
        mutableStateOf(context.checkSelfPermission(SmbWireGuardAndroid.PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        wireGuardPermissionGranted = granted
    }
    val validInput = host.isNotBlank() && share.isNotBlank() &&
        (!wireGuardEnabled || wireGuardTunnel.isNotBlank())
    var testing by rememberSaveable(connectionId) { mutableStateOf(false) }
    var testSucceeded by rememberSaveable(connectionId) { mutableStateOf<Boolean?>(null) }
    var testMessage by rememberSaveable(connectionId) { mutableStateOf<String?>(null) }

    fun clearTestResult() {
        testSucceeded = null
        testMessage = null
    }

    fun runConnectionTest() {
        if (testing || host.isBlank() || share.isBlank()) return
        testing = true
        testSucceeded = null
        testMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val draft = smbConnectionFromInput(
                        id = existing?.id ?: "connection-test",
                        name = name,
                        host = host,
                        sharePath = share,
                        username = username,
                        domain = domain,
                        port = existing?.port ?: 445,
                        wireGuardTunnel = wireGuardTunnel.takeIf { wireGuardEnabled }.orEmpty(),
                    )
                    val effectivePassword = if (existing != null && password.isEmpty()) {
                        Graph.smbConnections.password(existing.id)
                    } else {
                        password
                    }
                    SmbFileSystem.testConnection(draft, effectivePassword, Graph.smbWireGuard)
                    draft
                }
            }
            testing = false
            result.onSuccess { tested ->
                testSucceeded = true
                testMessage = "接続成功: ${tested.uncPath}"
            }.onFailure { error ->
                testSucceeded = false
                val detail = generateSequence(error) { it.cause }.last().message
                    ?: error.message
                    ?: "接続できませんでした"
                testMessage = "接続失敗: $detail"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "SMBサーバーを編集" else "SMBサーバーを追加") },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; clearTestResult() },
                    label = { Text("表示名（省略可）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; clearTestResult() },
                    label = { Text("ホスト / IPアドレス") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = share,
                    onValueChange = { share = it; clearTestResult() },
                    label = { Text("共有名 / パス") },
                    supportingText = {
                        Text("例: share/folder → share共有の folder から表示")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; clearTestResult() },
                    label = { Text("ユーザー名（匿名なら空欄）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; clearTestResult() },
                    label = {
                        Text(
                            if (editing) "パスワード（空欄なら変更しない）"
                            else "パスワード",
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it; clearTestResult() },
                    label = { Text("ドメイン（省略可）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = wireGuardEnabled,
                        onCheckedChange = { wireGuardEnabled = it; clearTestResult() },
                    )
                    Text("WireGuard（SMB接続失敗時に自動起動）")
                }
                if (wireGuardEnabled) {
                    OutlinedTextField(
                        value = wireGuardTunnel,
                        onValueChange = { wireGuardTunnel = it; clearTestResult() },
                        label = { Text("WireGuardのトンネル名") },
                        supportingText = { Text("公式WireGuardアプリでリモート操作を有効にしてください") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        permissionLauncher.launch(SmbWireGuardAndroid.PERMISSION)
                    }) {
                        Text(if (wireGuardPermissionGranted) "WireGuard操作権限：許可済み" else "WireGuard操作権限を許可")
                    }
                }
                testMessage?.let { message ->
                    Text(
                        text = message,
                        color = if (testSucceeded == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !testing && validInput,
                onClick = {
                    runCatching {
                        if (existing == null) {
                            Graph.smbConnections.add(
                                name = name,
                                host = host,
                                share = share,
                                username = username,
                                password = password,
                                domain = domain,
                                wireGuardTunnel = wireGuardTunnel.takeIf { wireGuardEnabled }.orEmpty(),
                            )
                        } else {
                            Graph.smbConnections.update(
                                id = existing.id,
                                name = name,
                                host = host,
                                share = share,
                                username = username,
                                password = password.takeIf { it.isNotEmpty() },
                                domain = domain,
                                port = existing.port,
                                wireGuardTunnel = wireGuardTunnel.takeIf { wireGuardEnabled }.orEmpty(),
                            )
                        }
                    }.onSuccess {
                        onSaved()
                        onDismiss()
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            error.message ?: "SMB接続を保存できませんでした",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !testing && validInput,
                    onClick = ::runConnectionTest,
                ) {
                    Text(if (testing) "テスト中…" else "接続テスト")
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Backward-compatible add-only entry point used by the synthetic add-server row. */
@Composable
fun AddSmbConnectionDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) = SmbConnectionDialog(
    connectionId = null,
    onDismiss = onDismiss,
    onSaved = onSaved,
)
