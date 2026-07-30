package com.local.hackcheck

import java.net.InetAddress

const val PROTO_TCP = 6
const val PROTO_UDP = 17

data class Ipv4Packet(
    val protocol: Int,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val headerLength: Int,
    val payloadOffset: Int,
    val totalLength: Int,
)

data class TcpSegment(
    val srcPort: Int,
    val dstPort: Int,
    val seq: Long,
    val ack: Long,
    val flagSyn: Boolean,
    val flagAck: Boolean,
    val flagFin: Boolean,
    val flagRst: Boolean,
    val dataOffset: Int,
    val window: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

data class UdpDatagram(
    val srcPort: Int,
    val dstPort: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

fun parseIpv4(buf: ByteArray, len: Int): Ipv4Packet? {
    if (len < 20) return null
    val versionIhl = buf[0].toInt() and 0xFF
    val version = versionIhl shr 4
    if (version != 4) return null
    val ihl = versionIhl and 0x0F
    val headerLength = ihl * 4
    if (headerLength < 20 || headerLength > len) return null
    val totalLength = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
    val protocol = buf[9].toInt() and 0xFF
    val srcIp = buf.copyOfRange(12, 16)
    val dstIp = buf.copyOfRange(16, 20)
    return Ipv4Packet(protocol, srcIp, dstIp, headerLength, headerLength, totalLength)
}

fun parseTcp(buf: ByteArray, offset: Int, len: Int): TcpSegment? {
    if (len - offset < 20) return null
    fun u16(o: Int) = ((buf[o].toInt() and 0xFF) shl 8) or (buf[o + 1].toInt() and 0xFF)
    fun u32(o: Int): Long =
        (((buf[o].toLong() and 0xFF) shl 24) or ((buf[o + 1].toLong() and 0xFF) shl 16) or
            ((buf[o + 2].toLong() and 0xFF) shl 8) or (buf[o + 3].toLong() and 0xFF))

    val srcPort = u16(offset)
    val dstPort = u16(offset + 2)
    val seq = u32(offset + 4)
    val ack = u32(offset + 8)
    val dataOffsetWords = (buf[offset + 12].toInt() and 0xFF) shr 4
    val dataOffset = dataOffsetWords * 4
    val flags = buf[offset + 13].toInt() and 0xFF
    val window = u16(offset + 14)
    val payloadOffset = offset + dataOffset
    val payloadLength = (len - payloadOffset).coerceAtLeast(0)
    return TcpSegment(
        srcPort, dstPort, seq, ack,
        flagSyn = (flags and 0x02) != 0,
        flagAck = (flags and 0x10) != 0,
        flagFin = (flags and 0x01) != 0,
        flagRst = (flags and 0x04) != 0,
        dataOffset = dataOffset,
        window = window,
        payloadOffset = payloadOffset,
        payloadLength = payloadLength,
    )
}

fun parseUdp(buf: ByteArray, offset: Int, len: Int): UdpDatagram? {
    if (len - offset < 8) return null
    fun u16(o: Int) = ((buf[o].toInt() and 0xFF) shl 8) or (buf[o + 1].toInt() and 0xFF)
    val srcPort = u16(offset)
    val dstPort = u16(offset + 2)
    val udpLength = u16(offset + 4)
    val payloadLength = (udpLength - 8).coerceAtLeast(0).coerceAtMost(len - offset - 8)
    return UdpDatagram(srcPort, dstPort, offset + 8, payloadLength)
}

fun ipToString(ip: ByteArray): String = InetAddress.getByAddress(ip).hostAddress ?: "?"

private fun checksum16(data: ByteArray, offset: Int, length: Int, initial: Long = 0): Int {
    var sum = initial
    var i = offset
    val end = offset + length
    while (i + 1 < end) {
        sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        i += 2
    }
    if (i < end) {
        sum += (data[i].toInt() and 0xFF) shl 8
    }
    while (sum shr 16 != 0L) {
        sum = (sum and 0xFFFF) + (sum shr 16)
    }
    return (sum.inv() and 0xFFFF).toInt()
}

/** Builds a full IPv4 packet: 20-byte IP header (no options) + payload, with correct IP checksum. */
fun buildIpv4Packet(protocol: Int, srcIp: ByteArray, dstIp: ByteArray, payload: ByteArray): ByteArray {
    val totalLength = 20 + payload.size
    val header = ByteArray(20)
    header[0] = 0x45 // version 4, IHL 5
    header[1] = 0
    header[2] = (totalLength shr 8).toByte()
    header[3] = (totalLength and 0xFF).toByte()
    header[4] = 0; header[5] = 0 // identification
    header[6] = 0x40.toByte(); header[7] = 0 // flags: don't fragment
    header[8] = 64 // TTL
    header[9] = protocol.toByte()
    header[10] = 0; header[11] = 0 // checksum placeholder
    System.arraycopy(srcIp, 0, header, 12, 4)
    System.arraycopy(dstIp, 0, header, 16, 4)
    val csum = checksum16(header, 0, 20)
    header[10] = (csum shr 8).toByte()
    header[11] = (csum and 0xFF).toByte()
    return header + payload
}

/** Builds a UDP payload (header + data). IPv4 UDP checksum is optional; we send zero (no checksum). */
fun buildUdpPayload(srcPort: Int, dstPort: Int, data: ByteArray): ByteArray {
    val length = 8 + data.size
    val header = ByteArray(8)
    header[0] = (srcPort shr 8).toByte(); header[1] = (srcPort and 0xFF).toByte()
    header[2] = (dstPort shr 8).toByte(); header[3] = (dstPort and 0xFF).toByte()
    header[4] = (length shr 8).toByte(); header[5] = (length and 0xFF).toByte()
    header[6] = 0; header[7] = 0 // checksum: 0 = not computed (valid for IPv4)
    return header + data
}

/**
 * Builds a TCP segment payload (header + data) with a correctly computed checksum
 * (mandatory for TCP, unlike UDP -- most stacks drop segments with a bad checksum).
 */
fun buildTcpPayload(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    seq: Long,
    ack: Long,
    syn: Boolean,
    ackFlag: Boolean,
    fin: Boolean,
    rst: Boolean,
    window: Int,
    data: ByteArray,
): ByteArray {
    val header = ByteArray(20)
    header[0] = (srcPort shr 8).toByte(); header[1] = (srcPort and 0xFF).toByte()
    header[2] = (dstPort shr 8).toByte(); header[3] = (dstPort and 0xFF).toByte()
    header[4] = (seq shr 24).toByte(); header[5] = (seq shr 16).toByte()
    header[6] = (seq shr 8).toByte(); header[7] = seq.toByte()
    header[8] = (ack shr 24).toByte(); header[9] = (ack shr 16).toByte()
    header[10] = (ack shr 8).toByte(); header[11] = ack.toByte()
    header[12] = 0x50 // data offset 5 (20 bytes), no options
    var flags = 0
    if (fin) flags = flags or 0x01
    if (syn) flags = flags or 0x02
    if (rst) flags = flags or 0x04
    if (ackFlag) flags = flags or 0x10
    header[13] = flags.toByte()
    header[14] = (window shr 8).toByte(); header[15] = (window and 0xFF).toByte()
    header[16] = 0; header[17] = 0 // checksum placeholder
    header[18] = 0; header[19] = 0 // urgent pointer

    val segment = header + data
    // TCP checksum covers a pseudo-header (src ip, dst ip, zero, protocol, tcp length) + the segment.
    val pseudo = ByteArray(12 + segment.size)
    System.arraycopy(srcIp, 0, pseudo, 0, 4)
    System.arraycopy(dstIp, 0, pseudo, 4, 4)
    pseudo[8] = 0
    pseudo[9] = PROTO_TCP.toByte()
    pseudo[10] = (segment.size shr 8).toByte()
    pseudo[11] = (segment.size and 0xFF).toByte()
    System.arraycopy(segment, 0, pseudo, 12, segment.size)
    val csum = checksum16(pseudo, 0, pseudo.size)
    segment[16] = (csum shr 8).toByte()
    segment[17] = (csum and 0xFF).toByte()
    return segment
}
