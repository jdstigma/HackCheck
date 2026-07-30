package com.local.hackcheck

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val HighColor = Color(0xFFB00020)
private val MediumColor = Color(0xFFFF6A3D)
private val InfoColor = Color(0xFF14202B)

data class NetworkSnapshot(
    val wifi: WifiSnapshot?,
    val bluetooth: List<BtDeviceRow>,
    val cellState: String,
    val usageAccessGranted: Boolean,
    val dataUsage: DataUsageResult?,
)

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
    var inventory by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var network by remember { mutableStateOf<NetworkSnapshot?>(null) }
    var checkingNetwork by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun runNetworkChecks() {
        checkingNetwork = true
        scope.launch(Dispatchers.Default) {
            val usageGranted = hasUsageAccess(context)
            val snapshot = NetworkSnapshot(
                wifi = currentWifiInfo(context),
                bluetooth = pairedBluetoothDevices(context),
                cellState = cellServiceState(context),
                usageAccessGranted = usageGranted,
                dataUsage = if (usageGranted) dataUsageByApp(context) else null,
            )
            checkingNetwork = false
            network = snapshot
        }
    }

    val permissionsToRequest = remember {
        buildList {
            add(android.Manifest.permission.READ_PHONE_STATE)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        runNetworkChecks()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("HackCheck") }) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Button(
                onClick = {
                    scanning = true
                    exportStatus = null
                    scope.launch(Dispatchers.Default) {
                        val r = runScan(context)
                        val inv = rawInventory(context)
                        scanning = false
                        result = r
                        inventory = inv
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning,
            ) {
                Text(if (scanning) "Scanning..." else "Run scan")
            }

            Button(
                onClick = { permissionLauncher.launch(permissionsToRequest) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = !checkingNetwork,
            ) {
                Text(if (checkingNetwork) "Checking..." else "Check network & devices")
            }

            if (network?.usageAccessGranted == false) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        context.startActivity(intent)
                    },
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

            if (result != null) {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val paths = Exporter.exportAll(context, result!!, inventory, network)
                            exportStatus = if (paths.isEmpty())
                                "Export failed"
                            else
                                "Saved: ${paths.joinToString(", ")}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !scanning,
                ) {
                    Text("Export logs to Downloads (CSV)")
                }
            }

            exportStatus?.let {
                Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }

            if (scanning || checkingNetwork) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                network?.let { n -> item { NetworkCard(n) } }

                result?.let { r ->
                    item {
                        Text(
                            "Scanned ${r.appsScanned} installed apps",
                            fontWeight = FontWeight.Medium,
                        )
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
