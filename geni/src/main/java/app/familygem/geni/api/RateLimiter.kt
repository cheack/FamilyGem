package app.familygem.geni.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps requests within Geni's rate limit with a sliding window.
 * The limit and the window are updated from the X-API-Rate-* response headers.
 * @param now Current time in milliseconds
 */
class RateLimiter(
    private var limit: Int = 40,
    private var windowMillis: Long = 10_000,
    private val now: () -> Long
) {
    private val lock = Any()
    private val queue = Mutex() // Serves waiting callers in order
    private val timestamps = ArrayDeque<Long>() // Start times of requests in the current window
    private var blockedUntil = 0L

    /** Suspends until a request may be sent, then counts it. */
    suspend fun acquire() = queue.withLock {
        while (true) {
            val wait = synchronized(lock) { reserveOrWait() }
            if (wait <= 0) return@withLock
            delay(wait)
        }
    }

    /** Returns 0 when the request was counted, otherwise the milliseconds to wait. */
    private fun reserveOrWait(): Long {
        val time = now()
        if (blockedUntil > time) return blockedUntil - time
        while (timestamps.isNotEmpty() && timestamps.first() <= time - windowMillis) timestamps.removeFirst()
        if (timestamps.size < limit) {
            timestamps.addLast(time)
            return 0
        }
        return timestamps.first() + windowMillis - time
    }

    /** Applies the values of the X-API-Rate-Limit, X-API-Rate-Remaining and X-API-Rate-Window headers. */
    fun update(limit: Int?, remaining: Int?, windowSeconds: Int?) = synchronized(lock) {
        if (limit != null && limit > 0) this.limit = limit
        if (windowSeconds != null && windowSeconds > 0) windowMillis = windowSeconds * 1000L
        if (remaining != null && remaining <= 0) blockedUntil = now() + windowMillis
    }
}
