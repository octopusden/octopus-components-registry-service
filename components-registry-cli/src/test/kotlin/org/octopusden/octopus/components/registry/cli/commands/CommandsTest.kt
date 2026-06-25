package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.testing.test
import org.octopusden.octopus.components.registry.cli.CrsClientFactory
import org.octopusden.octopus.components.registry.cli.client.CrsClient
import org.octopusden.octopus.components.registry.cli.client.HttpExchange
import org.octopusden.octopus.components.registry.cli.config.CrsctlConfig
import org.octopusden.octopus.components.registry.cli.crsctl
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Minimal in-memory HttpResponse<String>. */
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

/**
 * Fake exchange that replies with the next queued (status, body) per call and records every request.
 * A single queued reply is reused for all calls (handy for the "one canned response" cases).
 */
private class QueueExchange(
    private val replies: List<Pair<Int, String?>>,
) : HttpExchange {
    val requests = mutableListOf<HttpRequest>()
    override fun send(request: HttpRequest): HttpResponse<String> {
        requests += request
        val (status, body) = if (replies.size == 1) replies[0] else replies[requests.size - 1]
        return FakeResponse(status, body, request)
    }
}

/** Builds the CLI wired to [exchange], with an empty config and a fixed CRS URL flag in args. */
private fun cli(exchange: QueueExchange) =
    crsctl(
        configLoader = { CrsctlConfig.EMPTY },
        clientFactory = CrsClientFactory { target -> CrsClient(target.crsUrl, target.token, exchange) },
    )

private const val URL = "--crs-url=https://crs.example"

class CommandsTest {

    @Test
    fun `components list maps filter options to spec query params`() {
        val page = """{"content":[],"last":true}"""
        val ex = QueueExchange(listOf(200 to page))
        val result = cli(ex).test(
            listOf(
                URL, "components", "list",
                "--search", "foo",
                "--owner", "alice", "--owner", "bob",
                "--system", "SYS",
                "--product-type", "LIB",
                "--build-system", "GRADLE",
                "--label", "core",
                "--client-code", "C1",
                "--solution", "true",
                "--jira-project-key", "ABC",
                "--jira-technical", "false",
                "--vcs-path", "/git/x",
                "--production-branch", "main",
                "--parent", "root",
                "--group-key", "g1",
                "--archived", "false",
                "--can-be-parent", "true",
                "--distribution-explicit", "true",
                "--distribution-external", "false",
                "--page", "2", "--size", "50", "--sort", "name,asc",
            ),
        )
        assertEquals(0, result.statusCode, result.stderr)
        // rawQuery preserves percent-encoding; uri().query would decode %2F/%2C back to /,
        // hiding the wire-format the server actually receives.
        val q = ex.requests.single().uri().rawQuery
        // CLI kebab -> spec param-name mapping.
        assertTrue(q.contains("search=foo"))
        assertTrue(q.contains("owner=alice"))
        assertTrue(q.contains("owner=bob"))
        assertTrue(q.contains("system=SYS"))
        assertTrue(q.contains("productType=LIB"))
        assertTrue(q.contains("buildSystem=GRADLE"))
        assertTrue(q.contains("labels=core"), "label option must map to spec 'labels': $q")
        assertTrue(q.contains("clientCode=C1"))
        assertTrue(q.contains("solution=true"))
        assertTrue(q.contains("jiraProjectKey=ABC"))
        assertTrue(q.contains("jiraTechnical=false"))
        assertTrue(q.contains("vcsPath=%2Fgit%2Fx"))
        assertTrue(q.contains("productionBranch=main"))
        assertTrue(q.contains("parentComponentName=root"), "parent must map to spec 'parentComponentName': $q")
        assertTrue(q.contains("groupKey=g1"))
        assertTrue(q.contains("archived=false"))
        assertTrue(q.contains("canBeParent=true"))
        assertTrue(q.contains("distributionExplicit=true"))
        assertTrue(q.contains("distributionExternal=false"))
        assertTrue(q.contains("page=2"))
        assertTrue(q.contains("size=50"))
        assertTrue(q.contains("sort=name%2Casc"))
    }

