package com.local.hackcheck

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import kotlin.random.Random

private const val TAG = "HackCheckCapture"

/** Shared context every flow needs: how to reach the real network and write back to the TUN. */
class FlowContext(
    val context: Context,
    val vpnService: VpnService,
    val tunOut: FileOutputStream,
    val tunIp: ByteArray,
    val executor: ExecutorService,
    val onFlowClosed: (protocol: String, app: String, remote: String, sent: Long, received: Long, durationMs: Long) -> Unit,
)

fun resolveAppLabel(context: Context, protocol: Int, localPort: Int, remoteIp: String, remotePort: Int): String {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val local = InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort)
        val remote = InetSocketAddress(InetAddress.getByName(remoteIp), remotePort)
        val uid = cm.getConnectionOwnerUid(protocol, local, remote)
        if (uid <= 0) return "Unknown"
        val pkgs = context.packageManager.getPackagesForUid(uid) ?: return "uid $uid"
        val pkg = pkgs.firstOrNull() ?: return "uid $uid"
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        "Unknown"
    }
}

/**
 * One UDP "flow" (identified by local port + remote ip:port). Stateless protocol, so we just
 * forward datagrams both ways and expire the flow after a period of inactivity.
 */
class UdpFlow(
    private val fc: FlowContext,
    private val localPort: Int,
    private val remoteIp: ByteArray,
    private val remotePort: Int,
) {
    private val socket: DatagramSocket = DatagramSocket(0).also { fc.vpnService.protect(it) }
    private val startMs = System.currentTimeMillis()
    @Volatile var lastActivityMs = System.currentTimeMillis()
        private set
    @Volatile private var bytesSent = 0L
    @Volatile private var bytesReceived = 0L
    @Volatile private var closed = false
    private var appLabel: String? = null

    init {
        val remoteAddr = InetAddress.getByAddress(remoteIp)
        fc.executor.submit {
            try {
                val buf = ByteArray(65535)
                while (!closed) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    lastActivityMs = System.currentTimeMillis()
                    bytesReceived += packet.length
                    val udpPayload = buildUdpPayload(remotePort, localPort, packet.data.copyOfRange(0, packet.length))
                    val ipPacket = buildIpv4Packet(PROTO_UDP, remoteIp, fc.tunIp, udpPayload)
                    synchronized(fc.tunOut) { fc.tunOut.write(ipPacket) }
                }
            } catch (e: IOException) {
                // socket closed or send failed -- flow ending
            } catch (e: Exception) {
                // best-effort
            }
        }
        // fire-and-forget target for send(); using a connected socket keeps send() simple
        socket.connect(remoteAddr, remotePort)
    }

    fun send(payload: ByteArray) {
        if (closed) return
        try {
            lastActivityMs = System.currentTimeMillis()
            bytesSent += payload.size
            socket.send(DatagramPacket(payload, payload.size))
            if (appLabel == null) {
                appLabel = resolveAppLabel(fc.context, PROTO_UDP, localPort, ipToString(remoteIp), remotePort)
            }
        } catch (e: Exception) {
            // best-effort
        }
    }

    fun isIdle(nowMs: Long, timeoutMs: Long) = (nowMs - lastActivityMs) > timeoutMs

    fun close() {
        if (closed) return
        closed = true
        try { socket.close() } catch (e: Exception) {}
        fc.onFlowClosed(
            "UDP", appLabel ?: "Unknown", "${ipToString(remoteIp)}:$remotePort",
            bytesSent, bytesReceived, System.currentTimeMillis() - startMs,
        )
    }
}

private enum class TcpState { SYN_RECEIVED, ESTABLISHED, CLOSING, CLOSED }

/**
 * One TCP "flow". Simplified relay: no retransmission, no reordering, no window scaling --
 * a single-pass sequential relay that's correct for the common case but not a full TCP stack.
 */
