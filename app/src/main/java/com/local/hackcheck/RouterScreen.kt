package com.local.hackcheck

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Everything in this screen lives in one LazyColumn (static sections as
// item{} blocks, lists as items()) rather than a plain Column wrapping a
// nested LazyColumn. That earlier structure meant the whole screen had no
// scroll mechanism of its own -- content past the top-talkers list (the
// devices list, and the Hardware setup buttons at the very bottom) could
// get pushed off-screen with no way to reach it. A single LazyColumn also
// avoids the infinite-height-constraint crash that nesting a LazyColumn
// inside a verticalScroll(Column) would cause.
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
    appUsage: DataUsageResult?,
    checkingAppUsage: Boolean,
    usageAccessGranted: Boolean?,
    onCheckAppUsage: () -> Unit,
    onGrantUsageAccess: () -> Unit,
) {
    val context = LocalContext.current

    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text("App data usage", fontWeight = FontWeight.Bold)
            Text(
                "Works on any device right now -- no switch, box, or setup " +
                    "needed. Shows which apps have used the most data recently, " +
                    "using Android's own usage stats.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (usageAccessGranted == false) {
                Text(
                    "Requires \"Usage access\" -- a special permission granted " +
                        "in Settings, not the normal permission prompt.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Button(
                    onClick = onGrantUsageAccess,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Text("Grant usage access")
                }
            }

            Button(
                onClick = onCheckAppUsage,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = !checkingAppUsage,
            ) {
                Text(if (checkingAppUsage) "Checking..." else "Check app data usage")
            }

            appUsage?.let { usage ->
                Text(
                    "Last ${usage.windowDays} days " +
                        "(WiFi ${if (usage.wifiAvailable) "\u2713" else "unavailable"}, " +
                        "mobile ${if (usage.mobileAvailable) "\u2713" else "unavailable"}):",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                usage.rows.take(10).forEach { row ->
                    Text(
                        "${row.label}: ${formatBytes(row.wifiBytes + row.mobileBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        item {
            Text(
                "Backend URL",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 24.dp),
            )
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
                Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    CircularProgressIndicator()
                }
            }
        }

        item {
            // Pi-hole's admin page lives on the same box but a different port
            // (its own web server, not router-backend's FastAPI) -- Uri.host
            // strips the scheme and port from baseUrl cleanly rather than
            // string-splitting it by hand.
            val host = remember(baseUrl) { Uri.parse(baseUrl).host ?: baseUrl }

            Text(
                "Quick links",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
            )
            OutlinedButton(
                onClick = {
                    val uri = Uri.parse("$baseUrl/static/dashboard.html")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open dashboard")
            }
            OutlinedButton(
                onClick = {
                    val uri = Uri.parse("$baseUrl/static/topology.html")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Open topology graph")
            }
            OutlinedButton(
                onClick = {
                    val uri = Uri.parse("http://$host/admin")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Open Pi-hole admin")
            }
        }

        if (topTalkers.isNotEmpty()) {
            item {
                Text(
                    "Top talkers (by bandwidth)",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
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

        if (devices.isNotEmpty()) {
            item {
                Text(
                    "All known devices (${devices.size})",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(devices) { d ->
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

        item {
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
}
