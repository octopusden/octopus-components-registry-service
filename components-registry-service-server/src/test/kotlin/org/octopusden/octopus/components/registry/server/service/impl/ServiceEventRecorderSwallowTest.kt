package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.repository.ServiceEventRepository
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventStatus
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import java.time.Instant

/**
 * SYS-060: journaling is best-effort — a transaction/DB failure is swallowed (logged),
 * never propagated to the job/boot being observed. Simulated by a transaction manager
 * that fails to begin a transaction; every recorder method must return normally.
 */
class ServiceEventRecorderSwallowTest {
    private fun failingRecorder(): ServiceEventRecorderImpl {
        val txManager = mock(PlatformTransactionManager::class.java)
        `when`(txManager.getTransaction(any(TransactionDefinition::class.java)))
            .thenThrow(RuntimeException("cannot begin tx"))
        return ServiceEventRecorderImpl(mock(ServiceEventRepository::class.java), txManager)
    }

    @Test
    fun `SYS-060 a write failure is swallowed`() {
        val recorder = failingRecorder()
        assertDoesNotThrow {
            recorder.recordStart(ServiceEventType.TEAMCITY_RESYNC, ServiceEventSource.CRS, "u", "j", "s")
            recorder.recordFinish(ServiceEventType.TEAMCITY_RESYNC, ServiceEventSource.CRS, "u", "j", ServiceEventStatus.FAILED, "s", null)
            recorder.recordInstant(ServiceEventType.STARTUP, ServiceEventSource.CRS, "u")
        }
        // count-returning ops degrade to 0 rather than throwing.
        assertEquals(0, recorder.reconcileOrphanedRunning(ServiceEventSource.CRS))
        assertEquals(0, recorder.prune(Instant.now()))
    }
}
