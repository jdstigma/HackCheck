package com.local.hackcheck

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RfSweepScreen(
    wifiNetworks: List<NearbyWifiNetwork>,
    bleDevices: List<NearbyBleDevice>,
    scanningWifi: Boolean,
    scanningBle: Boolean,
    onScanWifi: () -> Unit,
    onScanBle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Wireless sweep",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Phones can't detect general RF bugs the way a dedicated " +
                "spectrum analyzer can -- but WiFi and Bluetooth are bands " +
                "the phone genuinely hears, which covers a lot of cheap " +
                "hidden cameras and BLE trackers.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )

        // --- WiFi ---
        Text("Nearby WiFi networks", fontWeight = FontWeight.Medium)
        Text(
            "Look for networks you don't recognize, open (unsecured) " +
                "networks, or hidden networks with unusually strong signal.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Button(onClick = onScanWifi, modifier = Modifier.fillMaxWidth(), enabled = !scanningWifi) {
            Text(if (scanningWifi) "Scanning..." else "Scan WiFi networks")
        }
        if (scanningWifi) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
        }
        if (!scanningWifi && wifiNetworks.isEmpty()) {
            Text(
                "No scan yet, or nothing found.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        wifiNetworks.forEach { net ->
            Card(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        if (net.isHidden) "(hidden network)" else net.ssid,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${net.bssid} -- ${net.signalDbm} dBm" +
                            (if (net.isOpen) " -- OPEN (unsecured)" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // --- BLE ---
        Text(
            "Nearby Bluetooth devices",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            "Scans for ALL advertising BLE devices nearby, not just ones " +
                "you've paired with -- an unnamed device with strong, " +
                "consistent signal is worth a second look.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Button(onClick = onScanBle, modifier = Modifier.fillMaxWidth(), enabled = !scanningBle) {
            Text(if (scanningBle) "Scanning (8s)..." else "Scan for BLE devices")
        }
        if (scanningBle) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
        }
        if (!scanningBle && bleDevices.isEmpty()) {
            Text(
                "No scan yet, or nothing found.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        bleDevices.forEach { dev ->
            Card(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(dev.name ?: "(unnamed device)", fontWeight = FontWeight.Medium)
                    Text("${dev.address} -- ${dev.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // --- Camera lens check ---
        Text(
            "Hidden camera lens check",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Not automated here -- but a real, low-effort check you " +
                        "can do with your existing camera app:",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "1. Darken the room as much as possible.\n" +
                        "2. Open your phone's camera app.\n" +
                        "3. Slowly pan it around the room, watching the " +
                        "screen (not the room directly).\n" +
                        "4. Small hidden camera lenses often show up as a " +
                        "tiny bright glint or dot, sometimes with a faint " +
                        "colored halo -- especially from IR LEDs, though " +
                        "many modern phone cameras filter IR fairly " +
                        "aggressively, so this isn't fully reliable.\n" +
                        "5. Check unusual objects at eye level or aimed at " +
                        "beds/desks -- smoke detectors, clocks, chargers, " +
                        "small electronics that don't otherwise need a " +
                        "clear line of sight to you.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
