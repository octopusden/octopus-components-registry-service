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
 * - `git > 0`  → 200 + the refresh duration; re-reads the Git config (matches 2.0.87).
 * - `git == 0` → 410 Gone (endpoint retired once the cutover to DB is complete).
 * - `git < 0`  → 410 Gone too: `git` is `gitResolver.size - countBySource("db")`,
 *   so stale/extra `source='db'` rows can push it negative.
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
    fun `negative git from stale db rows still retires the endpoint with 410`() {
        doReturn(MigrationStatus(git = -2, db = 7, total = 5)).`when`(importService).getMigrationStatus()

        val response = controller.updateConfigCache()

        assertEquals(HttpStatus.GONE, response.statusCode)
        verify(componentsRegistryService, never()).updateConfigCache()
    }

    @Test
    fun `active components migration returns 409 without re-reading or querying status`() {
        doReturn(MigrationLifecycleGate.ActiveJob(MigrationLifecycleGate.JobKind.COMPONENTS, "job-1"))
            .`when`(migrationLifecycleGate).current()

        val response = controller.updateConfigCache()

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        verify(componentsRegistryService, never()).updateConfigCache()
        verify(importService, never()).getMigrationStatus()
    }
}