    @Test
    fun `components list --all paginates until last and accumulates`() {
        val p0 = """{"content":[{"id":"1","name":"A","archived":false,"canBeParent":false,"labels":[]}],"last":false}"""
        val p1 = """{"content":[{"id":"2","name":"B","archived":false,"canBeParent":false,"labels":[]}],"last":true}"""
        val ex = QueueExchange(listOf(200 to p0, 200 to p1))
        val result = cli(ex).test(listOf(URL, "components", "list", "--all"))
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(2, ex.requests.size, "exactly two page fetches expected")
        assertEquals("page=0", ex.requests[0].uri().query)
        assertEquals("page=1", ex.requests[1].uri().query)
        assertTrue(result.stdout.contains("1"))
        assertTrue(result.stdout.contains("2"))
    }

    @Test
    fun `components list renders table by default and json on -o json`() {
        val page = """{"content":[{"id":"1","name":"A","archived":false,"canBeParent":false,""" +
            """"labels":[],"componentOwner":"alice","system":"SYS"}],"last":true}"""
        val tableEx = QueueExchange(listOf(200 to page))
        val table = cli(tableEx).test(listOf(URL, "components", "list"))
        assertEquals(0, table.statusCode, table.stderr)
        assertTrue(table.stdout.contains("ID"), "table header expected")
        assertTrue(table.stdout.contains("alice"))

        val jsonEx = QueueExchange(listOf(200 to page))
        val json = cli(jsonEx).test(listOf(URL, "-o", "json", "components", "list"))
        assertEquals(0, json.statusCode, json.stderr)
        assertTrue(json.stdout.trimStart().startsWith("["), "json array expected: ${json.stdout}")
        assertTrue(json.stdout.contains("\"id\""))
    }

    @Test
    fun `component overrides resolves name to uuid then calls field-overrides`() {
        val uuid = "11111111-1111-1111-1111-111111111111"
        val detail = """{"id":"$uuid","name":"my-comp","archived":false,"canBeParent":false,"version":1,""" +
            """"labels":[],"artifactIds":[],"configurations":[],"docs":[],"releaseManager":[],""" +
            """"securityChampion":[],"securityGroups":[],"teamcityProjects":[]}"""
        val overrides = """[{"id":"o1","overriddenAttribute":"build","rowType":"SCALAR_OVERRIDE","versionRange":"[1,2)"}]"""
        val ex = QueueExchange(listOf(200 to detail, 200 to overrides))
        val result = cli(ex).test(listOf(URL, "component", "overrides", "my-comp"))
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(2, ex.requests.size)
        assertTrue(ex.requests[0].uri().path.endsWith("/components/my-comp"))
        assertTrue(ex.requests[1].uri().path.endsWith("/components/$uuid/field-overrides"))
    }

    @Test
    fun `component overrides with uuid skips resolve`() {
        val uuid = "22222222-2222-2222-2222-222222222222"
        val overrides = """[]"""
        val ex = QueueExchange(listOf(200 to overrides))
        val result = cli(ex).test(listOf(URL, "component", "overrides", uuid))
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(1, ex.requests.size, "no resolve lookup expected for a UUID arg")
        assertTrue(ex.requests.single().uri().path.endsWith("/components/$uuid/field-overrides"))
    }

    @Test
    fun `component get happy path hits the components id path and renders`() {
        val uuid = "33333333-3333-3333-3333-333333333333"
        val detail = """{"id":"$uuid","name":"my-comp","archived":false,"canBeParent":false,"version":1,""" +
            """"labels":[],"artifactIds":[],"configurations":[],"docs":[],"releaseManager":[],""" +
            """"securityChampion":[],"securityGroups":[],"teamcityProjects":[],"componentOwner":"alice","system":"SYS"}"""
        // Table render.
        val tableEx = QueueExchange(listOf(200 to detail))
        val table = cli(tableEx).test(listOf(URL, "component", "get", uuid))
        assertEquals(0, table.statusCode, table.stderr)
        assertEquals("/rest/api/4/components/$uuid", tableEx.requests.single().uri().path)
        assertTrue(table.stdout.contains("FIELD"), "detail table header expected")
        assertTrue(table.stdout.contains("my-comp"))

        // JSON render.
        val jsonEx = QueueExchange(listOf(200 to detail))
        val json = cli(jsonEx).test(listOf(URL, "-o", "json", "component", "get", uuid))
        assertEquals(0, json.statusCode, json.stderr)
        assertTrue(json.stdout.contains("\"id\""))
        assertTrue(json.stdout.contains(uuid))
    }

