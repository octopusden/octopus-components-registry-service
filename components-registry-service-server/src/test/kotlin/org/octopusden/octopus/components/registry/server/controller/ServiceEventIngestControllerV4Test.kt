package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.config.ServiceEventProperties
import org.octopusden.octopus.components.registry.server.dto.v4.ServiceEventIngestRequest
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventStatus
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.octopusden.octopus.components.registry.server.support.RecordingServiceEventRecorder
import org.springframework.http.HttpStatus

/**
 * SYS-061: portal ingest is gated by the shared-secret header, fail-closed, and
 * validates the enum fields. Unit-level — the controller logic is independent of the
 * MVC/security stack (the filter-chain permitAll is a WebSecurityConfig concern).
 */
class ServiceEventIngestControllerV4Test {
    private val validRequest =
        ServiceEventIngestRequest(
            eventType = ServiceEventType.VALIDATION_SWEEP.name,
            status = ServiceEventStatus.COMPLETED.name,
            source = ServiceEventSource.PORTAL.wire,
            triggeredBy = "scheduler",
            summary = "sweep done",
        )

    private fun controller(
        recorder: RecordingServiceEventRecorder,
        token: String,
    ) = ServiceEventIngestControllerV4(recorder, ServiceEventProperties(ingestToken = token))

    @Test
    fun `SYS-061 valid token and body records the event`() {
        val recorder = RecordingServiceEventRecorder()
        val response = controller(recorder, token = "secret").ingest("secret", validRequest)

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        val recorded = recorder.instants.single()
        assertEquals(ServiceEventType.VALIDATION_SWEEP, recorded.type)
        assertEquals(ServiceEventSource.PORTAL, recorded.source)
        assertEquals(ServiceEventStatus.COMPLETED, recorded.status)
    }

    @Test
    fun `SYS-061 wrong token is 403 and records nothing`() {
        val recorder = RecordingServiceEventRecorder()
        val response = controller(recorder, token = "secret").ingest("nope", validRequest)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertTrue(recorder.instants.isEmpty())
    }

    @Test
    fun `SYS-061 blank configured token is fail-closed 403 even with a header`() {
        val recorder = RecordingServiceEventRecorder()
        val response = controller(recorder, token = "").ingest("anything", validRequest)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertTrue(recorder.instants.isEmpty())
    }

    @Test
    fun `SYS-061 unknown eventType is 400`() {
        val recorder = RecordingServiceEventRecorder()
        val response =
            controller(recorder, token = "secret")
                .ingest("secret", validRequest.copy(eventType = "NOT_A_TYPE"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(recorder.instants.isEmpty())
    }

    @Test
    fun `SYS-061 non-terminal status is 400 (ingest is terminal-only)`() {
        val recorder = RecordingServiceEventRecorder()
        val response =
            controller(recorder, token = "secret")
                .ingest("secret", validRequest.copy(status = ServiceEventStatus.RUNNING.name))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(recorder.instants.isEmpty())
    }

    @Test
    fun `SYS-061 non-portal source is 400 (ingest is portal-only)`() {
        val recorder = RecordingServiceEventRecorder()
        val response =
            controller(recorder, token = "secret")
                .ingest("secret", validRequest.copy(source = ServiceEventSource.CRS.wire))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(recorder.instants.isEmpty())
    }
}
