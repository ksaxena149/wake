package com.wake.dtn.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wake.dtn.data.BundleStoreManager

private const val MAX_STORE_BYTES = BundleStoreManager.MAX_STORE_BYTES

@Composable
fun StatusScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val isRunning by viewModel.isRunning.collectAsState()
    val storageUsedBytes by viewModel.storageUsedBytes.collectAsState()
    val lastSyncTimeMs by viewModel.lastSyncTimeMs.collectAsState()
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        viewModel.startRelay(context)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- Relay control ----
        Text("Relay", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (isRunning) "Active" else "Stopped",
            color = if (isRunning) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = {
            if (isRunning) {
                viewModel.stopRelay(context)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.startRelay(context)
                }
            }
        }) {
            Text(if (isRunning) "Stop relay" else "Start relay")
        }

        Spacer(Modifier.height(8.dp))

        // ---- Storage ----
        Text("Storage", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatBytes(storageUsedBytes))
            Text("/ ${formatBytes(MAX_STORE_BYTES)}")
        }
        LinearProgressIndicator(
            progress = { (storageUsedBytes.toFloat() / MAX_STORE_BYTES).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        // ---- Last sync ----
        Text("Last sync", style = MaterialTheme.typography.titleMedium)
        Text(
            text = lastSyncTimeMs?.let { formatRelativeTime(it) } ?: "Never",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatRelativeTime(timeMs: Long): String {
    val elapsed = System.currentTimeMillis() - timeMs
    return when {
        elapsed < 60_000L -> "Just now"
        elapsed < 3_600_000L -> "${elapsed / 60_000L} min ago"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L} hr ago"
        else -> "${elapsed / 86_400_000L} days ago"
    }
}
