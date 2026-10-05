package org.octopusden.octopus.components.registry.client

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.core.dto.ComponentV1
import org.octopusden.octopus.components.registry.core.dto.ComponentV2

/** The client's default mapper fails on unknown properties; v1/v2 components must still read a field added later. */
class ComponentUnknownPropertyTest {
    private val mapper = ClassicComponentsRegistryServiceClient.configureObjectMapper(ObjectMapper())

    @Test
    fun `SYS-099 component with testComponent and an unknown field deserializes`() {
        val body = """{"id":"c","name":null,"componentOwner":"u","testComponent":true,"addedLater":1}"""

        val v2 = mapper.readValue(body, ComponentV2::class.java)
        assertTrue(v2.testComponent)
        assertEquals("c", mapper.readValue(body, ComponentV1::class.java).id)
    }
}
