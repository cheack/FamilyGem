package app.familygem.geni.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimiterTest {

    @Test
    fun allowsLimitRequestsWithoutWaiting() = runTest {
        val limiter = RateLimiter(limit = 3, windowMillis = 10_000) { testScheduler.currentTime }
        repeat(3) { limiter.acquire() }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun waitsForWindowWhenLimitReached() = runTest {
        val limiter = RateLimiter(limit = 3, windowMillis = 10_000) { testScheduler.currentTime }
        repeat(4) { limiter.acquire() }
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test
    fun remainingZeroFromHeadersBlocksForWindow() = runTest {
        val limiter = RateLimiter(limit = 40, windowMillis = 10_000) { testScheduler.currentTime }
        limiter.update(limit = 40, remaining = 0, windowSeconds = 10)
        limiter.acquire()
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test
    fun headersChangeLimit() = runTest {
        val limiter = RateLimiter(limit = 40, windowMillis = 10_000) { testScheduler.currentTime }
        limiter.update(limit = 5, remaining = null, windowSeconds = 10)
        repeat(6) { limiter.acquire() }
        assertEquals(10_000L, testScheduler.currentTime)
    }
}
