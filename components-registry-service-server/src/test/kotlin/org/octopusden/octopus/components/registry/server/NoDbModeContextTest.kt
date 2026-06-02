package org.octopusden.octopus.components.registry.server

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.octopusden.octopus.components.registry.server.controller.ComponentControllerV4
import org.octopusden.octopus.components.registry.server.controller.ComponentsRegistryServiceController
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.service.ComponentsRegistryService
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.impl.ComponentRegistryResolverImpl
import org.octopusden.octopus.components.registry.server.service.impl.ComponentRoutingResolver
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncService
import org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import javax.sql.DataSource

/**
 * SYS-047: proves the service boots with NO database when the `no-db` profile is
 * active (`components-registry.database.enabled=false` + JDBC/JPA/Flyway
 * auto-configs excluded), serving v1/v2/v3 purely from the Git resolver.
 *
 * This is the completeness backstop for the @ConditionalOnDatabaseEnabled gating:
 * if any DB-coupled bean is left un-annotated it would demand a (now-absent)
 * repository/DataSource and this context would fail to start.
 *
 * webEnvironment = MOCK (not NONE): NONE gives a non-servlet context, under which
 * the servlet SecurityFilterChain bean cannot wire — a failure unrelated to the
 * database that would make the test prove the wrong thing. MOCK supplies a mock
 * servlet context so security wires exactly as in production; all assertions are
 * plain bean lookups + a direct service call (no MockMvc needed).
 *
 * `common` supplies the Git/FS boot config (vcs.enabled=false, groovy-path, …);
 * `no-db` is the mode under test. The `test` profile (Testcontainers Postgres) is
 * deliberately NOT activated. Assertions check wiring only — not fixture data —
 * so the test is not coupled to the shared DSL fixture content.
 */
@ExtendWith(SpringExtension::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = [ComponentRegistryServiceApplication::class],
    properties = ["auth-server.disabled=true"],
)
@ActiveProfiles("common", "no-db")
class NoDbModeContextTest {
    companion object {
        // Reuse only the shared boot data dir helper (sets
        // COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR, which application-common.yml's
        // work-dir/groovy-path placeholders need) WITHOUT extending the global
        // fixture base — this class asserts wiring only, so it stays decoupled from
        // the shared TestComponents/Defaults assertion surface. The companion
        // initializer runs at class-load, before Spring builds the context.
        init {
            BaseComponentsRegistryServiceTest.configureSpringAppTestDataDir()
        }
    }

    @Autowired
    private lateinit var ctx: ApplicationContext

    @Autowired
    private lateinit var registryService: ComponentsRegistryService

    @Test
    @DisplayName("SYS-047: no DataSource / Flyway-JPA stack is wired in no-db mode")
    fun `SYS-047 no DataSource bean exists in no-db mode`() {
        Assertions.assertTrue(
            ctx.getBeanNamesForType(DataSource::class.java).isEmpty(),
            "no-db mode must boot without a DataSource bean, found: " +
                ctx.getBeanNamesForType(DataSource::class.java).joinToString(),
        )
    }

    @Test
    @DisplayName("SYS-047: Git resolver is the sole ComponentRegistryResolver (routing resolver dropped)")
    fun `SYS-047 git resolver is the sole resolver in no-db mode`() {
        Assertions.assertTrue(
            ctx.getBeanNamesForType(ComponentRoutingResolver::class.java).isEmpty(),
            "ComponentRoutingResolver (git+db) must be absent in no-db mode",
        )
        val resolvers = ctx.getBeanNamesForType(ComponentRegistryResolver::class.java)
        Assertions.assertEquals(
            1,
            resolvers.size,
            "exactly one ComponentRegistryResolver expected, found: ${resolvers.joinToString()}",
        )
        Assertions.assertTrue(
            ctx.getBean(ComponentRegistryResolver::class.java) is ComponentRegistryResolverImpl,
            "the sole resolver must be the pure-Git ComponentRegistryResolverImpl",
        )
    }

    @Test
    @DisplayName("SYS-047: DB-only beans (v4 CRUD, TeamCity sync) are absent; git read controller stays")
    fun `SYS-047 db-only beans absent and git read path present in no-db mode`() {
        Assertions.assertTrue(
            ctx.getBeanNamesForType(ComponentControllerV4::class.java).isEmpty(),
            "v4 CRUD controller must be absent in no-db mode",
        )
        Assertions.assertTrue(
            ctx.getBeanNamesForType(TeamcitySyncService::class.java).isEmpty(),
            "DB-backed TeamcitySyncService must be absent in no-db mode",
        )
        Assertions.assertTrue(
            ctx.getBeanNamesForType(ImportService::class.java).isEmpty(),
            "DB-only ImportService must be absent in no-db mode " +
                "(so the controller's nullable importService is injected null)",
        )
        Assertions.assertTrue(
            ctx.getBeanNamesForType(ComponentManagementService::class.java).isEmpty(),
            "DB-only ComponentManagementService must be absent in no-db mode",
        )
        Assertions.assertEquals(
            1,
            ctx.getBeanNamesForType(ComponentsRegistryServiceController::class.java).size,
            "the v1/v2/v3 service controller (status/ping/updateCache) must stay wired",
        )
    }

    @Test
    @DisplayName("SYS-047: /service/status reports git routing and zero db components without a DB")
    fun `SYS-047 status reports defaultSource git and zero db components in no-db mode`() {
        val status = registryService.getComponentsRegistryStatus()
        Assertions.assertEquals("git", status.defaultSource, "no-db mode routes every component via Git")
        Assertions.assertEquals(
            0L,
            status.dbComponentCount,
            "dbComponentCount must be 0 (null-safe) when no ComponentSourceRepository is wired",
        )
    }
}
