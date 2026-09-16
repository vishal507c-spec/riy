package com.vishal.riy.blocker

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ON-DEVICE verification of the protection pipeline (runs on a real
 * device/emulator with the real Android WebView framework).
 *
 * What these tests prove: for an adult search URL the WebView's
 * [android.webkit.WebViewClient.shouldInterceptRequest] returns a 403
 * response built LOCALLY — meaning the results page is never fetched from the
 * network. That is exactly the acceptance criterion ("the adult query's
 * Google result page must be blocked before the request goes through"); a UI
 * warning alone would not make this pass, because the intercepted response is
 * the thing that stops the load.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class ProtectedWebViewClientTest {

    private val events = mutableListOf<ProtectedWebViewClient.ProtectionEvent>()

    private val client = ProtectedWebViewClient(
        blocklist = Blocklist(listOf("pornhub.com", "xvideos.com")),
        onEvent = { events += it },
    )

    @Before
    fun clearEvents() = events.clear()

    @Test
    fun adultSearchQueries_areBlockedBeforeAnyFetch() {
        for (url in listOf(
            "https://www.google.com/search?q=porn",
            "https://www.google.com/search?q=sex",
            "https://www.google.com/search?q=nude",
            "https://www.google.com/search?q=xxx",
            "https://www.google.com/search?q=hot+photo",
            "https://www.google.com/search?q=sexy+photo",
            "https://www.google.com/search?q=hot+girl",
            "https://www.google.com/search?q=sexy+girl",
            "https://www.google.com/search?q=HOT%20GIRL&tbm=isch",
        )) {
            events.clear()
            val response = client.shouldInterceptRequest(null, FakeRequest(url, mainFrame = true))
            assertNotNull("'$url' must be intercepted (not allowed to load)", response)
            assertEquals("'$url' must be answered 403", 403, response!!.statusCode)
            assertTrue(
                "'$url' must raise AdultSearchBlocked",
                events.any { it is ProtectedWebViewClient.ProtectionEvent.AdultSearchBlocked },
            )
        }
    }

    @Test
    fun benignSearchQueries_areAllowedThrough() {
        for (url in listOf(
            "https://www.google.com/search?q=hot+weather",
            "https://www.google.com/search?q=hot+coffee",
            "https://www.google.com/search?q=fashion+photo",
            "https://www.google.com/search?q=adult+education",
            "https://www.google.com/search?q=wikipedia",
        )) {
            events.clear()
            val response = client.shouldInterceptRequest(null, FakeRequest(url, mainFrame = true))
            assertNull("'$url' must NOT be intercepted", response)
        }
    }

    @Test
    fun adultHosts_areBlockedAtMainFrame() {
        val response = client.shouldInterceptRequest(
            null,
            FakeRequest("https://www.pornhub.com/", mainFrame = true),
        )
        assertNotNull(response)
        assertEquals(403, response!!.statusCode)
        assertTrue(events.any { it is ProtectedWebViewClient.ProtectionEvent.HostBlocked })
    }

    @Test
    fun normalWebsites_areAllowedThrough() {
        for (url in listOf(
            "https://www.wikipedia.org/",
            "https://github.com/",
            "https://www.google.com/",
        )) {
            events.clear()
            val response = client.shouldInterceptRequest(null, FakeRequest(url, mainFrame = true))
            assertNull("'$url' must NOT be intercepted", response)
        }
    }

    @Test
    fun blockedResponseBody_announcesAdultSearchBlock() {
        val response = client.shouldInterceptRequest(
            null,
            FakeRequest("https://www.google.com/search?q=porn", mainFrame = true),
        )!!
        val body = response.data?.bufferedReader()?.use { it.readText() }
        assertNotNull(body)
        assertTrue(body!!.contains("Adult Search Blocked", ignoreCase = true))
        assertEquals("no-store", response.responseHeaders?.get("Cache-Control"))
    }
}

/** Minimal [WebResourceRequest] stand-in so the client can be exercised directly. */
private class FakeRequest(
    private val url: String,
    private val mainFrame: Boolean,
) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)
    override fun isForMainFrame(): Boolean = mainFrame
    override fun hasGesture(): Boolean = false
    override fun isRedirect(): Boolean = false
    override fun getRequestHeaders(): Map<String, String> = emptyMap()
    override fun getMethod(): String = "GET"
}
