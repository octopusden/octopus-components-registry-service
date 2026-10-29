package org.octopusden.octopus.components.registry.server.listener

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.octopusden.octopus.components.registry.server.support.RecordingServiceEventRecorder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import java.util.Properties

/**
 * SYS-060: on startup the listener first reconciles any orphaned RUNNING crs rows to
 * FAILED, THEN writes the STARTUP marker with the build version — in that order, so a
 * run interrupted by the previous pod's death is reported as FAILED and never left
 * hanging RUNNING.
 */
class ServiceStartupListenerTest {
    @Suppress("UNCHECKED_CAST")
    private fun buildPropsProvider(version: String?): ObjectProvider<BuildProperties> {
        val provider = mock(ObjectProvider::class.java) as ObjectProvider<BuildProperties>
        val available = version?.let { BuildProperties(Properties().apply { setProperty("version", it) }) }
        `when`(provider.ifAvailable).thenReturn(available)
        return provider
    }

    @Test
    fun `SYS-060 startup reconciles orphaned running then records STARTUP with version`() {
        val recorder = RecordingServiceEventRecorder()
        val listener = ServiceStartupListener(recorder, buildPropsProvider("2.1.0"))

        listener.onApplicationEvent(mock(ApplicationReadyEvent::class.java))

        // Reconcile must precede the STARTUP write.
        assertEquals(listOf("reconcile", "instant:COMPLETED"), recorder.order)
        assertEquals(listOf(ServiceEventSource.CRS), recorder.reconciledSources)
        val startup = recorder.instants.single()
        assertEquals(ServiceEventType.STARTUP, startup.type)
        assertEquals(ServiceEventSource.CRS, startup.source)
        assertEquals("system", startup.triggeredBy)
        assertEquals("2.1.0", startup.serviceVersion)
    }

    @Test
    fun `SYS-060 startup tolerates missing build-info (blank version)`() {
        val recorder = RecordingServiceEventRecorder()
        val listener = ServiceStartupListener(recorder, buildPropsProvider(null))

        listener.onApplicationEvent(mock(ApplicationReadyEvent::class.java))

        assertEquals("", recorder.instants.single().serviceVersion)
    }
}
