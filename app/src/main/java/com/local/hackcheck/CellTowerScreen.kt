package com.local.hackcheck

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CellTowerScreen(
    cells: List<CellTowerInfo>,
    locations: Map<Long, CellTowerLocation?>,
    checking: Boolean,
    seenTowerCount: Int,
    onCheck: () -> Unit,
    onViewHistory: () -> Unit,
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth(), enabled = !checking) {
            Text(if (checking) "Reading towers..." else "Check nearby cell towers")
        }
        OutlinedButton(
            onClick = onViewHistory,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Text("View my cell tower map ($seenTowerCount towers seen)")
        }

        Text(
            "Requires location permission (already granted if you've used Network & Devices). " +
                "Tower positions come from OpenCellID's crowdsourced database and are " +
                "approximate -- coverage has gaps outside dense urban areas.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (checking) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }

        if (!checking && cells.isEmpty()) {
            Text(
                "No visible cell towers detected yet.",
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        cells.forEach { cell ->
            Column(modifier = Modifier.padding(top = 12.dp)) {
                CellCard(cell, locations[cell.cellId]) { lat, lon ->
                    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Cell+tower)")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            }
        }
    }
}

@Composable
private fun CellCard(
    cell: CellTowerInfo,
    location: CellTowerLocation?,
    onOpenMap: (Double, Double) -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                if (cell.isServingCell) "Serving cell (${cell.networkType})" else cell.networkType,
                fontWeight = FontWeight.Bold,
            )
            Text("Cell ID: ${cell.cellId}", modifier = Modifier.padding(top = 4.dp))
            Text("MCC/MNC: ${cell.mcc ?: "?"}/${cell.mnc ?: "?"}")
            Text("Tracking/Location Area: ${cell.tac ?: "?"}")
            Text("Signal strength: ${cell.signalStrengthDbm ?: "?"} dBm")

            when {
                location != null -> {
                    Text(
                        "Approx. location: ${location.lat}, ${location.lon}" +
                            (location.rangeMeters?.let { " (±${it}m)" } ?: ""),
                        modifier = Modifier.padding(top = 8.dp),
                        fontWeight = FontWeight.Medium,
                    )
                    OutlinedButton(
                        onClick = { onOpenMap(location.lat, location.lon) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text("Open in Maps")
                    }
                }
                else -> {
                    Text(
                        "Not found in OpenCellID database",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
