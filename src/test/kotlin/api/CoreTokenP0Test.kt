package api

import config.ApiConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTokenP0Test {
    @Test
    fun `constant-time compare accepts the active token`() {
        CoreToken.applyConfig(ApiConfig(coreToken = "secret-token-123", adminToken = ""))
        assertTrue(CoreToken.matches("secret-token-123"))
        assertFalse(CoreToken.matches("wrong-token"))
        assertFalse(CoreToken.matches(null))
        assertFalse(CoreToken.matches(""))
    }

    @Test
    fun `legacy admin_token still works when core_token is blank`() {
        CoreToken.applyConfig(ApiConfig(coreToken = "", adminToken = "legacy-tok"))
        assertTrue(CoreToken.matches("legacy-tok"))
        assertFalse(CoreToken.matches("secret-token-123"))
    }

    @Test
    fun `core_token takes priority over legacy alias`() {
        CoreToken.applyConfig(ApiConfig(coreToken = "new-tok", adminToken = "old-tok"))
        assertTrue(CoreToken.matches("new-tok"))
        assertFalse(CoreToken.matches("old-tok"))
    }

    @Test
    fun `rate limiter blocks beyond the limit and frees after window`() {
        var now = 1_000_000L
        val limiter = RateLimiter(limit = 3, windowMs = 60_000, clock = { now })
        assertEquals(0L, limiter.allow("ip1"))
        assertEquals(0L, limiter.allow("ip1"))
        assertEquals(0L, limiter.allow("ip1"))
        val retry = limiter.allow("ip1")
        assertTrue(retry > 0, "4th request must be limited")
        now += 61_000
        assertEquals(0L, limiter.allow("ip1"), "window passed; must be allowed again")
        // independent keys
        assertEquals(0L, limiter.allow("ip2"))
    }

    @Test
    fun `jobs and console limiters use canonical limits`() {
        assertEquals(30, CoreRateLimits.jobs.limit)
        assertEquals(30, CoreRateLimits.console.limit)
        assertEquals(10, CoreRateLimits.files.limit)
    }
}
