package com.local.hackcheck

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CaptureScreen(
    running: Boolean,
    log: List<CaptureEntry>,
    exportStatus: String?,
    onToggle: () -> Unit,
    onRefreshLog: () -> Unit,
    onExport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Relays every TCP/UDP connection through a local capture point and logs which app " +
                "talked to which remote address, how much data, and for how long. Simplified relay, " +
                "not a full TCP stack -- occasional flow drops are possible. No raw packet bytes saved yet.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(if (running) "Stop traffic capture" else "Start traffic capture")
        }
        Text(
            if (running)
                "Running -- see the VPN key icon and persistent notification. All device traffic " +
                    "is being relayed through this."
            else
                "Not running. Starting this shows Android's \"Connection request\" VPN consent dialog " +
                    "-- that's the OS, not this app, and can't be skipped.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        OutlinedButton(
            onClick = onRefreshLog,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text("Refresh capture log (${log.size} flows so far)")
        }

        if (log.isNotEmpty()) {
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Export capture log (${log.size} flows)")
            }
        }
        exportStatus?.let {
            Text(it, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
        }

        if (log.isNotEmpty()) {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Most recent 15 of ${log.size}", fontWeight = FontWeight.Bold)
                    log.takeLast(15).reversed().forEach { e ->
                        Text(
                            "${e.timestamp}  [${e.protocol}]  ${e.app} -> ${e.remoteAddress}  " +
                                "sent ${formatBytes(e.bytesSent)} / recv ${formatBytes(e.bytesReceived)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
