package app.local1st.files.ui.dialogs

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.local1st.files.R
import app.local1st.files.di.Graph

/** Connection editor opened directly from the SMB tree. */
@Composable
fun SmbConnectionDialog(
    connectionId: String? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
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
    var share by rememberSaveable(connectionId) { mutableStateOf(existing?.share.orEmpty()) }
    var username by rememberSaveable(connectionId) { mutableStateOf(existing?.username.orEmpty()) }
    var password by rememberSaveable(connectionId) { mutableStateOf("") }
    var domain by rememberSaveable(connectionId) { mutableStateOf(existing?.domain.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "SMBサーバーを編集" else "SMBサーバーを追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("表示名（省略可）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("ホスト / IPアドレス") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = share,
                    onValueChange = { share = it },
                    label = { Text("共有名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("ユーザー名（匿名なら空欄）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
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
                    onValueChange = { domain = it },
                    label = { Text("ドメイン（省略可）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = host.isNotBlank() && share.isNotBlank(),
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
