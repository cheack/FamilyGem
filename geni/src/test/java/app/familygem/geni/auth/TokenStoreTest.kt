package app.familygem.geni.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenStoreTest {

    @Test
    fun brokenStorageIsResetAndOpenedAgain() {
        var attempts = 0
        var resets = 0
        val result = openOrReset(open = { if (++attempts == 1) throw SecurityException("bad keyset") else "prefs" }, reset = { resets++ })
        assertEquals("prefs", result)
        assertEquals(1, resets)
        assertEquals(2, attempts)
    }

    @Test
    fun workingStorageIsNotReset() {
        var resets = 0
        assertEquals("prefs", openOrReset(open = { "prefs" }, reset = { resets++ }))
        assertEquals(0, resets)
    }
}
