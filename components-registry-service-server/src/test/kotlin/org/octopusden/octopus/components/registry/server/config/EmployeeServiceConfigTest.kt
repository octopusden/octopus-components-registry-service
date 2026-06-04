package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.employee.client.EmployeeServiceClient
import org.octopusden.employee.client.impl.ClassicEmployeeServiceClient
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Unit tests for the two-gate [EmployeeServiceConfig.employeeServiceClient]
 * registration.
 */
class EmployeeServiceConfigTest {
    private val config = EmployeeServiceConfig()

    @Test
    @DisplayName("enabled with blank url ⇒ context starts without a client bean")
    fun `blank url skips bean registration`() {
        ApplicationContextRunner()
            .withUserConfiguration(EmployeeServiceConfig::class.java)
            .withBean(EmployeeServiceProperties::class.java, { EmployeeServiceProperties(enabled = true, url = "") })
            .withPropertyValues("employee-service.enabled=true", "employee-service.url= ")
            .run { context ->
                assertEquals(false, context.containsBean("employeeServiceClient"))
                assertEquals(null, context.startupFailure)
            }
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
        val client: EmployeeServiceClient = config.employeeServiceClient(props)
        assertNotNull(client)
        assertEquals(true, client is ClassicEmployeeServiceClient)
    }
}
