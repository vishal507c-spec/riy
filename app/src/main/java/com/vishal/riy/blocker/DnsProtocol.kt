package com.vishal.riy.blocker

import java.io.ByteArrayOutputStream

/**
 * Pure-JVM (no Android imports) DNS message helpers so all response-building
 * logic is unit-testable on the desktop JVM.
 *
 * The filter answers blocked queries locally:
 *  - A     -> 0.0.0.0 (adblock convention; browsers fail fast)
 *  - AAAA  -> ::
 *  - other -> NXDOMAIN
 * Allowed queries are forwarded verbatim to a real upstream resolver and the
 * upstream response is relayed unchanged.
 */
internal object DnsProtocol {

    const val HEADER_SIZE = 12
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val CLASS_IN = 1
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    private const val FLAG_QR = 0x8000
    private const val FLAG_RD = 0x0100
    private const val FLAG_RA = 0x0080
    private const val FLAG_TC = 0x0200
    private const val BLOCKED_TTL_SECONDS = 10

    internal data class DnsQuestion(
        val name: String,
        val type: Int,
        /** Offset just past the question section inside [ByteArray] of the query. */
        val questionEnd: Int,
    )

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    /**
     * Parses the first question of a DNS message. Returns null for malformed
     * messages, zero questions, non-IN class or compressed question names
     * (compression pointers are illegal inside a question anyway).
     */
    fun question(dns: ByteArray): DnsQuestion? {
        if (dns.size < HEADER_SIZE) return null
        if (u16(dns, 4) < 1) return null // QDCOUNT
        val labels = ArrayList<String>(8)
        var i = HEADER_SIZE
        while (i < dns.size) {
            val len = u8(dns, i)
            if (len == 0) {
                if (i + 5 > dns.size) return null
                if (u16(dns, i + 3) != CLASS_IN) return null
                val name = labels.joinToString(".")
                if (name.isEmpty()) return null
                return DnsQuestion(name, u16(dns, i + 1), i + 5)
            }
            if (len and 0xC0 != 0) return null // compression pointer
            if (i + 1 + len > dns.size) return null
            val sb = StringBuilder(len)
            for (j in 0 until len) {
                val c = u8(dns, i + 1 + j)
                sb.append(if (c in 0x21..0x7E) c.toChar() else '-')
            }
            labels.add(sb.toString())
            if (labels.size > 12) return null // sanity cap
            i += 1 + len
        }
        return null // truncated
    }

    /** Response for a blocked query (0.0.0.0 / :: / NODATA). */
    fun buildBlockedResponse(query: ByteArray): ByteArray? {
        val q = question(query) ?: return null
        return when (q.type) {
            TYPE_A -> responseWithAddress(query, q, TYPE_A, ByteArray(4))
            TYPE_AAAA -> responseWithAddress(query, q, TYPE_AAAA, ByteArray(16))
            else -> {
                // Other record types (HTTPS RR, TXT, MX...): answer with
                // NOERROR/NODATA. NXDOMAIN here would make Chromium treat the
                // WHOLE domain as non-existent (breaks allowed domains whose
                // HTTPS-RR lookup we cannot answer properly).
                responseNoAnswer(query, q, rcode = 0)
            }
        }
    }

    /**
     * Response carrying a single A (4 bytes) or AAAA (16 bytes) answer for the
     * echoed question — used by SafeSearch VIP pinning.
     */
    fun buildAddressResponse(query: ByteArray, q: DnsQuestion, address: ByteArray): ByteArray? {
        val type = when (address.size) {
            4 -> TYPE_A
            16 -> TYPE_AAAA
            else -> return null
        }
        return responseWithAddress(query, q, type, address)
    }

    /** NOERROR/NODATA response (question echoed, no answers). */
    fun buildNoDataResponse(query: ByteArray, q: DnsQuestion): ByteArray =
        responseNoAnswer(query, q, rcode = 0)

