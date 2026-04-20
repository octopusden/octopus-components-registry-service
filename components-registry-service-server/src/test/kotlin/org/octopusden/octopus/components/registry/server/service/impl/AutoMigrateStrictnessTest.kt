package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.FullMigrationResult

/**
 * SYS-033 — under `ft-db` the auto-migrate contract is binary: either every
 * component migrates (or is already in the DB, counted as `skipped`) or the
 * service refuses to start. The previous implementation logged the failure
 * count and continued, which let the service serve traffic in a
 * partially-migrated state — with `default-source=db` that means failed-to-
 * migrate components are silently invisible via every API version.
 *
 * These tests pin the binary contract on the top-level check helper so a
 * regression that re-softens the check (e.g. back to a log + continue) fails
 * the unit suite instead of shipping an unsafe snapshot.
 */
class AutoMigrateStrictnessTest {
    private fun batchResult(
        total: Int,
        migrated: Int,
        failed: Int,
        skipped: Int,
    ) = BatchMigrationResult(
        total = total,
        migrated = migrated,
        failed = failed,
        skipped = skipped,
        results = emptyList(),
    )

    private fun fullResult(
        total: Int,
        migrated: Int,
        failed: Int,
        skipped: Int,
    ) = FullMigrationResult(
        defaults = emptyMap(),
        components = batchResult(total = total, migrated = migrated, failed = failed, skipped = skipped),
    )

    @Test
    @DisplayName("SYS-033: any failed migration must abort startup")
    fun aborts_on_failed_gt_zero() {
        assertThrows(IllegalStateException::class.java) {
            requireMigrationSucceeded(fullResult(total = 3, migrated = 2, failed = 1, skipped = 0))
        }
    }

    @Test
    @DisplayName("SYS-033: multiple failures also abort startup (message references counts)")
    fun aborts_on_multiple_failures() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                requireMigrationSucceeded(fullResult(total = 10, migrated = 6, failed = 4, skipped = 0))
            }
        val msg = error.message.orEmpty()
        assert(msg.contains("4")) { "expected failure count in message, got: $msg" }
        assert(msg.contains("10")) { "expected total count in message, got: $msg" }
    }

    @Test
    @DisplayName("SYS-033: clean migration passes")
    fun passes_on_zero_failures() {
        assertDoesNotThrow {
            requireMigrationSucceeded(fullResult(total = 5, migrated = 5, failed = 0, skipped = 0))
        }
    }

    @Test
    @DisplayName("SYS-033: all-skipped (re-boot on migrated DB) passes")
    fun passes_on_all_skipped() {
        assertDoesNotThrow {
            requireMigrationSucceeded(fullResult(total = 5, migrated = 0, failed = 0, skipped = 5))
        }
    }

    @Test
    @DisplayName("SYS-033: mix of migrated + skipped passes")
    fun passes_on_migrated_plus_skipped() {
        assertDoesNotThrow {
            requireMigrationSucceeded(fullResult(total = 5, migrated = 3, failed = 0, skipped = 2))
        }
    }
}
