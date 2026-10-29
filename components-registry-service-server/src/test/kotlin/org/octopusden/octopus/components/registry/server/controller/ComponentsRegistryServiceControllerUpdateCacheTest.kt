package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.octopusden.octopus.components.registry.server.service.ComponentsRegistryService
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.MigrationStatus
import org.springframework.http.HttpStatus

/**
 * Phase-aware `updateCache` branch coverage — pure Mockito, no Spring context,
 * no global fixtures (per `feedback_regression_guards_avoid_global_fixtures`).
 *
 * Retirement (410) is gated on `status.total > 0 && status.git == 0L`:
 * - `git > 0` (total > 0)  → 200 + the refresh duration; re-reads the Git config (matches 2.0.87).
 * - `git == 0` (total > 0) → 410 Gone (fully migrated — cutover to DB complete).
 * - `git < 0`  → 200 (not 410): defensive only. `git` is now a set difference (DSL component keys
 *   not present as `source='db'` rows) and is always >= 0, so getMigrationStatus no longer emits a
 *   negative; this case just pins that the controller never mis-retires on an unexpected negative.
 * - `total == 0` → 200 (not 410): the Git resolver returned empty or errored — indeterminate;
 *   attempt the re-read (the recovery action), not a false retirement.
 * - COMPONENTS migration active → 409, without re-reading or even querying status.
 */
class ComponentsRegistryServiceControllerUpdateCacheTest {
    private val componentsRegistryService = mock(ComponentsRegistryService::class.java)
    private val importService = mock(ImportService::class.java)
    private val migrationLifecycleGate = mock(MigrationLifecycleGate::class.java)
    private val controller =
        ComponentsRegistryServiceController(componentsRegistryService, importService, migrationLifecycleGate)

    // gate.current() is left unstubbed → Mockito returns null (no active job) by default.

    @Test
    fun `git greater than zero re-reads git and returns 200 with refresh duration`() {
        doReturn(MigrationStatus(git = 5, db = 0, total = 5)).`when`(importService).getMigrationStatus()
        doReturn(42L).`when`(componentsRegistryService).updateConfigCache()

        val response = controller.updateConfigCache()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(42L, response.body)
        verify(componentsRegistryService).updateConfigCache()
    }

    @Test
    fun `git equal to zero retires the endpoint with 410 and does not re-read`() {
        doReturn(MigrationStatus(git = 0, db = 5, total = 5)).`when`(importService).getMigrationStatus()

        val response = controller.updateConfigCache()

        assertEquals(HttpStatus.GONE, response.statusCode)
        verify(componentsRegistryService, never()).updateConfigCache()
    }

    @Test
    fun `negative git attempts refresh, not 410 (defensive)`() {
        // Defensive only: `git` is now a set difference (DSL keys not in the DB) and is
        // always >= 0, so getMigrationStatus no longer emits a negative. This pins that
        // the controller never falsely retires (410) on an unexpected negative — it
        // re-reads instead.
        doReturn(MigrationStatus(git = -2, db = 7, total = 5)).`when`(importService).getMigrationStatus()
        doReturn(7L).`when`(componentsRegistryService).updateConfigCache()

        val response = controller.updateConfigCache()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(7L, response.body)
        verify(componentsRegistryService).updateConfigCache()
    }

    @Test
    fun `indeterminate git status (total zero) attempts refresh, not 410`() {
        // total == 0 means the Git resolver returned empty or failed to load — do NOT
        // falsely retire (410), since the re-read is exactly the recovery action.
        doReturn(MigrationStatus(git = -3, db = 3, total = 0)).`when`(importService).getMigrationStatus()
        doReturn(11L).`when`(componentsRegistryService).updateConfigCache()

        val response = controller.updateConfigCache()

        assertEquals(HttpStatus.OK, response.statusCode)
        verify(componentsRegistryService).updateConfigCache()
    }

    @Test
    fun `active components migration returns 409 without re-reading or querying status`() {
        doReturn(MigrationLifecycleGate.ActiveJob(MigrationLifecycleGate.JobKind.COMPONENTS, "job-1"))
            .`when`(migrationLifecycleGate)
            .current()

        val response = controller.updateConfigCache()

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        verify(componentsRegistryService, never()).updateConfigCache()
        verify(importService, never()).getMigrationStatus()
    }
}
