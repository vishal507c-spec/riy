package com.vishal.riy.blocker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeSearchTest {

    // ------------------------------------------------------------- mapping

    @Test
    fun `google search hosts map to forcesafesearch`() {
        assertEquals("forcesafesearch.google.com", SafeSearchRules.safeHostFor("google.com"))
        assertEquals("forcesafesearch.google.com", SafeSearchRules.safeHostFor("www.google.com"))
        assertEquals("forcesafesearch.google.com", SafeSearchRules.safeHostFor("www.google.co.in"))
        assertEquals("forcesafesearch.google.com", SafeSearchRules.safeHostFor("m.google.com"))
    }

    @Test
    fun `other engines map to their safe frontends`() {
        assertEquals("strict.bing.com", SafeSearchRules.safeHostFor("www.bing.com"))
        assertEquals("strict.bing.com", SafeSearchRules.safeHostFor("bing.com"))
        assertEquals("safe.duckduckgo.com", SafeSearchRules.safeHostFor("duckduckgo.com"))
        assertEquals("familysearch.yandex.ru", SafeSearchRules.safeHostFor("yandex.ru"))
        assertEquals("restrict.youtube.com", SafeSearchRules.safeHostFor("www.youtube.com"))
        assertEquals("restrict.youtube.com", SafeSearchRules.safeHostFor("m.youtube.com"))
    }

    @Test
    fun `safe frontends themselves are NOT mapped (no resolution loop)`() {
        SafeSearchRules.SAFE_HOSTS.forEach { host ->
            assertNull("self-mapping loop for $host", SafeSearchRules.safeHostFor(host))
        }
    }

    @Test
    fun `unknown and lookalike hosts are NOT mapped`() {
        assertNull(SafeSearchRules.safeHostFor("pornhub.com"))
        assertNull(SafeSearchRules.safeHostFor("wikipedia.org"))
        assertNull(SafeSearchRules.safeHostFor("notgoogle.com"))
        assertNull(SafeSearchRules.safeHostFor("google.com.evil.com"))
        assertNull(SafeSearchRules.safeHostFor("evil-google.com"))
        assertNull(SafeSearchRules.safeHostFor("mail.google.com")) // conservative list
        assertNull(SafeSearchRules.safeHostFor(""))
    }

    // ------------------------------------------------------------- enforcer

    @Test
    fun `answerFor pins Google fallback VIP before refresh`() = runBlocking {
        val enforcer = SafeSearchEnforcer(
            scope = { CoroutineScope(Dispatchers.Unconfined) },
            resolve = { _, _ -> null }, // refresh resolves nothing
        )
        val query = SafeSearchRules.buildDnsQuery("www.google.com", type = 1)
        val q = DnsProtocol.question(query)!!
        val response = enforcer.answerFor(query, q)

        assertNotNull("Google A query must always be pinned (documented VIP fallback)", response)
        val rdata = response!!.copyOfRange(response.size - 4, response.size)
        assertTrue(rdata.contentEquals(SafeSearchRules.GOOGLE_FALLBACK_V4))
    }

    @Test
    fun `answerFor uses dynamically resolved VIP`() = runBlocking {
        val resolved = byteArrayOf(8, 8, 1, 1)
        val enforcer = SafeSearchEnforcer(
            scope = { CoroutineScope(Dispatchers.Unconfined) },
            resolve = { name, type ->
                if (name == "forcesafesearch.google.com" && type == 1) {
                    // Upstream replies are FULL DNS messages — emulate one.
                    val upstreamQuery = SafeSearchRules.buildDnsQuery(name, type)
                    DnsProtocol.buildAddressResponse(
                        upstreamQuery,
                        DnsProtocol.question(upstreamQuery)!!,
                        resolved,
                    )
                } else {
                    null
                }
            },
        )
        enforcer.refreshAll()
        val query = SafeSearchRules.buildDnsQuery("google.com", type = 1)
        val q = DnsProtocol.question(query)!!
        val response = enforcer.answerFor(query, q)!!
        assertTrue(response.copyOfRange(response.size - 4, response.size).contentEquals(resolved))
    }

    @Test
    fun `mapped non-A and non-AAAA types answer NODATA (blocks HTTPS-RR hint bypass)`() = runBlocking {
        val enforcer = SafeSearchEnforcer(
            scope = { CoroutineScope(Dispatchers.Unconfined) },
            resolve = { _, _ -> null },
        )
        val query = SafeSearchRules.buildDnsQuery("www.google.com", type = 65) // HTTPS RR
        val q = DnsProtocol.question(query)!!
        val response = enforcer.answerFor(query, q)!!
        assertEquals("rcode must be NOERROR (NODATA)", 0, (u16(response, 2) and 0x000F))
        assertEquals(0, u16(response, 6)) // no answers
    }

    @Test
    fun `unmapped hosts return null so the query is forwarded`() = runBlocking {
        val enforcer = SafeSearchEnforcer(
            scope = { CoroutineScope(Dispatchers.Unconfined) },
            resolve = { _, _ -> null },
        )
        val query = SafeSearchRules.buildDnsQuery("example.com", type = 1)
        val q = DnsProtocol.question(query)!!
        assertNull(enforcer.answerFor(query, q))
    }

    // ----------------------------------------------------------- query build

    @Test
    fun `buildDnsQuery roundtrips through DnsProtocol_question`() {
        val query = SafeSearchRules.buildDnsQuery("forcesafesearch.google.com", type = 28)
        val q = DnsProtocol.question(query)!!
        assertEquals("forcesafesearch.google.com", q.name)
        assertEquals(28, q.type)
        assertEquals(query.size, q.questionEnd)
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
}
