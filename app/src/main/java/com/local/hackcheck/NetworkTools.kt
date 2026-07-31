package com.local.hackcheck

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

private const val MAX_PORT_SCAN_RANGE = 1024
private const val PORT_SCAN_TIMEOUT_MS = 400

/** No-root network recon commands for the in-app CLI. All standard Java/Android APIs or
 *  shelling out to the device's own system ping binary -- no raw sockets, no elevated access. */
object NetworkTools {

    val helpText = """
        Commands:
          ping <host> [count]        ICMP ping via the system ping binary (default count 4)
          dns <host>                 Resolve a hostname to its IP address(es)
          portscan <host> <start> <end>   TCP connect scan (max $MAX_PORT_SCAN_RANGE ports/run)
          myip                       This device's local network interfaces/addresses
          help                       Show this text
    """.trimIndent()

    suspend fun run(context: Context, commandLine: String): String {
        val parts = commandLine.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        return when (parts[0].lowercase()) {
            "help", "?" -> helpText
            "ping" -> {
                if (parts.size < 2) "Usage: ping <host> [count]"
                else ping(parts[1], parts.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 10) ?: 4)
            }
            "dns", "nslookup", "resolve" -> {
                if (parts.size < 2) "Usage: dns <host>" else dnsLookup(parts[1])
            }
            "portscan", "scan" -> {
                if (parts.size < 4) "Usage: portscan <host> <startPort> <endPort>"
                else {
                    val start = parts[2].toIntOrNull()
                    val end = parts[3].toIntOrNull()
                    if (start == null || end == null || start !in 1..65535 || end !in 1..65535 || end < start) {
                        "Invalid port range"
                    } else if (end - start + 1 > MAX_PORT_SCAN_RANGE) {
                        "Range too large -- max $MAX_PORT_SCAN_RANGE ports per run"
                    } else {
                        portScan(parts[1], start, end)
                    }
                }
            }
            "myip", "ifconfig" -> myIp(context)
            else -> "Unknown command \"${parts[0]}\" -- type \"help\" for a list"
        }
    }

    private suspend fun ping(host: String, count: Int): String = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("/system/bin/ping", "-c", count.toString(), "-W", "3", host)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(((count + 2) * 3).toLong(), TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()
            if (!finished) {
                process.destroy()
                "$output\n(timed out)"
            } else {
                output.ifBlank { "No output (ping binary unavailable or blocked)" }
            }
        } catch (e: Exception) {
            "ping failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun dnsLookup(host: String): String = withContext(Dispatchers.IO) {
        try {
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty()) "No addresses found for $host"
            else addresses.joinToString("\n") { it.hostAddress ?: it.toString() }
        } catch (e: Exception) {
            "DNS lookup failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun portScan(host: String, startPort: Int, endPort: Int): String = withContext(Dispatchers.IO) {
        val addr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return@withContext "Could not resolve $host: ${e.message}"
        }
        val openPorts = mutableListOf<Int>()
        for (port in startPort..endPort) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(addr, port), PORT_SCAN_TIMEOUT_MS)
                    openPorts += port
                }
            } catch (e: Exception) {
                // closed, filtered, or timed out -- not open
            }
        }
        buildString {
            append("Scanned $startPort-$endPort on ${addr.hostAddress}\n")
            if (openPorts.isEmpty()) append("No open ports found")
            else append("Open: ${openPorts.joinToString(", ")}")
        }
    }

    private fun myIp(context: Context): String = buildString {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses.toList().filterIsInstance<Inet4Address>()
                if (addrs.isNotEmpty() && !iface.isLoopback) {
                    append("${iface.displayName}: ${addrs.joinToString(", ") { it.hostAddress ?: "?" }}\n")
                }
            }
        } catch (e: Exception) {
            append("Interface enumeration failed: ${e.message}\n")
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        try {
            @Suppress("DEPRECATION")
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null) {
                append("Gateway: ${intToIp(dhcp.gateway)}\n")
                append("DNS1: ${intToIp(dhcp.dns1)}  DNS2: ${intToIp(dhcp.dns2)}\n")
            }
        } catch (e: Exception) {
            // best-effort
        }
    }.trim().ifBlank { "No network interfaces found" }

    private fun intToIp(value: Int): String =
        "${value and 0xFF}.${(value shr 8) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 24) and 0xFF}"
}
