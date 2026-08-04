package com.local.hackcheck

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Same severity color scheme as dashboard.html's .severity-1/2/3 classes,
// for anyone who's used both -- 1 = high, 3 = low, per Suricata's convention.
private fun severityColor(severity: Int?): Color = when (severity) {
    1 -> Color(0xFFFF6A6A)
    2 -> Color(0xFFFFB84D)
    else -> Color(0xFF9AA0AC)
}

// Whole screen is one LazyColumn (static header content as item{} blocks,
// the two lists as items()) rather than a plain Column -- avoids the same
// unreachable-content bug the Router screen had before its rewrite.
@Composable
fun SecurityScreen(
    backendUrl: String,
    alerts: List<SecurityAlertInfo>,
    topDomains: List<TopDnsDomain>,
    checking: Boolean,
    onRefresh: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text("Security", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "Signature-based alerts from Suricata, and DNS activity from Pi-hole. " +
                    "Uses the backend set on the Router screen: $backendUrl",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), enabled = !checking) {
                Text(if (checking) "Loading..." else "Refresh (last 24h alerts, last 1h DNS)")
            }
            if (checking) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    CircularProgressIndicator()
                }
            }
        }

        item {
            Text(
                "Alerts (${alerts.size})",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            if (alerts.isEmpty() && !checking) {
                Text(
                    "No alerts in this window -- or Suricata isn't installed/running on the backend box.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(alerts) { alert ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row {
                        Text(
                            "Severity ${alert.severity ?: "?"}",
                            color = severityColor(alert.severity),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(alert.signature, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "${alert.category ?: "?"} -- ${alert.srcIp ?: "?"} -> ${alert.dstIp ?: "?"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(alert.timestamp, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Text(
                "Top DNS domains (${topDomains.size})",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            if (topDomains.isEmpty() && !checking) {
                Text(
                    "No DNS queries in this window -- or Pi-hole isn't installed/running on the backend box.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(topDomains) { domain ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(domain.domain, fontWeight = FontWeight.Medium)
                    Text(
                        "${domain.queryCount} queries" +
                            if (domain.blockedCount > 0) ", ${domain.blockedCount} blocked" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (domain.blockedCount > 0) severityColor(1) else Color.Unspecified,
                    )
                }
            }
        }
    }
}
