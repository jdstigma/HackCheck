package com.local.hackcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val HighColor = Color(0xFFB00020)
private val MediumColor = Color(0xFFFF6A3D)
private val InfoColor = Color(0xFF14202B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HackCheckScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HackCheckScreen() {
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("HackCheck") }) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Button(
                onClick = {
                    scanning = true
                    scope.launch(Dispatchers.Default) {
                        val r = runScan(context)
                        scanning = false
                        result = r
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning,
            ) {
                Text(if (scanning) "Scanning..." else "Run scan")
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
                Text(
                    "Scanned ${r.appsScanned} installed apps",
                    modifier = Modifier.padding(vertical = 12.dp),
                    fontWeight = FontWeight.Medium,
                )
                if (r.findings.isEmpty()) {
                    Text("No flags raised by this scan. Note: some things (like installed trusted CA certificates) can't be checked by any app and need a manual look -- see below.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(r.findings) { finding -> FindingCard(finding) }
                        item { ManualCheckCard() }
                    }
                }
                if (r.findings.isEmpty()) {
                    ManualCheckCard()
                }
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
            Text(
                finding.level.name,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Text(finding.title, fontWeight = FontWeight.Medium)
            Text(finding.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ManualCheckCard() {
    Card(modifier = Modifier.padding(top = 8.dp)) {
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
