package app.local1st.files.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.SmbBackendExecution
import app.local1st.files.core.fs.SmbCapacityEngine
import app.local1st.files.core.fs.SmbCapacityOperation
import app.local1st.files.core.fs.smbBackendExecutions

/** Last observed successful SMB capacity calls, not a build-time backend preference. */
@Composable
internal fun SmbBackendDiagnosticsSection() {
    val recent by smbBackendExecutions.lastSuccess.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.smb_backend_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            BackendExecutionRow(
                label = stringResource(R.string.smb_backend_share_capacity),
                execution = recent[SmbCapacityOperation.SHARE_CAPACITY],
            )
            BackendExecutionRow(
                label = stringResource(R.string.smb_backend_folder_size),
                execution = recent[SmbCapacityOperation.DIRECTORY_SIZE],
            )
        }
    }
}

@Composable
private fun BackendExecutionRow(label: String, execution: SmbBackendExecution?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (execution == null) {
                stringResource(R.string.smb_backend_not_run)
            } else {
                stringResource(
                    R.string.smb_backend_result,
                    if (execution.engine == SmbCapacityEngine.RUST) "Rust" else "SMBJ",
                    execution.connectionName,
                )
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