class TcpFlow(
    private val fc: FlowContext,
    private val localPort: Int,
    private val remoteIp: ByteArray,
    private val remotePort: Int,
    initialDeviceSeq: Long,
) {
    private var state = TcpState.SYN_RECEIVED
    private var socket: Socket? = null
    private val ourInitialSeq = Random.nextLong(0, 0xFFFFFFFL)
    private var ourSeq = ourInitialSeq // next byte we'll send, relative sequence tracking
    private var deviceSeq = initialDeviceSeq + 1 // next byte we expect from device (after SYN)
    private val startMs = System.currentTimeMillis()
    @Volatile private var bytesSent = 0L
    @Volatile private var bytesReceived = 0L
    @Volatile private var closed = false
    private var appLabel: String? = null

    init {
        // Connect to the real backend BEFORE telling the device the connection is ready. Sending
        // the SYN-ACK first (the original approach) let the device's TCP stack think the
        // handshake was done and immediately send its first data (a protocol hello/handshake --
        // very common) while `socket` was still null, silently dropping it via the `?.write()`
        // no-op below and stalling the connection forever. Connecting first removes the race.
        fc.executor.submit {
            try {
                val s = Socket()
                val protected = fc.vpnService.protect(s)
                if (!protected) {
                    Log.w(TAG, "protect() returned false for ${ipToString(remoteIp)}:$remotePort")
                }
                s.connect(InetSocketAddress(InetAddress.getByAddress(remoteIp), remotePort), 8000)
                socket = s
                appLabel = resolveAppLabel(fc.context, PROTO_TCP, localPort, ipToString(remoteIp), remotePort)
                writeTcp(syn = true, ack = true, fin = false, rst = false, seq = ourSeq, ackNum = deviceSeq)
                ourSeq += 1
                val input = s.getInputStream()
                val buf = ByteArray(16384)
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytesReceived += n
                    sendData(buf.copyOfRange(0, n))
                }
                finish()
            } catch (e: Exception) {
                Log.w(TAG, "TCP connect failed for ${ipToString(remoteIp)}:$remotePort", e)
                reset()
            }
        }
    }

    /** Called when the device's ACK completing the handshake arrives, or with data segments. */
    fun onDeviceSegment(seg: TcpSegment, payload: ByteArray) {
        if (closed) return
        if (state == TcpState.SYN_RECEIVED && seg.flagAck) {
            state = TcpState.ESTABLISHED
        }
        if (seg.flagRst) {
            teardown(logIt = true)
            return
        }
        if (seg.payloadLength > 0) {
            bytesSent += seg.payloadLength
            deviceSeq += seg.payloadLength
            try {
                socket?.getOutputStream()?.write(payload)
            } catch (e: Exception) {
                reset()
                return
            }
            // Pure ACK for the received data.
            writeTcp(syn = false, ack = true, fin = false, rst = false, seq = ourSeq, ackNum = deviceSeq)
        }
        if (seg.flagFin) {
            deviceSeq += 1
            writeTcp(syn = false, ack = true, fin = false, rst = false, seq = ourSeq, ackNum = deviceSeq)
            try { socket?.shutdownOutput() } catch (e: Exception) {}
        }
    }

    private fun sendData(data: ByteArray) {
        if (closed) return
        writeTcp(syn = false, ack = true, fin = false, rst = false, seq = ourSeq, ackNum = deviceSeq, data = data)
        ourSeq += data.size
    }

    private fun finish() {
        if (closed) return
        writeTcp(syn = false, ack = true, fin = true, rst = false, seq = ourSeq, ackNum = deviceSeq)
        ourSeq += 1
        teardown(logIt = true)
    }

    private fun reset() {
        if (closed) return
        // RST+ACK (not a bare RST) so a device still waiting on the original SYN sees this as a
        // proper connection-refused response instead of an unexpected/ignorable segment.
        writeTcp(syn = false, ack = true, fin = false, rst = true, seq = ourSeq, ackNum = deviceSeq)
        teardown(logIt = true)
    }

    private fun teardown(logIt: Boolean) {
        if (closed) return
        closed = true
        try { socket?.close() } catch (e: Exception) {}
        if (logIt) {
            fc.onFlowClosed(
                "TCP", appLabel ?: "Unknown", "${ipToString(remoteIp)}:$remotePort",
                bytesSent, bytesReceived, System.currentTimeMillis() - startMs,
            )
        }
    }

    private fun writeTcp(syn: Boolean, ack: Boolean, fin: Boolean, rst: Boolean, seq: Long, ackNum: Long, data: ByteArray = ByteArray(0)) {
        val tcpPayload = buildTcpPayload(
            remoteIp, fc.tunIp, remotePort, localPort, seq, ackNum, syn, ack, fin, rst, 65535, data,
        )
        val ipPacket = buildIpv4Packet(PROTO_TCP, remoteIp, fc.tunIp, tcpPayload)
        try {
            synchronized(fc.tunOut) { fc.tunOut.write(ipPacket) }
        } catch (e: Exception) {
            closed = true
        }
    }

    fun forceClose() = teardown(logIt = true)
}