    @Test
    fun `component as-code uses text-plain Accept and passes the body through verbatim`() {
        val raw = "component(\"my-comp\") {\n    owner \"alice\"\n}\n"
        val ex = QueueExchange(listOf(200 to raw))
        val result = cli(ex).test(listOf(URL, "component", "as-code", "my-comp"))
        assertEquals(0, result.statusCode, result.stderr)
        val request = ex.requests.single()
        assertTrue(request.uri().path.endsWith("/components/my-comp/as-code"))
        assertEquals("text/plain", request.headers().firstValue("Accept").orElse(""))
        assertTrue(result.stdout.contains("component(\"my-comp\")"), "raw body must pass through: ${result.stdout}")
        assertTrue(result.stdout.contains("owner \"alice\""))
    }

    @Test
    fun `component get percent-encodes a name with a space in the path`() {
        val detail = """{"id":"x","name":"my comp","archived":false,"canBeParent":false,"version":1,""" +
            """"labels":[],"artifactIds":[],"configurations":[],"docs":[],"releaseManager":[],""" +
            """"securityChampion":[],"securityGroups":[],"teamcityProjects":[]}"""
        val ex = QueueExchange(listOf(200 to detail))
        // A raw space would make URI.create throw; if encoding works the call succeeds (exit 0).
        val result = cli(ex).test(listOf(URL, "component", "get", "my comp"))
        assertEquals(0, result.statusCode, result.stderr)
        val uri = ex.requests.single().uri()
        assertTrue(uri.rawPath.contains("%20"), "space must be percent-encoded in the path: ${uri.rawPath}")
        assertTrue(uri.rawPath.endsWith("/components/my%20comp"))
    }

    @Test
    fun `components list --all stops after totalPages even when last is never true`() {
        // A misbehaving server: always last=false, but reports totalPages=2. The single reply is
        // reused for every call, so without the totalPages guard this would loop forever.
        val page = """{"content":[{"id":"1","name":"A","archived":false,"canBeParent":false,"labels":[]}],""" +
            """"last":false,"totalPages":2}"""
        val ex = QueueExchange(listOf(200 to page))
        val result = cli(ex).test(listOf(URL, "components", "list", "--all"))
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(2, ex.requests.size, "must stop after totalPages=2 fetches")
    }

    @Test
    fun `meta employees 401 maps to AUTH_REQUIRED`() {
        val ex = QueueExchange(listOf(401 to """{"errorMessage":"login required"}"""))
        val result = cli(ex).test(listOf(URL, "--token=tok", "meta", "employees", "--search", "ali"))
        assertEquals(4, result.statusCode, "AUTH_REQUIRED exit code expected")
        assertTrue(ex.requests.single().uri().query.contains("search=ali"))
    }

    @Test
    fun `meta employees without any credential exits AUTH_REQUIRED and makes no http call`() {
        val ex = QueueExchange(listOf(500 to "should not be called"))
        val result = cli(ex).test(listOf(URL, "meta", "employees", "--search", "x"))
        assertEquals(4, result.statusCode, "AUTH_REQUIRED exit code expected: ${result.stderr}")
        assertEquals(0, ex.requests.size, "no HTTP call must be made when there is no credential")
        assertTrue(
            result.stderr.contains("this command requires authentication"),
            "clear no-credential message expected: ${result.stderr}",
        )
    }

