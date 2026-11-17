package com.vishal.riy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the architectural invariants from docs/INVARIANTS.md at build time.
 * A regression that reintroduces a removed component or a leaked credential
 * fails the test suite instead of silently shipping.
 */
class InvariantEnforcementTest {

    private fun source(name: String): String = File(name).readText()

    @Test
    fun noAccessibilityServiceIsDeclaredAnywhere() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("accessibility", ignoreCase = true))
        assertFalse(manifest.contains("StatusBlocker", ignoreCase = true))
        assertFalse(source("src/main/res/values/strings.xml").contains("status_blocker", ignoreCase = true))
    }

    @Test
    fun noRemovedGuardSymbolsRemainInSource() {
        val roots = listOf(File("src/main/java"), File("src/debug/java"), File("src/release/java"))
        roots.forEach { root ->
            root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                assertFalse("${file.path}: UiGuard", text.contains("UiGuard"))
                assertFalse("${file.path}: WhatsAppStatusRule", text.contains("WhatsAppStatusRule"))
            }
        }
    }

    @Test
    fun releaseBuildDoesNotInjectGitHubToken() {
        val gradle = source("build.gradle.kts")
        val tokenFields = gradle.windowed(
            """buildConfigField("String", "GITHUB_TOKEN"""".length,
        ).count { it == """buildConfigField("String", "GITHUB_TOKEN"""" }
        // Two declarations only: a blank defaultConfig value and the debug
        // build-type injection. The release block must not contain one.
        assertTrue("expected exactly two GITHUB_TOKEN declarations, got $tokenFields", tokenFields == 2)
        val releaseStart = gradle.indexOf("release {")
        val releaseBlock = if (releaseStart >= 0) gradle.substring(releaseStart) else ""
        assertFalse(
            "release build must not inject a token",
            releaseBlock.contains("buildConfigField(\"String\", \"GITHUB_TOKEN\""),
        )
    }

    @Test
    fun invariantsDocumentIsPresentAndComplete() {
        val doc = File("../docs/INVARIANTS.md").takeIf { it.exists() }
            ?: File("docs/INVARIANTS.md")
        assertTrue("docs/INVARIANTS.md missing", doc.exists())
        val text = doc.readText()
        listOf("I1 —", "I2 —", "I3 —", "I4 —", "I5 —").forEach {
            assertTrue("missing $it", text.contains(it))
        }
    }
}
