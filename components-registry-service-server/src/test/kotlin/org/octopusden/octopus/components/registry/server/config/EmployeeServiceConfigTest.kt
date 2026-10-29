package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.employee.client.EmployeeServiceClient
import org.octopusden.employee.client.impl.ClassicEmployeeServiceClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

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
    @DisplayName("SYS-052: enabled with unresolvable placeholder in url ⇒ context starts without a client bean")
    fun `SYS-052 unresolvable url placeholder skips bean registration`() {
        // Mirrors the compat-stand environment: service-config ships
        // employee-service.url=https://${api-gateway.hostname}/employee-service
        // while api-gateway.hostname is undefined on the stand. The condition
        // must treat the unresolvable URL as "not configured" (fail-open, no
        // client bean) instead of failing the whole context refresh.
        ApplicationContextRunner()
            .withUserConfiguration(EmployeeServiceConfig::class.java)
            .withBean(EmployeeServiceProperties::class.java, { EmployeeServiceProperties(enabled = true, url = "") })
            .withPropertyValues(
                "employee-service.enabled=true",
                "employee-service.url=https://\${api-gateway.hostname}/employee-service",
            ).run { context ->
                assertEquals(null, context.startupFailure)
                assertEquals(false, context.containsBean("employeeServiceClient"))
            }
    }

    @Test
    @DisplayName("SYS-052: binding path — @EnableConfigurationProperties + unresolvable placeholder must not fail boot")
    fun `SYS-052 unresolvable url placeholder boots through the real binding path`() {
        // Unlike the case above (which stubs EmployeeServiceProperties via
        // withBean and so bypasses the Binder), this goes through the real
        // @EnableConfigurationProperties binding — locking that neither the
        // condition NOR the properties binding throws on the unresolvable
        // placeholder (the prod-misconfiguration scenario end-to-end).
        ApplicationContextRunner()
            .withUserConfiguration(BindingPathConfig::class.java)
            .withPropertyValues(
                "employee-service.enabled=true",
                "employee-service.url=https://\${api-gateway.hostname}/employee-service",
            ).run { context ->
                assertEquals(null, context.startupFailure)
                assertEquals(false, context.containsBean("employeeServiceClient"))
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EmployeeServiceProperties::class)
    @Import(EmployeeServiceConfig::class)
    open class BindingPathConfig

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
