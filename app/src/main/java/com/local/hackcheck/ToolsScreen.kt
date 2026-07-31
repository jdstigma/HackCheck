package com.local.hackcheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ToolsHistoryEntry(val command: String, val output: String)

@Composable
fun ToolsScreen(
    input: String,
    onInputChange: (String) -> Unit,
    history: List<ToolsHistoryEntry>,
    running: Boolean,
    onRun: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "No-root network recon: ping, DNS lookup, TCP port scan, local interface info. " +
                "Type \"help\" for the command list. Only use against hosts/networks you're " +
                "authorized to test.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            enabled = !running,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Button(onClick = onRun, enabled = !running && input.isNotBlank()) {
                Text(if (running) "Running..." else "Run")
            }
            OutlinedButton(onClick = onClear, enabled = !running) {
                Text("Clear")
            }
        }

        if (running) {
            Row(modifier = Modifier.padding(top = 8.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("Running...", style = MaterialTheme.typography.bodySmall)
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            items(history.reversed()) { entry -> HistoryCard(entry) }
        }
    }
}

@Composable
private fun HistoryCard(entry: ToolsHistoryEntry) {
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "> ${entry.command}",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                entry.output,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
