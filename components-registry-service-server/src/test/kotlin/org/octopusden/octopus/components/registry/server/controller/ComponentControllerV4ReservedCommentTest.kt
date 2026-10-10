package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * The "sync from TeamCity" change-comment prefix is the provenance tag
 * `PlacementEditHistory` trusts, so a REST client must not be able to forge it on any v4 write that
 * accepts a `changeComment`. If the guard is missing the request reaches the (stubbed-to-fail)
 * service and answers 500 (the stub's failure) instead of 400.
 */
class ComponentControllerV4ReservedCommentTest {
    private val service = mock<ComponentManagementService>()
    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                ComponentControllerV4(service, mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock()),
            ).setControllerAdvice(ControllerExceptionHandler())
            .build()
    private val id = UUID.randomUUID()

    init {
        whenever(service.createComponent(any())).thenThrow(IllegalStateException("reached the service"))
        whenever(service.updateComponent(any(), any())).thenThrow(IllegalStateException("reached the service"))
        whenever(service.setSupportedVersions(any(), any())).thenThrow(IllegalStateException("reached the service"))
    }

    private fun comment(prefix: String) = """"changeComment":"$prefix (job ${UUID.randomUUID()})""""

    @Test
    @DisplayName("SYS-099: PATCH with the reserved sync comment is a 400")
    fun `SYS-099 PATCH with the reserved sync comment is a 400`() {
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":1,${comment("sync from TeamCity")}}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("reserved")))
        verify(service, never()).updateComponent(any(), any())
    }

    @Test
    @DisplayName("SYS-099: the reserved prefix is matched case-insensitively after trim")
    fun `SYS-099 the reserved prefix is matched case-insensitively after trim`() {
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":1,"changeComment":"  SYNC FROM teamcity manually"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("SYS-099: POST create with the reserved sync comment is a 400")
    fun `SYS-099 POST create with the reserved sync comment is a 400`() {
        mvc
            .perform(
                post("/rest/api/4/components")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"comp-one",${comment("sync from TeamCity")}}"""),
            ).andExpect(status().isBadRequest)
        verify(service, never()).createComponent(any())
    }

    @Test
    @DisplayName("SYS-099: PUT supported-versions with the reserved sync comment is a 400")
    fun `SYS-099 PUT supported-versions with the reserved sync comment is a 400`() {
        mvc
            .perform(
                put("/rest/api/4/components/$id/supported-versions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"all":true,${comment("sync from TeamCity")}}"""),
            ).andExpect(status().isBadRequest)
        verify(service, never()).setSupportedVersions(any(), any())
    }

    @Test
    fun `an ordinary comment still reaches the service`() {
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":1,"changeComment":"fix the owner"}"""),
            ).andExpect(status().isInternalServerError)
        verify(service).updateComponent(any(), any())
    }
}
