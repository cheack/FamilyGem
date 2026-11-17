package app.familygem.geni.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadPolicyTest {

    private val day = 24 * 3600 * 1000L
    private fun link(loaded: Boolean, at: Long) = GeniLink(1, "profile-1", "I1", loaded, null, null, at)

    @Test
    fun notLoadedPersonLoads() = assertTrue(LoadPolicy.shouldLoad(link(false, 0), nowMillis = 10 * day))

    @Test
    fun freshPersonSkips() = assertFalse(LoadPolicy.shouldLoad(link(true, 10 * day - 1000), nowMillis = 10 * day))

    @Test
    fun stalePersonLoads() = assertTrue(LoadPolicy.shouldLoad(link(true, 8 * day), nowMillis = 10 * day))

    @Test
    fun personWithoutLinkSkips() = assertFalse(LoadPolicy.shouldLoad(null, nowMillis = 10 * day))

    @Test
    fun openedCardRefreshesAfterAnHour() {
        val hour = 3600 * 1000L
        assertFalse(LoadPolicy.shouldLoad(link(true, 10 * day - hour + 1000), 10 * day, LoadPolicy.OPEN_REFRESH_MILLIS))
        assertTrue(LoadPolicy.shouldLoad(link(true, 10 * day - hour - 1000), 10 * day, LoadPolicy.OPEN_REFRESH_MILLIS))
    }

    @Test
    fun dailyRefreshOnlyOnceADayAndNotOnMeteredNetwork() {
        assertTrue(LoadPolicy.dailyRefreshDue(lastMillis = null, nowMillis = 10 * day, metered = false))
        assertTrue(LoadPolicy.dailyRefreshDue(lastMillis = 9 * day - 1, nowMillis = 10 * day, metered = false))
        assertFalse(LoadPolicy.dailyRefreshDue(lastMillis = 9 * day + 1, nowMillis = 10 * day, metered = false))
        assertFalse(LoadPolicy.dailyRefreshDue(lastMillis = null, nowMillis = 10 * day, metered = true))
    }
}
