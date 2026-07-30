package com.local.hackcheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val HighColor = Color(0xFFB00020)
private val MediumColor = Color(0xFFFF6A3D)
private val InfoColor = Color(0xFF14202B)

@Composable
fun ScanScreen(
    result: ScanResult?,
    scanning: Boolean,
    exportStatus: String?,
    onRunScan: () -> Unit,
    onExport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onRunScan, modifier = Modifier.fillMaxWidth(), enabled = !scanning) {
            Text(if (scanning) "Scanning..." else "Run scan")
        }

        if (result != null) {
            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = !scanning,
            ) {
                Text("Export logs to Downloads (CSV)")
            }
        }
        exportStatus?.let {
            Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        }

        if (scanning) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }

        result?.let { r ->
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                item {
                    Text("Scanned ${r.appsScanned} installed apps", fontWeight = FontWeight.Medium)
                }
                if (r.findings.isEmpty()) {
                    item { Text("No flags raised by this scan.") }
                } else {
                    items(r.findings) { finding -> FindingCard(finding) }
                }
                item { ManualCheckCard() }
            }
        }
    }
}

@Composable
private fun FindingCard(finding: Finding) {
    val color = when (finding.level) {
        RiskLevel.HIGH -> HighColor
        RiskLevel.MEDIUM -> MediumColor
        RiskLevel.INFO -> InfoColor
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(finding.level.name, color = color, fontWeight = FontWeight.Bold)
            Text(finding.title, fontWeight = FontWeight.Medium)
            Text(finding.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ManualCheckCard() {
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Manual check needed", fontWeight = FontWeight.Bold)
            Text(
                "No app -- including this one -- can read the system-wide list of trusted " +
                    "certificates. Check it yourself: Settings > Security (or Encryption & " +
                    "credentials) > Trusted credentials > User tab. Anything listed there " +
                    "that you didn't install yourself is a red flag (can indicate traffic " +
                    "interception/monitoring).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
