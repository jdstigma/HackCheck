package com.local.hackcheck

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RouterDeviceDetailScreen(
    device: RouterDevice?,
    summary: TrafficSummary?,
    flows: List<RouterFlow>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            device?.hostname ?: device?.macAddress ?: "Device",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        device?.let {
            Text(it.macAddress, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }

        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !loading) {
            Text(if (loading) "Loading..." else "Refresh (last 60 min)")
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Back to Router")
        }

        if (loading) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }

        summary?.let { s ->
            Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Traffic summary (last ${s.sinceMinutes} min)", fontWeight = FontWeight.Bold)
                    Text("Sent: ${formatBytes(s.totalBytesSent)}", modifier = Modifier.padding(top = 4.dp))
                    Text("Received: ${formatBytes(s.totalBytesRecv)}")
                    Text("Flow count: ${s.flowCount}")
                }
            }
        }

        if (flows.isNotEmpty()) {
            Text(
                "Recent flows",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            LazyColumn {
                items(flows) { flow ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "${flow.srcIp} -> ${flow.dstIp}${flow.dstPort?.let { ":$it" } ?: ""}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${flow.protocol ?: "?"} -- sent ${formatBytes(flow.bytesSent)}, " +
                                    "recv ${formatBytes(flow.bytesRecv)} -- ${flow.timestamp}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        } else if (!loading) {
            Text(
                "No flows in this window.",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
