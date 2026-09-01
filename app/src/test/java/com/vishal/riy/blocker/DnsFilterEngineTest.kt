package com.vishal.riy.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Tests for the pure-JVM DNS filtering primitives ([DnsProtocol], [IpPacket]).
 * Includes independent checksum verification (own implementation) so a bug in
 * the production checksum code cannot hide behind itself.
 */
class DnsFilterEngineTest {

    // ------------------------------------------------------------ DNS message

    @Test
    fun `parses question from A query`() {
        val query = dnsQuery("www.pornhub.com", type = 1)
        val q = DnsProtocol.question(query)!!
        assertEquals("www.pornhub.com", q.name)
        assertEquals(1, q.type)
        assertEquals(query.size, q.questionEnd)
    }

    @Test
    fun `blocked A response answers 0-0-0-0`() {
        val query = dnsQuery("www.pornhub.com", type = 1, id = 0xBEEF)
        val response = DnsProtocol.buildBlockedResponse(query)!!

        assertEquals(0xBEEF, u16(response, 0))
        assertTrue("QR flag must be set", (u16(response, 2) and 0x8000) != 0)
        assertEquals("rcode must be NOERROR", 0, u16(response, 2) and 0x000F)
        assertEquals(1, u16(response, 6)) // ANCOUNT

        // Answer record: name pointer, TYPE A, CLASS IN, TTL, RDLENGTH 4, 0.0.0.0
        val answerStart = query.size // question is echoed verbatim
        assertEquals(0xC0, u8(response, answerStart))
        assertEquals(0x0C, u8(response, answerStart + 1))
        assertEquals(1, u16(response, answerStart + 2)) // TYPE A
        assertEquals(1, u16(response, answerStart + 4)) // CLASS IN
        assertEquals(4, u16(response, answerStart + 10)) // RDLENGTH
        for (i in 0 until 4) assertEquals(0, u8(response, answerStart + 12 + i))
    }

    @Test
    fun `blocked AAAA response answers empty IPv6`() {
        val query = dnsQuery("www.pornhub.com", type = 28)
        val response = DnsProtocol.buildBlockedResponse(query)!!
        assertEquals(1, u16(response, 6))
        val answerStart = query.size
        assertEquals(28, u16(response, answerStart + 2)) // TYPE AAAA
        assertEquals(16, u16(response, answerStart + 10)) // RDLENGTH
        for (i in 0 until 16) assertEquals(0, u8(response, answerStart + 12 + i))
    }

    @Test
    fun `blocked other types respond NODATA (NOERROR, no answers)`() {
        val query = dnsQuery("www.pornhub.com", type = 16) // TXT
        val response = DnsProtocol.buildBlockedResponse(query)!!
        assertEquals(
            "rcode must be NOERROR — NXDOMAIN would break Chromium HTTPS-RR lookups",
            0,
            u16(response, 2) and 0x000F,
        )
        assertEquals(0, u16(response, 6)) // no answers
        // question echoed verbatim after the 12-byte header
        assertEquals(query.size, response.size)
        for (i in 12 until query.size) assertEquals(query[i], response[i])
    }

    @Test
    fun `servfail response carries rcode 2`() {
        val query = dnsQuery("example.com", type = 1)
        val response = DnsProtocol.buildServFail(query)!!
        assertEquals(DnsProtocol.RCODE_SERVFAIL, u16(response, 2) and 0x000F)
    }

    @Test
    fun `truncated response has TC bit and no answers`() {
        val query = dnsQuery("example.com", type = 1)
        val response = DnsProtocol.buildTruncatedResponse(query)!!
        assertTrue(DnsProtocol.isTruncated(response))
        assertEquals(0, u16(response, 6))
        assertFalse(DnsProtocol.isTruncated(dnsQuery("example.com")))
    }