    @Test
    fun `meta employees attaches a bearer token on the outgoing request`() {
        val ex = QueueExchange(listOf(200 to "[]"))
        val result = cli(ex).test(listOf(URL, "--token=tok", "meta", "employees", "--search", "x"))
        assertEquals(0, result.statusCode, result.stderr)
        val auth = ex.requests.single().headers().firstValue("Authorization").orElse("")
        assertEquals("Bearer tok", auth, "meta employees must send the bearer token")
    }

    @Test
    fun `whoami without token prints static line and makes no http call`() {
        val ex = QueueExchange(listOf(500 to "should not be called"))
        val result = cli(ex).test(listOf(URL, "whoami"))
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(0, ex.requests.size, "whoami without token must not hit the network")
        assertTrue(result.stdout.contains("anonymous; for current CRS versions ACCESS_COMPONENTS is implied"))
    }

    @Test
    fun `whoami with no url configured still reports anonymous out of the box`() {
        val ex = QueueExchange(listOf(500 to "should not be called"))
        val result = cli(ex).test(listOf("whoami"))
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(0, ex.requests.size, "anonymous whoami must not hit the network")
        assertTrue(result.stdout.contains("anonymous; for current CRS versions ACCESS_COMPONENTS is implied"))
    }

    @Test
    fun `audit recent maps filter options to spec query params and pageable`() {
        val page = """{"content":[],"last":true}"""
        val ex = QueueExchange(listOf(200 to page))
        val result = cli(ex).test(
            listOf(
                URL, "--token=tok", "audit", "recent",
                "--entity-type", "COMPONENT",
                "--entity-id", "abc",
                "--changed-by", "alice",
                "--action", "UPDATE",
                "--source", "PORTAL",
                "--from", "2026-06-01T00:00:00Z",
                "--to", "2026-06-14T00:00:00Z",
                "--include-migrated", "true",
                "--page", "1", "--size", "25", "--sort", "changedAt,desc",
            ),
        )
        assertEquals(0, result.statusCode, result.stderr)
        val request = ex.requests.single()
        assertTrue(request.uri().path.endsWith("/rest/api/4/audit/recent"))
        // rawQuery preserves percent-encoding for the wire-format the server receives.
        val q = request.uri().rawQuery
        assertTrue(q.contains("entityType=COMPONENT"), "entity-type must map to spec 'entityType': $q")
        assertTrue(q.contains("entityId=abc"), "entity-id must map to spec 'entityId': $q")
        assertTrue(q.contains("changedBy=alice"), "changed-by must map to spec 'changedBy': $q")
        assertTrue(q.contains("action=UPDATE"))
        assertTrue(q.contains("source=PORTAL"))
        assertTrue(q.contains("from=2026-06-01T00%3A00%3A00Z"), "from must be sent percent-encoded: $q")
        assertTrue(q.contains("to=2026-06-14T00%3A00%3A00Z"))
        assertTrue(q.contains("includeMigrated=true"), "include-migrated must map to spec 'includeMigrated': $q")
        assertTrue(q.contains("page=1"))
        assertTrue(q.contains("size=25"))
        assertTrue(q.contains("sort=changedAt%2Cdesc"))
    }

    @Test
    fun `audit recent omits include-migrated when not given`() {
        val ex = QueueExchange(listOf(200 to """{"content":[],"last":true}"""))
        val result = cli(ex).test(listOf(URL, "--token=tok", "audit", "recent"))
        assertEquals(0, result.statusCode, result.stderr)
        val q = ex.requests.single().uri().rawQuery ?: ""
        assertTrue(!q.contains("includeMigrated"), "include-migrated must be omitted when not passed: $q")
    }

    @Test
    fun `audit history builds the entityType entityId path with encoded segments and pageable`() {
        val ex = QueueExchange(listOf(200 to """{"content":[],"last":true}"""))
        val result = cli(ex).test(
            listOf(
                URL, "--token=tok", "audit", "history", "COMPONENT TYPE", "id/with slash",
                "--include-migrated", "false", "--page", "0", "--size", "10", "--sort", "changedAt,asc",
            ),
        )
        assertEquals(0, result.statusCode, result.stderr)
        val uri = ex.requests.single().uri()
        // Both path segments must be percent-encoded (space -> %20, slash -> %2F) so they stay single segments.
        assertTrue(
            uri.rawPath.endsWith("/rest/api/4/audit/COMPONENT%20TYPE/id%2Fwith%20slash"),
            "encoded entity path expected: ${uri.rawPath}",
        )
        val q = uri.rawQuery
        assertTrue(q.contains("includeMigrated=false"))
        assertTrue(q.contains("page=0"))
        assertTrue(q.contains("size=10"))
        assertTrue(q.contains("sort=changedAt%2Casc"))
    }

