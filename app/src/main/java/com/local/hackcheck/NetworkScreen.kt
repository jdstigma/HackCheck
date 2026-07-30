package com.local.hackcheck

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun NetworkScreen(
    network: NetworkSnapshot?,
    checking: Boolean,
    exportStatus: String?,
    onCheck: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    onExport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth(), enabled = !checking) {
            Text(if (checking) "Checking..." else "Check network & devices")
        }

        if (network?.usageAccessGranted == false) {
            OutlinedButton(
                onClick = onGrantUsageAccess,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Grant Usage Access (needed for data usage)")
            }
            Text(
                "Find HackCheck in that list and enable it, then come back and tap " +
                    "\"Check network & devices\" again.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (network != null) {
            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = !checking,
            ) {
                Text("Export logs to Downloads (CSV)")
            }
        }
        exportStatus?.let {
            Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        }

        if (checking) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }

        network?.let { n ->
            Column(modifier = Modifier.padding(top = 12.dp)) {
                NetworkCard(n)
            }
        }
    }
}

@Composable
private fun NetworkCard(n: NetworkSnapshot) {
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Network & devices", fontWeight = FontWeight.Bold)

            Text("Cell service: ${n.cellState}", modifier = Modifier.padding(top = 6.dp))

            val wifi = n.wifi
            Text(
                if (wifi?.ssid != null)
                    "WiFi: ${wifi.ssid} (${wifi.frequencyMHz} MHz, ${wifi.linkSpeedMbps} Mbps)"
                else
                    "WiFi: not connected, or SSID unavailable (needs location permission + location services on)",
                modifier = Modifier.padding(top = 4.dp),
            )

            Text(
                if (n.bluetooth.isEmpty())
                    "Paired Bluetooth devices: none (or permission not granted)"
                else
                    "Paired Bluetooth devices (${n.bluetooth.size}):",
                modifier = Modifier.padding(top = 8.dp),
                fontWeight = FontWeight.Medium,
            )
            n.bluetooth.forEach { d ->
                Text("  ${d.name} (${d.address})", style = MaterialTheme.typography.bodySmall)
            }

            val usage = n.dataUsage
            if (usage != null) {
                Text(
                    "Data usage, last ${usage.windowDays} days (WiFi ${if (usage.wifiAvailable) "✓" else "unavailable"}, " +
                        "mobile ${if (usage.mobileAvailable) "✓" else "unavailable on this device/Android version"}):",
                    modifier = Modifier.padding(top = 8.dp),
                    fontWeight = FontWeight.Medium,
                )
                usage.rows.take(10).forEach { row ->
                    Text(
                        "  ${row.label}: ${formatBytes(row.wifiBytes + row.mobileBytes)} " +
                            "(WiFi ${formatBytes(row.wifiBytes)}, mobile ${formatBytes(row.mobileBytes)})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
