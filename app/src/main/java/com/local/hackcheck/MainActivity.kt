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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                    HackCheckApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HackCheckApp() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var currentScreen by remember { mutableStateOf(Screen.Home) }

    // Scan state
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var inventory by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanExportStatus by remember { mutableStateOf<String?>(null) }

    // Network state
    var network by remember { mutableStateOf<NetworkSnapshot?>(null) }
    var checkingNetwork by remember { mutableStateOf(false) }
    var networkExportStatus by remember { mutableStateOf<String?>(null) }

    // Monitor state
    var monitoringRunning by remember { mutableStateOf(MonitorService.isRunning(context)) }
    var monitorLog by remember { mutableStateOf(MonitorLog.readAll(context)) }
    var monitorExportStatus by remember { mutableStateOf<String?>(null) }
    var intervalMinutes by remember { mutableStateOf(MonitorPrefs.getIntervalMinutes(context)) }

    // Capture state
    var capturingRunning by remember { mutableStateOf(CaptureVpnService.isRunning(context)) }
    var captureLog by remember { mutableStateOf(CaptureLog.readAll(context)) }
    var captureExportStatus by remember { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        MonitorService.start(context, intervalMinutes)
        monitoringRunning = true
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            CaptureVpnService.start(context)
            capturingRunning = true
        }
    }

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

    val networkPermissionsToRequest = remember {
        buildList {
            add(android.Manifest.permission.READ_PHONE_STATE)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
    }
    val networkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        runNetworkChecks()
    }

    val scanSubtitle = when {
        scanning -> "Scanning..."
        result == null -> "Not run yet"
        else -> "${result!!.findings.size} findings from ${result!!.appsScanned} apps"
    }
    val networkSubtitle = when {
        checkingNetwork -> "Checking..."
        network == null -> "Not checked yet"
        else -> "Cell: ${network!!.cellState} · WiFi: ${network!!.wifi?.ssid ?: "none"}"
    }
    val monitorSubtitle = if (monitoringRunning)
        "Running, ${monitorLog.size} events logged"
    else
        "Stopped, ${monitorLog.size} events logged"
    val captureSubtitle = if (capturingRunning)
        "Running, ${captureLog.size} flows logged"
    else
        "Stopped, ${captureLog.size} flows logged"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentScreen.title) },
                navigationIcon = {
                    if (currentScreen != Screen.Home) {
                        IconButton(onClick = { currentScreen = Screen.Home }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.padding(padding)) {
            when (currentScreen) {
                Screen.Home -> HomeScreen(
                    scanSubtitle = scanSubtitle,
                    networkSubtitle = networkSubtitle,
                    monitorSubtitle = monitorSubtitle,
                    captureSubtitle = captureSubtitle,
                    onOpen = { currentScreen = it },
                )

                Screen.Scan -> ScanScreen(
                    result = result,
                    scanning = scanning,
                    exportStatus = scanExportStatus,
                    onRunScan = {
                        scanning = true
                        scanExportStatus = null
                        scope.launch(Dispatchers.Default) {
                            val r = runScan(context)
                            val inv = rawInventory(context)
                            scanning = false
                            result = r
                            inventory = inv
                        }
                    },
                    onExport = {
                        scope.launch(Dispatchers.IO) {
                            val paths = Exporter.exportScan(context, result!!, inventory)
                            scanExportStatus = if (paths.isEmpty()) "Export failed" else "Saved: ${paths.joinToString(", ")}"
                        }
                    },
                )

                Screen.Network -> NetworkScreen(
                    network = network,
                    checking = checkingNetwork,
                    exportStatus = networkExportStatus,
                    onCheck = { networkPermissionLauncher.launch(networkPermissionsToRequest) },
                    onGrantUsageAccess = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    onExport = {
                        scope.launch(Dispatchers.IO) {
                            val path = Exporter.exportNetwork(context, network!!)
                            networkExportStatus = path?.let { "Saved: $it" } ?: "Export failed"
                        }
                    },
                )

                Screen.Monitor -> MonitorScreen(
                    running = monitoringRunning,
                    intervalMinutes = intervalMinutes,
                    log = monitorLog,
                    exportStatus = monitorExportStatus,
                    onIntervalChange = { minutes ->
                        intervalMinutes = minutes
                        MonitorPrefs.setIntervalMinutes(context, minutes)
                    },
                    onToggle = {
                        if (monitoringRunning) {
                            MonitorService.stop(context)
                            monitoringRunning = false
                            monitorLog = MonitorLog.readAll(context)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            MonitorService.start(context, intervalMinutes)
                            monitoringRunning = true
                        }
                    },
                    onRefreshLog = { monitorLog = MonitorLog.readAll(context) },
                    onExport = {
                        scope.launch(Dispatchers.IO) {
                            val path = Exporter.exportMonitorLog(context)
                            monitorExportStatus = path?.let { "Saved: $it" } ?: "Export failed"
                        }
                    },
                )

                Screen.Capture -> CaptureScreen(
                    running = capturingRunning,
                    log = captureLog,
                    exportStatus = captureExportStatus,
                    onToggle = {
                        if (capturingRunning) {
                            CaptureVpnService.stop(context)
                            capturingRunning = false
                            captureLog = CaptureLog.readAll(context)
                        } else {
                            val prepareIntent = android.net.VpnService.prepare(context)
                            if (prepareIntent != null) {
                                vpnPermissionLauncher.launch(prepareIntent)
                            } else {
                                CaptureVpnService.start(context)
                                capturingRunning = true
                            }
                        }
                    },
                    onRefreshLog = { captureLog = CaptureLog.readAll(context) },
                    onExport = {
                        scope.launch(Dispatchers.IO) {
                            val path = Exporter.exportCaptureLog(context)
                            captureExportStatus = path?.let { "Saved: $it" } ?: "Export failed"
                        }
                    },
                )
            }
        }
    }
}
