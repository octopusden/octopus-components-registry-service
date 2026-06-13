package org.octopusden.octopus.components.registry.cli.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryParamsTest {

    @Test
    fun `omits null values`() {
        val q = QueryParams.builder()
            .add("present", "yes")
            .add("absent", null)
            .build()
        assertEquals("present=yes", q.encode())
    }

    @Test
    fun `empty builder produces empty query`() {
        val q = QueryParams.builder().build()
        assertTrue(q.isEmpty())
        assertEquals("", q.encode())
    }

    @Test
    fun `serializes page and size and repeats sort`() {
        val q = QueryParams.builder()
            .pageable(page = 2, size = 50, sort = listOf("name,asc", "id,desc"))
            .build()
        assertEquals("page=2&size=50&sort=name%2Casc&sort=id%2Cdesc", q.encode())
    }

    @Test
    fun `pageable omits null page and size and skips null sort`() {
        val q = QueryParams.builder()
            .pageable(page = null, size = null, sort = null)
            .build()
        assertTrue(q.isEmpty())
    }

    @Test
    fun `filters skip null values and encode spaces`() {
        val q = QueryParams.builder()
            .filters(mapOf("system" to "core service", "owner" to null, "archived" to false))
            .build()
        assertEquals("system=core%20service&archived=false", q.encode())
    }
}
