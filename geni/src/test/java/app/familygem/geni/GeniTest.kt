package app.familygem.geni

import org.junit.Assert.assertEquals
import org.junit.Test

class GeniTest {

    @Test
    fun isConfiguredMatchesBuildCredentials() {
        val expected = BuildConfig.GENI_APP_ID.isNotEmpty() && BuildConfig.GENI_APP_KEY.isNotEmpty()
        assertEquals(expected, Geni.isConfigured)
    }
}
