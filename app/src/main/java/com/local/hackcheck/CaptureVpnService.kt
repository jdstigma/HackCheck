package com.local.hackcheck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Local capture VPN: routes all device traffic through a TUN interface, relays each TCP/UDP
 * flow out over a real (protected) socket so connectivity keeps working, and logs per-flow
 * metadata (app, remote address, bytes, duration) to CaptureLog. This is a simplified relay
 * (see FlowRelay.kt) -- not a full TCP stack, not byte-level pcap. See IDEAS.md #2/#3 for the
 * scoping behind this approach.
 */
class CaptureVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var running = false
    private val tcpFlows = ConcurrentHashMap<String, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()
    private var sweepThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        startForeground(NOTIFICATION_ID, buildNotification())
        val builder = Builder()
            .addAddress(TUN_IP_STRING, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setSession("HackCheck Capture")
            .setMtu(MTU)
        vpnInterface = builder.establish()
        val iface = vpnInterface
        if (iface == null) {
            CaptureLog.appendFlow(applicationContext, "meta", "HackCheck", "vpn_establish_failed", 0, 0, 0)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        Thread { readLoop(iface) }.start()
        sweepThread = Thread { sweepLoop() }.also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        tcpFlows.values.forEach { it.forceClose() }
        tcpFlows.clear()
        udpFlows.values.forEach { it.close() }
        udpFlows.clear()
        try { vpnInterface?.close() } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    private fun sweepLoop() {
        while (running) {
            try {
                Thread.sleep(15_000)
                val now = System.currentTimeMillis()
                val expired = udpFlows.entries.filter { it.value.isIdle(now, UDP_IDLE_TIMEOUT_MS) }
                expired.forEach { (key, flow) ->
                    flow.close()
                    udpFlows.remove(key)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                // keep sweeping
            }
        }
    }

    private fun readLoop(iface: ParcelFileDescriptor) {
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val fc = FlowContext(
            context = applicationContext,
            vpnService = this,
            tunOut = output,
            tunIp = TUN_IP_BYTES,
            onFlowClosed = { protocol, app, remote, sent, received, durationMs ->
                CaptureLog.appendFlow(applicationContext, protocol, app, remote, sent, received, durationMs)
            },
        )
        val buf = ByteArray(32767)
        try {
            while (running) {
                val len = input.read(buf)
                if (len <= 0) continue
                val ip = parseIpv4(buf, len) ?: continue
                when (ip.protocol) {
                    PROTO_TCP -> handleTcp(fc, buf, len, ip)
                    PROTO_UDP -> handleUdp(fc, buf, len, ip)
                    else -> {} // ICMP and others: not relayed in this simplified version
                }
            }
        } catch (e: Exception) {
            // read loop ending (service stopping or interface closed)
        }
    }

    private fun handleTcp(fc: FlowContext, buf: ByteArray, len: Int, ip: Ipv4Packet) {
        val seg = parseTcp(buf, ip.payloadOffset, len) ?: return
        val key = "${seg.srcPort}-${ipToString(ip.dstIp)}-${seg.dstPort}"
        val existing = tcpFlows[key]
        if (existing == null) {
            if (!seg.flagSyn) return // ignore stray non-SYN packets for unknown flows
            val flow = TcpFlow(fc, seg.srcPort, ip.dstIp, seg.dstPort, seg.seq)
            tcpFlows[key] = flow
            return
        }
        val payload = if (seg.payloadLength > 0) buf.copyOfRange(seg.payloadOffset, seg.payloadOffset + seg.payloadLength) else ByteArray(0)
        existing.onDeviceSegment(seg, payload)
        if (seg.flagFin || seg.flagRst) {
            tcpFlows.remove(key)
        }
    }

    private fun handleUdp(fc: FlowContext, buf: ByteArray, len: Int, ip: Ipv4Packet) {
        val dgram = parseUdp(buf, ip.payloadOffset, len) ?: return
        val key = "${dgram.srcPort}-${ipToString(ip.dstIp)}-${dgram.dstPort}"
        val flow = udpFlows.getOrPut(key) { UdpFlow(fc, dgram.srcPort, ip.dstIp, dgram.dstPort) }
        val payload = if (dgram.payloadLength > 0) buf.copyOfRange(dgram.payloadOffset, dgram.payloadOffset + dgram.payloadLength) else ByteArray(0)
        flow.send(payload)
    }

    private fun buildNotification(): Notification {
        val channelId = "hackcheck_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Traffic capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("HackCheck capture")
            .setContentText("Relaying and logging network connections")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4202
        private const val MTU = 1500
        private const val TUN_IP_STRING = "10.0.0.2"
        private val TUN_IP_BYTES = byteArrayOf(10, 0, 0, 2)
        private const val UDP_IDLE_TIMEOUT_MS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CaptureVpnService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureVpnService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            return manager.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == CaptureVpnService::class.java.name
            }
        }
    }
}
