package com.vishal.riy.update

import com.vishal.riy.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the credential boundary: a shipped (non-debug) artifact must never
 * embed a GitHub token. Debug builds may carry one for private-repo testing.
 */
class BuildConfigTokenTest {

    @Test
    fun releaseBuildsCarryNoGitHubToken() {
        if (!BuildConfig.DEBUG) {
            assertEquals("", BuildConfig.GITHUB_TOKEN)
        }
    }
}