    @Test
    fun `malformed DNS messages return null without throwing`() {
        assertNull(DnsProtocol.question(ByteArray(0)))
        assertNull(DnsProtocol.question(ByteArray(8)))
        assertNull(DnsProtocol.question(ByteArray(12)))
        // QDCOUNT = 0
        assertNull(DnsProtocol.question(ByteArray(12).also { it[4] = 0 }))
        // compression pointer in question
        val compressed = ByteArray(16).also { it[12] = 0xC0.toByte(); it[13] = 0x0C }
        compressed[4] = 0; compressed[5] = 1
        assertNull(DnsProtocol.question(compressed))
        assertNull(DnsProtocol.buildBlockedResponse(ByteArray(4)))
        assertNull(DnsProtocol.buildServFail(ByteArray(0)))
    }

    // ----------------------------------------------------------- IP/UDP layer

    @Test
    fun `parses IPv4 UDP DNS packet`() {
        val dns = dnsQuery("example.com")
        val packet = buildIpv4Packet(
            src = bytes(10, 0, 0, 5),
            dst = bytes(10, 111, 222, 3),
            srcPort = 54321,
            dstPort = 53,
            payload = dns,
        )
        val parsed = IpPacket.parseUdpDns(packet, packet.size)!!
        assertFalse(parsed.isIpv6)
        assertTrue(parsed.srcIp.contentEquals(bytes(10, 0, 0, 5)))
        assertTrue(parsed.dstIp.contentEquals(bytes(10, 111, 222, 3)))
        assertEquals(54321, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertTrue(packet.copyOfRange(parsed.dnsStart, parsed.dnsStart + parsed.dnsLength).contentEquals(dns))
    }

    @Test
    fun `parses IPv6 UDP DNS packet`() {
        val dns = dnsQuery("example.com")
        val src = ByteArray(16) { (it + 1).toByte() }
        val dst = ByteArray(16) { (it + 17).toByte() }
        val packet = buildIpv6Packet(src, dst, 40001, 53, dns)
        val parsed = IpPacket.parseUdpDns(packet, packet.size)!!
        assertTrue(parsed.isIpv6)
        assertTrue(parsed.srcIp.contentEquals(src))
        assertTrue(parsed.dstIp.contentEquals(dst))
        assertEquals(40001, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
    }

    @Test
    fun `buildReply swaps addresses and ports with valid checksums`() {
        val dns = dnsQuery("example.com")
        val packet = buildIpv4Packet(
            src = bytes(10, 0, 0, 5),
            dst = bytes(10, 111, 222, 3),
            srcPort = 54321,
            dstPort = 53,
            payload = dns,
        )
        val parsed = IpPacket.parseUdpDns(packet, packet.size)!!
        val reply = IpPacket.buildReply(parsed, dns)

        // reply src = client, reply dst = resolver; ports swapped
        assertTrue(reply.copyOfRange(12, 16).contentEquals(bytes(10, 111, 222, 3)))
        assertTrue(reply.copyOfRange(16, 20).contentEquals(bytes(10, 0, 0, 5)))
        assertEquals(53, u16(reply, 20))
        assertEquals(54321, u16(reply, 22))

        // IPv4 header checksum valid (independent implementation)
        assertEquals(0xFFFF, foldRaw(sum16(reply, 0, 20)))

        // UDP checksum valid
        val udpSum = sum16(reply, 20, reply.size) +
            pseudoHeaderSum(bytes(10, 111, 222, 3), bytes(10, 0, 0, 5), reply.size - 20)
        assertEquals(0xFFFF, foldRaw(udpSum))

        // payload intact
        assertTrue(reply.copyOfRange(28, reply.size).contentEquals(dns))
    }

    @Test
    fun `buildReply handles IPv6 checksums`() {
        val dns = dnsQuery("example.com")
        val src = ByteArray(16) { (it + 1).toByte() }
        val dst = ByteArray(16) { (it + 17).toByte() }
        val packet = buildIpv6Packet(src, dst, 40001, 53, dns)
        val parsed = IpPacket.parseUdpDns(packet, packet.size)!!
        val reply = IpPacket.buildReply(parsed, dns)

        assertTrue(reply.copyOfRange(8, 24).contentEquals(dst))  // src = resolver
        assertTrue(reply.copyOfRange(24, 40).contentEquals(src)) // dst = client
        assertEquals(53, u16(reply, 40))
        assertEquals(40001, u16(reply, 42))

        val udpSum = sum16(reply, 40, reply.size) +
            pseudoHeaderSum(dst, src, reply.size - 40)
        assertEquals(0xFFFF, foldRaw(udpSum))
    }

    @Test
    fun `drops non-DNS packets without throwing`() {
        val dns = dnsQuery("example.com")
        // TCP packet (protocol 6)
        val tcp = buildIpv4Packet(
            src = bytes(10, 0, 0, 5),
            dst = bytes(10, 111, 222, 3),
            srcPort = 44444,
            dstPort = 53,
            payload = dns,
            protocol = 6,
        )
        assertNull(IpPacket.parseUdpDns(tcp, tcp.size))
        // wrong port
        val other = buildIpv4Packet(
            src = bytes(10, 0, 0, 5),
            dst = bytes(10, 111, 222, 3),
            srcPort = 54321,
            dstPort = 8080,
            payload = dns,
        )
        assertNull(IpPacket.parseUdpDns(other, other.size))
        // truncated garbage
        assertNull(IpPacket.parseUdpDns(ByteArray(10), 10))
        assertNull(IpPacket.parseUdpDns(ByteArray(0), 0))
        // non-first fragment (fragment offset != 0 -> no UDP header present)
        val fragment = buildIpv4Packet(
            src = bytes(10, 0, 0, 5),
            dst = bytes(10, 111, 222, 3),
            srcPort = 54321,
            dstPort = 53,
            payload = dns,
        ).also { it[6] = 0x01 } // low 5 bits of the flags byte = fragment offset high bits
        assertNull(IpPacket.parseUdpDns(fragment, fragment.size))
    }

    @Test
    fun `address response carries the pinned IPv4 and IPv6 answers`() {
        val query = dnsQuery("www.google.com", type = 1)
        val q = DnsProtocol.question(query)!!
        val v4Response = DnsProtocol.buildAddressResponse(query, q, byteArrayOf(216.toByte(), 239.toByte(), 38, 120))!!
        assertEquals(1, u16(v4Response, 6)) // one answer
        assertEquals(4, u16(v4Response, v4Response.size - 6)) // RDLENGTH at end
        assertTrue(
            v4Response.copyOfRange(v4Response.size - 4, v4Response.size)
                .contentEquals(byteArrayOf(216.toByte(), 239.toByte(), 38, 120)),
        )

        val aaaaQuery = dnsQuery("www.google.com", type = 28)
        val aaaaQ = DnsProtocol.question(aaaaQuery)!!
        val v6 = ByteArray(16) { (it + 1).toByte() }
        val v6Response = DnsProtocol.buildAddressResponse(aaaaQuery, aaaaQ, v6)!!
        assertEquals(28, u16(v6Response, v6Response.size - 26)) // TYPE AAAA
        assertTrue(v6Response.copyOfRange(v6Response.size - 16, v6Response.size).contentEquals(v6))
    }

    @Test
    fun `address response rejects invalid address length`() {
        val query = dnsQuery("www.google.com", type = 1)
        val q = DnsProtocol.question(query)!!
        assertNull(DnsProtocol.buildAddressResponse(query, q, ByteArray(6)))
    }

    @Test
    fun `noDataResponse is NOERROR with no answers and echoes the question`() {
        val query = dnsQuery("www.google.com", type = 65)
        val q = DnsProtocol.question(query)!!
        val response = DnsProtocol.buildNoDataResponse(query, q)
        assertEquals(0, u16(response, 2) and 0x000F)
        assertEquals(0, u16(response, 6))
        assertEquals(query.size, response.size)
        for (i in 12 until query.size) assertEquals(query[i], response[i])
    }

    @Test
    fun `firstAddressRdata extracts A through a CNAME chain`() {
        // Build an upstream reply for forcesafesearch.google.com (single A).
        val query = dnsQuery("forcesafesearch.google.com", type = 1)
        val q = DnsProtocol.question(query)!!
        val response = DnsProtocol.buildAddressResponse(query, q, byteArrayOf(216.toByte(), 239.toByte(), 38, 120))!!
        val rdata = DnsProtocol.firstAddressRdata(response, 1)!!
        assertTrue(rdata.contentEquals(byteArrayOf(216.toByte(), 239.toByte(), 38, 120)))
        assertNull(DnsProtocol.firstAddressRdata(response, 28)) // no AAAA present
    }

    @Test
    fun `firstAddressRdata handles malformed input`() {
        assertNull(DnsProtocol.firstAddressRdata(ByteArray(0), 1))
        assertNull(DnsProtocol.firstAddressRdata(ByteArray(12), 1))
        assertNull(DnsProtocol.firstAddressRdata(dnsQuery("a.com"), 16))
    }

    // --------------------------------------------------------------- helpers

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    private fun dnsQuery(name: String, type: Int = 1, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        fun w16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        w16(id); w16(0x0100); w16(1); w16(0); w16(0); w16(0)
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        w16(type); w16(1) // QTYPE, QCLASS IN
        return out.toByteArray()
    }

    private fun buildIpv4Packet(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        protocol: Int = 17,
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45
        out[1] = 0
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[8] = 64
        out[9] = protocol.toByte()
        src.copyInto(out, 12)
        dst.copyInto(out, 16)
        out[20] = (srcPort shr 8).toByte(); out[21] = srcPort.toByte()
        out[22] = (dstPort shr 8).toByte(); out[23] = dstPort.toByte()
        val udpLen = 8 + payload.size
        out[24] = (udpLen shr 8).toByte(); out[25] = udpLen.toByte()
        payload.copyInto(out, 28)
        val ipChecksum = IpPacket.checksum(out, 0, 20)
        out[10] = (ipChecksum shr 8).toByte(); out[11] = ipChecksum.toByte()
        return out
    }

    private fun buildIpv6Packet(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val out = ByteArray(40 + udpLen)
        out[0] = 0x60
        out[4] = (udpLen shr 8).toByte(); out[5] = udpLen.toByte()
        out[6] = 17
        out[7] = 64
        src.copyInto(out, 8)
        dst.copyInto(out, 24)
        out[40] = (srcPort shr 8).toByte(); out[41] = srcPort.toByte()
        out[42] = (dstPort shr 8).toByte(); out[43] = dstPort.toByte()
        out[44] = (udpLen shr 8).toByte(); out[45] = udpLen.toByte()
        payload.copyInto(out, 48)
        return out
    }

    // Independent checksum implementation (verification must not reuse prod code).

    private fun sum16(bytes: ByteArray, from: Int, to: Int, initial: Long = 0): Long {
        var sum = initial
        var i = from
        while (i < to) {
            val hi = u8(bytes, i)
            val lo = if (i + 1 < to) u8(bytes, i + 1) else 0
            sum += (hi shl 8) or lo
            i += 2
        }
        return sum
    }

    private fun foldRaw(sum: Long): Int {
        var s = sum
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return (s and 0xFFFF).toInt()
    }

    private fun pseudoHeaderSum(srcIp: ByteArray, dstIp: ByteArray, udpLength: Int): Long {
        var sum = sum16(srcIp, 0, srcIp.size)
        sum = sum16(dstIp, 0, dstIp.size, sum)
        return if (srcIp.size == 4) {
            sum16(
                byteArrayOf(
                    0,
                    17,
                    ((udpLength shr 8) and 0xFF).toByte(),
                    (udpLength and 0xFF).toByte(),
                ),
                0,
                4,
                sum,
            )
        } else {
            val tail = byteArrayOf(
                0, 0,
                ((udpLength shr 8) and 0xFF).toByte(),
                (udpLength and 0xFF).toByte(),
                0, 0, 0, 17,
            )
            sum16(tail, 0, 8, sum)
        }
    }
}
