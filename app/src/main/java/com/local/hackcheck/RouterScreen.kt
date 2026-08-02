package com.local.hackcheck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RouterScreen(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    connectionStatus: String?,
    checking: Boolean,
    topTalkers: List<TopTalker>,
    devices: List<RouterDevice>,
    onCheckConnection: () -> Unit,
    onRefresh: () -> Unit,
    onDeviceClick: (RouterDevice) -> Unit,
    onOpenBoxInfo: () -> Unit,
    onOpenBoxSetup: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Backend URL", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
            placeholder = { Text("http://192.168.1.50:8000") },
        )
        Text(
            "The machine on your LAN running router-backend (see /router-backend in the repo).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = onCheckConnection, enabled = !checking) {
                Text("Test connection")
            }
        }
        connectionStatus?.let {
            Text(it, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            enabled = !checking,
        ) {
            Text(if (checking) "Loading..." else "Refresh top talkers & devices")
        }

        if (checking) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }

        if (topTalkers.isNotEmpty()) {
            Text(
                "Top talkers (by bandwidth)",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            LazyColumn {
                items(topTalkers) { talker ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(talker.hostname ?: talker.macAddress ?: "Unknown device", fontWeight = FontWeight.Medium)
                            Text(
                                "Total: ${formatBytes(talker.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        if (devices.isNotEmpty()) {
            Text(
                "All known devices (${devices.size})",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            devices.forEach { d ->
                Text(
                    "${d.hostname ?: "(unnamed)"} -- ${d.macAddress} -- last seen ${d.lastSeen}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { onDeviceClick(d) },
                )
            }
        }

        Text(
            "Hardware setup",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
        )
        Text(
            "Optional: run your own capture box for real network-wide data.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedButton(
            onClick = onOpenBoxInfo,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("What do I need to build the box?")
        }
        OutlinedButton(
            onClick = onOpenBoxSetup,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Box configuration setup")
        }
    }
}