    /**
     * Extracts the rdata of the FIRST record of [type] (A or AAAA) from a DNS
     * response, transparently skipping the question section and any CNAME
     * chain records. Returns null when no matching record exists.
     */
    fun firstAddressRdata(response: ByteArray, type: Int): ByteArray? {
        if (response.size < HEADER_SIZE) return null
        if (type != TYPE_A && type != TYPE_AAAA) return null
        var i = HEADER_SIZE
        repeat(u16(response, 4)) { // QDCOUNT
            i = skipName(response, i)
            i += 4
        }
        repeat(u16(response, 6)) { // ANCOUNT
            if (i >= response.size) return null
            i = skipName(response, i)
            if (i + 10 > response.size) return null
            val rtype = u16(response, i)
            val rdlength = u16(response, i + 8)
            if (i + 10 + rdlength > response.size) return null
            if (rtype == type && rdlength == expectedRdlength(type)) {
                return response.copyOfRange(i + 10, i + 10 + rdlength)
            }
            i += 10 + rdlength
        }
        return null
    }

    private fun expectedRdlength(type: Int): Int = if (type == TYPE_A) 4 else 16

    /** Skips a (possibly compressed) domain name; returns the offset after it. */
    private fun skipName(b: ByteArray, from: Int): Int {
        var j = from
        while (j < b.size) {
            val len = u8(b, j)
            if (len == 0) return j + 1
            if (len and 0xC0 != 0) return j + 2 // compression pointer
            j += 1 + len
        }
        return j
    }

    /** SERVFAIL response used when no upstream resolver is reachable. */
    fun buildServFail(query: ByteArray): ByteArray? {
        val q = question(query) ?: return null
        return responseNoAnswer(query, q, rcode = RCODE_SERVFAIL)
    }

    /**
     * Truncated (TC=1) response: the client should retry over TCP, which the
     * filter performs on its behalf; this is only a fallback.
     */
    fun buildTruncatedResponse(query: ByteArray): ByteArray? {
        val q = question(query) ?: return null
        val out = ByteArrayOutputStream(q.questionEnd)
        val rd = u16(query, 2) and FLAG_RD
        writeHeader(out, u16(query, 0), (FLAG_QR or FLAG_TC or FLAG_RA or rd).toShort(), anCount = 0)
        out.write(query, HEADER_SIZE, q.questionEnd - HEADER_SIZE)
        return out.toByteArray()
    }

    /** True when the response header has the TC (truncation) bit set. */
    fun isTruncated(response: ByteArray): Boolean =
        response.size >= 4 && (u16(response, 2) and FLAG_TC) != 0

    private fun responseNoAnswer(query: ByteArray, q: DnsQuestion, rcode: Int): ByteArray {
        val out = ByteArrayOutputStream(q.questionEnd)
        val rd = u16(query, 2) and FLAG_RD
        writeHeader(out, u16(query, 0), ((FLAG_QR or FLAG_RA or rd) or rcode).toShort(), anCount = 0)
        out.write(query, HEADER_SIZE, q.questionEnd - HEADER_SIZE)
        return out.toByteArray()
    }

    private fun responseWithAddress(
        query: ByteArray,
        q: DnsQuestion,
        type: Int,
        rdata: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(q.questionEnd + 12 + rdata.size)
        val rd = u16(query, 2) and FLAG_RD
        writeHeader(out, u16(query, 0), (FLAG_QR or FLAG_RA or rd).toShort(), anCount = 1)
        out.write(query, HEADER_SIZE, q.questionEnd - HEADER_SIZE) // echoed question
        out.write(0xC0); out.write(0x0C) // name pointer -> question at offset 12
        writeU16(out, type)
        writeU16(out, CLASS_IN)
        writeU32(out, BLOCKED_TTL_SECONDS)
        writeU16(out, rdata.size)
        out.write(rdata)
        return out.toByteArray()
    }

    private fun writeHeader(out: ByteArrayOutputStream, id: Int, flags: Short, anCount: Int) {
        writeU16(out, id)
        writeU16(out, flags.toInt() and 0xFFFF)
        writeU16(out, 1) // QDCOUNT
        writeU16(out, anCount)
        writeU16(out, 0) // NSCOUNT
        writeU16(out, 0) // ARCOUNT
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        writeU16(out, (value shr 16) and 0xFFFF)
        writeU16(out, value and 0xFFFF)
    }
}
