package com.vishal.riy.ui.browser

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.vishal.riy.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end ON-DEVICE test of the actual screen behavior. Drives the REAL
 * [BrowserViewModel] instance the running UI is bound to (same ViewModelStore),
 * so a submit goes through the true chain:
 *
 *   address input -> search/URL resolution -> WebView.loadUrl ->
 *   ProtectedWebViewClient.shouldInterceptRequest (real interception, 403
 *   served locally so the results page is never fetched) -> blocked state ->
 *   the native "Adult Search Blocked" screen renders.
 *
 * Everything except the physical keyboard tap is the production path.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class BrowserScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun browserViewModel(): BrowserViewModel =
        ViewModelProvider(composeRule.activity)[BrowserViewModel::class.java]

    @Test
    fun adultQueries_showTheAdultSearchBlockedScreen() {
        for (query in listOf("porn", "xxx", "sex", "nude", "hot photo", "sexy girl")) {
            composeRule.runOnIdle { browserViewModel().loadInput(query) }
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodes(hasText("Adult Search Blocked"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // "Protected browsing is enabled." is part of the blocked screen.
            composeRule.onNodeWithText("Go Home").performClick()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun benignQueries_areNotBlocked_andKeepBrowsing() {
        for (query in listOf("hot weather", "hot coffee", "fashion photo", "adult education")) {
            composeRule.runOnIdle { browserViewModel().loadInput(query) }
            // An adult block arrives almost instantly; give the load a moment
            // and confirm the blocked screen never appears.
            Thread.sleep(4_000)
            assertFalse(
                "'$query' must NOT show the blocked screen",
                composeRule.onAllNodes(hasText("Adult Search Blocked"))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test
    fun theBrowserShowsAnAddressBarTheUserCanTypeInto() {
        // Guards the address-bar contract: it exists and is editable.
        composeRule.onNode(hasSetTextAction()).performTextInput("example.com")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.waitForIdle()
        assertNotNull(browserViewModel())
    }
}
