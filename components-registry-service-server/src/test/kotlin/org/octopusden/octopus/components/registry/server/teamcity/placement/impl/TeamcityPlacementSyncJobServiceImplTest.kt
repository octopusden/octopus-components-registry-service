package org.octopusden.octopus.components.registry.server.teamcity.placement.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncService
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The caller's `SecurityContext` must reach the background
 * work even though it runs on a DIFFERENT thread (`migrationExecutor` is a real pool, not the
 * `SyncTaskExecutor` most job-service tests use) -- `ComponentManagementServiceImpl` resolves
 * `audit_log.changed_by` from `SecurityContextHolder` at write time, so without this every write
 * would attribute to "system" instead of the triggering user.
 */
class TeamcityPlacementSyncJobServiceImplTest {
    @Test
    fun `the caller's SecurityContext reaches the background work on a different thread`() {
        val auth: Authentication = TestingAuthenticationToken("alice", null)
        SecurityContextHolder.getContext().authentication = auth
        try {
            val syncService = mock<TeamcityPlacementSyncService>()
            val observedAuth = CompletableFuture<Authentication?>()
            whenever(syncService.sync(any(), anyOrNull(), any(), any())).thenAnswer {
                observedAuth.complete(SecurityContextHolder.getContext().authentication)
                PlacementSyncResult("alice", 0, 0, 0, 0, emptyList())
            }
            // A REAL background thread, unlike the inline SyncTaskExecutor most job-service tests
            // use -- this is the one property that actually exercises SecurityContext propagation.
            val service = TeamcityPlacementSyncJobServiceImpl(
                syncService,
                SimpleAsyncTaskExecutor(),
                MigrationLifecycleGate(),
            )

            service.startAsync("alice", listOf(UUID.randomUUID()), "D1") { PlacementDiffResult(Instant.now(), emptyList(), diffId = "D1") }

            assertEquals(auth, observedAuth.get(5, TimeUnit.SECONDS))
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
