package com.local.hackcheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    scanSubtitle: String,
    networkSubtitle: String,
    cellTowerSubtitle: String,
    monitorSubtitle: String,
    captureSubtitle: String,
    toolsSubtitle: String,
    onOpen: (Screen) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MenuButton("App Scan", scanSubtitle) { onOpen(Screen.Scan) }
        MenuButton("Network & Devices", networkSubtitle) { onOpen(Screen.Network) }
        MenuButton("Cell Tower Locator", cellTowerSubtitle) { onOpen(Screen.CellTower) }
        MenuButton("Background Monitoring", monitorSubtitle) { onOpen(Screen.Monitor) }
        if (CAPTURE_FEATURE_ENABLED) {
            MenuButton("Traffic Capture", captureSubtitle) { onOpen(Screen.Capture) }
        }
        MenuButton("Network Tools", toolsSubtitle) { onOpen(Screen.Tools) }
    }
}

@Composable
private fun MenuButton(title: String, subtitle: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
