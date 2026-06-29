package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.employee.client.EmployeeServiceClient
import org.octopusden.employee.client.common.dto.Employee
import org.octopusden.employee.client.common.exception.NotFoundException

/**
 * Unit tests for [EmployeeDirectoryService] — the exact-lookup wrapper that maps
 * the employee-service client results / exceptions to [ActiveStatus] and powers
 * the batch `statuses` + exact-probe `search`. Uses a Mockito mock client (no
 * Spring, no network). Vanilla Mockito to match the codebase convention.
 */
class EmployeeDirectoryServiceTest {
    private fun directoryWith(client: EmployeeServiceClient) =
        EmployeeDirectoryService(SingletonObjectProvider(client))

    private fun disabledDirectory() =
        EmployeeDirectoryService(EmptyObjectProvider())

    @Test
    @DisplayName("active==true → ACTIVE")
    fun `active maps to ACTIVE`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee("alice")).thenReturn(Employee("alice", true))
        assertEquals(ActiveStatus.ACTIVE, directoryWith(client).isActive("alice"))
    }

    @Test
    @DisplayName("active==false → INACTIVE")
    fun `inactive maps to INACTIVE`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee("bob")).thenReturn(Employee("bob", false))
        assertEquals(ActiveStatus.INACTIVE, directoryWith(client).isActive("bob"))
    }

    @Test
    @DisplayName("NotFoundException → UNKNOWN")
    fun `notfound maps to UNKNOWN`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee("ghost")).thenThrow(NotFoundException("no such employee"))
        assertEquals(ActiveStatus.UNKNOWN, directoryWith(client).isActive("ghost"))
    }

    @Test
    @DisplayName("transport/runtime error → UNAVAILABLE (fail-open)")
    fun `transport error maps to UNAVAILABLE`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee("flaky")).thenThrow(RuntimeException("connection refused"))
        assertEquals(ActiveStatus.UNAVAILABLE, directoryWith(client).isActive("flaky"))
    }

    @Test
    @DisplayName("no client bean → DISABLED")
    fun `no bean maps to DISABLED`() {
        assertEquals(ActiveStatus.DISABLED, disabledDirectory().isActive("anyone"))
        assertEquals(false, disabledDirectory().isEnabled())
    }

    @Test
    @DisplayName("statuses() batches exact lookups: true / false / null")
    fun `statuses batch`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee("alice")).thenReturn(Employee("alice", true))
        `when`(client.getEmployee("bob")).thenReturn(Employee("bob", false))
        `when`(client.getEmployee("ghost")).thenThrow(NotFoundException("no"))
        val result = directoryWith(client).statuses(listOf("alice", "bob", "ghost", " ", "alice"))
        assertEquals(mapOf("alice" to true, "bob" to false, "ghost" to null), result)
    }

    @Test
    @DisplayName("statuses() on disabled directory yields all-null")
    fun `statuses disabled`() {
        assertEquals(mapOf("alice" to null), disabledDirectory().statuses(listOf("alice")))
    }

    @Test
    @DisplayName("search() exact probe returns one match for an existing user")
    fun `search hit`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee("alice")).thenReturn(Employee("alice", true))
        assertEquals(listOf(EmployeeMatch("alice", true)), directoryWith(client).search("  alice  "))
    }

    @Test
    @DisplayName("search() returns empty for unknown / disabled / blank")
    fun `search misses`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee("ghost")).thenThrow(NotFoundException("no"))
        assertEquals(emptyList<EmployeeMatch>(), directoryWith(client).search("ghost"))
        assertEquals(emptyList<EmployeeMatch>(), disabledDirectory().search("alice"))
        assertEquals(emptyList<EmployeeMatch>(), directoryWith(client).search("   "))
    }

    @Test
    @DisplayName("probe(): NotFound for the synthetic username proves the chain end-to-end → UP")
    fun `probe notfound is UP`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee(EmployeeDirectoryService.PROBE_USERNAME))
            .thenThrow(NotFoundException("no such employee"))
        assertEquals(IntegrationHealth.UP, directoryWith(client).probe())
    }

    @Test
    @DisplayName("probe(): a real answer (active or not) also proves the chain → UP")
    fun `probe real answer is UP`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee(EmployeeDirectoryService.PROBE_USERNAME))
            .thenReturn(Employee(EmployeeDirectoryService.PROBE_USERNAME, false))
        assertEquals(IntegrationHealth.UP, directoryWith(client).probe())
    }

    @Test
    @DisplayName("probe(): transport / auth / runtime error → DOWN")
    fun `probe transport error is DOWN`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee(EmployeeDirectoryService.PROBE_USERNAME))
            .thenThrow(IllegalArgumentException("Bearer token or basic credentials must be provided"))
        assertEquals(IntegrationHealth.DOWN, directoryWith(client).probe())
    }

    @Test
    @DisplayName("probe(): no client bean → DISABLED")
    fun `probe disabled`() {
        assertEquals(IntegrationHealth.DISABLED, disabledDirectory().probe())
    }

    @Test
    @DisplayName("probeHealth(): downstream error → DOWN carrying the real cause in the detail")
    fun `probeHealth surfaces the failure detail`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee(EmployeeDirectoryService.PROBE_USERNAME))
            .thenThrow(RuntimeException("403 Forbidden: [no body]"))
        val result = directoryWith(client).probeHealth()
        assertEquals(IntegrationHealth.DOWN, result.health)
        assertTrue(result.detail!!.contains("403")) { "detail should carry the downstream status: ${result.detail}" }
        assertTrue(result.detail!!.contains("RuntimeException")) { result.detail!! }
    }

    @Test
    @DisplayName("probeHealth(): a real answer (active or not) → UP with no detail")
    fun `probeHealth real answer is UP`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee(EmployeeDirectoryService.PROBE_USERNAME))
            .thenReturn(Employee(EmployeeDirectoryService.PROBE_USERNAME, false))
        val result = directoryWith(client).probeHealth()
        assertEquals(IntegrationHealth.UP, result.health)
        assertNull(result.detail)
    }

    @Test
    @DisplayName("probeHealth(): NotFound → UP with no detail")
    fun `probeHealth notfound is UP`() {
        val client = mock(EmployeeServiceClient::class.java)
        `when`(client.getEmployee(EmployeeDirectoryService.PROBE_USERNAME))
            .thenThrow(NotFoundException("no such employee"))
        val result = directoryWith(client).probeHealth()
        assertEquals(IntegrationHealth.UP, result.health)
        assertNull(result.detail)
    }

    @Test
    @DisplayName("probeHealth(): no client bean → DISABLED with no detail")
    fun `probeHealth disabled`() {
        val result = disabledDirectory().probeHealth()
        assertEquals(IntegrationHealth.DISABLED, result.health)
        assertNull(result.detail)
    }
}
