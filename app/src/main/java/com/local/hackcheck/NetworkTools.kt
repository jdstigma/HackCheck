package com.local.hackcheck

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

private const val MAX_PORT_SCAN_RANGE = 1024
private const val PORT_SCAN_TIMEOUT_MS = 400
private const val NC_CONNECT_TIMEOUT_MS = 5000
private const val NC_READ_TIMEOUT_MS = 3000
private const val NC_MAX_BYTES = 4096
private const val NC_LISTEN_MAX_SECONDS = 120

/** No-root network recon commands for the in-app CLI. All standard Java/Android APIs or
 *  shelling out to the device's own system ping binary -- no raw sockets, no elevated access. */
object NetworkTools {

    val helpText = """
        Commands:
          ping <host> [count]        ICMP ping via the system ping binary (default count 4)
          dns <host>                 Resolve a hostname to its IP address(es)
          portscan <host> <start> <end>   TCP connect scan (max $MAX_PORT_SCAN_RANGE ports/run)
          myip                       This device's local network interfaces/addresses
          nc <host> <port> [msg]      TCP connect, optionally send msg, show what comes back
          ncudp <host> <port> [msg]   Same as nc but UDP
          nclisten <port> [secs]      Wait for one inbound TCP connection, show what it sends
          ncudplisten <port> [secs]   Wait for one inbound UDP datagram, show sender + data
          help                       Show this text
          hashhelp                   Show hash/crypto command list (hash, crack, bruteforce, etc.)
          forensics                  How to run MVT/ALEAPP deep analysis on this device (PC-side)
    """.trimIndent()

    private val forensicsText = """
        MVT (Mobile Verification Toolkit) and ALEAPP are PC-side Python tools -- they
        can't run inside this app, since they analyze a full device acquisition, not
        just what's reachable from app-space. Run from a computer, with this device
        connected via USB (adb, same as installing this app):

          1. One-time setup: analysis/forensics/setup.ps1 (in the HackCheck repo)
          2. Acquire: run androidqf.exe with the device connected
          3. Analyze: mvt-android check-androidqf <output-folder>
             (checks against known spyware/stalkerware indicators, and parses
             Android's Intrusion Logging data if this device has it enabled)
          4. Optional deeper pass: ALEAPP, same acquisition folder

        See analysis/forensics/README.md in the repo for the full walkthrough.
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
            "nc", "netcat" -> {
                if (parts.size < 3) "Usage: nc <host> <port> [message]"
                else {
                    val port = parts[2].toIntOrNull()
                    if (port == null || port !in 1..65535) "Invalid port"
                    else ncTcp(parts[1], port, parts.drop(3).joinToString(" ").ifBlank { null })
                }
            }
            "ncudp" -> {
                if (parts.size < 3) "Usage: ncudp <host> <port> [message]"
                else {
                    val port = parts[2].toIntOrNull()
                    if (port == null || port !in 1..65535) "Invalid port"
                    else ncUdp(parts[1], port, parts.drop(3).joinToString(" ").ifBlank { null })
                }
            }
            "nclisten" -> {
                if (parts.size < 2) "Usage: nclisten <port> [seconds]"
                else {
                    val port = parts[1].toIntOrNull()
                    val secs = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(1, NC_LISTEN_MAX_SECONDS) ?: 30
                    if (port == null || port !in 1..65535) "Invalid port" else ncListenTcp(port, secs)
                }
            }
            "ncudplisten" -> {
                if (parts.size < 2) "Usage: ncudplisten <port> [seconds]"
                else {
                    val port = parts[1].toIntOrNull()
                    val secs = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(1, NC_LISTEN_MAX_SECONDS) ?: 30
                    if (port == null || port !in 1..65535) "Invalid port" else ncListenUdp(port, secs)
                }
            }
            "hashhelp" -> HashTools.helpText
            "forensics" -> forensicsText
            else -> HashTools.run(parts) ?: "Unknown command \"${parts[0]}\" -- type \"help\" or \"hashhelp\" for a list"
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

    private suspend fun ncTcp(host: String, port: Int, message: String?): String = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), NC_CONNECT_TIMEOUT_MS)
                val sentNote = if (message != null) {
                    s.getOutputStream().write(message.toByteArray(Charsets.UTF_8))
                    s.getOutputStream().flush()
                    "Sent ${message.toByteArray(Charsets.UTF_8).size} bytes\n"
                } else ""
                s.soTimeout = NC_READ_TIMEOUT_MS
                val received = readUpTo(s.getInputStream(), NC_MAX_BYTES)
                "Connected to $host:$port\n$sentNote" + describeReceived(received)
            }
        } catch (e: Exception) {
            "nc failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun ncUdp(host: String, port: Int, message: String?): String = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { s ->
                val addr = InetAddress.getByName(host)
                val payload = (message ?: "").toByteArray(Charsets.UTF_8)
                s.send(DatagramPacket(payload, payload.size, addr, port))
                s.soTimeout = NC_READ_TIMEOUT_MS
                val buf = ByteArray(NC_MAX_BYTES)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                    "Sent ${payload.size} bytes to $host:$port\n" +
                        describeReceived(buf.copyOfRange(0, packet.length))
                } catch (e: SocketTimeoutException) {
                    "Sent ${payload.size} bytes to $host:$port\nNo response within ${NC_READ_TIMEOUT_MS}ms " +
                        "(normal for UDP -- no delivery/response guarantee)"
                }
            }
        } catch (e: Exception) {
            "ncudp failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun ncListenTcp(port: Int, timeoutSeconds: Int): String = withContext(Dispatchers.IO) {
        try {
            ServerSocket(port).use { server ->
                server.soTimeout = timeoutSeconds * 1000
                val client = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    return@withContext "Listening on $port timed out after ${timeoutSeconds}s with no connection"
                }
                client.use { c ->
                    c.soTimeout = NC_READ_TIMEOUT_MS
                    val received = readUpTo(c.getInputStream(), NC_MAX_BYTES)
                    "Connection from ${c.inetAddress?.hostAddress}:${c.port}\n" + describeReceived(received)
                }
            }
        } catch (e: Exception) {
            "nclisten failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun ncListenUdp(port: Int, timeoutSeconds: Int): String = withContext(Dispatchers.IO) {
        try {
            DatagramSocket(port).use { s ->
                s.soTimeout = timeoutSeconds * 1000
                val buf = ByteArray(NC_MAX_BYTES)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                    "Datagram from ${packet.address?.hostAddress}:${packet.port}\n" +
                        describeReceived(buf.copyOfRange(0, packet.length))
                } catch (e: SocketTimeoutException) {
                    "Listening (UDP) on $port timed out after ${timeoutSeconds}s with no datagram"
                }
            }
        } catch (e: Exception) {
            "ncudplisten failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun readUpTo(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        return try {
            val n = input.read(buf)
            if (n <= 0) ByteArray(0) else buf.copyOfRange(0, n)
        } catch (e: SocketTimeoutException) {
            ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun describeReceived(data: ByteArray): String {
        if (data.isEmpty()) return "No data received"
        return "Received ${data.size} bytes:\n${String(data, Charsets.UTF_8)}"
    }
}
