package com.vishal.riy.blocker

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The on-device self-test: it proves the filter is actually enforcing, instead
 * of assuming enforcement from the fact that the VPN service started.
 *
 * HOW IT WORKS
 * ------------
 * The probe opens a plain [DatagramSocket] and deliberately does **not** call
 * `VpnService.protect()`, so the kernel routes it into the tun exactly like an
 * ordinary app's DNS query. It then asks the filter for two canaries:
 *
 *  - [SelfTestCanaries.BLOCK] — an adult-TLD name the matcher resolves locally,
 *    so it must come back sinkholed and never leave the device;
 *  - [SelfTestCanaries.ALLOW] — a reserved documentation domain that must still
 *    resolve, proving ordinary browsing is not being blackholed.
 *
 * No user query, browsing history or page content is involved: both canaries are
 * hardcoded constants owned by this app.
 */
class FilterSelfTest(
    private val dnsServer: String,
    private val dnsPort: Int = DNS_PORT,
    private val timeoutMs: Int = TIMEOUT_MS,
) {

    /**
     * Runs both probes and reduces them to a verdict. Never throws — a failed
     * self-test is reported, not propagated, because taking the filter down over
     * a flaky probe would be worse than reporting it honestly.
     */
    fun run(ruleCount: Int): SelfTestReport = runCatching {
        val block = probe(SelfTestCanaries.BLOCK)
        val allow = probe(SelfTestCanaries.ALLOW)
        SelfTestReport.evaluate(
            ruleCount = ruleCount,
            blockSinkholed = block?.let { isSinkhole(it) },
            allowResolved = allow?.let { isRealAddress(it) },
        )
    }.getOrElse { SelfTestReport.evaluate(ruleCount, null, null) }

    /** The A record of the first A answer, or null when nothing usable came back. */
    private fun probe(host: String): ByteArray? {
        val socket = DatagramSocket()
        return try {
            socket.soTimeout = timeoutMs
            // Numeric literal only: getByName() performs no DNS lookup here.
            val address = InetAddress.getByName(dnsServer)
            val query = SafeSearchRules.buildDnsQuery(host, TYPE_A, id = nextId())
            socket.send(DatagramPacket(query, query.size, address, dnsPort))
            val buffer = ByteArray(RESPONSE_BUFFER)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            DnsProtocol.firstAddressRdata(buffer.copyOf(packet.length), TYPE_A)
        } catch (_: Exception) {
            null // timeout, unreachable filter, malformed answer
        } finally {
            runCatching { socket.close() }
        }
    }

    /** The filter's block answer for A is 0.0.0.0. */
    private fun isSinkhole(rdata: ByteArray): Boolean =
        rdata.size == 4 && rdata.all { it == 0.toByte() }

    /** A working answer must be a real (non-zero) IPv4 address. */
    private fun isRealAddress(rdata: ByteArray): Boolean =
        rdata.size == 4 && rdata.any { it != 0.toByte() }

    private companion object {
        const val TYPE_A = 1
        const val DNS_PORT = 53
        const val TIMEOUT_MS = 3_000
        const val RESPONSE_BUFFER = 2048

        /** Distinct transaction IDs so a probe can never match a stale reply. */
        val idSequence = AtomicInteger(0x1000)
    }

    private fun nextId(): Int = idSequence.incrementAndGet() and 0xFFFF
}