package org.octopusden.octopus.components.registry.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class TransientRetryTest {
    @Test
    @DisplayName("200 vs 500 (either direction) → retry once")
    fun oneSided5xxRetries() {
        assertEquals(true, TransientRetry.shouldRetry(200, 500))
        assertEquals(true, TransientRetry.shouldRetry(500, 200))
        assertEquals(true, TransientRetry.shouldRetry(404, 503))
        assertEquals(true, TransientRetry.shouldRetry(502, 404))
    }

    @Test
    @DisplayName("equal statuses never retry — including both-5xx (a both-sides outage must surface)")
    fun equalStatusesNeverRetry() {
        assertEquals(false, TransientRetry.shouldRetry(200, 200))
        assertEquals(false, TransientRetry.shouldRetry(500, 500))
        assertEquals(false, TransientRetry.shouldRetry(404, 404))
    }

    @Test
    @DisplayName("non-5xx status diffs never retry — 404 vs 200 is a REAL contract diff (e.g. the distribution cluster)")
    fun non5xxDiffsNeverRetry() {
        assertEquals(false, TransientRetry.shouldRetry(404, 200))
        assertEquals(false, TransientRetry.shouldRetry(200, 404))
        assertEquals(false, TransientRetry.shouldRetry(200, 0))
        assertEquals(false, TransientRetry.shouldRetry(0, 200))
    }

    @Test
    @DisplayName("both-5xx but different codes (500 vs 503) never retry — equal-side semantics, surfaces as-is")
    fun bothSidesDifferent5xxNeverRetries() {
        assertEquals(false, TransientRetry.shouldRetry(500, 503))
    }
}
