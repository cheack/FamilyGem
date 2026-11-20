package app.familygem.geni

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniHooksTest {

    @Test
    fun onboardsOnceWhenConfigured() {
        assertTrue(GeniHooks.shouldOnboard(configured = true, alreadyShown = false))
    }

    @Test
    fun neverTwiceInOneProcess() {
        assertFalse(GeniHooks.shouldOnboard(configured = true, alreadyShown = true))
    }

    @Test
    fun notWithoutGeniCredentials() {
        assertFalse(GeniHooks.shouldOnboard(configured = false, alreadyShown = false))
    }
}
