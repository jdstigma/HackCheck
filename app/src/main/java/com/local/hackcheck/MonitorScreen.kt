package com.local.hackcheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MonitorScreen(
    running: Boolean,
    intervalMinutes: Int,
    log: List<LogEntry>,
    exportStatus: String?,
    onIntervalChange: (Int) -> Unit,
    onToggle: () -> Unit,
    onRefreshLog: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Snapshot interval: " + (if (intervalMinutes <= 0) "Off (event-driven only)" else "$intervalMinutes min") +
                if (running) " (stop monitoring to change)" else "",
            fontWeight = FontWeight.Medium,
        )
        @Composable
        fun IntervalButton(minutes: Int) {
            val selected = intervalMinutes == minutes
            val label = if (minutes == 0) "Off" else "${minutes}m"
            if (selected) {
                Button(onClick = { onIntervalChange(minutes) }, enabled = !running) { Text(label) }
            } else {
                OutlinedButton(onClick = { onIntervalChange(minutes) }, enabled = !running) { Text(label) }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            listOf(0, 5, 15).forEach { IntervalButton(it) }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            listOf(30, 60).forEach { IntervalButton(it) }
        }

        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(if (running) "Stop background monitoring" else "Start background monitoring")
        }
        Text(
            if (running)
                "Running -- see the persistent notification. Logging cell/WiFi changes as they happen" +
                    (if (intervalMinutes > 0) ", plus a snapshot every $intervalMinutes min." else " (no periodic snapshots).") +
                    " Survives a reboot automatically."
            else
                "Not running. Android requires a visible notification while this runs (can't be hidden).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        OutlinedButton(
            onClick = onRefreshLog,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text("Refresh monitoring log (${log.size} events so far)")
        }

        if (log.isNotEmpty()) {
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Export monitoring log (${log.size} events)")
            }
        }
        exportStatus?.let {
            Text(it, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
        }

        if (log.isNotEmpty()) {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Most recent 15 of ${log.size}", fontWeight = FontWeight.Bold)
                    log.takeLast(15).reversed().forEach { e ->
                        Text(
                            "${e.timestamp}  [${e.eventType}]  ${e.detail}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
