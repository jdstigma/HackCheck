package com.local.hackcheck

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val SETUP_SCRIPT_CURL_COMMAND =
    "curl -fsSL https://raw.githubusercontent.com/jdstigma/HackCheck/main/router-backend/setup-pi.sh | bash"
private const val SETUP_SCRIPT_VIEW_URL =
    "https://github.com/jdstigma/HackCheck/blob/main/router-backend/setup-pi.sh"

@Composable
fun NtopngBoxSetupScreen(
    boxRunning: Boolean?,
    checkingStatus: Boolean,
    actionInProgress: Boolean,
    lastActionResult: String?,
    onCheckStatus: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onViewSetupScript: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Box configuration",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )

        Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Automated setup (recommended)", fontWeight = FontWeight.Medium)
                Text(
                    "Run this ON the Pi/PC itself (SSH in, or its own " +
                        "terminal) -- not from this phone. It installs " +
                        "ntopng, clones the repo, sets up router-backend's " +
                        "Python environment, and configures the sudoers " +
                        "entry needed for the Start/Stop buttons below.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                SelectionContainer {
                    Text(
                        SETUP_SCRIPT_CURL_COMMAND,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(SETUP_SCRIPT_CURL_COMMAND)) },
                    ) {
                        Text("Copy command")
                    }
                }
                OutlinedButton(
                    onClick = onViewSetupScript,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("View script on GitHub first")
                }
                Text(
                    "Worth reading before piping anything into bash, " +
                        "including this one.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Text(
            "Manual steps (if you'd rather not run the script)",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )

        SetupStep(
            "1. Install ntopng",
            "sudo apt install ntopng, then configure it to listen on the " +
                "interface connected to your mirrored switch port.",
        )
        SetupStep(
            "2. Set up router-backend",
            "Clone the HackCheck repo, follow router-backend/README.md " +
                "(Postgres, .env, init_db.py, uvicorn) -- run it on this " +
                "same box so it's reachable at the URL you set on the " +
                "Router screen.",
        )
        SetupStep(
            "3. Allow this backend to control ntopng (optional)",
            "For the Start/Stop buttons below to work, this box needs " +
                "passwordless sudo for exactly the systemctl start/stop " +
                "commands for ntopng -- run \"sudo visudo\" and add the " +
                "line documented at the top of router-backend/" +
                "ntopng_control.py. Skip this step if you'd rather " +
                "start/stop ntopng manually on the box itself; " +
                "everything else still works without it.",
        )

        Card(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("ntopng service control", fontWeight = FontWeight.Medium)
                Text(
                    "Requires step 3 above. If that's not set up, these " +
                        "buttons will show a clear error rather than doing " +
                        "anything silently.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )

                Row {
                    Text(
                        when {
                            checkingStatus -> "Checking status..."
                            boxRunning == true -> "Status: running"
                            boxRunning == false -> "Status: stopped"
                            else -> "Status: unknown"
                        },
                        fontWeight = FontWeight.Medium,
                    )
                }

                Button(
                    onClick = onCheckStatus,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    enabled = !checkingStatus && !actionInProgress,
                ) {
                    Text("Refresh status")
                }

                if (actionInProgress) {
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        CircularProgressIndicator()
                    }
                }

                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    enabled = !actionInProgress && !checkingStatus,
                ) {
                    Text("Start box")
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !actionInProgress && !checkingStatus,
                ) {
                    Text("Stop box")
                }

                lastActionResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun SetupStep(title: String, body: String) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    }
}
