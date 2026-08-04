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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // RF/wireless sweep state
    var wifiNetworks by remember { mutableStateOf<List<NearbyWifiNetwork>>(emptyList()) }
    var bleDevices by remember { mutableStateOf<List<NearbyBleDevice>>(emptyList()) }
    var scanningWifi by remember { mutableStateOf(false) }
    var scanningBle by remember { mutableStateOf(false) }

    // Cell tower state
    var cellTowers by remember { mutableStateOf<List<CellTowerInfo>>(emptyList()) }
    var cellTowerLocations by remember { mutableStateOf<Map<Long, CellTowerLocation?>>(emptyMap()) }
    var checkingCellTowers by remember { mutableStateOf(false) }
    val cellTowerHistoryDb = remember { CellTowerHistoryDb(context) }
    var seenTowers by remember { mutableStateOf<List<SeenTower>>(emptyList()) }

    // Load the persisted tower history once when the screen first composes,
    // so the "X towers seen" count on Cell Tower Locator is accurate even
    // before the user taps Check this session.
    LaunchedEffect(Unit) {
        seenTowers = withContext(Dispatchers.IO) { cellTowerHistoryDb.allSeenTowers() }
    }
    val openCellIdApiKey = BuildConfig.OPENCELLID_API_KEY

    // Router backend state
    var routerBaseUrl by remember { mutableStateOf(RouterPrefs.getBaseUrl(context)) }
    var routerConnectionStatus by remember { mutableStateOf<String?>(null) }
    var checkingRouter by remember { mutableStateOf(false) }
    var topTalkers by remember { mutableStateOf<List<TopTalker>>(emptyList()) }
    var routerDevices by remember { mutableStateOf<List<RouterDevice>>(emptyList()) }

    // Security screen state (Suricata alerts + Pi-hole top domains) --
    // reuses routerBaseUrl rather than its own separate URL field
    var securityAlerts by remember { mutableStateOf<List<SecurityAlertInfo>>(emptyList()) }
    var securityTopDomains by remember { mutableStateOf<List<TopDnsDomain>>(emptyList()) }
    var checkingSecurity by remember { mutableStateOf(false) }

    // App data usage state (no hardware needed -- shown at the top of the
    // Router screen as the no-setup alternative to the ntopng path below it)
    var routerAppUsage by remember { mutableStateOf<DataUsageResult?>(null) }
    var checkingRouterAppUsage by remember { mutableStateOf(false) }
    var routerUsageAccessGranted by remember { mutableStateOf<Boolean?>(null) }

    // Router device detail state
    var selectedRouterDevice by remember { mutableStateOf<RouterDevice?>(null) }
    var selectedDeviceSummary by remember { mutableStateOf<TrafficSummary?>(null) }
    var selectedDeviceFlows by remember { mutableStateOf<List<RouterFlow>>(emptyList()) }
    var loadingDeviceDetail by remember { mutableStateOf(false) }

    // ntopng box control state
    var ntopngBoxRunning by remember { mutableStateOf<Boolean?>(null) }
    var checkingNtopngStatus by remember { mutableStateOf(false) }
    var ntopngActionInProgress by remember { mutableStateOf(false) }
    var ntopngLastActionResult by remember { mutableStateOf<String?>(null) }

    // Monitor state
    var monitoringRunning by remember { mutableStateOf(MonitorService.isRunning(context)) }
    var monitorLog by remember { mutableStateOf(MonitorLog.readAll(context)) }
    var monitorExportStatus by remember { mutableStateOf<String?>(null) }
    var intervalMinutes by remember { mutableStateOf(MonitorPrefs.getIntervalMinutes(context)) }

    // Capture state
    var capturingRunning by remember { mutableStateOf(CaptureVpnService.isRunning(context)) }
    var captureLog by remember { mutableStateOf(CaptureLog.readAll(context)) }
    var captureExportStatus by remember { mutableStateOf<String?>(null) }

    // Network tools (CLI) state
    var toolsInput by remember { mutableStateOf("") }
    var toolsHistory by remember { mutableStateOf<List<ToolsHistoryEntry>>(emptyList()) }
    var toolsRunning by remember { mutableStateOf(false) }

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

    fun runWifiScan() {
        scanningWifi = true
        scope.launch(Dispatchers.Default) {
            wifiNetworks = scanNearbyWifi(context)
            scanningWifi = false
        }
    }

    fun runBleScan() {
        scanningBle = true
        scope.launch(Dispatchers.Default) {
            bleDevices = scanNearbyBle(context)
            scanningBle = false
        }
    }

    fun runCellTowerChecks() {
        checkingCellTowers = true
        scope.launch(Dispatchers.Default) {
            val cells = visibleCellTowers(context)
            cellTowers = cells
            val locations = cells.associate { cell ->
                cell.cellId to geolocateCellTower(cell, openCellIdApiKey)
            }
            cellTowerLocations = locations

            val now = System.currentTimeMillis()
            for (cell in cells) {
                val location = locations[cell.cellId] ?: continue
                cellTowerHistoryDb.recordSighting(cell, location, now)
            }
            seenTowers = cellTowerHistoryDb.allSeenTowers()

            checkingCellTowers = false
        }
    }

    fun runAppUsageCheck() {
        checkingRouterAppUsage = true
        scope.launch(Dispatchers.Default) {
            val granted = hasUsageAccess(context)
            routerUsageAccessGranted = granted
            routerAppUsage = if (granted) dataUsageByApp(context) else null
            checkingRouterAppUsage = false
        }
    }

    fun checkRouterConnection() {
        checkingRouter = true
        routerConnectionStatus = null
        RouterPrefs.setBaseUrl(context, routerBaseUrl)
        scope.launch(Dispatchers.Default) {
            val ok = RouterApi.healthCheck(routerBaseUrl)
            routerConnectionStatus = if (ok) "Connected" else "Could not reach backend at $routerBaseUrl"
            checkingRouter = false
        }
    }

    fun refreshRouterData() {
        checkingRouter = true
        RouterPrefs.setBaseUrl(context, routerBaseUrl)
        scope.launch(Dispatchers.Default) {
            topTalkers = RouterApi.fetchTopTalkers(routerBaseUrl)
            routerDevices = RouterApi.fetchDevices(routerBaseUrl)
            checkingRouter = false
        }
    }

    fun refreshSecurityData() {
        checkingSecurity = true
        scope.launch(Dispatchers.Default) {
            securityAlerts = RouterApi.fetchAlerts(routerBaseUrl)
            securityTopDomains = RouterApi.fetchTopDnsDomains(routerBaseUrl)
            checkingSecurity = false
        }
    }

    fun loadDeviceDetail(device: RouterDevice) {
        selectedRouterDevice = device
        loadingDeviceDetail = true
        scope.launch(Dispatchers.Default) {
            selectedDeviceSummary = RouterApi.fetchTrafficSummary(routerBaseUrl, device.id)
            selectedDeviceFlows = RouterApi.fetchFlows(routerBaseUrl, deviceId = device.id)
            loadingDeviceDetail = false
        }
    }

    fun checkNtopngStatus() {
        checkingNtopngStatus = true
        scope.launch(Dispatchers.Default) {
            ntopngBoxRunning = RouterApi.ntopngStatus(routerBaseUrl)
            checkingNtopngStatus = false
        }
    }

    fun startNtopngBox() {
        ntopngActionInProgress = true
        ntopngLastActionResult = null
        scope.launch(Dispatchers.Default) {
            val result = RouterApi.startNtopngBox(routerBaseUrl)
            ntopngLastActionResult = result.message
            ntopngActionInProgress = false
            checkNtopngStatus()
        }
    }

    fun stopNtopngBox() {
        ntopngActionInProgress = true
        ntopngLastActionResult = null
        scope.launch(Dispatchers.Default) {
            val result = RouterApi.stopNtopngBox(routerBaseUrl)
            ntopngLastActionResult = result.message
            ntopngActionInProgress = false
            checkNtopngStatus()
        }
    }

    val networkPermissionsToRequest = remember {
        buildList {
            add(android.Manifest.permission.READ_PHONE_STATE)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
                add(android.Manifest.permission.BLUETOOTH_SCAN)
            }
        }.toTypedArray()
    }
    val networkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        runNetworkChecks()
    }
    val cellTowerPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        runCellTowerChecks()
    }
    val rfWifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        runWifiScan()
    }
    val rfBlePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        runBleScan()
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
    val routerSubtitle = when {
        checkingRouter -> "Checking..."
        topTalkers.isEmpty() && routerDevices.isEmpty() -> "Not checked yet"
        else -> "${routerDevices.size} devices, ${topTalkers.size} top talkers"
    }
    val securitySubtitle = when {
        checkingSecurity -> "Checking..."
        securityAlerts.isEmpty() && securityTopDomains.isEmpty() -> "Not checked yet"
        else -> "${securityAlerts.size} alerts, ${securityTopDomains.size} DNS domains"
    }
    val rfSweepSubtitle = when {
        scanningWifi || scanningBle -> "Scanning..."
        wifiNetworks.isEmpty() && bleDevices.isEmpty() -> "Not checked yet"
        else -> "${wifiNetworks.size} WiFi, ${bleDevices.size} BLE devices seen"
    }
    val cellTowerSubtitle = when {
        checkingCellTowers -> "Checking..."
        cellTowers.isEmpty() -> "Not checked yet"
        else -> "${cellTowers.size} visible, ${cellTowerLocations.values.count { it != null }} located"
    }
    val monitorSubtitle = if (monitoringRunning)
        "Running, ${monitorLog.size} events logged"
    else
        "Stopped, ${monitorLog.size} events logged"
    val captureSubtitle = if (capturingRunning)
        "Running, ${captureLog.size} flows logged"
    else
        "Stopped, ${captureLog.size} flows logged"
    val toolsSubtitle = if (toolsHistory.isEmpty()) "ping, DNS, port scan, and more" else "${toolsHistory.size} commands run"

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
                    rfSweepSubtitle = rfSweepSubtitle,
                    cellTowerSubtitle = cellTowerSubtitle,
                    routerSubtitle = routerSubtitle,
                    securitySubtitle = securitySubtitle,
                    monitorSubtitle = monitorSubtitle,
                    captureSubtitle = captureSubtitle,
                    toolsSubtitle = toolsSubtitle,
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

                Screen.RfSweep -> RfSweepScreen(
                    wifiNetworks = wifiNetworks,
                    bleDevices = bleDevices,
                    scanningWifi = scanningWifi,
                    scanningBle = scanningBle,
                    onScanWifi = { rfWifiPermissionLauncher.launch(networkPermissionsToRequest) },
                    onScanBle = { rfBlePermissionLauncher.launch(networkPermissionsToRequest) },
                )

                Screen.CellTower -> CellTowerScreen(
                    cells = cellTowers,
                    locations = cellTowerLocations,
                    checking = checkingCellTowers,
                    seenTowerCount = seenTowers.size,
                    onCheck = { cellTowerPermissionLauncher.launch(networkPermissionsToRequest) },
                    onViewHistory = { currentScreen = Screen.CellTowerHistory },
                )

                Screen.CellTowerHistory -> CellTowerHistoryScreen(
                    towers = seenTowers,
                )

                Screen.Router -> RouterScreen(
                    baseUrl = routerBaseUrl,
                    onBaseUrlChange = { routerBaseUrl = it },
                    connectionStatus = routerConnectionStatus,
                    checking = checkingRouter,
                    topTalkers = topTalkers,
                    devices = routerDevices,
                    onCheckConnection = { checkRouterConnection() },
                    onRefresh = { refreshRouterData() },
                    onOpenBoxInfo = { currentScreen = Screen.NtopngBoxInfo },
                    onOpenBoxSetup = {
                        currentScreen = Screen.NtopngBoxSetup
                        checkNtopngStatus()
                    },
                    appUsage = routerAppUsage,
                    checkingAppUsage = checkingRouterAppUsage,
                    usageAccessGranted = routerUsageAccessGranted,
                    onCheckAppUsage = { runAppUsageCheck() },
                    onGrantUsageAccess = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    onDeviceClick = { device ->
                        currentScreen = Screen.RouterDeviceDetail
                        loadDeviceDetail(device)
                    },
                )

                Screen.RouterDeviceDetail -> RouterDeviceDetailScreen(
                    device = selectedRouterDevice,
                    summary = selectedDeviceSummary,
                    flows = selectedDeviceFlows,
                    loading = loadingDeviceDetail,
                    onRefresh = { selectedRouterDevice?.let { loadDeviceDetail(it) } },
                    onBack = { currentScreen = Screen.Router },
                )

                Screen.Security -> SecurityScreen(
                    backendUrl = routerBaseUrl,
                    alerts = securityAlerts,
                    topDomains = securityTopDomains,
                    checking = checkingSecurity,
                    onRefresh = { refreshSecurityData() },
                )

                Screen.NtopngBoxInfo -> NtopngBoxInfoScreen()

                Screen.NtopngBoxSetup -> NtopngBoxSetupScreen(
                    boxRunning = ntopngBoxRunning,
                    checkingStatus = checkingNtopngStatus,
                    actionInProgress = ntopngActionInProgress,
                    lastActionResult = ntopngLastActionResult,
                    onCheckStatus = { checkNtopngStatus() },
                    onStart = { startNtopngBox() },
                    onStop = { stopNtopngBox() },
                    onViewSetupScript = {
                        val uri = android.net.Uri.parse(
                            "https://github.com/jdstigma/HackCheck/blob/main/router-backend/setup-pi.sh"
                        )
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
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

                Screen.Tools -> ToolsScreen(
                    input = toolsInput,
                    onInputChange = { toolsInput = it },
                    history = toolsHistory,
                    running = toolsRunning,
                    onRun = {
                        val command = toolsInput
                        toolsRunning = true
                        scope.launch(Dispatchers.Default) {
                            val output = NetworkTools.run(context, command)
                            toolsHistory = toolsHistory + ToolsHistoryEntry(command, output)
                            toolsRunning = false
                            toolsInput = ""
                        }
                    },
                    onClear = { toolsHistory = emptyList() },
                )
            }
        }
    }
}
