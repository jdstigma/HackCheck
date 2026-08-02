package com.local.hackcheck

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun CellTowerHistoryScreen(towers: List<SeenTower>) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "${towers.size} tower${if (towers.size == 1) "" else "s"} in your personal map",
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Built up automatically every time you check Cell Tower Locator -- " +
                "each unique tower is recorded once, with how many times and when " +
                "you've seen it.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        if (towers.isEmpty()) {
            Text(
                "Nothing recorded yet -- go check Cell Tower Locator first.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        LazyColumn {
            items(towers) { tower ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(tower.networkType, fontWeight = FontWeight.Medium)
                        Text(
                            "Seen ${tower.timesSeen} time${if (tower.timesSeen == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            "First: ${dateFormat.format(Date(tower.firstSeenMillis))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Last: ${dateFormat.format(Date(tower.lastSeenMillis))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${tower.lat}, ${tower.lon}" +
                                (tower.rangeMeters?.let { " (\u00b1${it}m)" } ?: ""),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        OutlinedButton(
                            onClick = {
                                val uri = Uri.parse("geo:${tower.lat},${tower.lon}?q=${tower.lat},${tower.lon}(Cell+tower)")
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            },
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Text("Open in Maps")
                        }
                    }
                }
            }
        }
    }
}
