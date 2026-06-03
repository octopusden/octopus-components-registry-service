package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.employee.client.EmployeeServiceClient
import org.octopusden.employee.client.impl.ClassicEmployeeServiceClient

/**
 * Unit tests for the two-gate [EmployeeServiceConfig.employeeServiceClient]
 * factory (mirrors the FaultInjectionConfig / inert-URL pattern). The
 * `@ConditionalOnProperty("employee-service.enabled", havingValue="true")` gate
 * is enforced by Spring at context build (covered indirectly by the
 * MockMvc tests, which run with `enabled=false` and would fail if the bean
 * demanded a real URL). Here we exercise the SECOND gate — the in-factory
 * non-blank-url guard — directly, with no Spring context.
 */
class EmployeeServiceConfigTest {
    private val config = EmployeeServiceConfig()

    @Test
    @DisplayName("blank url ⇒ no client (returns null) even when enabled")
    fun `blank url yields null`() {
        val props = EmployeeServiceProperties(enabled = true, url = "")
        assertNull(config.employeeServiceClient(props), "blank url must skip client construction (stays fail-open)")
    }

    @Test
    @DisplayName("non-blank url ⇒ a ClassicEmployeeServiceClient is built")
    fun `non-blank url builds client`() {
        val props =
            EmployeeServiceProperties(
                enabled = true,
                url = "http://employee.example/api",
                token = "tok",
                retryTimeMillis = 1234,
            )
        val client: EmployeeServiceClient? = config.employeeServiceClient(props)
        assertNotNull(client)
        assertEquals(true, client is ClassicEmployeeServiceClient)
    }
}
