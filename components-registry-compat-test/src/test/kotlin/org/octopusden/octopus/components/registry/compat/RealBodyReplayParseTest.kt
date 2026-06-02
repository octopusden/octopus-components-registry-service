package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Pure-unit coverage for [RealBodyReplayCompatTest.parseBodyLine] — the
 * `post-bodies.ndjson` line parser. No HTTP / Spring scaffolding, so it runs in
 * the URL-free `:unitTest` task on every PR (`@Tag("unit")`).
 */
@Tag("unit")
class RealBodyReplayParseTest {
    private val mapper = jacksonObjectMapper()

    private fun parse(line: String) = RealBodyReplayCompatTest.parseBodyLine(line, mapper)

    @Test
    fun `parses a find-by-artifacts array body`() {
        val line =
            """{"count":12,"method":"POST","path":"/rest/api/3/components/find-by-artifacts","body":[{"group":"g","name":"n","version":"1.2.3"}]}"""
        val e = parse(line)!!
        assertEquals(12L, e.count)
        assertEquals("POST", e.method)
        assertEquals("/rest/api/3/components/find-by-artifacts", e.path)
        assertTrue(e.body.isArray, "body should be a JSON array")
        assertEquals(1, e.body.size())
    }

    @Test
    fun `parses a detailed-versions object body`() {
        val line =
            """{"count":3,"method":"POST","path":"/rest/api/2/components/x/detailed-versions","body":{"versions":["1.0","1.1"]}}"""
        val e = parse(line)!!
        assertTrue(e.body.isObject, "body should be a JSON object")
        assertEquals(2, e.body.path("versions").size())
    }

    @Test
    fun `keeps a real empty-array body (clients send it)`() {
        val line = """{"count":60,"method":"POST","path":"/rest/api/3/components/find-by-artifacts","body":[]}"""
        val e = parse(line)!!
        assertTrue(e.body.isArray)
        assertEquals(0, e.body.size())
    }

    @Test
    fun `defaults count to 1 when absent`() {
        val line = """{"method":"POST","path":"/rest/api/3/components/find-by-artifacts","body":[]}"""
        assertEquals(1L, parse(line)!!.count)
    }

    @Test
    fun `lowercases-then-uppercases method`() {
        val line = """{"method":"post","path":"/rest/api/3/components/find-by-artifacts","body":[]}"""
        assertEquals("POST", parse(line)!!.method)
    }

    @Test
    fun `rejects malformed json`() = assertNull(parse("""{"method":"POST", not json"""))

    @Test
    fun `rejects a bare-array (non-object) line`() = assertNull(parse("""[1,2,3]"""))

    @Test
    fun `rejects missing method`() =
        assertNull(parse("""{"path":"/rest/api/3/components/find-by-artifacts","body":[]}"""))

    @Test
    fun `rejects missing path`() = assertNull(parse("""{"method":"POST","body":[]}"""))

    @Test
    fun `rejects path not starting with slash`() =
        assertNull(parse("""{"method":"POST","path":"rest/api/3/x","body":[]}"""))

    @Test
    fun `rejects missing body`() =
        assertNull(parse("""{"method":"POST","path":"/rest/api/3/components/find-by-artifacts"}"""))

    @Test
    fun `rejects explicit null body`() =
        assertNull(parse("""{"method":"POST","path":"/rest/api/3/components/find-by-artifacts","body":null}"""))
}
