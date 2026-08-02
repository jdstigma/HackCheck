package com.local.hackcheck

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class RequirementItem(val title: String, val body: String)

private val requirements = listOf(
    RequirementItem(
        "A managed/smart switch with port mirroring",
        "The switch your Archer (or main router) plugs into needs a " +
            "\"port mirror\" / SPAN feature -- most \"smart\" or " +
            "\"managed\" switches have this, cheap unmanaged switches " +
            "usually don't. Check your switch model's admin page under " +
            "a \"Monitoring\" or \"Port Mirror\" tab.",
    ),
    RequirementItem(
        "A dedicated box for ntopng",
        "A Raspberry Pi 4 (or newer -- a Pi 3 will struggle) or any " +
            "spare PC/laptop with a wired Ethernet port. WiFi will not " +
            "work for this; it has to be a physical cable into the " +
            "mirrored switch port.",
    ),
    RequirementItem(
        "Wired connection to the mirrored port",
        "An Ethernet cable from the box directly into whichever switch " +
            "port you configure as the mirror destination -- not into " +
            "the Archer, not into a regular switch port.",
    ),
    RequirementItem(
        "ntopng installed and running",
        "On Debian/Ubuntu/Raspberry Pi OS: sudo apt install ntopng. " +
            "It needs to be told which network interface to listen on " +
            "-- the one connected to the mirrored port.",
    ),
    RequirementItem(
        "This backend running on the same box",
        "router-backend (Postgres + FastAPI, from the HackCheck repo) " +
            "needs to run on that same Pi/PC so it can poll ntopng " +
            "locally and, if you set it up, start/stop the ntopng " +
            "service directly.",
    ),
)

@Composable
fun NtopngBoxInfoScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "What you need to build the ntopng box",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "This is the piece that actually captures your network's traffic. " +
                "It's optional -- HackCheck works without it -- but it's what " +
                "powers the Router dashboard and network topology graph.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )

        requirements.forEach { item ->
            Text(item.title, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
            Text(item.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
