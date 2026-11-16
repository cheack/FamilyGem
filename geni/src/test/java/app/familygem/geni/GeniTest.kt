package app.familygem.geni

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniTest {

    @Test
    fun notConfiguredWithoutAppId() =
        assertFalse(GeniConfig(appId = "", host = "www.geni.com", redirectUri = "").isConfigured)

    @Test
    fun configuredWithAppId() =
        assertTrue(GeniConfig(appId = "123", host = "www.geni.com", redirectUri = "123://authorize").isConfigured)
}
