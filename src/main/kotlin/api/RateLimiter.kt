package api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Fixed-window, in-memory, per-key rate limiter (no new dependencies).
// The window index is derived from the injected clock so tests can advance
// time without sleeping. Cleanup happens inline on each allow() check.
class RateLimiter(
    val limit: Int,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Bucket(val windowIndex: AtomicLong, val count: AtomicLong)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    // Returns 0 when allowed, otherwise the seconds until the window resets.
    fun allow(key: String): Long {
        val now = clock()
        val idx = now / windowMs
        val bucket = buckets.computeIfAbsent(key) { Bucket(AtomicLong(idx), AtomicLong(0)) }
        // Rotate stale windows lazily (also prunes old keys eventually).
        if (bucket.windowIndex.get() != idx) {
            bucket.windowIndex.set(idx)
            bucket.count.set(0)
        }
        val used = bucket.count.incrementAndGet()
        if (used > limit) {
            // Lazily drop fully-stale buckets to keep memory bounded.
            if (buckets.size > 10_000) {
                buckets.entries.removeIf { it.value.windowIndex.get() < idx }
            }
            return ((bucket.windowIndex.get() + 1) * windowMs - now) / 1000 + 1
        }
        return 0
    }
}

// Shared limiters for the core node HTTP API.
object CoreRateLimits {
    val jobs = RateLimiter(limit = 30)
    val console = RateLimiter(limit = 30)
    val files = RateLimiter(limit = 10)

    fun clientIp(call: ApplicationCall): String =
        call.request.local.remoteHost.take(64).ifBlank { "unknown" }

    suspend fun respondRateLimited(call: ApplicationCall, retryAfterSeconds: Long) {
        call.response.headers.append("Retry-After", retryAfterSeconds.coerceAtLeast(1).toString())
        call.respondText(
            JSONObject().put("error", "rate limit exceeded; retry after ${retryAfterSeconds}s").toString(),
            ContentType.Application.Json,
            HttpStatusCode.TooManyRequests
        )
    }
}
