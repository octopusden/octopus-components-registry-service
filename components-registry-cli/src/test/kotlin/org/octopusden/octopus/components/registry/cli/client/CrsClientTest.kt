package org.octopusden.octopus.components.registry.cli.client

import org.octopusden.octopus.components.registry.cli.model.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.cli.model.PageComponentSummaryResponse
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Minimal in-memory HttpResponse<String> for the fake seam. */
private class FakeResponse(
    private val status: Int,
    private val body: String?,
    private val req: HttpRequest,
) : HttpResponse<String> {
    override fun statusCode(): Int = status

    override fun request(): HttpRequest = req

    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

    override fun body(): String? = body

    override fun sslSession(): Optional<SSLSession> = Optional.empty()

    override fun uri(): java.net.URI = req.uri()

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}

private class CapturingExchange(
    private val status: Int,
    private val body: String?,
) : HttpExchange {
    var lastRequest: HttpRequest? = null

    override fun send(request: HttpRequest): HttpResponse<String> {
        lastRequest = request
        return FakeResponse(status, body, request)
    }
}

class CrsClientTest {
    @Test
    fun `2xx json body is deserialized`() {
        val json = """{"content":[{"id":"a","name":"A","archived":false,"canBeParent":true,"labels":[]}],"totalElements":1}"""
        val exchange = CapturingExchange(200, json)
        val client = CrsClient("https://crs.example/", token = null, exchange = exchange)

        val page = client.getJson("/rest/api/4/components", PageComponentSummaryResponse.serializer())

        assertEquals(1L, page.totalElements)
        assertEquals(listOf(ComponentSummaryResponse("a", "A", false, true, emptyList())), page.content)
    }

    @Test
    fun `bearer header set only when token present`() {
        val withToken = CapturingExchange(200, "{}")
        CrsClient("https://crs.example", token = "tok-123", exchange = withToken)
            .getJson("/x", PageComponentSummaryResponse.serializer())
        assertEquals(
            "Bearer tok-123",
            withToken.lastRequest!!
                .headers()
                .firstValue("Authorization")
                .get(),
        )

        val noToken = CapturingExchange(200, "{}")
        CrsClient("https://crs.example", token = null, exchange = noToken)
            .getJson("/x", PageComponentSummaryResponse.serializer())
        assertTrue(
            noToken.lastRequest!!
                .headers()
                .firstValue("Authorization")
                .isEmpty,
        )
    }

    @Test
    fun `uri is built from base path and query`() {
        val exchange = CapturingExchange(200, "{}")
        val client = CrsClient("https://crs.example/", exchange = exchange)
        val q = QueryParams.builder().pageable(page = 1, size = 10).build()
        client.getJson("/rest/api/4/components", PageComponentSummaryResponse.serializer(), q)
        assertEquals("https://crs.example/rest/api/4/components?page=1&size=10", exchange.lastRequest!!.uri().toString())
    }

    @Test
    fun `request carries a non-null timeout`() {
        val exchange = CapturingExchange(200, "{}")
        CrsClient("https://crs.example", exchange = exchange)
            .getJson("/x", PageComponentSummaryResponse.serializer())
        assertTrue(exchange.lastRequest!!.timeout().isPresent, "per-request timeout must be set")
    }

    @Test
    fun `non-2xx with ErrorResponse body throws CrsApiException with parsed fields`() {
        val errBody = """{"errorCode":"COMPONENT_NOT_FOUND","errorMessage":"No such component: zzz"}"""
        val exchange = CapturingExchange(404, errBody)
        val client = CrsClient("https://crs.example", exchange = exchange)

        val ex = assertFailsWith<CrsApiException> {
            client.getJson("/rest/api/4/components/zzz", PageComponentSummaryResponse.serializer())
        }
        assertEquals(404, ex.httpStatus)
        assertEquals("COMPONENT_NOT_FOUND", ex.errorCode)
        assertEquals("No such component: zzz", ex.errorMessage)
        assertEquals(ExitCode.NOT_FOUND, ExitCodes.fromThrowable(ex))
    }

    @Test
    fun `401 throws so command layer can map to AUTH_REQUIRED`() {
        val exchange = CapturingExchange(401, """{"errorMessage":"Authentication required"}""")
        val client = CrsClient("https://crs.example", exchange = exchange)
        val ex = assertFailsWith<CrsApiException> {
            client.getJson("/auth/me", PageComponentSummaryResponse.serializer())
        }
        assertNull(ex.errorCode)
        assertEquals(ExitCode.AUTH_REQUIRED, ExitCodes.fromThrowable(ex))
    }

    @Test
    fun `non-2xx with non-json body still throws with fallback message`() {
        val exchange = CapturingExchange(503, "<html>gateway down</html>")
        val client = CrsClient("https://crs.example", exchange = exchange)
        val ex = assertFailsWith<CrsApiException> {
            client.getJson("/x", PageComponentSummaryResponse.serializer())
        }
        assertEquals(503, ex.httpStatus)
        assertEquals("HTTP 503", ex.errorMessage)
    }

    @Test
    fun `text endpoint returns raw body`() {
        val exchange = CapturingExchange(200, "Component('x') { }")
        val client = CrsClient("https://crs.example", exchange = exchange)
        assertEquals("Component('x') { }", client.getText("/rest/api/4/components/x/as-code"))
        assertEquals(
            "text/plain",
            exchange.lastRequest!!
                .headers()
                .firstValue("Accept")
                .get(),
        )
    }
}