    @Test
    fun `audit recent renders table by default and json on -o json`() {
        val page = """{"content":[{"id":1,"action":"UPDATE","entityType":"COMPONENT",""" +
            """"entityId":"abc","componentKey":"my-component","changedAt":"2026-06-14T00:00:00Z",""" +
            """"source":"PORTAL","changedBy":"alice"}],""" +
            """"last":true}"""
        val tableEx = QueueExchange(listOf(200 to page))
        val table = cli(tableEx).test(listOf(URL, "--token=tok", "audit", "recent"))
        assertEquals(0, table.statusCode, table.stderr)
        assertTrue(table.stdout.contains("CHANGED_AT"), "table header expected: ${table.stdout}")
        assertTrue(table.stdout.contains("COMPONENT_KEY"), "componentKey column expected: ${table.stdout}")
        assertTrue(table.stdout.contains("UPDATE"))
        assertTrue(table.stdout.contains("alice"))
        // SYS-054: the resolved key must survive decode->render, not be dropped.
        assertTrue(table.stdout.contains("my-component"), "componentKey value expected in table: ${table.stdout}")

        val jsonEx = QueueExchange(listOf(200 to page))
        val json = cli(jsonEx).test(listOf(URL, "--token=tok", "-o", "json", "audit", "recent"))
        assertEquals(0, json.statusCode, json.stderr)
        assertTrue(json.stdout.trimStart().startsWith("["), "json array expected: ${json.stdout}")
        assertTrue(json.stdout.contains("\"action\""))
        assertTrue(json.stdout.contains("my-component"), "componentKey must survive into json output: ${json.stdout}")
    }

    @Test
    fun `audit recent attaches a bearer token on the outgoing request`() {
        val ex = QueueExchange(listOf(200 to """{"content":[],"last":true}"""))
        val result = cli(ex).test(listOf(URL, "--token=secret-tok", "audit", "recent"))
        assertEquals(0, result.statusCode, result.stderr)
        val auth = ex.requests.single().headers().firstValue("Authorization").orElse("")
        assertEquals("Bearer secret-tok", auth, "audit must send the bearer token")
    }

    @Test
    fun `audit recent without any credential exits AUTH_REQUIRED and makes no http call`() {
        val ex = QueueExchange(listOf(500 to "should not be called"))
        val result = cli(ex).test(listOf(URL, "audit", "recent"))
        assertEquals(4, result.statusCode, "AUTH_REQUIRED exit code expected: ${result.stderr}")
        assertEquals(0, ex.requests.size, "no HTTP call must be made when there is no credential")
        assertTrue(
            result.stderr.contains("this command requires authentication"),
            "clear no-credential message expected: ${result.stderr}",
        )
    }

    @Test
    fun `audit history without any credential exits AUTH_REQUIRED and makes no http call`() {
        val ex = QueueExchange(listOf(500 to "should not be called"))
        val result = cli(ex).test(listOf(URL, "audit", "history", "COMPONENT", "abc"))
        assertEquals(4, result.statusCode, "AUTH_REQUIRED exit code expected: ${result.stderr}")
        assertEquals(0, ex.requests.size, "no HTTP call must be made when there is no credential")
    }

    @Test
    fun `meta owners returns string list as table`() {
        val ex = QueueExchange(listOf(200 to """["alice","bob"]"""))
        val result = cli(ex).test(listOf(URL, "meta", "owners"))
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(ex.requests.single().uri().path.endsWith("/components/meta/owners"))
        assertTrue(result.stdout.contains("alice"))
        assertTrue(result.stdout.contains("bob"))
    }
}
