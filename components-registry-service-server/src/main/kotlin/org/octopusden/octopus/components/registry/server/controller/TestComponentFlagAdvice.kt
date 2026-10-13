package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.dto.ArtifactComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.Component
import org.octopusden.octopus.components.registry.core.dto.ComponentV3
import org.octopusden.octopus.components.registry.core.dto.ComponentsDTO
import org.octopusden.octopus.components.registry.server.service.TestComponentFlags
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * The one place that sets `testComponent` on v1/v2/v3 responses (SYS-099): every `Component` DTO a
 * v1–v3 component controller returns — bare, in a collection, or wrapped — is marked from one
 * [TestComponentFlags] read, so a new endpoint cannot forget the flag. `TestComponentFlagAdviceTest`
 * checks that every v1–v3 handler returning such a DTO is in this advice's scope.
 */
@ControllerAdvice(assignableTypes = [BaseComponentController::class, ComponentControllerV3::class])
class TestComponentFlagAdvice(
    private val testComponentFlags: TestComponentFlags,
) : ResponseBodyAdvice<Any> {
    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        body?.let { testComponentFlags.mark(componentsIn(it)) }
        return body
    }

    companion object {
        /** Every `Component` DTO carried by a v1–v3 response body. */
        fun componentsIn(body: Any): List<Component> =
            when (body) {
                is Component -> listOf(body)
                is ComponentV3 -> listOf(body.component)
                is ComponentsDTO<*> -> body.components.toList()
                is ArtifactComponentsDTO -> body.artifactComponents.mapNotNull { it.component }
                is Collection<*> -> body.filterNotNull().flatMap(::componentsIn)
                else -> emptyList()
            }
    }
}
