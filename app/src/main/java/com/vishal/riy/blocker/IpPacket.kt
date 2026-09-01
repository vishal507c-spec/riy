package com.vishal.riy.blocker

/**
 * Pure-JVM IPv4/IPv6 + UDP packet helpers. The VPN tun interface hands us raw
 * IP packets; only UDP port-53 (DNS) packets destined for the resolver IPs the
 * VPN routes are processed, everything else that reaches the tun is dropped.
 *
 * [buildReply] produces the answer packet with source/destination swapped and
 * correct IPv4 header + UDP checksums (UDP checksum is mandatory on IPv6).
 */
internal object IpPacket {

    const val UDP_PORT_DNS = 53

    /** A parsed UDP/DNS packet as received from the tun interface. */
    internal class ParsedUdpDns(
        val isIpv6: Boolean,
        val srcIp: ByteArray, // 4 or 16 bytes
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dnsStart: Int,
        val dnsLength: Int,
        val ipHeaderLen: Int,
    )

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    /** Parses an inbound tun packet; null when it is not IPv4/IPv6 UDP DNS. */
    fun parseUdpDns(packet: ByteArray, length: Int): ParsedUdpDns? {
        if (length < 1) return null
        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> parseV4(packet, length)
            6 -> parseV6(packet, length)
            else -> null
        }
    }

    private fun parseV4(packet: ByteArray, length: Int): ParsedUdpDns? {
        if (length < 20) return null
        val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
        if (ipHeaderLen < 20 || length < ipHeaderLen + 8) return null
        // Non-first IP fragments have no UDP header: drop them safely.
        if (u16(packet, 6) and 0x1FFF != 0) return null
        if (packet[9].toInt() != 17) return null // protocol UDP only
        val totalLen = minOf(u16(packet, 2), length)
        if (totalLen < ipHeaderLen + 8) return null
        val srcPort = u16(packet, ipHeaderLen)
        val dstPort = u16(packet, ipHeaderLen + 2)
        // Defense in depth: only port 53 is DNS — anything else reaching the
        // tun (e.g. DoH to a routed resolver IP) must be dropped, not parsed.
        if (dstPort != UDP_PORT_DNS) return null
        val udpLen = u16(packet, ipHeaderLen + 4)
        val dnsLength = (udpLen - 8).coerceIn(0, totalLen - ipHeaderLen - 8)
        if (dnsLength < DnsProtocol.HEADER_SIZE) return null
        return ParsedUdpDns(
            isIpv6 = false,
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            dnsStart = ipHeaderLen + 8,
            dnsLength = dnsLength,
            ipHeaderLen = ipHeaderLen,
        )
    }

    private fun parseV6(packet: ByteArray, length: Int): ParsedUdpDns? {
        if (length < 48) return null
        if (packet[6].toInt() != 17) return null // next-header UDP (no extension headers)
        val dstPort = u16(packet, 42)
        if (dstPort != UDP_PORT_DNS) return null
        val udpLen = u16(packet, 44)
        val dnsLength = (udpLen - 8).coerceIn(0, length - 48)
        if (dnsLength < DnsProtocol.HEADER_SIZE) return null
        return ParsedUdpDns(
            isIpv6 = true,
            srcIp = packet.copyOfRange(8, 24),
            dstIp = packet.copyOfRange(24, 40),
            srcPort = u16(packet, 40),
            dstPort = dstPort,
            dnsStart = 48,
            dnsLength = dnsLength,
            ipHeaderLen = 40,
        )
    }

    /**
     * Builds the reply IP/UDP packet (source/destination swapped) that carries
     * [dnsPayload] back to the original client.
     */
    fun buildReply(parsed: ParsedUdpDns, dnsPayload: ByteArray): ByteArray {
        val ipHeaderLen = if (parsed.isIpv6) 40 else parsed.ipHeaderLen
        val udpLength = 8 + dnsPayload.size
        val totalLen = ipHeaderLen + udpLength
        val out = ByteArray(totalLen)

        if (parsed.isIpv6) {
            out[0] = 0x60 // version 6, traffic class 0, flow label 0
            writeU16(out, 4, udpLength) // payload length
            out[6] = 17 // next header: UDP
            out[7] = 64 // hop limit
            System.arraycopy(parsed.dstIp, 0, out, 8, 16)  // reply src = orig dst
            System.arraycopy(parsed.srcIp, 0, out, 24, 16) // reply dst = orig src
        } else {
            out[0] = 0x45 // version 4, IHL 5
            out[1] = 0x00 // DSCP/ECN
            writeU16(out, 2, totalLen)
            writeU16(out, 4, 0) // identification
            writeU16(out, 6, 0) // flags + fragment offset
            out[8] = 64 // TTL
            out[9] = 17 // protocol UDP
            // checksum computed after addresses are in place
            System.arraycopy(parsed.dstIp, 0, out, 12, 4)
            System.arraycopy(parsed.srcIp, 0, out, 16, 4)
            val checksum = checksum(out, 0, 20)
            writeU16(out, 10, checksum)
        }

        val udpStart = ipHeaderLen
        writeU16(out, udpStart, parsed.dstPort)     // reply src port = orig dst port
        writeU16(out, udpStart + 2, parsed.srcPort) // reply dst port = orig src port
        writeU16(out, udpStart + 4, udpLength)
        writeU16(out, udpStart + 6, 0) // checksum placeholder
        System.arraycopy(dnsPayload, 0, out, udpStart + 8, dnsPayload.size)

        val replySrc = parsed.dstIp
        val replyDst = parsed.srcIp
        var sum = pseudoHeaderSum(replySrc, replyDst, udpLength)
        sum = onesComplementSum(out, udpStart, totalLen, sum)
        var udpChecksum = foldAndInvert(sum)
        if (udpChecksum == 0) udpChecksum = 0xFFFF // transmitted zero is 0xFFFF
        writeU16(out, udpStart + 6, udpChecksum)
        return out
    }

    /** Internet checksum (16-bit ones-complement sum, inverted) over [from, to). */
    internal fun checksum(bytes: ByteArray, from: Int, to: Int): Int =
        foldAndInvert(onesComplementSum(bytes, from, to, 0L))

    private fun pseudoHeaderSum(srcIp: ByteArray, dstIp: ByteArray, udpLength: Int): Long {
        var sum = onesComplementSum(srcIp, 0, srcIp.size, 0L)
        sum = onesComplementSum(dstIp, 0, dstIp.size, sum)
        if (srcIp.size == 4) {
            // IPv4 pseudo header: zero byte, protocol, UDP length.
            sum += 17
            sum = onesComplementSum(byteArrayOf(((udpLength shr 8) and 0xFF).toByte(), (udpLength and 0xFF).toByte()), 0, 2, sum)
        } else {
            // IPv6 pseudo header: 32-bit upper-layer packet length, zero bytes, next header.
            val len = byteArrayOf(
                0, 0,
                ((udpLength shr 8) and 0xFF).toByte(),
                (udpLength and 0xFF).toByte(),
                0, 0, 0, 17,
            )
            sum = onesComplementSum(len, 0, 8, sum)
        }
        return sum
    }

    /** 16-bit ones-complement sum over [from, to) starting from [initial]. */
    private fun onesComplementSum(bytes: ByteArray, from: Int, to: Int, initial: Long): Long {
        var sum = initial
        var i = from
        while (i < to) {
            val high = u8(bytes, i)
            val low = if (i + 1 < to) u8(bytes, i + 1) else 0
            sum += (high shl 8) or low
            i += 2
        }
        return sum
    }

    private fun foldAndInvert(sum: Long): Int {
        var s = sum
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return s.toInt().inv() and 0xFFFF
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }
}
